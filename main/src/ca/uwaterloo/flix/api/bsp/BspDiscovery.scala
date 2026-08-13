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
package ca.uwaterloo.flix.api.bsp

import ca.uwaterloo.flix.api.Version
import ca.uwaterloo.flix.util.{Result, Validation}
import ch.epfl.scala.bsp4j.Bsp4j
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods
import org.json4s.{JString, JValue, jvalue2monadic}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/**
  * The `.bsp/flix.json` file a client reads to find this server.
  *
  * ==Why a command and not something a build writes==
  *
  * A discovery file names a jar on *this* machine, and writing one is a change to the user's
  * project. Neither belongs in `flix init` (which would put a machine-local path in a fresh
  * repository) nor in `flix bsp` itself (a server that edits the workspace when a client connects is
  * a surprise, and it would be writing while its own stdout is the protocol). sbt and Mill both make
  * it an explicit command; so does this.
  *
  * ==Why it refuses more than it overwrites==
  *
  * `.bsp/` is shared: another build tool's connection file lives beside this one, and a file called
  * `flix.json` that some other tool wrote is still not ours to replace. So a file whose `name` is not
  * this server's is left alone unless the caller insists.
  */
object BspDiscovery {

  /** The directory a client looks in, relative to the project. */
  private val DiscoveryDirectory: String = ".bsp"

  /** The file this server owns inside it. */
  private val DiscoveryFile: String = "flix.json"

  /** Returns the path of the connection file for the project at `projectPath`. */
  def connectionFile(projectPath: Path): Path =
    projectPath.resolve(DiscoveryDirectory).resolve(DiscoveryFile).normalize()

  /**
    * Writes the connection file for the project at `projectPath`.
    *
    * @param jar   the compiler jar to name, or `None` to work it out from where this class was loaded.
    * @param force overwrite a connection file this server does not own.
    */
  def install(projectPath: Path, jar: Option[Path], force: Boolean): Result[Path, String] = {
    val file = connectionFile(projectPath)

    for {
      argv <- commandLine(jar)
      _ <- refuseForeignFile(file, force)
      _ <- write(file, document(argv))
    } yield {
      file
    }
  }

  /** Returns the document to write, as a client expects to read it. */
  private def document(argv: List[String]): JValue =
    ("name" -> BspSession.ServerName) ~
      ("version" -> Version.CurrentVersion.toString) ~
      ("bspVersion" -> Bsp4j.PROTOCOL_VERSION) ~
      ("languages" -> List(BuildTargets.LanguageId)) ~
      ("argv" -> argv)

  /**
    * Returns the command that starts this server.
    *
    * Absolute throughout, because a client runs it with the *workspace* as its working directory and
    * a relative path would resolve against the wrong place.
    *
    * Three cases, and the third refuses rather than guesses. A jar is the ordinary one. A directory
    * means the compiler is running from a checkout -- from Mill or an IDE -- and the command has to
    * name the classpath instead; that is machine-local and said to be, but refusing would make the
    * feature undevelopable in the tree where it is developed.
    */
  private def commandLine(jar: Option[Path]): Result[List[String], String] = {
    val java = javaBinary()
    jar.map(_.toAbsolutePath.normalize()) match {
      case Some(explicit) =>
        if (!Files.isRegularFile(explicit)) Result.Err(s"no jar at '$explicit'")
        else Result.Ok(List(java, "-jar", explicit.toString, "bsp"))

      case None => ownLocation() match {
        case Some(location) if Files.isRegularFile(location) =>
          Result.Ok(List(java, "-jar", location.toString, "bsp"))

        case Some(_) =>
          // A checkout, not a jar. `java.class.path` is what this JVM was actually started with.
          val classpath = System.getProperty("java.class.path")
          if (classpath == null || classpath.isEmpty) {
            Result.Err("cannot tell how this compiler was started; pass --jar")
          } else {
            Result.Ok(List(java, "-cp", classpath, "ca.uwaterloo.flix.Main", "bsp"))
          }

        case None =>
          Result.Err("cannot find the running compiler's location; pass --jar")
      }
    }
  }

  /** Returns `true` if the connection file was written from a checkout rather than a jar. */
  def isFromCheckout(jar: Option[Path]): Boolean =
    jar.isEmpty && !ownLocation().exists(Files.isRegularFile(_))

  /** Returns where this class was loaded from, if the JVM will say. */
  private def ownLocation(): Option[Path] =
    for {
      source <- Option(getClass.getProtectionDomain).flatMap(d => Option(d.getCodeSource))
      location <- Option(source.getLocation)
      path <- try Some(Paths.get(location.toURI)) catch { case _: Exception => None }
    } yield path.toAbsolutePath.normalize()

  /** Returns the `java` of the running JVM, which is the one that can run this compiler. */
  private def javaBinary(): String = {
    val name = if (System.getProperty("os.name", "").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", name).toAbsolutePath.normalize().toString
  }

  /**
    * Fails if `file` exists and belongs to something other than this server, unless `force`.
    *
    * An unreadable or unparseable file counts as foreign: it is not ours to interpret, and
    * clobbering it because it could not be understood is the wrong way round.
    */
  private def refuseForeignFile(file: Path, force: Boolean): Result[Unit, String] = {
    if (force || !Files.exists(file)) {
      return Result.Ok(())
    }
    val owned =
      try {
        JsonMethods.parse(Files.readString(file)) \ "name" match {
          case JString(BspSession.ServerName) => true
          case _ => false
        }
      } catch {
        case _: Exception => false
      }
    if (owned) Result.Ok(())
    else Result.Err(s"'$file' was not written by this server. Pass --force to replace it.")
  }

  /**
    * Writes `json` to `file` through a temporary file in the same directory.
    *
    * A client may be reading it at the same moment -- that is what it is for -- and a half-written
    * connection file is one a client cannot parse. A rename in the same directory is atomic where
    * writing in place is not.
    */
  private def write(file: Path, json: JValue): Result[Unit, String] = {
    try {
      Files.createDirectories(file.getParent)
      val text = JsonMethods.pretty(JsonMethods.render(json)) + System.lineSeparator()
      val temp = Files.createTempFile(file.getParent, ".flix-bsp", ".tmp")
      try {
        Files.write(temp, text.getBytes(StandardCharsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Result.Ok(())
      } finally {
        Files.deleteIfExists(temp)
      }
    } catch {
      case e: Exception => Result.Err(s"cannot write '$file': ${e.getMessage}")
    }
  }
}
