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

import ca.uwaterloo.flix.tools.pkg.{PackageError, SemVer}
import ca.uwaterloo.flix.util.Result.Err
import org.json4s.JsonDSL.*
import org.json4s.JValue
import org.scalatest.funsuite.AnyFunSuite

import java.net.URI

class TestGitHub extends AnyFunSuite {

  test("downloadFailure.01: classifies authentication and rate-limit refusals") {
    val url = new URI("https://api.github.com/repos/owner/repo/releases/assets/1").toURL

    assertResult(PackageError.DownloadRefused(url, 403, Some("60")))(GitHub.downloadFailure(url, 403, Some("60")))
    assertResult(PackageError.DownloadRefused(url, 429, None))(GitHub.downloadFailure(url, 429, None))
  }

  test("downloadFailure.02: preserves unexpected response statuses") {
    val url = new URI("https://api.github.com/repos/owner/repo/releases/assets/1").toURL

    assertResult(PackageError.DownloadFailed(url, 401))(GitHub.downloadFailure(url, 401, None))
  }

  test("parseAsset.01: apiUrl is read from the REST API asset field") {
    val json: JValue =
      ("name" -> "flix.toml") ~
        ("browser_download_url" -> "https://github.com/wstein/pr13165-package/releases/download/v0.1.1/flix.toml") ~
        ("url" -> "https://api.github.com/repos/wstein/pr13165-package/releases/assets/1")

    val asset = GitHub.parseAsset(json)

    assertResult(expected = "flix.toml")(actual = asset.name)
    assertResult(expected = "https://api.github.com/repos/wstein/pr13165-package/releases/assets/1")(actual = asset.apiUrl.toString)
  }

  test("parseReleaseVersion.01: update checks do not require asset metadata") {
    val json: JValue = "tag_name" -> "v1.2.3"

    assertResult(expected = SemVer(1, 2, 3))(actual = GitHub.parseReleaseVersion(json))
  }

  test("findAsset.01: selects only the exact manifest-defined asset name") {
    val expected = GitHub.Asset("flix-json.fpkg", new URI("https://api.github.com/assets/2").toURL)
    val release = GitHub.Release(SemVer(0, 13, 3), List(
      GitHub.Asset("other.fpkg", new URI("https://api.github.com/assets/1").toURL),
      expected
    ))

    assertResult(expected = Some(expected))(actual = GitHub.findAsset(release, "flix-json.fpkg"))
    assertResult(expected = None)(actual = GitHub.findAsset(release, "json.fpkg"))
  }

  test("requireAsset.01: reports a confirmed release missing the named asset, not an ambiguous one") {
    val project = GitHub.Project("owner", "repo")
    val version = SemVer(0, 13, 3)
    val release = GitHub.Release(version, List(GitHub.Asset("other.fpkg", new URI("https://api.github.com/assets/1").toURL)))

    assertResult(expected = Err(PackageError.AssetNotFound(project, version, "flix-json.fpkg")))(
      actual = GitHub.requireAsset(project, version, release, "flix-json.fpkg")
    )
  }

  test("apiAssetDownloadRequest.01: authenticates an API binary download") {
    val url = new URI("https://api.github.com/repos/owner/repo/releases/assets/1").toURL
    val request = GitHub.apiAssetDownloadRequest(url, "test-token")

    assertResult(expected = "Bearer test-token")(actual = request.headers().firstValue("Authorization").get())
    assertResult(expected = "application/octet-stream")(actual = request.headers().firstValue("Accept").get())
  }

  test("releaseFailure.01: classifies targeted release failures") {
    val project = GitHub.Project("owner", "repo")
    val version = SemVer(1, 2, 3)
    val url = new URI("https://api.github.com/repos/owner/repo/releases/tags/v1.2.3").toURL

    assertResult(expected = PackageError.VersionDoesNotExist(version, project))(
      actual = GitHub.releaseFailure(project, version, url, 404, None)
    )
    assertResult(expected = PackageError.DownloadRefused(url, 429, Some("60")))(
      actual = GitHub.releaseFailure(project, version, url, 429, Some("60"))
    )
  }
}
