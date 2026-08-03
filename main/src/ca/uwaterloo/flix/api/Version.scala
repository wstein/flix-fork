/*
 * Copyright 2015-2016 Magnus Madsen
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

object Version {

  /**
    * Reads the `version.txt` resource written by the `versionResource` Mill task.
    *
    * Returns `None` if the resource is missing, which happens only outside Mill's managed build
    * (e.g. a stray classpath assembled by hand): the fallback below keeps that case working rather
    * than crashing over a value that only affects a version string.
    */
  private def readDescribe(): Option[String] = {
    val stream = getClass.getResourceAsStream("/version.txt")
    if (stream == null) {
      None
    } else {
      try Some(scala.io.Source.fromInputStream(stream).mkString.trim) finally stream.close()
    }
  }

  /**
    * Matches a fork release tag, e.g. `v0.75.1+fork.wstein.260802.1-5-gabc1234-dirty`. Release tags
    * are named `v<upstream-version>+fork.wstein.<YYMMDD>.<N>`; `git describe` appends
    * `-<commits>-g<sha>[-dirty]` to whichever tag it resolves to.
    */
  private val QualifiedDescribePattern = """^v(\d+)\.(\d+)\.(\d+)\+(\S+?)-(\d+)-g([0-9a-f]+)(-dirty)?$""".r

  /** Matches a plain semver tag with no fork qualifier, e.g. `v0.75.1-5-gabc1234-dirty`. */
  private val PlainDescribePattern = """^v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-f]+)(-dirty)?$""".r

  /**
    * Parses `describe` into a [[Version]], falling back to `0.0.0` with the raw string as the
    * qualifier if it doesn't match either expected format -- a fresh clone with no reachable `v*`
    * tag reports `unknown` here rather than failing the build.
    */
  private[api] def parseDescribe(describe: Option[String]): Version = describe match {
    case Some(QualifiedDescribePattern(major, minor, revision, fork, "0", _, null)) =>
      // Exactly on the tag with a clean tree: the tag's own fork qualifier is the whole story.
      Version(major.toInt, minor.toInt, revision.toInt, Some(fork))
    case Some(QualifiedDescribePattern(major, minor, revision, fork, commits, sha, dirty)) =>
      val suffix = if (dirty == null) "" else ".dirty"
      Version(major.toInt, minor.toInt, revision.toInt, Some(s"$fork.$commits.g$sha$suffix"))
    case Some(PlainDescribePattern(major, minor, revision, "0", _, null)) =>
      Version(major.toInt, minor.toInt, revision.toInt)
    case Some(PlainDescribePattern(major, minor, revision, commits, sha, dirty)) =>
      val suffix = if (dirty == null) "" else ".dirty"
      Version(major.toInt, minor.toInt, revision.toInt, Some(s"$commits.g$sha$suffix"))
    case Some(other) =>
      Version(0, 0, 0, Some(other))
    case None =>
      Version(0, 0, 0, Some("unknown"))
  }

  /**
    * Represents the current version of Flix.
    *
    * Derived at build time from `git describe --tags --long --dirty --match "v[0-9]*"`, written to the
    * `version.txt` resource by the `versionResource` task in `build.mill`. An exact tag (e.g.
    * `v0.75.1+fork.wstein.260802.1`, zero commits past it, clean tree) reports the tag's own fork
    * qualifier verbatim; anything else appends how far the build is from that tag, e.g.
    * `0.75.1+fork.wstein.260802.1.5.gabc1234` or `...5.gabc1234.dirty`.
    */
  val CurrentVersion: Version = parseDescribe(readDescribe())
}

/**
  * A case class to represent versions.
  */
case class Version(major: Int, minor: Int, revision: Int, qualifier: Option[String] = None) {
  override val toString: String = qualifier match {
    case Some(q) => s"$major.$minor.$revision+$q"
    case None => s"$major.$minor.$revision"
  }
}
