/*
 * Copyright 2021 Matthew Lutze
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

import ca.uwaterloo.flix.tools.pkg.{PackageError, ReleaseError, SemVer}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.Result
import org.json4s.*
import org.json4s.JsonAST.{JArray, JValue}
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.{compact, parse, render}

import java.io.{IOException, InputStream}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URL}
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
  * An interface for the GitHub API.
  */
object GitHub {

  /**
    * A GitHub project.
    */
  case class Project(owner: String, repo: String) {
    override def toString: String = s"$owner/$repo"
  }

  /**
    * A release of a GitHub project.
    */
  case class Release(version: SemVer, assets: List[Asset])

  /**
    * An asset from a GitHub project release.
    *
    * `url` is the link to download the asset.
    */
  case class Asset(name: String, url: URL)

  /**
    * A source of GitHub HTTP responses. Implicit, since it never changes across a call chain,
    * including recursion through transitive dependencies: [[Transport.live]] answers it by default,
    * via this type's companion, and a test brings a scripted one into scope instead.
    */
  final case class Transport(send: HttpRequest => HttpResponse[InputStream])

  object Transport {
    implicit val live: Transport = {
      val client: HttpClient = HttpClient.newBuilder()
        // A release download address redirects to the storage the asset actually lives on, and the
        // default policy is to follow nothing at all, which would turn every download into a 302.
        .followRedirects(HttpClient.Redirect.NORMAL)
        // Without a bound, a host that never answers -- dropped rather than refused -- hangs the
        // installer indefinitely instead of failing as [[PackageError.DownloadUnreachable]].
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()
      Transport(request => client.send(request, HttpResponse.BodyHandlers.ofInputStream()))
    }
  }

  /**
    * Lists the project's releases.
    *
    * This reads a single page: GitHub paginates the listing, and a project with more releases than
    * fit on one page can have older ones fall off the end. [[getReleaseByTag]] does not have this
    * problem -- it asks for one release directly -- and is what [[findReleaseAsset]] uses. This
    * function remains for `outdated`, which genuinely needs to see every version to compare against.
    */
  def getReleases(project: Project, apiKey: Option[String])(implicit transport: Transport): Result[List[Release], PackageError] = {
    val url = releasesUrl(project)
    val reqBuilder = HttpRequest.newBuilder(url.toURI)
    // add the API key as bearer if needed
    apiKey.foreach(key => reqBuilder.header("Authorization", "Bearer " + key))
    val req = reqBuilder.GET().build()
    val response = try {
      transport.send(req)
    } catch {
      case ex: IOException => return Err(PackageError.ProjectNotFound(url, project, ex))
      case ex: InterruptedException => return Err(PackageError.ProjectNotFound(url, project, new IOException(ex)))
    }
    response.statusCode() match {
      case status if status >= 200 && status < 300 =>
        val json = readBody(response)
        try {
          Ok(parse(json).asInstanceOf[JArray].arr.map(parseRelease))
        } catch {
          case _: ClassCastException => Err(PackageError.JsonError(json, project))
        }
      case status @ (403 | 429) =>
        response.body().close()
        Err(rateLimitError(url, status, response))
      case status =>
        response.body().close()
        Err(PackageError.DownloadFailed(url, status))
    }
  }

  /**
    * Publish a new release the given project.
    */
  def publishRelease(project: Project, version: SemVer, artifacts: Iterable[Path], apiKey: String): Result[Unit, ReleaseError] = {
    for (
      _ <- verifyRelease(project, version, apiKey);
      id <- createDraftRelease(project, version, apiKey);
      _ <- Result.traverse(artifacts)(p => uploadAsset(p, project, id, apiKey));
      _ <- markReleaseReady(project, version, id, apiKey)
    ) yield Ok(())
  }

  /**
    * Verifies that the release does not already exist.
    */
  private def verifyRelease(project: Project, version: SemVer, apiKey: String): Result[Unit, ReleaseError] = {
    val url = releaseVersionUrl(project, version)
    val req = HttpRequest.newBuilder(url.toURI)
      .header("Authorization", "Bearer " + apiKey)
      .GET()
      .build()

    try {
      // Send request
      val resp = Client.sendRequest(req)

      // Process response errors
      val code = resp.statusCode()
      code match {
        case 200 => Err(ReleaseError.ReleaseAlreadyExists(project, version))
        case _ => Ok(())
      }
    } catch {
      case _: IOException => Err(ReleaseError.NetworkError)
    }
  }

  /**
    * Create a new release marked as a draft, meaning that it is not publicly visible.
    * The release will not contain any assets (apart from the default zips of the source code).
    *
    * Returns the ID of the release if successful.
    */
  private def createDraftRelease(project: Project, version: SemVer, apiKey: String): Result[String, ReleaseError] = {
    val content: JValue =
      ("tag_name" -> s"v$version") ~
        ("name" -> s"v$version") ~
        ("generate_release_notes" -> true) ~
        ("draft" -> true)

    val jsonCompact = compact(render(content))

    val url = releasesUrl(project)
    val req = HttpRequest.newBuilder(url.toURI)
      .header("Authorization", "Bearer " + apiKey)
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofByteArray(jsonCompact.getBytes("utf-8")))
      .build()

    val json = try {
      // Send request
      val resp = Client.sendRequest(req)

      // Process response errors
      val code = resp.statusCode()
      code match {
        case 201 => resp.body()
        case 401 => return Err(ReleaseError.InvalidApiKeyError)
        case 404 => return Err(ReleaseError.RepositoryNotFound(project))
        case _ => return Err(ReleaseError.UnexpectedResponseCode(code, resp.body()))
      }

    } catch {
      case _: IOException => return Err(ReleaseError.NetworkError)
    }

    // Extract URL from returned JSON
    val id = try {
      val obj = parse(json).asInstanceOf[JObject]
      val jsonId = (obj \ "id").asInstanceOf[JInt]
      jsonId.values.toString
    } catch {
      case _: ClassCastException => return Err(ReleaseError.UnexpectedResponseJson(json))
    }

    Ok(id)
  }

  /**
    * Uploads a single asset.
    */
  private def uploadAsset(assetPath: Path, project: Project, releaseId: String, apiKey: String): Result[Unit, ReleaseError] = {
    val assetName = assetPath.getFileName.toString

    val url = releaseAssetUploadUrl(project, releaseId, assetName)
    val req = HttpRequest.newBuilder(url.toURI)
      .header("Authorization", "Bearer " + apiKey)
      .header("Content-Type", "application/octet-stream")
      .POST(BodyPublishers.ofFile(assetPath))
      .build()

    try {
      // Send request
      val resp = Client.sendRequest(req)

      // Process response errors
      val code = resp.statusCode()
      code match {
        case 201 => Ok(())
        case 401 => Err(ReleaseError.InvalidApiKeyError)
        case _ => Err(ReleaseError.UnexpectedResponseCode(code, resp.body()))
      }

    } catch {
      case _: IOException => Err(ReleaseError.NetworkError)
    }
  }

  /**
    * Mark the given release as no longer being a draft, making it publicly available.
    */
  private def markReleaseReady(project: Project, version: SemVer, releaseId: String, apiKey: String): Result[Unit, ReleaseError] = {
    val content: JValue = "draft" -> false
    val jsonCompact = compact(render(content))

    val url = releaseIdUrl(project, releaseId)
    val req = HttpRequest.newBuilder(url.toURI)
      .header("Authorization", "Bearer " + apiKey)
      .header("Content-Type", "application/json")
      .method("PATCH", BodyPublishers.ofByteArray(jsonCompact.getBytes("utf-8")))
      .build()

    try {
      // Send request
      val resp = Client.sendRequest(req)

      // Process response errors
      val code = resp.statusCode()
      code match {
        case 200 => Ok(())
        case 401 => Err(ReleaseError.InvalidApiKeyError)
        case 404 => Err(ReleaseError.RepositoryNotFound(project))
        case 422 => Err(ReleaseError.ReleaseAlreadyExists(project, version))
        case _ => Err(ReleaseError.UnexpectedResponseCode(code, resp.body()))
      }
    } catch {
      case _: IOException => Err(ReleaseError.NetworkError)
    }
  }

  /**
    * Parses a GitHub project from an `<owner>/<repo>` string.
    */
  def parseProject(string: String): Result[Project, PackageError] = string.split('/') match {
    case Array(owner, repo) if owner.nonEmpty && repo.nonEmpty => Ok(Project(owner, repo))
    case _ => Err(PackageError.InvalidProjectName(string))
  }

  /**
    * Opens a stream over the asset `assetName` of the given project's release `version`, without
    * consulting the REST API. The caller is responsible for closing the stream.
    *
    * A release asset has a permanent address built from the owner, repository, tag and asset name,
    * so an asset whose name is known needs no lookup; this does not consume the REST quota, though
    * it is still an ordinary download and can be throttled operationally. The caller does not always
    * know the true name in advance -- see [[findReleaseAsset]] for the fallback when this 404s.
    */
  def downloadReleaseAsset(project: Project, version: SemVer, assetName: String)(implicit transport: Transport): Result[InputStream, PackageError] = {
    val url = releaseAssetUrl(project, version, assetName)
    download(url) match {
      // At this address a 404 means the release or the asset is absent, which is worth saying in
      // those terms rather than as a status code.
      case Err(PackageError.DownloadFailed(_, 404)) => Err(PackageError.ReleaseAssetNotFound(project, version, assetName, url))
      case other => other
    }
  }

  /**
    * Opens a stream over `url`, following redirects.
    *
    * The caller is responsible for closing the stream. The ways a download can fail are kept apart:
    * a refusal (which for an anonymous request usually means a rate limit), any other unexpected
    * status including a redirect that could not be followed, and never reaching a server at all.
    */
  def download(url: URL)(implicit transport: Transport): Result[InputStream, PackageError] = {
    val request = HttpRequest.newBuilder(url.toURI).GET().build()

    val response = try {
      transport.send(request)
    } catch {
      case ex: IOException => return Err(PackageError.DownloadUnreachable(url, ex.getMessage))
      case ex: InterruptedException => return Err(PackageError.DownloadUnreachable(url, ex.getMessage))
    }

    response.statusCode() match {
      case status if status >= 200 && status < 300 =>
        Ok(response.body())
      case status =>
        // Every non-success response still carries a body, and leaving it open holds the connection.
        response.body().close()
        status match {
          case 403 | 429 => Err(rateLimitError(url, status, response))
          case _ => Err(PackageError.DownloadFailed(url, status))
        }
    }
  }

  /**
    * Reads and closes `response`'s body as UTF-8 text.
    */
  private def readBody(response: HttpResponse[InputStream]): String = {
    val bytes = response.body().readAllBytes()
    response.body().close()
    new String(bytes, StandardCharsets.UTF_8)
  }

  /**
    * Builds a [[PackageError.DownloadRefused]] from a 403/429 `response`, preserving `Retry-After`
    * and the `X-RateLimit-*` headers rather than reducing the refusal to a bare status code.
    */
  private def rateLimitError(url: URL, status: Int, response: HttpResponse[InputStream]): PackageError =
    PackageError.DownloadRefused(url, status, header(response, "Retry-After"), header(response, "X-RateLimit-Reset"), header(response, "X-RateLimit-Remaining"))

  /**
    * Returns the first value of header `name` on `response`, if it has one.
    */
  private def header(response: HttpResponse[InputStream], name: String): Option[String] = {
    val h = response.headers().firstValue(name)
    if (h.isPresent) Some(h.get()) else None
  }

  /**
    * Gets the project release with the relevant semantic version, directly by tag. Unlike
    * [[getReleases]] this does not page: a tag names exactly one release.
    */
  def getReleaseByTag(project: Project, version: SemVer, apiKey: Option[String])(implicit transport: Transport): Result[Release, PackageError] = {
    val url = releaseVersionUrl(project, version)
    val reqBuilder = HttpRequest.newBuilder(url.toURI)
    apiKey.foreach(key => reqBuilder.header("Authorization", "Bearer " + key))
    val req = reqBuilder.GET().build()
    val response = try {
      transport.send(req)
    } catch {
      case ex: IOException => return Err(PackageError.ProjectNotFound(url, project, ex))
      case ex: InterruptedException => return Err(PackageError.ProjectNotFound(url, project, new IOException(ex)))
    }
    response.statusCode() match {
      case 200 =>
        val json = readBody(response)
        try {
          Ok(parseRelease(parse(json)))
        } catch {
          case _: ClassCastException => Err(PackageError.JsonError(json, project))
        }
      case 404 =>
        response.body().close()
        Err(PackageError.VersionDoesNotExist(version, project))
      case status @ (403 | 429) =>
        response.body().close()
        Err(rateLimitError(url, status, response))
      case status =>
        response.body().close()
        Err(PackageError.DownloadFailed(url, status))
    }
  }

  /**
    * Finds the single asset with the given extension in the project's release `version`.
    *
    * This reads the REST API, and does so because it has to: a release publishes its package under
    * a name the publisher chose, and nothing the consumer holds is guaranteed to predict it.
    * [[downloadReleaseAsset]] tries the likely names first; this is the fallback for when none hit.
    */
  def findReleaseAsset(project: Project, version: SemVer, extension: String, apiKey: Option[String])(implicit transport: Transport): Result[Asset, PackageError] = {
    getReleaseByTag(project, version, apiKey).flatMap { release =>
      release.assets.filter(_.name.endsWith(s".$extension")) match {
        case Nil => Err(PackageError.NoSuchFile(project.toString, extension))
        case asset :: Nil => Ok(asset)
        case _ => Err(PackageError.TooManyFiles(project.toString, extension))
      }
    }
  }

  /**
    * Returns the address of a release asset.
    *
    * This is the permanent form of a release download link. It is not the REST API and does not
    * consume its quota.
    */
  private def releaseAssetUrl(project: Project, version: SemVer, assetName: String): URL = {
    new URI(s"https://github.com/${project.owner}/${project.repo}/releases/download/v$version/$assetName").toURL
  }

  /**
    * Returns the URL that returns data related to the project's releases.
    */
  private def releasesUrl(project: Project): URL = {
    new URI(s"https://api.github.com/repos/${project.owner}/${project.repo}/releases").toURL
  }

  /**
    * Returns the URL for updating information about this specific release.
    */
  private def releaseIdUrl(project: Project, releaseId: String): URL = {
    new URI(s"${releasesUrl(project).toString}/$releaseId").toURL
  }

  /**
    * Returns the URL for viewing basic information about this specific release.
    */
  private def releaseVersionUrl(project: Project, version: SemVer): URL = {
    new URI(s"${releasesUrl(project).toString}/tags/v$version").toURL
  }

  /**
    * Returns the URL that release assets can be uploaded to.
    */
  private def releaseAssetUploadUrl(project: Project, releaseId: String, assetName: String): URL = {
    new URI(s"https://uploads.github.com/repos/${project.owner}/${project.repo}/releases/$releaseId/assets?name=$assetName").toURL
  }

  /**
    * Parses a Release JSON.
    */
  private def parseRelease(json: JValue): Release = {
    val version = parseSemVer((json \ "tag_name").values.toString)
    val assetJsons = (json \ "assets").asInstanceOf[JArray]
    val assets = assetJsons.arr.map(parseAsset)
    Release(version, assets)
  }

  /**
    * Parses an Asset JSON.
    */
  private def parseAsset(asset: JValue): Asset = {
    val url = asset \ "browser_download_url"
    val name = asset \ "name"
    Asset(name.values.toString, new URI(url.values.toString).toURL)
  }

  /**
    * Parses a semantic version, starting with v, e.g.
    *
    * * `v2.3.4`
    */
  private def parseSemVer(str: String): SemVer = {
    val (v, num) = str.splitAt(1)
    if (v != "v") {
      throw new RuntimeException(s"Invalid semantic version: $str")
    }
    SemVer.ofString(num) match {
      case Some(semver) => semver
      case _ => throw new RuntimeException(s"Invalid semantic version: $str")
    }
  }

  /** A thread-safe HTTP Client. */
  private object Client {

    /**
      * Internally re-used Http Client.
      *
      * Reusing the instance yields better performance since it can
      * keep connections open.
      *
      * This field should only be accessed in a thread-safe manner, e.g.,
      * such as using `this.synchronized` blocks or some other locking mechanism.
      */
    private val HTTP_CLIENT: HttpClient = HttpClient.newHttpClient()

    /**
      * Sends the HTTP request, `request`, and returns the response.
      *
      * Is blocking and thread-safe.
      *
      * May throw [[IOException]].
      */
    def sendRequest(request: HttpRequest): HttpResponse[String] = this.synchronized {
      HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
    }

  }
}
