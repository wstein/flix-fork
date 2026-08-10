package ca.uwaterloo.flix.tools.pkg.github

import ca.uwaterloo.flix.tools.pkg.PackageError
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.scalatest.funsuite.AnyFunSuite

import java.net.{InetSocketAddress, URI, URL}
import java.nio.charset.StandardCharsets
import scala.util.Using

/**
  * Tests for the HTTP half of downloading, served locally.
  *
  * What a download does is decided almost entirely by responses a registry will not produce to
  * order: a refusal, a redirect that cannot be followed, a success with no content. Served from
  * here they are ordinary test inputs, and none of it spends the rate limit this work exists to
  * stop spending.
  */
class TestGitHubDownload extends AnyFunSuite {

  /** Runs `f` against a server answering `/asset` with the given handler. */
  private def serving[T](handler: HttpExchange => Unit)(f: URL => T): T = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/asset", (exchange: HttpExchange) => handler(exchange))
    server.start()
    try f(URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/asset").toURL)
    finally server.stop(0)
  }

  /** A handler answering with `status` and `body`. */
  private def answering(status: Int, body: String, headers: (String, String)*): HttpExchange => Unit = exchange => {
    headers.foreach { case (name, value) => exchange.getResponseHeaders.add(name, value) }
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    if (bytes.isEmpty) {
      exchange.sendResponseHeaders(status, -1)
    } else {
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      Using.resource(exchange.getResponseBody)(_.write(bytes))
    }
    exchange.close()
  }

  private def read(url: URL): (String, Option[Long]) =
    GitHub.download(url) match {
      case Ok(download) =>
        val text = Using.resource(download.stream)(s => new String(s.readAllBytes(), StandardCharsets.UTF_8))
        (text, download.contentLength)
      case Err(e) => fail(s"Expected a download, but found: $e")
    }

  test("200 is a download, and its length is reported") {
    serving(answering(200, "package bytes")) { url =>
      assertResult(("package bytes", Some(13L)))(read(url))
    }
  }

  test("204 is not a download") {
    // A success with nothing in it would otherwise install an empty file as though it were a
    // package, and an empty file has no length and no digest to catch it with.
    serving(answering(204, "")) { url =>
      GitHub.download(url) match {
        case Err(PackageError.DownloadFailed(_, 204)) => succeed
        case other => fail(s"Expected 204 to be refused, but found: $other")
      }
    }
  }

  test("404 is reported as the status it was") {
    serving(answering(404, "no such thing")) { url =>
      GitHub.download(url) match {
        case Err(PackageError.DownloadFailed(_, 404)) => succeed
        case other => fail(s"Expected a 404, but found: $other")
      }
    }
  }

  test("403 is a refusal, and carries the wait it asked for") {
    serving(answering(403, "go away", "Retry-After" -> "120")) { url =>
      GitHub.download(url) match {
        case Err(PackageError.DownloadRefused(_, 403, Some("120"))) => succeed
        case other => fail(s"Expected a refusal naming Retry-After, but found: $other")
      }
    }
  }

  test("429 is a refusal even without a Retry-After") {
    serving(answering(429, "slow down")) { url =>
      GitHub.download(url) match {
        case Err(PackageError.DownloadRefused(_, 429, None)) => succeed
        case other => fail(s"Expected a refusal, but found: $other")
      }
    }
  }

  test("500 is reported as the status it was") {
    serving(answering(500, "broken")) { url =>
      GitHub.download(url) match {
        case Err(PackageError.DownloadFailed(_, 500)) => succeed
        case other => fail(s"Expected a 500, but found: $other")
      }
    }
  }

  test("a redirect is followed") {
    // Release addresses redirect to the storage the asset lives on, so a client that follows
    // nothing -- which is the default -- turns every download into a 302.
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/target", (exchange: HttpExchange) => answering(200, "moved bytes")(exchange))
    server.createContext("/asset", (exchange: HttpExchange) => {
      exchange.getResponseHeaders.add("Location", "/target")
      exchange.sendResponseHeaders(302, -1)
      exchange.close()
    })
    server.start()
    try {
      val url = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/asset").toURL
      assertResult("moved bytes")(read(url)._1)
    } finally server.stop(0)
  }

  test("a redirect with nowhere to go is a transport failure, not a body") {
    // A 302 without a Location never reaches the status check: the client raises `Invalid
    // redirection` while following it. The 3xx branch of the status check is for the redirect the
    // client declines rather than fails on -- an https address redirecting to http, which
    // `Redirect.NORMAL` refuses and hands back as a status. That one needs TLS to provoke and is
    // not reproduced here.
    serving(exchange => {
      exchange.sendResponseHeaders(302, -1)
      exchange.close()
    }) { url =>
      GitHub.download(url) match {
        case Err(_: PackageError.DownloadUnreachable) => succeed
        case other => fail(s"Expected a transport failure, but found: $other")
      }
    }
  }

  test("a server that is not there is unreachable rather than a status") {
    // Port 1 on the loopback address: nothing listens, and nothing is meant to.
    val url = URI.create("http://127.0.0.1:1/asset").toURL
    GitHub.download(url) match {
      case Err(_: PackageError.DownloadUnreachable) => succeed
      case other => fail(s"Expected an unreachable server, but found: $other")
    }
  }

  test("a checksum file is read when it holds a digest alone") {
    assertResult(Some("a" * 64))(GitHub.parseChecksum("A" * 64, "thing.fpkg"))
  }

  test("a checksum file is read in the format sha256sum writes") {
    assertResult(Some("b" * 64))(GitHub.parseChecksum(s"${"B" * 64}  thing.fpkg\n", "thing.fpkg"))
  }

  test("a checksum file marked as a binary read is read") {
    assertResult(Some("c" * 64))(GitHub.parseChecksum(s"${"c" * 64} *thing.fpkg\n", "thing.fpkg"))
  }

  test("a checksum naming a different file is refused") {
    // The digest is of something else, so checking against it would report the wrong thing.
    assertResult(None)(GitHub.parseChecksum(s"${"d" * 64}  other.fpkg", "thing.fpkg"))
  }

  test("a digest of the wrong length is refused") {
    // Refused rather than compared, so that broken metadata is not reported as a corrupt package.
    assertResult(None)(GitHub.parseChecksum("abc123", "thing.fpkg"))
    assertResult(None)(GitHub.parseChecksum("e" * 63, "thing.fpkg"))
    assertResult(None)(GitHub.parseChecksum("e" * 65, "thing.fpkg"))
  }

  test("a digest that is not hexadecimal is refused") {
    assertResult(None)(GitHub.parseChecksum("z" * 64, "thing.fpkg"))
  }

  test("an empty checksum file is refused") {
    assertResult(None)(GitHub.parseChecksum("", "thing.fpkg"))
    assertResult(None)(GitHub.parseChecksum("   \n", "thing.fpkg"))
  }

  test("a checksum file with more than a digest and a name is refused") {
    assertResult(None)(GitHub.parseChecksum(s"${"f" * 64}  thing.fpkg extra", "thing.fpkg"))
  }
}
