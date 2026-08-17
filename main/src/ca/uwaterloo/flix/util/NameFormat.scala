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
package ca.uwaterloo.flix.util

/**
  * The format of compiler-generated names: which version of it this compiler speaks, and how
  * a key is presented to [[StableName]] before it is hashed.
  *
  * See `docs/adr/0002-content-addressed-generated-names.md`.
  */
object NameFormat {

  /**
    * The version of the generated-name format.
    *
    * Bump it whenever a key shape, the hash, or the rendering changes -- that is, whenever the
    * same program would compile to different class names than it did before. A downstream
    * consumer holding recorded names (a build directory, a snapshot, a published jar) can then
    * tell "the compiler names things differently now" from "this program changed".
    *
    * The version is part of every preimage, so a bump *necessarily* changes every generated
    * name. That is what makes it self-enforcing rather than a number someone remembers to
    * increment: a key shape cannot change without moving the goldens in `TestNameFormat`, and
    * the version cannot change without moving them either, so neither passes silently.
    */
  val Version: Int = 1

  /**
    * Returns the preimage hashed for `key`: the key under the current format version.
    *
    * The separator is a character no key produces on its own, so `preimage("a|b")` and
    * `preimage("a") + "|b"` cannot be confused for one another.
    */
  def preimage(key: String): String = s"v$Version|$key"

  /**
    * Returns the sentence a name-collision message ends with, given the configured id width.
    *
    * A collision at the supported width is a defect in whatever built the key: at 62 bits, a
    * program would need on the order of a billion generated names before one became likely.
    * Below it, a collision is the arithmetic working as expected, and the reader needs to know
    * that before going looking for a bug.
    */
  def collisionAdvice(width: Int): String =
    if (width >= StableName.DefaultWidth)
      s" Ids are $width base-36 digits (--Xstable-name-length), so this is a key defect rather than a narrow-width collision."
    else
      s" Ids are $width base-36 digits (--Xstable-name-length), below the supported width of ${StableName.DefaultWidth}, where collisions are expected."

}
