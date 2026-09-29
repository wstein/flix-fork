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

  sealed trait Mode

  object Mode {
    case object Stable extends Mode
    case object Counter extends Mode
  }

  /** The fallback and compaction hash width, in base-36 digits, used by default. */
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
    * `width` is `--Xsymbol-hash-length`, from 1 to [[MaxWidth]]. Counter mode is independent of
    * this width. Provenance is required in either mode.
    */
  def build(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int): JvmNameTable =
    build(entries, width, Map.empty)

  /**
    * Returns the table as [[build]] does, but naming a lambda, local definition, or anonymous class
    * by its readable origin in `readable` wherever that spelling is unique among the classes it
    * could collide with; everything else keeps its hash. Counter mode ignores readable origins.
    */
  def build(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, readable: Map[Symbol, JvmReadableOrigin]): JvmNameTable =
    build(entries, width, readable, Mode.Stable)

  def build(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, readable: Map[Symbol, JvmReadableOrigin], mode: Mode): JvmNameTable =
    build(entries, width, readable, Map.empty, mode)

  def build(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, readable: Map[Symbol, JvmReadableOrigin], spellings: Map[Symbol, String], mode: Mode): JvmNameTable =
    buildWithDigest(entries, width, readable, spellings, mode, key => BigInt(1, MessageDigest.getInstance("SHA-256").digest(key.bytes)))

  private[jvm] def buildWithDigest(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, digest: GeneratedJvmKey => BigInt): JvmNameTable =
    buildWithDigest(entries, width, Map.empty, digest)

  private[jvm] def buildWithDigest(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, readable: Map[Symbol, JvmReadableOrigin], digest: GeneratedJvmKey => BigInt): JvmNameTable = {
    buildWithDigest(entries, width, readable, Mode.Stable, digest)
  }

  private[jvm] def buildWithDigest(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, readable: Map[Symbol, JvmReadableOrigin], mode: Mode, digest: GeneratedJvmKey => BigInt): JvmNameTable = {
    buildWithDigest(entries, width, readable, Map.empty, mode, digest)
  }

  private[jvm] def buildWithDigest(entries: Iterable[(Symbol, GeneratedJvmKey)], width: Int, readable: Map[Symbol, JvmReadableOrigin], spellings: Map[Symbol, String], mode: Mode, digest: GeneratedJvmKey => BigInt): JvmNameTable = {
    if (width < 1 || width > MaxWidth) {
      throw InternalCompilerException(s"Stable JVM name width $width is outside 1 to $MaxWidth.", SourceLocation.Unknown)
    }
    val namespaceSize = BigInt(36).pow(width)
    val provenance = mutable.Map.empty[Symbol, GeneratedJvmKey]
    val owners = mutable.Map.empty[GeneratedJvmKey, Symbol]
    val names = mutable.Map.empty[Symbol, String]

    entries.foreach { case (sym, key) =>
      JvmProvenance.checkConsistent(sym, provenance.get(sym), key)
      owners.get(key).foreach { previous =>
        if (previous != sym) {
          throw InternalCompilerException(s"Duplicate JVM naming provenance '$key' for '$previous' and '$sym'.", SourceLocation.Unknown)
        }
      }
      val name =
        if (mode == Mode.Counter) counterOf(sym)
        else {
          val digits = (digest(key) mod namespaceSize).toString(36)
          "0" * (width - digits.length) + digits
        }
      provenance(sym) = key
      owners(key) = sym
      names(sym) = name
    }

    val hashed = names.toMap
    val result = if (mode == Mode.Counter) hashed else {
      val specialized = hashed ++ spellings.filter { case (sym, _) => hashed.contains(sym) }
      specialized ++ readableNames(specialized, readable)
    }
    // Only names that remain hashes consume the truncated-hash namespace. A readable or mangled
    // name must not fail because of a hash it never emits.
    if (mode == Mode.Stable) {
      val claims = mutable.Map.empty[String, GeneratedJvmKey]
      result.foreach { case (sym, name) if name == hashed(sym) =>
        val key = provenance(sym)
        claims.get(name).foreach { previous =>
          if (previous != key) {
            throw InternalCompilerException(s"Stable JVM name collision on '$name': '$previous' and '$key'." + collisionAdvice(width), SourceLocation.Unknown)
          }
        }
        claims(name) = key
      case _ => () }
    }
    val duplicateNames = result.toList.groupBy { case (sym, name) => (classGroup(sym), name) }
      .collect { case (spelling, owners) if owners.lengthIs > 1 => spelling }
    if (duplicateNames.nonEmpty) {
      throw InternalCompilerException(s"Duplicate JVM specialization spelling '${duplicateNames.head}'.", SourceLocation.Unknown)
    }
    new JvmNameTable(result)
  }

  /**
    * Returns the readable names that can replace hashes in `hashed`.
    *
    * A readable name is kept only if no other class it could collide with -- one with the same
    * prefix, see [[classGroup]] -- is spelled the same way, readable or hashed. When two readable
    * spellings coincide, both keep their hashes: which one would win is not a stable property.
    */
  private def readableNames(hashed: Map[Symbol, String], readable: Map[Symbol, JvmReadableOrigin]): Map[Symbol, String] = {
    val candidates = readable.iterator.flatMap {
      case (sym, origin) if hashed.contains(sym) => render(sym, origin, hashed).map(sym -> _)
      case _ => None
    }.toMap
    val spellings = candidates.toList.groupBy { case (sym, name) => (classGroup(sym), name) }
    val kept = hashed.iterator.collect { case (sym, name) if !candidates.contains(sym) => (classGroup(sym), name) }.toSet
    candidates.filter { case (sym, name) =>
      val spelling = (classGroup(sym), name)
      spellings(spelling).lengthIs == 1 && !kept.contains(spelling)
    }
  }

  /**
    * Returns the readable suffix of `sym`, or `None` if its origin cannot be spelled.
    *
    * A specialized owner contributes its own suffix, which tells its copies apart; an owner that is
    * not generated has none to contribute. An anonymous class also spells its owner, since its
    * class name has no other place for it.
    */
  private def render(sym: Symbol, origin: JvmReadableOrigin, hashed: Map[Symbol, String]): Option[String] = {
    val specialization = origin.specialization match {
      case Some(owner: Symbol.DefnSym) if owner.id.isDefined => hashed.get(owner).map(List(_))
      case _ => Some(Nil)
    }
    specialization.filter(_ => origin.path.nonEmpty).flatMap { spec =>
      sym match {
        case _: Symbol.AnonClassSym if origin.owner.nonEmpty => Some((origin.owner ++ spec ++ origin.path).mkString("$"))
        case _: Symbol.DefnSym => Some((spec ++ origin.path).mkString("$"))
        case _ => None
      }
    }
  }

  /**
    * Returns what `sym`'s class name shares with the classes its suffix could collide with: a
    * definition's namespace and name, which prefix its suffix, or the single root package every
    * anonymous class shares.
    */
  private def classGroup(sym: Symbol): List[String] = sym match {
    case s: Symbol.DefnSym => "definition" :: s.namespace ::: List(s.text)
    case s: Symbol.EnumSym => "enum" :: s.namespace ::: List(s.text)
    case s: Symbol.StructSym => "struct" :: s.namespace ::: List(s.text)
    case _: Symbol.AnonClassSym => List("anonymous-class")
    case other => List("other", other.toString)
  }

  /** Returns the counter id `sym` was minted with, or its text if it has none. */
  private def counterOf(sym: Symbol): String = sym match {
    case s: Symbol.DefnSym => s.id.map(_.toString).getOrElse(s.text)
    case s: Symbol.EnumSym => s.id.map(_.toString).getOrElse(s.text)
    case s: Symbol.StructSym => s.id.map(_.toString).getOrElse(s.text)
    case s: Symbol.AnonClassSym => s.id.toString
    case other => throw InternalCompilerException(s"Unexpected symbol '$other' in the JVM name table.", SourceLocation.Unknown)
  }

  /**
    * Says what a collision means at `width`: below the default it is expected and a wider width
    * is the cure; at or above it, 36^width names make an accidental collision implausible, so it
    * points at two keys that should not both exist.
    */
  private def collisionAdvice(width: Int): String =
    if (width < DefaultWidth)
      s" Suffixes are $width base-36 digits (--Xsymbol-hash-length), below the default of $DefaultWidth, where collisions are expected: use a wider width."
    else
      s" Suffixes are $width base-36 digits (--Xsymbol-hash-length), so this is a provenance defect rather than a narrow-width collision."
}
