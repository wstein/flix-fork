package ca.uwaterloo.flix.language.phase.jvm

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Bounds a generated class's final filename component without changing short names. */
object JvmNameCompaction {
  val MaxFileNameBytes: Int = 240
  private val Extension = ".class"
  private val Separator = "$$$$"

  /** The namespace is a directory path, so only the simple class name and `.class` count. */
  def compact(name: String, hashWidth: Int): String = {
    if (bytes(name) + bytes(Extension) <= MaxFileNameBytes) return name
    require(hashWidth >= 1 && hashWidth <= JvmNameTable.MaxWidth)
    val digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))
    val modulus = BigInt(36).pow(hashWidth)
    val digits = (BigInt(1, digest) mod modulus).toString(36)
    val hash = "0" * (hashWidth - digits.length) + digits
    val pieceBudget = (MaxFileNameBytes - bytes(Extension) - 2 * bytes(Separator) - hashWidth) / 4
    val prefix = takeBytes(name, pieceBudget)
    val suffix = takeRightBytes(name, pieceBudget)
    prefix + Separator + hash + Separator + suffix
  }

  private def bytes(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  private def takeBytes(s: String, limit: Int): String = {
    val result = new java.lang.StringBuilder
    val points = s.codePoints().toArray
    var used = 0
    var i = 0
    while (i < points.length && used + utf8Bytes(points(i)) <= limit) {
      result.appendCodePoint(points(i))
      used += utf8Bytes(points(i))
      i += 1
    }
    result.toString()
  }

  private def takeRightBytes(s: String, limit: Int): String = {
    val points = s.codePoints().toArray
    var used = 0
    var i = points.length - 1
    while (i >= 0 && used + utf8Bytes(points(i)) <= limit) {
      used += utf8Bytes(points(i))
      i -= 1
    }
    new String(points, i + 1, points.length - i - 1)
  }

  private def utf8Bytes(point: Int): Int =
    if (point <= 0x7f) 1 else if (point <= 0x7ff) 2 else if (point <= 0xffff) 3 else 4
}
