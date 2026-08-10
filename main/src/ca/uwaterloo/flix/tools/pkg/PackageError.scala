/*
 * Copyright 2023 Anna Blume Jakobsen
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

import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.tools.pkg.Dependency.FlixDependency
import ca.uwaterloo.flix.tools.pkg.github.GitHub.Project
import ca.uwaterloo.flix.util.Formatter

import java.io.IOException
import java.net.URL

sealed trait PackageError {
  /**
    * Returns a human-readable and formatted string representation of this error.
    */
  def message(f: Formatter): String
}

object PackageError {
  case class VersionDoesNotExist(version: SemVer, project: Project) extends PackageError {
    override def message(f: Formatter): String =
      s"Version ${f.bold(version.toString)} does not exist for project ${f.bold(project.toString)}"
  }

  case class NoSuchFile(project: String, extension: String) extends PackageError {
    override def message(f: Formatter): String =
      s"""There are no files in project '${f.bold(project)}' with extension '${f.bold(s".$extension")}'.
         |""".stripMargin
  }

  case class TooManyFiles(project: String, extension: String) extends PackageError {
    override def message(f: Formatter): String =
      s"""There are too many files in project '${f.bold(project)}' with extension '${f.bold(s".$extension")}'.
         |There should only be one $extension file in each project.
         |""".stripMargin
  }

  /**
    * An error raised when a release asset is not where its address says it should be.
    *
    * A download of an exact version is built from the version and the asset name, so a 404 says one
    * of two things and cannot say which: the release does not exist, or it exists without this
    * asset. The message names both possibilities and the address, rather than guessing.
    */
  case class ReleaseAssetNotFound(project: Project, version: SemVer, assetName: String, url: URL) extends PackageError {
    override def message(f: Formatter): String =
      s"""Could not find ${f.bold(assetName)} in release ${f.bold(s"v$version")} of ${f.bold(project.toString)}.
         |Either the release does not exist, or it does not publish that file.
         |Looked at ${f.cyan(url.toString)}.
         |""".stripMargin
  }

  /**
    * An error raised when a download is refused, which for an anonymous request usually means a
    * rate limit.
    */
  case class DownloadRefused(url: URL, status: Int, retryAfter: Option[String]) extends PackageError {
    override def message(f: Formatter): String = {
      val when = retryAfter.map(s => s"Retry after $s seconds.").getOrElse("This is usually a rate limit.")
      s"""Refused (HTTP ${f.red(status.toString)}) by ${f.cyan(url.toString)}.
         |$when
         |""".stripMargin
    }
  }

  /**
    * An error raised when a download answers with a status that is neither success nor a refusal.
    */
  case class DownloadFailed(url: URL, status: Int) extends PackageError {
    override def message(f: Formatter): String = {
      val detail =
        if (status >= 300 && status < 400) "The address redirected somewhere that could not be followed."
        else "Unexpected response."
      s"""Could not download ${f.cyan(url.toString)}: HTTP ${f.red(status.toString)}.
         |$detail
         |""".stripMargin
    }
  }

  /**
    * An error raised when a download never reached a server at all.
    */
  case class DownloadUnreachable(url: URL, message: String) extends PackageError {
    override def message(f: Formatter): String =
      s"""Could not reach ${f.cyan(url.toString)}.
         |$message
         |""".stripMargin
  }

  /**
    * An error raised when a download does not match the digest published beside it.
    *
    * The file is discarded rather than installed: a package that does not hash to what its
    * publisher said it does is not the package that was asked for, whether it was corrupted in
    * transit or replaced.
    */
  case class ChecksumMismatch(project: Project, version: SemVer, assetName: String, expected: String, actual: String) extends PackageError {
    override def message(f: Formatter): String =
      s"""${f.bold(assetName)} from release ${f.bold(s"v$version")} of ${f.bold(project.toString)} is not what was published.
         |Expected sha256 ${f.green(expected)}
         |but downloaded ${f.red(actual)}.
         |The download was discarded.
         |""".stripMargin
  }

  /**
    * An error raised when a download stops short of the length the server promised.
    */
  case class DownloadTruncated(project: Project, version: SemVer, assetName: String, expected: Long, actual: Long) extends PackageError {
    override def message(f: Formatter): String =
      s"""${f.bold(assetName)} from release ${f.bold(s"v$version")} of ${f.bold(project.toString)} arrived incomplete.
         |Expected ${f.green(s"$expected bytes")} but received ${f.red(s"$actual bytes")}.
         |The download was discarded.
         |""".stripMargin
  }

  /**
    * An error raised when a published checksum cannot be read.
    */
  case class ChecksumUnreadable(project: Project, version: SemVer, assetName: String, url: URL) extends PackageError {
    override def message(f: Formatter): String =
      s"""The checksum published for ${f.bold(assetName)} in release ${f.bold(s"v$version")} of ${f.bold(project.toString)} is empty.
         |Read from ${f.cyan(url.toString)}.
         |""".stripMargin
  }

  /**
    * An error raised when a download was served but could not be written to disk.
    */
  case class DownloadIncomplete(project: Project, version: SemVer, assetName: String, message: Option[String]) extends PackageError {
    override def message(f: Formatter): String =
      s"""Could not save ${f.bold(assetName)} from release ${f.bold(s"v$version")} of ${f.bold(project.toString)}.
         |${message.getOrElse("The file was not created.")}
         |""".stripMargin
  }

  case class InvalidProjectName(projectString: String) extends PackageError {
    override def message(f: Formatter): String =
      s"""A GitHub project should be formatted like so: 'owner/repository'.
         |Instead found: ${f.red(projectString)}.
         |""".stripMargin
  }

  case class ProjectNotFound(url: URL, project: Project, exception: IOException) extends PackageError {
    override def message(f: Formatter): String =
      s"""An I/O error occurred while trying to read the following url:
         |${f.cyan(url.toString)}
         |Project: ${f.bold(project.toString)}
         |Error: ${f.red(exception.getMessage)}
         |""".stripMargin
  }

  case class JsonError(json: String, project: Project) extends PackageError {
    override def message(f: Formatter): String =
      s"""An error occurred while trying to parse the following as JSON:
         |${f.cyan(json)}
         |Project: ${f.bold(project.toString)}
         |""".stripMargin
  }

  case class DownloadErrorJar(url: String, fileName: String, message: Option[String]) extends PackageError {
    override def message(f: Formatter): String =
      s"""A download error occurred while downloading ${f.bold(fileName)} from $url
         |${
        message match {
          case Some(e) => e
          case None => ""
        }
      }
         |""".stripMargin
  }

  case class CoursierError(errorMsg: String) extends PackageError {
    override def message(f: Formatter): String =
      s"""An error occurred with Coursier:
         |$errorMsg
         |""".stripMargin
  }

  case class ManifestParseError(e: ManifestError) extends PackageError {
    override def message(f: Formatter): String = e.message(f)
  }

  /**
    * An error raised when the dependency graph itself is inconsistent w.r.t. security contexts.
    *
    * @param manifest   the manifest that declares [[dependency]].
    * @param dependency the dependency that requires a higher security level than what is allowed by the declaring manifest.
    * @param sctx       the maximum allowed security context.
    */
  case class DepGraphSecurityError(manifest: Manifest, dependency: FlixDependency, sctx: SecurityContext) extends PackageError {
    // TODO: Show the offending original dependency/-ies (from origin manifest)
    // TODO: Maybe collect list of errors that can all be displayed in a single error message.
    override def message(f: Formatter): String = {
      s"""${f.underline("Found security violation in the dependency graph:")}
         |  Dependency '$dependency' of package ${manifest.name} requires security context '${dependency.sctx}' but context '$sctx' was given.
         |
         |  There are several possible actions:
         |    - Remove the offending dependency
         |    - Use a different dependency.
         |    - Increase security level. ${f.yellow("WARNING")}: This can be dangerous and may expose you to supply chain attacks.
         |""".stripMargin
    }
  }

  /**
    * An error raised when the dependency graph itself is inconsistent w.r.t. security contexts.
    *
    * @param manifest the dependency that requires a stronger security context than what is allowed by the declaring manifest.
    * @param sctx     the maximum allowed security level.
    */
  case class IllegalJavaDependencyForSctx(manifest: Manifest, dependency: Dependency, sctx: SecurityContext) extends PackageError {
    // TODO: Show the offending original dependency/-ies (from origin manifest)
    // TODO: Maybe collect list of errors that can all be displayed in a single error message.
    override def message(f: Formatter): String = {
      s"""${f.underline("Found security violation in the dependency graph:")}
         |  Project '${manifest.name}' declares Java dependency '$dependency' which requires security context '${SecurityContext.Unrestricted}' but only $sctx was given.
         |
         |  There are several possible actions:
         |    - Remove the offending dependency
         |    - Use a different dependency.
         |    - Increase security level. ${f.yellow("WARNING")}: This can be dangerous and may expose you to supply chain attacks.
         |""".stripMargin
    }
  }

  /**
    * An error raised to indicate that the version number declared in `manifest`
    * does not match the targeted version in `dependency`.
    *
    * @param manifest   the manifest which [[dependency]] resolves to.
    * @param dependency a valid flix dependency.
    */
  case class MismatchedVersions(manifest: Manifest, dependency: FlixDependency) extends PackageError {
    override def message(f: Formatter): String = {
      s"""Mismatched versions:
         |  Dependency ${dependency.identifier} required version ${dependency.version}
         |  but the manifest declared version ${manifest.version}
         |
         |  Required: ${dependency.version}
         |  Declared: ${manifest.version}
         |""".stripMargin
    }
  }
}
