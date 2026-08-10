/*
 * Copyright 2023 Magnus Madsen
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

import ca.uwaterloo.flix.api.Bootstrap
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.tools.pkg.Dependency.{FlixDependency, JarDependency, MavenDependency}
import ca.uwaterloo.flix.tools.pkg.github.GitHub
import ca.uwaterloo.flix.util.{Formatter, Result}
import ca.uwaterloo.flix.util.Result.{Err, Ok, traverse}
import ca.uwaterloo.flix.util.collection.ListMap

import java.io.{IOException, PrintStream}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.{DigestInputStream, MessageDigest}
import java.util.HexFormat
import scala.collection.mutable
import scala.util.Using

object FlixPackageManager {

  /**
    * The name a release publishes its manifest under.
    *
    * `Bootstrap.release` uploads the project's `flix.toml` unchanged, so this is fixed for every
    * package rather than a convention that a publisher chooses.
    */
  private val ManifestAssetName: String = "flix.toml"

  /**
    * How the file to download is found.
    *
    * The distinction is the whole point of this change, so it is in the types rather than in a
    * boolean: an asset whose name is fixed by the release format is fetched from an address that is
    * computed, and only an asset named by whoever published it costs a REST request to find.
    */
  private sealed trait AssetSource

  private object AssetSource {

    /** The asset's name is known, so its address is too. Reads no API. */
    case class Named(assetName: String) extends AssetSource

    /**
      * The asset is published under a computable name by a current compiler, but releases made
      * before that was true named it after the directory they were built in. The computed address
      * is tried first and the listing is only read when it is not there, so a package published by
      * a current `flix release` costs no REST request and a legacy one costs one.
      */
    case class NamedOrLookedUp(assetName: String, apiKey: Option[String]) extends AssetSource
  }

  /**
    * An asset that has been opened, and the name it was found under when that name was computed.
    *
    * A checksum lives at an address computed from the asset's name, so it can only be looked for
    * when the asset's name was computed too. Asking for one on the legacy path would spend a
    * round trip on a 404 for every package published before checksums existed.
    */
  private case class OpenedAsset(download: GitHub.Download, computedName: Option[String])

  /**
    * Opens a stream over the asset described by `source`.
    */
  private def openAsset(project: GitHub.Project, version: SemVer, extension: String, source: AssetSource): Result[OpenedAsset, PackageError] =
    source match {
      case AssetSource.Named(assetName) =>
        GitHub.downloadReleaseAsset(project, version, assetName).map(OpenedAsset(_, Some(assetName)))
      case AssetSource.NamedOrLookedUp(assetName, apiKey) =>
        GitHub.downloadReleaseAsset(project, version, assetName) match {
          case Ok(download) => Ok(OpenedAsset(download, Some(assetName)))
          case Err(_: PackageError.ReleaseAssetNotFound) =>
            GitHub.findReleaseAsset(project, version, extension, apiKey)
              .flatMap(asset => GitHub.download(asset.url))
              .map(OpenedAsset(_, None))
          case Err(e) => Err(e)
        }
    }

  /**
    * Represents the dependency resolution of [[origin]].
    * All fields should be considered private except [[origin]] and [[manifests]].
    *
    * @param origin              the manifest that corresponds to the current / local project.
    * @param manifests           all manifests in the resolution.
    * @param immediateDependents all immediate dependents / parents of each manifest.
    * @param manifestToFlixDeps  a mapping from [[Manifest]]s to [[FlixDependency]]s.
    *                            A manifest is the resource a flix dependency resolves to.
    */
  case class Resolution(origin: Manifest,
                        manifests: List[Manifest],
                        immediateDependents: Map[Manifest, List[Manifest]],
                        manifestToFlixDeps: ListMap[Manifest, FlixDependency])

  /**
    * Represents the dependency resolution of [[origin]] where the maximum security level has been computed
    * for each manifest.
    *
    * @param origin             the manifest that corresponds to the current / local project.
    * @param security           the maximum allowed security level of each manifest.
    * @param manifestToFlixDeps a mapping from [[Manifest]]s to [[FlixDependency]]s.
    *                           A manifest is the resource a flix dependency resolves to.
    */
  case class SecureResolution(origin: Manifest,
                              security: Map[Manifest, SecurityContext],
                              manifestToFlixDeps: ListMap[Manifest, FlixDependency]) {
    /**
      * All manifests in the resolution.
      */
    val manifests: List[Manifest] = security.keys.toList
  }

  /**
    * Finds all the transitive dependencies for `manifest` and
    * returns their manifests. The toml files for the manifests
    * will be put at `path/lib`.
    */
  def findTransitiveDependencies(manifest: Manifest, path: Path)(implicit formatter: Formatter, out: PrintStream): Result[Resolution, PackageError] = {
    out.println("Resolving Flix dependencies...")
    implicit val immediateDependents: mutable.Map[Manifest, List[Manifest]] = mutable.Map(manifest -> List.empty)
    implicit val manifestToFlixDeps: mutable.Map[Manifest, List[FlixDependency]] = mutable.Map(manifest -> List.empty)
    findTransitiveDependenciesRec(manifest, path, List(manifest)).map(manifests => Resolution(manifest, manifests, immediateDependents.toMap, ListMap.from(manifestToFlixDeps.flatMap { case (m, deps) => deps.map(d => (m, d)) })))
  }

  /**
    * Resolves the maximal allowed security level for all dependencies in `resolution`.
    */
  def resolveSecurityLevels(resolution: Resolution): SecureResolution = {
    implicit val securityContexts: mutable.Map[Manifest, SecurityContext] = mutable.Map(resolution.origin -> SecurityContext.Unrestricted)
    implicit val res: Resolution = resolution
    val manifests = resolution.manifests.map(m => (m, minSecurityLevel(m))).toMap
    SecureResolution(resolution.origin, manifests, resolution.manifestToFlixDeps)
  }

  /**
    * Finds all security errors at the package level if any.
    * A security error exists if at least one of the following holds:
    *   1. A manifest `m0` has allowed security context `t0` and `m0` contains a dependency with security context `t1` where `t1 > t0`. E.g., `unrestricted > plain`.
    *   1. A manifest `m0` has security context `plain` or lower and contains at least one jar or maven dependency.
    */
  def checkSecurity(resolution: SecureResolution): List[PackageError] = {
    resolution.security.flatMap { case (m, t) => findSecurityViolations(m, t) }.toList
  }

  /**
    * Finds the Flix dependencies in a Manifest.
    */
  def findFlixDependencies(manifest: Manifest): List[FlixDependency] = {
    manifest.dependencies.collect { case dep: FlixDependency => dep }
  }

  /**
    * Finds the most relevant available updates for the given dependency.
    */
  def findAvailableUpdates(dep: FlixDependency, apiKey: Option[String]): Result[AvailableUpdates, PackageError] = {
    for {
      githubProject <- GitHub.parseProject(s"${dep.username}/${dep.projectName}")
      releases <- GitHub.getReleases(githubProject, apiKey)
      availableVersions = releases.map(r => r.version)

      ver = dep.version
      major = ver.majorUpdate(availableVersions)
      minor = ver.minorUpdate(availableVersions)
      patch = ver.patchUpdate(availableVersions)
    } yield AvailableUpdates(major, minor, patch)
  }

  /**
    * Installs all the Flix dependencies of `resolution` into the `lib/` directory of `projectRoot` and
    * returns a list of paths to all the dependencies along with their allowed security context.
    */
  def installAll(resolution: SecureResolution, projectRoot: Path, apiKey: Option[String])(implicit formatter: Formatter, out: PrintStream): Result[List[(Path, SecurityContext)], PackageError] = {
    out.println("Downloading Flix dependencies...")

    val allFlixDeps = ListMap.from(resolution.manifestToFlixDeps.map { case (manifest, flixDep) => resolution.security(manifest) -> flixDep })

    val flixPaths = allFlixDeps.map { case (sctx, dep) =>
      val depName: String = s"${dep.username}/${dep.projectName}"
      install(depName, dep.version, "fpkg", AssetSource.NamedOrLookedUp(s"${dep.projectName}.fpkg", apiKey), projectRoot) match {
        case Ok(p) => (p, sctx)
        case Err(e) =>
          out.println(s"ERROR: Installation of `$depName' failed.")
          return Err(e)
      }
    }.toList

    Ok(flixPaths)
  }

  /**
    * Installs a flix package from the Github `project`.
    *
    * `project` must be of the form `<owner>/<repo>`
    *
    * The package is installed at `lib/<owner>/<repo>`
    *
    * `source` says how to reach the file; `extension` names the local copy, which keeps the
    * `<repo>-<version>.<extension>` form it has always had so that existing caches are still found.
    *
    * Returns the path to the downloaded file.
    */
  private def install(project: String, version: SemVer, extension: String, source: AssetSource, p: Path)(implicit formatter: Formatter, out: PrintStream): Result[Path, PackageError] = {
    GitHub.parseProject(project).flatMap { proj =>
      val lib = Bootstrap.getLibraryDirectory(p)
      val localName = s"${proj.repo}-$version.$extension"
      val dirPath = lib.resolve("github").resolve(proj.owner).resolve(proj.repo).resolve(version.toString)
      // create the directory if it does not exist
      Files.createDirectories(dirPath)
      val assetPath = dirPath.resolve(localName)

      if (Files.exists(assetPath)) {
        out.println(s"  Cached `${formatter.blue(s"${proj.owner}/${proj.repo}.$extension")}` (${formatter.cyan(s"v$version")}).")
        Ok(assetPath)
      } else {
        out.print(s"  Downloading `${formatter.blue(s"${proj.owner}/${proj.repo}.$extension")}` (${formatter.cyan(s"v$version")})... ")
        out.flush()
        fetchInto(proj, version, extension, source, assetPath, localName) match {
          case Ok(verified) =>
            out.println(if (verified) "OK (sha256 verified)." else "OK.")
            Ok(assetPath)
          case Err(e) =>
            out.println("ERROR.")
            Err(e)
        }
      }
    }
  }

  /**
    * Downloads the asset described by `source` to `assetPath`, and reports whether a published
    * checksum was checked.
    *
    * Nothing is written to `assetPath` until the bytes have been accepted. The download goes to a
    * temporary file beside it and is moved into place only once the length and, when the release
    * publishes one, the digest agree. Writing straight to the final path leaves a truncated file
    * behind when a connection drops, and the next run finds that file, calls it cached, and never
    * downloads it again -- a corrupt dependency that survives every retry.
    */
  private def fetchInto(project: GitHub.Project, version: SemVer, extension: String, source: AssetSource, assetPath: Path, localName: String): Result[Boolean, PackageError] = {
    val partPath = assetPath.resolveSibling(s"${assetPath.getFileName}.part")

    val result = for {
      opened <- openAsset(project, version, extension, source)
      written <- copyToFile(opened.download, partPath, project, version, localName)
      _ <- verifyLength(opened.download, written, project, version, localName)
      // A checksum that cannot be read is an error rather than a reason to skip verification: a
      // release that publishes one is not installed unverified because the digest was unreachable.
      expected <- checksumOf(project, version, opened.computedName)
      _ <- verifyDigest(expected, written, project, version, localName)
    } yield {
      Files.move(partPath, assetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      expected.isDefined
    }

    if (result.isInstanceOf[Err[?, ?]]) {
      Files.deleteIfExists(partPath)
    }
    result
  }

  /**
    * Returns the digest published for the asset, if the release publishes one.
    *
    * Only an asset reached at a computed address can have a computed checksum address. A release
    * old enough to need the listing predates published checksums, so it is not asked for one.
    */
  private def checksumOf(project: GitHub.Project, version: SemVer, computedName: Option[String]): Result[Option[String], PackageError] =
    computedName match {
      case Some(assetName) => GitHub.fetchChecksum(project, version, assetName)
      case None => Ok(None)
    }

  /**
    * Copies `download` to `path`, returning what was written.
    */
  private def copyToFile(download: GitHub.Download, path: Path, project: GitHub.Project, version: SemVer, localName: String): Result[WrittenFile, PackageError] = {
    val digest = MessageDigest.getInstance("SHA-256")
    try {
      Using.resource(new DigestInputStream(download.stream, digest)) { stream =>
        val bytes = Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING)
        Ok(WrittenFile(bytes, HexFormat.of().formatHex(digest.digest())))
      }
    } catch {
      case e: IOException => Err(PackageError.DownloadIncomplete(project, version, localName, Some(e.getMessage)))
    }
  }

  /** What a completed write produced. */
  private case class WrittenFile(bytes: Long, sha256: String)

  /**
    * Checks the bytes received against the length the server promised, when it promised one.
    */
  private def verifyLength(download: GitHub.Download, written: WrittenFile, project: GitHub.Project, version: SemVer, localName: String): Result[Unit, PackageError] =
    download.contentLength match {
      case Some(expected) if expected != written.bytes =>
        Err(PackageError.DownloadTruncated(project, version, localName, expected, written.bytes))
      case _ => Ok(())
    }

  /**
    * Checks the bytes received against the published digest, when the release published one.
    */
  private def verifyDigest(expected: Option[String], written: WrittenFile, project: GitHub.Project, version: SemVer, localName: String): Result[Unit, PackageError] =
    expected match {
      case Some(digest) if !digest.equalsIgnoreCase(written.sha256) =>
        Err(PackageError.ChecksumMismatch(project, version, localName, digest, written.sha256))
      case _ => Ok(())
    }

  /**
    * Recursively finds all transitive dependencies of `manifest`.
    * Downloads any missing toml files for found dependencies and
    * parses them to manifests. Returns the list of manifests.
    * `res` is the list of Manifests found so far to avoid duplicates.
    */
  private def findTransitiveDependenciesRec(manifest: Manifest, path: Path, res: List[Manifest])(implicit immediateDependents: mutable.Map[Manifest, List[Manifest]], manifestToDep: mutable.Map[Manifest, List[Dependency.FlixDependency]], formatter: Formatter, out: PrintStream): Result[List[Manifest], PackageError] = {
    // find Flix dependencies of the current manifest
    val flixDeps = findFlixDependencies(manifest)

    for {
      // download toml files
      tomlPaths <- traverse(flixDeps) { dep =>
        val depName = s"${dep.username}/${dep.projectName}"
        install(depName, dep.version, "toml", AssetSource.Named(ManifestAssetName), path).map(p => (p, dep))
      }

      // parse manifests
      transitiveManifests <- traverse(tomlPaths) { case (p, d) => validateManifest(p, d) }

    } yield {
      for (m <- transitiveManifests) {
        immediateDependents.put(m, manifest :: immediateDependents.getOrElse(m, List.empty))
      }

      // remove duplicates
      val newManifests = transitiveManifests.filter(!res.contains(_))
      var newRes = res ++ newManifests

      // do recursive calls for all dependencies
      for (m <- newManifests) {
        findTransitiveDependenciesRec(m, path, newRes) match {
          case Ok(t) => newRes = newRes ++ t.filter(!newRes.contains(_))
          case Err(e) => return Err(e)
        }
      }
      newRes
    }
  }

  /** Parses and validates the manifest at `p`
    * w.r.t. `d` by checking that declared and required versions match.
    *
    * Also mutates `manifestToDep` by adding or updating the mapping `m -> ds` to `m -> d :: ds`.
    */
  private def validateManifest(p: Path, flixDep: FlixDependency)(implicit manifestToDep: mutable.Map[Manifest, List[Dependency.FlixDependency]]): Result[Manifest, PackageError] = {
    parseManifest(p).flatMap {
      m =>
        manifestToDep.put(m, flixDep :: manifestToDep.getOrElse(m, List.empty))
        if (m.version == flixDep.version) {
          Ok(m)
        } else {
          Err(PackageError.MismatchedVersions(m, flixDep))
        }
    }
  }

  /**
    * Computes the maximum allowed security level for `manifest` which is the minimum / greatest lower bound of both
    *   1. the [[minSecurityLevel]] of all (transitive) dependent / parent manifests and
    *   1. the security levels with which `manifest` is depended upon,
    *      i.e., when `"security" = "..."` occurs in a manifest and that dependency points to `manifest`.
    */
  private def minSecurityLevel(manifest: Manifest)(implicit resolution: Resolution, securityLevels: mutable.Map[Manifest, SecurityContext]): SecurityContext = {
    securityLevels.get(manifest) match {
      case Some(t) => t
      case None =>
        val incomingSctxs = resolution.manifestToFlixDeps(manifest).map(_.sctx)
        val parentSctxs = resolution.immediateDependents(manifest).map(minSecurityLevel)
        val glb = SecurityContext.glb(parentSctxs ::: incomingSctxs)
        securityLevels.put(manifest, glb)
        glb
    }
  }

  /**
    * A security error is present if one of the following holds:
    *   1. A manifest `m0` has allowed security context `t0` and `m0` contains a dependency with security context `t1` where `t1 > t0`. E.g., `unrestricted > plain`.
    *   1. A manifest `m0` has security context `plain` or lower and contains at least one jar or maven dependency.
    *
    * Note that the first condition represents an inconsistency in the security levels of the dependency graph itself.
    * Such an inconsistency exists if for some path `u ~> v` it holds that `security(u) >= security(v)`
    * and an edge `v -> w` exists where `security(w) > security(u)`.
    * E.g., if an edge with security context `unrestricted` is found on a path with max security context `plain`, then an error exists.
    */
  private def findSecurityViolations(m: Manifest, sctx: SecurityContext): List[PackageError] = {
    val graphErrors = checkGraphErrors(m, sctx)
    val dependencyErrors = checkJavaDependencies(m, sctx)
    dependencyErrors ::: graphErrors
  }

  /**
    * Checks condition 1 of [[findSecurityViolations]] and returns all security inconsistencies.
    */
  private def checkGraphErrors(m: Manifest, sctx: SecurityContext): List[PackageError.DepGraphSecurityError] = {
    val flixDeps = m.dependencies.collect { case d: FlixDependency => d }
    flixDeps.filter(d => d.sctx.greaterThan(sctx)).map(d => PackageError.DepGraphSecurityError(m, d, sctx))
  }

  /**
    * Checks condition 2 of [[findSecurityViolations]] and returns all illegal dependencies.
    */
  private def checkJavaDependencies(m: Manifest, sctx: SecurityContext): List[PackageError.IllegalJavaDependencyForSctx] = sctx match {
    case SecurityContext.Unrestricted =>
      List.empty

    case SecurityContext.Plain =>
      // No maven or jar deps allowed
      m.dependencies.collect {
        case d: MavenDependency => d
        case d: JarDependency => d
      }.map(d => PackageError.IllegalJavaDependencyForSctx(m, d, sctx))

    case SecurityContext.Paranoid =>
      // No maven or jar deps allowed
      m.dependencies.collect {
        case d: MavenDependency => d
        case d: JarDependency => d
      }.map(d => PackageError.IllegalJavaDependencyForSctx(m, d, sctx))
  }

  /**
    * Parses the toml file at `path` into a Manifest,
    * and converts any error to a PackageError.
    */
  private def parseManifest(path: Path): Result[Manifest, PackageError] = {
    ManifestParser.parse(path) match {
      case Ok(t) => Ok(t)
      case Err(e) => Err(PackageError.ManifestParseError(e))
    }
  }

}
