/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.runtime

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.*
import ca.uwaterloo.flix.language.ast.shared.Source
import ca.uwaterloo.flix.language.phase.jvm.{DebugCalls, JvmClass, JvmLexicalOrigins}

import java.lang.constant.ClassDesc

/**
  * A class representing the result of a compilation.
  *
  * The generated classes are *not* loaded into the JVM. Use [[JvmLoader.load]] to obtain a [[LoadedProgram]].
  *
  * @param root      the generated JVM classes together with the main and test entry points.
  * @param totalTime the total compilation time.
  * @param codeSize  the number of bytes the compiler generated.
  * @param flix      the Flix instance that produced this result (provides the external JAR class loader and crash reporting).
  */
class CompilationResult(val root: BytecodeAst.Root,
                        val totalTime: Long,
                        val codeSize: Int,
                        val flix: Flix,
                        val debugDefinitions: Map[String, Map[String, List[JvmLexicalOrigins.Binding]]] = Map.empty,
                        val debugCalls: List[DebugCalls.Call] = Nil,
                        val coverageSession: Option[CoverageSession] = None
                       ) {

  /** Returns the generated JVM classes. */
  def getClasses: Map[ClassDesc, JvmClass] =
    root.classes

  /** Returns source bindings keyed by stable generated class name for a debug compilation. */
  def getDebugDefinitions: Map[String, Map[String, List[JvmLexicalOrigins.Binding]]] =
    debugDefinitions

  /** Returns direct call sites resolved to stable generated definitions for a debug compilation. */
  def getDebugCalls: List[DebugCalls.Call] = debugCalls

  /** Returns coverage probe metadata when this was a coverage compilation. */
  def getCoverageSession: Option[CoverageSession] = coverageSession

  /** Optionally returns the main entry point. */
  def getMain: Option[BytecodeAst.Def] =
    root.main

  /** Returns all the test entry points in the program. */
  def getTests: Map[Symbol.DefnSym, BytecodeAst.Test] =
    root.tests

  /** Returns the sources of the program. */
  def getSources: Map[Source, SourceLocation] =
    root.sources

  /** Returns the total number of lines of compiled code. */
  def getTotalLines: Int = root.sources.foldLeft(0) {
    case (acc, (_, sl)) => acc + sl.endLine
  }

}
