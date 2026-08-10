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
package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.tools.pkg.github.GitHub
import ca.uwaterloo.flix.tools.pkg.github.GitHub.{Project, Release, ReleaseResponse}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.{Formatter, Result}
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.{compact, parse, render}
import org.json4s.{JInt, JLong, JString, JValue, jvalue2monadic}

import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
  * A cache of GitHub release listings, for the one thing that needs them.
  *
  * Installing an exact dependency does not read a listing; `outdated` has to, because "is there a
  * newer version" is a question only metadata answers. Asked once per dependency on every
  * invocation, that spends the anonymous hourly budget on a question whose answer changes rarely.
  *
  * The cache is deliberately **not** used by dependency resolution. A listing that is minutes stale
  * is a perfectly good answer to "what versions exist"; it is a bad answer to "where is the release
  * I just published", which is what an install would be asking.
  */
object ReleaseCache {

  /**
    * How long a listing is used without asking again.
    *
    * Long enough that a session's repeated `outdated` costs nothing, short enough that someone
    * checking after publishing a release does not have to wonder why it is missing. Past it the
    * listing is not discarded but revalidated, which is cheaper than fetching it again.
    */
  private val TimeToLiveMillis: Long = 15 * 60 * 1000

  /**
    * An entry: the listing as it was served, the entity tag to quote when asking again, and when it
    * was last known to be current.
    */
  private case class Entry(body: String, etag: Option[String], fetchedAt: Long)

  /**
    * Returns the releases of `project`, using the cache when it can.
    *
    * A fresh entry is used as it is. A stale one is revalidated with its entity tag, which either
    * confirms it or replaces it. If GitHub refuses the request, a cached listing is used however
    * old it is, with a note saying so: answering `outdated` from yesterday's data is more use than
    * refusing to answer, and the note is what stops that being silent.
    */
  def getReleases(project: Project, apiKey: Option[String], now: Long, cacheDir: Path = defaultCacheDirectory)(implicit formatter: Formatter, out: PrintStream): Result[List[Release], PackageError] = {
    val cached = read(project, cacheDir)

    cached match {
      case Some(entry) if now - entry.fetchedAt < TimeToLiveMillis =>
        GitHub.parseReleases(entry.body, project)

      case _ =>
        GitHub.fetchReleases(project, apiKey, cached.flatMap(_.etag)) match {
          case Ok(ReleaseResponse.Modified(body, etag)) =>
            write(project, cacheDir, Entry(body, etag, now))
            GitHub.parseReleases(body, project)

          case Ok(ReleaseResponse.NotModified) =>
            cached match {
              case Some(entry) =>
                // Still current: remember that, so the next call inside the window asks nothing.
                write(project, cacheDir, entry.copy(fetchedAt = now))
                GitHub.parseReleases(entry.body, project)
              case None =>
                // Only reachable if the entry vanished between reading and asking.
                GitHub.getReleases(project, apiKey)
            }

          case Err(e: PackageError.ApiRateLimited) =>
            cached match {
              case Some(entry) =>
                out.println(formatter.yellow(
                  s"  Using cached release data for ${project.toString}: GitHub's rate limit is spent."))
                GitHub.parseReleases(entry.body, project)
              case None => Err(e)
            }

          case Err(e) => Err(e)
        }
    }
  }

  /**
    * Returns the directory holding cached listings.
    *
    * A user-level directory rather than a project-level one: the answer is about a repository, not
    * about the project asking, so every checkout on the machine can share it. `FLIX_CACHE_DIR`
    * overrides it, which is what a CI job wants in order to place it somewhere it already caches.
    */
  def defaultCacheDirectory: Path = {
    val base = sys.env.get("FLIX_CACHE_DIR")
      .orElse(sys.env.get("XDG_CACHE_HOME").map(dir => s"$dir/flix"))
      .getOrElse(s"${System.getProperty("user.home")}/.cache/flix")
    Paths.get(base).resolve("releases")
  }

  /**
    * Returns the file holding the cached listing for `project`.
    */
  private def entryFile(project: Project, cacheDir: Path): Path =
    cacheDir.resolve(project.owner).resolve(s"${project.repo}.json")

  /**
    * Reads the entry for `project`, or nothing if there is none that can be read.
    *
    * A cache that cannot be read is a cache miss and never an error. It holds nothing that cannot
    * be fetched again, so a corrupt or half-written file must not be able to stop a build.
    */
  private def read(project: Project, cacheDir: Path): Option[Entry] = {
    val file = entryFile(project, cacheDir)
    try {
      if (!Files.exists(file)) return None
      val json = parse(Files.readString(file, StandardCharsets.UTF_8))
      val body = json \ "body" match {
        case JString(s) => Some(s)
        case _ => None
      }
      val etag = json \ "etag" match {
        case JString(s) => Some(s)
        case _ => None
      }
      val fetchedAt = json \ "fetchedAt" match {
        case JInt(n) => Some(n.toLong)
        case JLong(n) => Some(n)
        case _ => None
      }
      for (b <- body; f <- fetchedAt) yield Entry(b, etag, f)
    } catch {
      case _: Exception => None
    }
  }

  /**
    * Writes the entry for `project`.
    *
    * Written beside its destination and moved into place, so that a run interrupted mid-write
    * leaves the previous entry rather than a truncated one. Failing to write is ignored for the
    * same reason reading failures are: the cache is an optimisation, not a source of truth.
    */
  private def write(project: Project, cacheDir: Path, entry: Entry): Unit = {
    val file = entryFile(project, cacheDir)
    try {
      Files.createDirectories(file.getParent)
      val json: JValue =
        ("body" -> entry.body) ~
          ("etag" -> entry.etag) ~
          ("fetchedAt" -> entry.fetchedAt)
      val part = file.resolveSibling(s"${file.getFileName}.part")
      Files.writeString(part, compact(render(json)), StandardCharsets.UTF_8)
      Files.move(part, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: Exception => ()
    }
  }
}
