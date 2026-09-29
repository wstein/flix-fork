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

  /** The suffix width, in base-36 digits, that code generation uses. */
  val DefaultWidth: Int = 12

  /**
    * Returns the table naming each symbol in `entries` after its provenance, with suffixes of
    * `width` base-36 digits.
    */
  def build(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int): JvmNameTable =
    buildWithDigest(entries, width, key => BigInt(1, MessageDigest.getInstance("SHA-256").digest(key.bytes)))

  private[jvm] def buildWithDigest(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, digest: GeneratedJvmKey => BigInt): JvmNameTable = {
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
          throw InternalCompilerException(s"Stable JVM name collision on '$name': '$previous' and '$key'.", SourceLocation.Unknown)
        }
      }
      provenance(sym) = key
      owners(key) = sym
      claims(name) = key
      names(sym) = name
    }

    new JvmNameTable(names.toMap)
  }
}
