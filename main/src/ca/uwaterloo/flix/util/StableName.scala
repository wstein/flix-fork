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

import ca.uwaterloo.flix.language.ast.SourceLocation

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
  * Content-addressed suffixes for generated symbol names: a SHA-256 digest of a
  * canonical key, truncated and rendered as fixed-width lowercase base-36.
  *
  * `width` (in base-36 digits) is the only user-facing knob; see [[suffix]].
  */
object StableName {

  /** The default width: 12 base-36 digits (roughly 62 bits of digest entropy). */
  val DefaultWidth: Int = 12

  /**
    * The largest width this utility accepts: 25 base-36 digits, roughly 128 bits
    * of digest entropy. SHA-256 has 256 bits of digest to draw from in total (a
    * hard ceiling around 49 base-36 digits); requests are capped well below that
    * so generated names cannot grow unreasonably long.
    */
  val MaxWidth: Int = 25

  private val Log2_36: Double = math.log(36) / math.log(2)

  /** Returns the approximate number of bits of entropy a `width`-digit base-36 string carries. */
  def bitsFor(width: Int): Int = (width * Log2_36).toInt

  private val Digest: ThreadLocal[MessageDigest] =
    ThreadLocal.withInitial(() => MessageDigest.getInstance("SHA-256"))

  /**
    * Returns the stable id of `key`: its SHA-256 digest, read as an unsigned
    * integer and reduced modulo `36^width`.
    */
  def of(key: String, width: Int = DefaultWidth): BigInt = {
    require(width >= 1, s"width must be positive, got $width")
    require(width <= MaxWidth, s"width must be at most $MaxWidth, got $width")
    val digest = Digest.get().digest(key.getBytes(StandardCharsets.UTF_8))
    val full = BigInt(1, digest)
    // Modulo, not floored to whole bits: log2(36) is irrational, so flooring wastes up to
    // a bit of entropy per character depending on width. The resulting bias is negligible
    // (on the order of 2^-127 at the largest supported width).
    full % BigInt(36).pow(width)
  }

  /**
    * Renders `id` as lowercase base-36, left-padded with zeros to `width` digits.
    *
    * Padding is what makes `width` a width rather than an upper bound: roughly one
    * id in 36 reduces to a value with a leading zero digit, and an unpadded render
    * of it is indistinguishable in shape from a shorter id or from a counter.
    */
  private def render(id: BigInt, width: Int): String = {
    val digits = id.toString(36)
    if (digits.length >= width) digits else "0" * (width - digits.length) + digits
  }

  /**
    * Returns `suffix` if it is a well-formed id of exactly `width` lowercase base-36 digits,
    * and throws an [[InternalCompilerException]] otherwise.
    *
    * Uniform length is a property the rest of the compiler is entitled to rely on: a generated
    * name is read back by tooling that has only the string, so an id one digit short is
    * indistinguishable from a narrower id or from a `GenSym` counter. Padding makes a short id
    * unreachable; this makes it *rejected*, so a future change to the rendering fails here
    * rather than in a build directory. Lowercase, because two ids differing only in case are
    * one file on a case-insensitive filesystem.
    */
  def validated(suffix: String, width: Int): String = {
    if (suffix.length != width) {
      throw InternalCompilerException(
        s"Generated id '$suffix' is ${suffix.length} digits wide, expected exactly $width.",
        SourceLocation.Unknown
      )
    }
    if (!suffix.forall(isBase36Digit)) {
      throw InternalCompilerException(
        s"Generated id '$suffix' is not lowercase base-36.",
        SourceLocation.Unknown
      )
    }
    suffix
  }

  private def isBase36Digit(c: Char): Boolean =
    ('0' <= c && c <= '9') || ('a' <= c && c <= 'z')

  /** Returns the stable, content-addressed suffix for `key` at the given `width`. */
  def suffix(key: String, width: Int = DefaultWidth): String =
    validated(render(of(key, width), width), width)

}
