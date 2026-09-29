package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}
import ca.uwaterloo.flix.util.InternalCompilerException

import java.security.MessageDigest
import scala.collection.mutable

final class JvmNameTable private (names: Map[Symbol, String]) {

  def suffix(sym: Symbol): String = names.getOrElse(sym,
    throw InternalCompilerException(s"Missing JVM naming provenance for '$sym'.", SourceLocation.Unknown))
}

object JvmNameTable {

  /** The suffix width, in base-36 digits, that `--Xstable-name-length` defaults to. */
  val DefaultWidth: Int = 12

  /**
    * The widest suffix a SHA-256 digest fills: `36^49 < 2^256 < 36^50`, so a 50th digit would be
    * a constant, not information.
    */
  val MaxWidth: Int = 49

  /**
    * Returns the table naming each symbol in `entries` after its provenance, with suffixes of
    * `width` base-36 digits.
    *
    * `width` is `--Xstable-name-length`.
    */
  def build(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int): JvmNameTable =
    buildWithDigest(entries, width, key => BigInt(1, MessageDigest.getInstance("SHA-256").digest(key.bytes)))

  private[jvm] def buildWithDigest(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, digest: GeneratedJvmKey => BigInt): JvmNameTable = {
    if (width < 1 || width > MaxWidth) {
      throw InternalCompilerException(s"Stable JVM name width $width is outside 1 to $MaxWidth.", SourceLocation.Unknown)
    }
    val namespaceSize = BigInt(36).pow(width)
    val provenance = mutable.Map.empty[Symbol, GeneratedJvmKey]
    val owners = mutable.Map.empty[GeneratedJvmKey, Symbol]
    val claims = mutable.Map.empty[String, GeneratedJvmKey]
    val names = mutable.Map.empty[Symbol, String]

    entries.foreach { case (sym, key) =>
      JvmProvenance.checkConsistent(sym, provenance.get(sym), key)
      owners.get(key).foreach { previous =>
        if (previous != sym) {
          throw InternalCompilerException(s"Duplicate JVM naming provenance '$key' for '$previous' and '$sym'.", SourceLocation.Unknown)
        }
      }
      val digits = (digest(key) mod namespaceSize).toString(36)
      val name = "0" * (width - digits.length) + digits
      claims.get(name).foreach { previous =>
        if (previous != key) {
          throw InternalCompilerException(s"Stable JVM name collision on '$name': '$previous' and '$key'." + collisionAdvice(width), SourceLocation.Unknown)
        }
      }
      provenance(sym) = key
      owners(key) = sym
      claims(name) = key
      names(sym) = name
    }

    new JvmNameTable(names.toMap)
  }

  /**
    * Says what a collision means at `width`: below the default it is expected and a wider width
    * is the cure; at or above it, 36^width names make an accidental collision implausible, so it
    * points at two keys that should not both exist.
    */
  private def collisionAdvice(width: Int): String =
    if (width < DefaultWidth)
      s" Suffixes are $width base-36 digits (--Xstable-name-length), below the default of $DefaultWidth, where collisions are expected: use a wider width."
    else
      s" Suffixes are $width base-36 digits (--Xstable-name-length), so this is a provenance defect rather than a narrow-width collision."
}
