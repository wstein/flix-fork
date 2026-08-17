/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.tools.pkg.github

import ca.uwaterloo.flix.tools.pkg.github.GitHub.Project
import ca.uwaterloo.flix.tools.pkg.{PackageError, SemVer}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import com.sun.net.httpserver.HttpServer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.io.IOException
import java.net.{InetSocketAddress, URI, URL}
import java.nio.charset.StandardCharsets

/**
  * Exercises [[GitHub]]'s HTTP handling deterministically: status codes, redirects and rate-limit
  * headers via a real local server, and the by-tag/listing split via a [[FakeTransport]]. Belongs in
  * the default `flix.test` pass rather than behind the network-gated `flix.testPackageManager`.
  */
class TestGitHubHttp extends AnyFunSuite with BeforeAndAfterAll {

  private var server: HttpServer = _
  private var port: Int = _

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    port = server.getAddress.getPort

    def respond(path: String, status: Int, headers: Map[String, String] = Map.empty, body: String = ""): Unit =
      server.createContext(path, exchange => {
        headers.foreach { case (k, v) => exchange.getResponseHeaders.add(k, v) }
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, if (body.isEmpty) -1 else bytes.length.toLong)
        if (body.nonEmpty) exchange.getResponseBody.write(bytes)
        exchange.close()
      })

    respond("/ok", 200, body = "hello")
    respond("/redirect", 302, headers = Map("Location" -> s"http://localhost:$port/ok"))
    respond("/missing", 404)
    respond("/refused", 403, headers = Map("Retry-After" -> "42", "X-RateLimit-Remaining" -> "0", "X-RateLimit-Reset" -> "1234567890"))
    respond("/secondary-limit", 429)

    server.start()
  }

  override def afterAll(): Unit = server.stop(0)

  private def urlFor(path: String): URL = new URI(s"http://localhost:$port$path").toURL

  test("download succeeds, follows a redirect, and reports 404/403/429 distinctly") {
    implicit val transport: GitHub.Transport = GitHub.Transport.live
    for (path <- List("/ok", "/redirect")) {
      GitHub.download(urlFor(path)) match {
        case Ok(stream) => assertResult("hello")(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
        case Err(e) => fail(s"$path: ${e.getClass.getSimpleName}")
      }
    }
    GitHub.download(urlFor("/missing")) match {
      case Err(PackageError.DownloadFailed(_, 404)) => succeed
      case other => fail(other.toString)
    }
    GitHub.download(urlFor("/refused")) match {
      case Err(PackageError.DownloadRefused(_, 403, Some("42"), Some("1234567890"), Some("0"))) => succeed
      case other => fail(other.toString)
    }
    GitHub.download(urlFor("/secondary-limit")) match {
      case Err(PackageError.DownloadRefused(_, 429, _, _, _)) => succeed
      case other => fail(other.toString)
    }
  }

  test("download reports an unreachable server distinctly from a refusal") {
    // A transport that never returns normally stands in for a dropped connection -- deterministic,
    // unlike the real socket timing a genuinely closed or firewalled port would need.
    implicit val unreachable: GitHub.Transport = GitHub.Transport(_ => throw new IOException("simulated: no route to host"))

    GitHub.download(new URI("http://example.invalid/x").toURL) match {
      case Err(PackageError.DownloadUnreachable(_, _)) => succeed
      case other => fail(other.toString)
    }
  }

  private val project = Project("flix", "museum")
  private val version = SemVer(1, 1, 0)
  private val listingUrl = "https://api.github.com/repos/flix/museum/releases"
  private val tagUrl = "https://api.github.com/repos/flix/museum/releases/tags/v1.1.0"
  private val museumFpkgJson = """{"tag_name":"v1.1.0","assets":[{"name":"museum.fpkg","browser_download_url":"https://example.invalid/museum.fpkg"}]}"""

  test("getReleases reports 403/429 as refusals rather than JSON parse errors") {
    // Before this fix, a rate-limit error body -- itself valid but non-array JSON -- was fed
    // straight to the JSON parser and reported as a malformed release listing.
    for (status <- List(403, 429)) {
      implicit val transport: GitHub.Transport = FakeTransport(Map(
        listingUrl -> List(CannedResponse(status, headers = Map("X-RateLimit-Remaining" -> "0"), body = """{"message":"rate limited"}"""))
      ))
      GitHub.getReleases(project, apiKey = None) match {
        case Err(PackageError.DownloadRefused(_, `status`, _, _, Some("0"))) => succeed
        case other => fail(s"status $status: $other")
      }
    }
  }

  test("getReleaseByTag succeeds without reading the paginated listing, and reports 404/403 distinctly") {
    // Only the tag URL is ever scripted; a fallback to the listing makes FakeTransport throw.
    implicit val ok: GitHub.Transport = FakeTransport(Map(tagUrl -> List(CannedResponse(200, body = museumFpkgJson))))
    GitHub.getReleaseByTag(project, version, apiKey = None)(ok) match {
      case Ok(release) => assertResult(version)(release.version)
      case other => fail(other.toString)
    }

    implicit val notFound: GitHub.Transport = FakeTransport(Map(tagUrl -> List(CannedResponse(404))))
    GitHub.getReleaseByTag(project, version, apiKey = None)(notFound) match {
      case Err(PackageError.VersionDoesNotExist(v, p)) =>
        assertResult(version)(v)
        assertResult(project)(p)
      case other => fail(other.toString)
    }

    implicit val refused: GitHub.Transport = FakeTransport(Map(tagUrl -> List(CannedResponse(403, headers = Map("Retry-After" -> "17")))))
    GitHub.getReleaseByTag(project, version, apiKey = None)(refused) match {
      case Err(PackageError.DownloadRefused(_, 403, Some("17"), _, _)) => succeed
      case other => fail(other.toString)
    }
  }

  test("findReleaseAsset finds the single matching asset via the tag endpoint, not the listing") {
    implicit val transport: GitHub.Transport = FakeTransport(Map(tagUrl -> List(CannedResponse(200, body = museumFpkgJson))))
    GitHub.findReleaseAsset(project, version, "fpkg", apiKey = None) match {
      case Ok(asset) => assertResult("museum.fpkg")(asset.name)
      case other => fail(other.toString)
    }
  }
}
