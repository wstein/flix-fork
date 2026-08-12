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

package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.util.{Options, Result}
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods
import org.json4s.{JArray, JBool, JInt, JString, JValue, jvalue2monadic}

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/**
  * A record of what the previous build wrote and of the non-source inputs it wrote them from.
  *
  * This is the equivalent of Zinc's analysis file, restricted to what Flix's compiler can
  * actually say. Two properties of the compiler decide the shape:
  *
  *   - `Flix.codeGen` is whole-program. Every call runs the monomorphizer, both tree shakers
  *     and `CodeGen` over the entire program, so the set of classes it emits is *complete*:
  *     it is every class the current sources require, not a subset that changed. A build that
  *     succeeds therefore knows the exact product set, and the products of the previous build
  *     that must disappear are precisely `previous.products -- current products`.
  *
  *   - For the same reason there is no per-source product ownership to record. Most generated
  *     classes are keyed on *types* aggregated over the whole program rather than on a
  *     declaration - tuples, records, function interfaces, closures, tag classes and the
  *     effect runtime are emitted from `root.types`, and a monomorphized specialization exists
  *     because of a call site in a source other than the one that declares it. `sources` is
  *     therefore the set of inputs the whole product set came from, not a per-file mapping. A
  *     per-file mapping would look like ownership while being wrong about it, and nothing in
  *     the deletion path needs one: the set difference above is exact where an ownership
  *     approximation would not be.
  *
  * The manifest is what makes a build that does *not* wipe the class directory safe:
  * without it a build that fails, or a build interrupted between writing classes and
  * packaging, leaves a directory nobody can describe.
  *
  * ==What makes a build skippable==
  *
  * [[sourcesDigest]] is the one thing here that is about the sources' *contents*, and it exists so
  * that a build with nothing to do can be recognised as such rather than repeated. The fingerprint
  * deliberately excludes source changes -- putting them there would force a full reset on every edit
  * -- but a *digest*, compared against and never reset from, answers a different question: are the
  * sources the ones this build was made from? With the fingerprint and the products both intact, that
  * is the whole up-to-date condition.
  *
  * It is a content hash and not a modification time, and that is the point. `Source` equality is by
  * path, so a build that reused state on the strength of an mtime would silently emit the previous
  * program; mtimes are millisecond-resolution at best and whole seconds on some filesystems, so two
  * writes inside one tick are ordinary. A digest cannot be fooled by a clock.
  *
  * @param fingerprint   the [[fingerprintOf]] hash of the non-source inputs of the build.
  * @param products      every class file the build wrote, as a class-directory-relative,
  *                      `/`-separated name, sorted.
  * @param sources       the source files the build read, as project-relative where possible,
  *                      `/`-separated, sorted.
  * @param sourcesDigest the [[digestOfSources]] hash of those sources' contents.
  * @param hasMain       whether the program the build compiled had a main entry point. Recorded
  *                      because a build that is skipped answers no question about the program, and a
  *                      client asking what to run must still be told.
  */
case class BuildManifest(fingerprint: String,
                         products: List[String],
                         sources: List[String],
                         sourcesDigest: String,
                         hasMain: Boolean)

object BuildManifest {

  /**
    * The name of the manifest file, relative to the build directory.
    *
    * `Bootstrap.clean` knows this name: it is the one file in the build directory that is
    * neither a class file nor documentation, and a `clean` that refused to delete it would
    * leave a manifest describing products that are gone.
    */
  val FileName: String = "build.json"

  /**
    * The format version of the manifest.
    *
    * A manifest written in any other version is discarded rather than migrated: it describes
    * products this compiler cannot interpret, and discarding it costs one full rebuild while
    * misreading it packages the wrong files.
    */
  private val FormatVersion: Int = 2

  /** Returns the path of the manifest inside the build directory `buildDir`. */
  def fileIn(buildDir: Path): Path = buildDir.resolve(FileName).normalize()

  /**
    * Returns a hash of every input of a build other than the source files themselves:
    * the compiler that runs it, the options that change what the back end emits, and the
    * dependencies it resolves against.
    *
    * A build whose fingerprint differs from the recorded one may not reuse anything the
    * previous build left behind. Source changes are deliberately *not* part of this: they are
    * handled by recompiling and diffing the product set, which is exact, whereas a source
    * change forcing a full reset would defeat the point.
    *
    * The option list is conservative in the safe direction - an option included here that
    * turns out to change nothing costs an occasional full rebuild, where one left out that does
    * change something would let a build reuse state produced under different settings. "Something"
    * means the front end as well as the back end: a recorded build also answers `flix check`, so an
    * option that changes what type checking reports belongs here too.
    * Jars are passed in rather than read from `options`, because that is where they live: the
    * project's resolved dependencies and the jars a `--lib` flag added are both inputs to a build,
    * and both are on the `Flix` instance's class loader. See `Bootstrap.fingerprintOf`.
    */
  def fingerprintOf(options: Options, dependencies: List[Path]): String = {
    val settings = List(
      s"format=$FormatVersion",
      s"compiler=${Version.CurrentVersion}",
      s"lib=${options.lib}",
      s"build=${options.build}",
      s"coverage=${options.coverage}",
      s"entryPoint=${options.entryPoint.map(_.toString).getOrElse("")}",
      s"subeffecting=${options.xsubeffecting.map(_.toString).toList.sorted.mkString(",")}",
      s"datalogDebug=${options.xdatalogDebug.map(_.toString).toList.sorted.mkString(",")}",
      s"newmono=${options.xnewmono}",
      s"debug=${options.xdebug}",
      s"chaosMonkey=${options.xchaosMonkey}",
      // Reaches `Weeder2`, so it changes whether a program compiles at all -- and now also whether a
      // recorded build may answer for a *type check*, which is the reason it was noticed. An option
      // that decides what the front end reports has to be here, not only one that reaches the back end.
      s"noDeprecated=${options.xnodeprecated}",
    )
    // A dependency is identified the way `Bootstrap` identifies a stale source - by size and
    // modification time - rather than by hashing it. Hashing every dependency jar on every
    // build costs more than the rebuild it would occasionally save.
    //
    // Deduplicated after stamping, not before: callers union lists that overlap - a project's Maven
    // jars are also in the class loader they were added to - and the same file can arrive under two
    // spellings of its path. Two identical stamps would otherwise make one build's fingerprint differ
    // from another's over nothing, and a build that is permanently stale against its own manifest
    // never reuses anything.
    val deps = dependencies.map(stampOf).distinct.sorted
    hash((settings ::: deps).mkString("\n"))
  }

  /**
    * Returns a hash of the *contents* of `sources`, together with their names.
    *
    * The name is in the digest as well as the content, so that renaming a file, adding one or deleting
    * one all change it: the question this answers is whether the project's sources are the ones a
    * build was made from, and that includes which files there are.
    *
    * A source that cannot be read counts as a change rather than as an error. It may have been deleted
    * between the scan and this call, and the safe answer to "is the build still current" is no.
    *
    * Cost: every project source is read on every build that consults this. That is a few milliseconds
    * against a whole-program compile of seconds, and it is the price of an answer a clock cannot give.
    * The standard library is not among them -- it is not a project source, and the compiler that
    * carries it is in [[fingerprintOf]] already.
    */
  def digestOfSources(projectPath: Path, sources: List[Path]): String = {
    val entries = sources.map { p =>
      val name = relativeName(projectPath, p)
      val content =
        try hashOf(Files.readAllBytes(p))
        catch { case _: Exception => "unreadable" }
      s"$name:$content"
    }
    hash(entries.sorted.mkString("\n"))
  }

  /**
    * Returns the manifest name of the already-relative path `relative`.
    *
    * Names are `/`-separated whatever the platform's separator is, so that a manifest written on
    * one platform still describes the same products on another.
    */
  def nameOf(relative: Path): String = relative.toString.replace('\\', '/')

  /**
    * Returns `path` relative to `dir`, as a manifest name, or the whole of `path` when it does
    * not lie under `dir`.
    *
    * A source outside the project - a dependency unpacked elsewhere, an absolute path handed to
    * the compiler - has no relative name, and recording it in full is more useful than failing
    * to record the build.
    */
  def relativeName(dir: Path, path: Path): String = {
    val d = dir.normalize()
    val p = path.normalize()
    if (p.startsWith(d)) nameOf(d.relativize(p)) else nameOf(p)
  }

  /**
    * Reads the manifest at `path`.
    *
    * Returns `None` if there is no manifest, or if it cannot be read as one of this format
    * version. A manifest that cannot be read is not an error: it means the state of the class
    * directory is unknown, which the caller answers with a full rebuild.
    */
  def read(path: Path): Option[BuildManifest] = {
    if (!Files.isRegularFile(path)) {
      return None
    }
    val json =
      try JsonMethods.parse(Files.readString(path))
      catch { case _: Exception => return None }

    (json \ "formatVersion") match {
      case JInt(v) if v == BigInt(FormatVersion) => ()
      case _ => return None
    }

    val fingerprint = (json \ "fingerprint") match {
      case JString(s) => s
      case _ => return None
    }

    val sourcesDigest = (json \ "sourcesDigest") match {
      case JString(s) => s
      case _ => return None
    }

    val hasMain = (json \ "hasMain") match {
      case JBool(b) => b
      case _ => return None
    }

    for {
      products <- stringsOf(json \ "products")
      sources <- stringsOf(json \ "sources")
    } yield BuildManifest(fingerprint, products, sources, sourcesDigest, hasMain)
  }

  /** Writes `manifest` to `path`, creating the parent directory if needed. */
  def write(path: Path, manifest: BuildManifest): Result[Unit, Exception] = {
    val json: JValue =
      ("formatVersion" -> FormatVersion) ~
        ("compilerVersion" -> Version.CurrentVersion.toString) ~
        ("fingerprint" -> manifest.fingerprint) ~
        ("products" -> manifest.products) ~
        ("sources" -> manifest.sources) ~
        ("sourcesDigest" -> manifest.sourcesDigest) ~
        ("hasMain" -> manifest.hasMain)
    try {
      Files.createDirectories(path.getParent.normalize())
      Files.write(path, JsonMethods.pretty(JsonMethods.render(json)).getBytes)
      Result.Ok(())
    } catch {
      case e: Exception => Result.Err(e)
    }
  }

  /** Returns the strings of the JSON array `json`, or `None` if it is not an array of strings. */
  private def stringsOf(json: JValue): Option[List[String]] = json match {
    case JArray(values) =>
      val strings = values.collect { case JString(s) => s }
      if (strings.length == values.length) Some(strings) else None
    case _ => None
  }

  /** Returns a stable identity of the dependency at `p`: its path, size, and modification time. */
  private def stampOf(p: Path): String = {
    val n = p.normalize()
    val size = try Files.size(n) catch { case _: Exception => -1L }
    val modified = try Files.getLastModifiedTime(n).toMillis catch { case _: Exception => -1L }
    s"dep=$n:$size:$modified"
  }

  /** Returns the SHA-256 of `s`, hex-encoded. */
  private def hash(s: String): String = hashOf(s.getBytes("UTF-8"))

  /** Returns the SHA-256 of `bytes`, hex-encoded. */
  private def hashOf(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"${b & 0xff}%02x").mkString
  }

}
