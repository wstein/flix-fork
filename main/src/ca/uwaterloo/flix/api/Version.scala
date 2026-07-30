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
    * Represents the current version of Flix.
    */
  val CurrentVersion: Version = Version(major = 0, minor = 75, revision = 1, qualifier = Some("fork.wstein.260730.1"))
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
