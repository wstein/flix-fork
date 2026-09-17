/*
 * Copyright 2025 Jakob Schneider Villumsen
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
package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.api.{Bootstrap, Flix, InstalledPackage, Version}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Formatter, Options}

import java.nio.file.Path

/**
  * Contains a test utilities for the package manager tests that rely heavily on I/O
  */
object PkgTestUtils {

  /**
    * GitHub token of the CI runner if available.
    */
  val gitHubToken: Option[String] = envToken("GITHUB_CI_RUNNER_TOKEN")

  /**
    * A token with read access to a private repository set up for testing private-repo installs
    * (`wstein/pr13165-package-renamed`), distinct from [[gitHubToken]]: the CI-runner token above
    * is GitHub Actions' built-in, repo-scoped `secrets.GITHUB_TOKEN` -- it cannot see any other
    * repository, private or not. This one is a real PAT (`secrets.GH_TOKEN` in CI), only present
    * when a private-repo test is meant to actually run.
    */
  val privateRepoTestToken: Option[String] = envToken("GITHUB_PRIVATE_TEST_TOKEN")

  private def envToken(name: String): Option[String] = {
    val propValue = System.getenv(name)
    if (propValue == null || propValue.isBlank || propValue.isEmpty)
      None
    else
      Some(propValue)
  }

  /**
    * Returns a new [[Flix]] object that has the GitHub token of the CI runner set if available.
    */
  def mkFlix: Flix = mkFlix(Nil)

  /**
    * Returns a new [[Flix]] object with the given packages that has the GitHub token of the CI runner set if available.
    */
  def mkFlix(pkgs: List[InstalledPackage]): Flix = {
    val flix = new Flix(pkgs = pkgs)
    flix.setOptions(flix.options.copy(githubToken = gitHubToken, progress = false))
  }

  /**
    * Returns a new [[Flix]] object for the given `bootstrap` that has the GitHub token of the CI runner set if available.
    */
  def mkFlix(bootstrap: Bootstrap): Flix =
    bootstrap.mkFlix(Options.Default.copy(githubToken = gitHubToken, progress = false), Formatter.NoFormatter)

  def mkTomlWithDeps(deps: String): String = {
    s"""
       |[package]
       |name = "test"
       |description = "test"
       |version = "0.1.0"
       |flix = "${Version.CurrentVersion}"
       |authors = ["flix"]
       |
       |[dependencies]
       |$deps
       |""".stripMargin
  }

}
