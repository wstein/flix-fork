package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}
import ca.uwaterloo.flix.util.InternalCompilerException

import scala.collection.mutable

final class JvmProvenance {

  private var origins = mutable.Map.empty[Symbol, GeneratedJvmKey]
  private var frozen = false

  def register(sym: Symbol, key: GeneratedJvmKey): Unit = synchronized {
    requireOpen()
    origins.get(sym).foreach { previous =>
      if (previous != key) {
        throw InternalCompilerException(s"Conflicting JVM naming provenance for '$sym': '$previous' and '$key'.", SourceLocation.Unknown)
      }
    }
    origins(sym) = key
  }

  def origin(sym: Symbol): GeneratedJvmKey = synchronized {
    origins.getOrElse(sym,
      throw InternalCompilerException(s"Missing JVM naming provenance for '$sym'.", SourceLocation.Unknown))
  }

  def retainLive(live: Set[Symbol]): Unit = synchronized {
    requireOpen()
    origins = mutable.Map.from(origins.iterator.filter { case (sym, _) => live.contains(sym) })
  }

  def freeze(required: Iterable[Symbol]): JvmNameTable = synchronized {
    requireOpen()
    frozen = true
    try {
      JvmNameTable.build(required.iterator.map(sym => sym -> origin(sym)).toList)
    } finally {
      origins = mutable.Map.empty
    }
  }

  private def requireOpen(): Unit = {
    if (frozen) {
      throw InternalCompilerException("JVM naming provenance has already been frozen.", SourceLocation.Unknown)
    }
  }
}
