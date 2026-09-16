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
import org.json4s.{JArray, JBool, JInt, JNothing, JNull, JString, JValue, jvalue2monadic}

import java.nio.file.{Files, Path}
import java.security.MessageDigest

/**
  * A record of what the previous build wrote and of the non-source inputs it wrote them from.
  *
  * @param fingerprint         the [[fingerprintOf]] hash of the non-source inputs of the build.
  * @param frontendFingerprint the [[frontendFingerprintOf]] hash of those of them that can change what
  *                            the front end reports.
  * @param products            every class file the build wrote, as a class-directory-relative,
  *                            `/`-separated name, sorted.
  * @param sources             the source files the build read, as project-relative where possible,
  *                            `/`-separated, sorted.
  * @param sourcesDigest       the [[digestOfSources]] hash of those sources' contents.
  * @param hasMain             whether the program the build compiled had a main entry point.
  * @param launch              how to start what this build produced. See [[LaunchSpec]].
  */
case class BuildManifest(fingerprint: String,
                         frontendFingerprint: String,
                         products: List[String],
                         sources: List[String],
                         sourcesDigest: String,
                         hasMain: Boolean,
                         launch: LaunchSpec)

/**
  * How to start the program a build left behind, without asking the compiler again.
  *
  * @param java             the absolute path of the `java` to start the program with.
  * @param mainClass        the class with the program's entry point, or `None` when the build had no main.
  * @param runtimeClasspath the classpath entries, absolute, in order.
  */
case class LaunchSpec(java: String, mainClass: Option[String], runtimeClasspath: List[String])

object BuildManifest {

  /** The name of the manifest file, relative to the build output directory. */
  val FileName: String = "build.json"

  /**
    * The format version of the manifest.
    *
    * Format version 4 carries the `launch` object containing `java`, `mainClass`, and `runtimeClasspath`.
    */
  val FormatVersion: Int = 4

  /** Returns the path of the manifest inside the build directory `buildDir`. */
  def fileIn(buildDir: Path): Path = buildDir.resolve(FileName).normalize()

  /**
    * Returns a hash of every input of a build other than the source files themselves:
    * the compiler version, options, and dependencies.
    */
  def fingerprintOf(options: Options, dependencies: List[Path]): String =
    hash(stampedInputs(options, dependencies).mkString("\n"))

  /**
    * Returns the fingerprint of the inputs that can change what the front end reports.
    */
  def frontendFingerprintOf(options: Options, dependencies: List[Path]): String =
    hash(frontendInputs(options, dependencies).mkString("\n"))

  private def stampedInputs(options: Options, dependencies: List[Path]): List[String] = {
    val settings = List(
      s"format=$FormatVersion",
      s"compiler=${Version.CurrentVersion}",
      s"lib=${options.lib}",
      s"build=${options.build}",
      s"entryPoint=${options.entryPoint.map(_.toString).getOrElse("")}",
      s"subeffecting=${options.xsubeffecting.map(_.toString).toList.sorted.mkString(",")}",
      s"chaosMonkey=${options.xchaosMonkey}",
      s"noDeprecated=${options.xnodeprecated}",
      s"inMemory=${options.inMemory}",
      s"newmono=${options.xnewmono}"
    )
    settings ::: dependencies.map(stampOf).distinct.sorted
  }

  private def frontendInputs(options: Options, dependencies: List[Path]): List[String] = {
    val settings = List(
      s"format=$FormatVersion",
      s"compiler=${Version.CurrentVersion}",
      s"lib=${options.lib}",
      s"noDeprecated=${options.xnodeprecated}"
    )
    settings ::: dependencies.map(stampOf).distinct.sorted
  }

  /**
    * Computes a deterministic SHA-256 digest of the contents of the given source files.
    */
  def digestOfSources(projectPath: Path, sources: List[Path]): String = {
    val entries = sources.map { p =>
      val name = relativeName(projectPath, p)
      val content =
        try hashOf(Files.readAllBytes(p))
        catch { case _: Exception => "<missing>" }
      s"$name=$content"
    }
    hash(entries.sorted.mkString("\n"))
  }

  /** Reads a [[BuildManifest]] from `path`, returning `None` if absent or incompatible. */
  def read(path: Path): Option[BuildManifest] = {
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

    val frontendFingerprint = (json \ "frontendFingerprint") match {
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

    val launch = launchOf(json \ "launch") match {
      case Some(spec) => spec
      case None => return None
    }

    for {
      products <- stringsOf(json \ "products")
      sources <- stringsOf(json \ "sources")
    } yield BuildManifest(fingerprint, frontendFingerprint, products, sources, sourcesDigest, hasMain, launch)
  }

  private def launchOf(json: JValue): Option[LaunchSpec] = {
    val java = (json \ "java") match {
      case JString(s) => s
      case _ => return None
    }
    val mainClass = (json \ "mainClass") match {
      case JString(s) => Some(s)
      case JNothing | JNull => None
      case _ => return None
    }
    stringsOf(json \ "runtimeClasspath").map(LaunchSpec(java, mainClass, _))
  }

  /** Writes `manifest` to `path` atomically. */
  def write(path: Path, manifest: BuildManifest): Result[Unit, Exception] = {
    val json: JValue =
      ("formatVersion" -> FormatVersion) ~
        ("compilerVersion" -> Version.CurrentVersion.toString) ~
        ("fingerprint" -> manifest.fingerprint) ~
        ("frontendFingerprint" -> manifest.frontendFingerprint) ~
        ("products" -> manifest.products) ~
        ("sources" -> manifest.sources) ~
        ("sourcesDigest" -> manifest.sourcesDigest) ~
        ("hasMain" -> manifest.hasMain) ~
        ("launch" -> (
          ("java" -> manifest.launch.java) ~
            ("mainClass" -> manifest.launch.mainClass) ~
            ("runtimeClasspath" -> manifest.launch.runtimeClasspath)))
    try {
      val parent = path.getParent
      if (parent != null) Files.createDirectories(parent)
      val temp = Files.createTempFile(parent, "build-manifest", ".tmp")
      Files.writeString(temp, JsonMethods.pretty(JsonMethods.render(json)) + "\n")
      Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
      Result.Ok(())
    } catch {
      case e: Exception => Result.Err(e)
    }
  }

  private def stringsOf(json: JValue): Option[List[String]] = json match {
    case JArray(values) =>
      val strings = values.collect { case JString(s) => s }
      if (strings.length == values.length) Some(strings) else None
    case _ => None
  }

  private def stampOf(p: Path): String = {
    val n = p.normalize()
    val size = try Files.size(n) catch { case _: Exception => -1L }
    val modified = try Files.getLastModifiedTime(n).toMillis catch { case _: Exception => -1L }
    s"dep=$n:$size:$modified"
  }

  private def relativeName(projectPath: Path, p: Path): String = {
    val rel = if (p.startsWith(projectPath)) projectPath.relativize(p).toString else p.toString
    rel.replace('\\', '/')
  }

  private def hash(s: String): String = hashOf(s.getBytes("UTF-8"))

  private def hashOf(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"${b & 0xff}%02x").mkString
  }
}
