/*
 * Copyright 2023 Magnus Madsen
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

import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.tools.pkg.github.GitHub

case class Manifest(name: String,
                    description: String,
                    version: SemVer,
                    repository: Option[GitHub.Project],
                    modules: PackageModules,
                    flix: SemVer,
                    license: Option[String],
                    authors: List[String],
                    dependencies: List[Dependency]) {
  def flixDependencies: List[Dependency.FlixDependency] = dependencies.collect { case dep: Dependency.FlixDependency => dep }

  def mavenDependencies: List[Dependency.MavenDependency] = dependencies.collect { case dep: Dependency.MavenDependency => dep }

  def jarDependencies: List[Dependency.JarDependency] = dependencies.collect { case dep: Dependency.JarDependency => dep }
}

object Manifest {

  /**
    * Formats `manifest` as a string / a valid `.toml` file.
    * Parsing the output yields the original manifest, i.e., `manifest`.
    */
  def format(manifest: Manifest): String = {
    val packageSection = mkPackageSection(manifest)
    val flixDepSection = mkFlixDependencySection(manifest)
    val mvnDepSection = mkMavenDependencySection(manifest)
    val jarDepSection = mkJarDependencySection(manifest)
    List(packageSection, flixDepSection, mvnDepSection, jarDepSection)
      .map(formatTomlSection)
      .mkString(System.lineSeparator())
  }

  private def mkPackageSection(manifest: Manifest): TomlSection = {
    val repository = manifest.repository.map(proj => TomlEntry.Present(TomlKey("repository"), TomlExp.TomlValue(s"github:$proj")))
      .getOrElse(TomlEntry.Absent)
    val modules = manifest.modules match {
      case PackageModules.All => TomlEntry.Absent
      case PackageModules.Selected(included) =>
        TomlEntry.Present(TomlKey("modules"), TomlExp.TomlArray(included.toList.map(TomlExp.TomlValue.apply)))
    }
    val license = manifest.license.map(license => TomlEntry.Present(TomlKey("license"), TomlExp.TomlValue(license)))
      .getOrElse(TomlEntry.Absent)
    val name = TomlEntry.Present(TomlKey("name"), TomlExp.TomlValue(manifest.name))
    val description = TomlEntry.Present(TomlKey("description"), TomlExp.TomlValue(manifest.description))
    val version = TomlEntry.Present(TomlKey("version"), TomlExp.TomlValue(manifest.version))
    val flixVersion = TomlEntry.Present(TomlKey("flix"), TomlExp.TomlValue(manifest.flix))
    val authors = TomlEntry.Present(TomlKey("authors"), TomlExp.TomlArray(manifest.authors.map(TomlExp.TomlValue.apply)))

    TomlSection("package",
      List(
        name,
        description,
        version,
        repository,
        modules,
        flixVersion,
        license,
        authors,
      )
    )
  }

  private def mkFlixDependencySection(manifest: Manifest): TomlSection = {
    TomlSection("dependencies", manifest.flixDependencies.map(mkFlixDependency))
  }

  private def mkMavenDependencySection(manifest: Manifest) = {
    TomlSection("mvn-dependencies", manifest.mavenDependencies.map(mkMavenDependency))
  }

  private def mkJarDependencySection(manifest: Manifest) = {
    TomlSection("jar-dependencies", manifest.jarDependencies.map(mkJarDependency))
  }

  private def mkFlixDependency(dep: Dependency.FlixDependency): TomlEntry = {
    val key = TomlKey(dep.identifier)
    val version = TomlEntry.Present(TomlKey("version"), TomlExp.TomlValue(dep.version))
    // The default mount and the default security context are not rendered.
    val mount = if (dep.hasDefaultMount) TomlEntry.Absent else TomlEntry.Present(TomlKey("mount"), TomlExp.TomlValue(dep.mount))
    val security = dep.sctx match {
      case SecurityContext.Default => TomlEntry.Absent
      case sctx => TomlEntry.Present(TomlKey("security"), TomlExp.TomlValue(sctx))
    }
    // A record with only the version is rendered as the bare version string.
    val values = TomlExp.TomlRecord(List(version, mount, security).collect { case e: TomlEntry.Present => e })
    TomlEntry.Present(key, values)
  }

  private def mkMavenDependency(dep: Dependency.MavenDependency): TomlEntry = {
    val key = TomlKey(dep.identifier)
    val value = TomlExp.TomlValue(dep.versionTag)
    TomlEntry.Present(key, value)
  }

  private def mkJarDependency(dep: Dependency.JarDependency): TomlEntry = {
    val key = TomlKey(dep.identifier)
    val value = TomlExp.TomlValue(s"url:${dep.url}")
    TomlEntry.Present(key, value)
  }

  private def formatTomlSection(section0: TomlSection): String = {
    s"""[${section0.section}]
       |${padKeys(section0.entries.collect { case e: TomlEntry.Present => e }).map(formatTomlEntry).mkString(System.lineSeparator())}
       |""".stripMargin
  }

  private def formatTomlEntry(entry: TomlEntry): String = entry match {
    case TomlEntry.Absent => ""
    case TomlEntry.Present(key, texp) => s"${formatTomlKey(key)} = ${formatTomlExp(texp)}"
  }

  private def formatTomlExp(exp0: TomlExp): String = exp0 match {
    case TomlExp.TomlValue(v) =>
      val escaped = escape(v.toString)
      s"\"$escaped\""

    case TomlExp.TomlArray(v) =>
      v.map(formatTomlExp).mkString("[", ", ", "]")

    case TomlExp.TomlRecord(List(TomlEntry.Present(_, texp))) =>
      // Special case for record with only one key-value pair: just render the value.
      formatTomlExp(texp)

    case TomlExp.TomlRecord(v) =>
      v.map(formatTomlEntry).mkString("{ ", ", ", " }")
  }

  private def formatTomlKey(key0: TomlKey): String = {
    val padding = List.range(0, key0.padding).map(_ => " ").mkString
    s"\"${key0.k}\"$padding"
  }

  /** Returns the list of entries, where the padding has been adjusted to account for the longest key. */
  private def padKeys(entries: List[TomlEntry.Present]): List[TomlEntry.Present] = {
    val optLongestKey = entries.map(_.key.k.length).maxOption
    optLongestKey match {
      case Some(longestKey) => entries.map {
        case TomlEntry.Present(TomlKey(key, _), texp) => TomlEntry.Present(TomlKey(key, longestKey - key.length), texp)
      }
      case None => entries
    }
  }

  /** Escapes `\` and `"` characters to `\\` and `\"`, respectively. */
  private def escape(str: String): String = {
    str.replace("\\", "\\\\")
      .replace("\"", "\\\"")
  }

  private case class TomlSection(section: String, entries: List[TomlEntry])

  private sealed trait TomlEntry

  private object TomlEntry {

    case object Absent extends TomlEntry

    case class Present(key: TomlKey, value: TomlExp) extends TomlEntry

  }

  private case class TomlKey(k: String, padding: Int = 0)

  private sealed trait TomlExp

  private object TomlExp {

    case class TomlValue(v: Any) extends TomlExp

    case class TomlArray(v: List[TomlExp]) extends TomlExp

    case class TomlRecord(v: List[TomlEntry]) extends TomlExp

  }

}

/**
  * Utilities for the portable artifact basename represented by `package.name`.
  */
object PackageName {
  /** The portable 255-character component limit less the `.fpkg` extension. */
  val MaxLength: Int = 250

  private val DeviceSuffix = "-package"

  /** Returns `true` if `name` is valid as a portable package artifact basename. */
  def isValid(name: String): Boolean =
    name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*") &&
      name.length <= MaxLength &&
      !name.endsWith(".") &&
      !isWindowsDeviceName(name)

  /**
    * Derives a valid package artifact basename from an arbitrary directory name.
    *
    * Invalid characters become hyphens. Windows device names receive a suffix before any extension,
    * reserving room for that suffix before the result is truncated.
    */
  def normalize(rawName: String): String = {
    val portable = rawName.map {
      case c if c <= 0x7f && (c.isLetterOrDigit || c == '.' || c == '-' || c == '_') => c
      case _ => '-'
    }.dropWhile(c => !c.isLetterOrDigit)
    val fallback = if (portable.isEmpty) "package" else portable
    val limit = if (isWindowsDeviceName(fallback)) MaxLength - DeviceSuffix.length else MaxLength
    val truncated = fallback.take(limit).reverse.dropWhile(_ == '.').reverse
    val name = if (truncated.isEmpty) "package" else truncated
    if (isWindowsDeviceName(name)) insertDeviceSuffix(name) else name
  }

  /** Returns `true` if `name` is a Windows reserved device name, with an optional extension. */
  private def isWindowsDeviceName(name: String): Boolean = {
    val baseName = name.takeWhile(_ != '.').toLowerCase(java.util.Locale.ROOT)
    baseName == "con" || baseName == "prn" || baseName == "aux" || baseName == "nul" || baseName == "clock$" ||
      baseName.matches("com[1-9]") || baseName.matches("lpt[1-9]")
  }

  /** Inserts the suffix before an optional extension so the device basename is no longer reserved. */
  private def insertDeviceSuffix(name: String): String = {
    val dot = name.indexOf('.')
    if (dot < 0) s"$name$DeviceSuffix"
    else s"${name.substring(0, dot)}$DeviceSuffix${name.substring(dot)}"
  }
}
