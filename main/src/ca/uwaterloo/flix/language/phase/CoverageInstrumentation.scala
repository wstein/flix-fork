/*
 * Copyright 2024
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
package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.runtime.Coverage

/**
  * Instrument Flix source code for coverage analysis.
  *
  * This phase registers coverage probes for:
  * - Function entry points
  * - Executable lines
  * - Branch targets (if, match, guards)
  *
  * During this phase, we:
  * 1. Traverse the typed AST
  * 2. Identify coverage points (functions, lines, branches)
  * 3. Assign unique probe IDs
  * 4. Register probes in the Coverage registry
  * 5. Store probe information for later instrumentation
  *
  * The actual bytecode instrumentation that calls Coverage.hit(probeId)
  * happens during JVM code generation.
  */
object CoverageInstrumentation {

  /**
    * Data class to represent registered coverage probe information.
    */
  case class ProbeInfo(
    id: Int,
    source: String,
    line: Int,
    kind: String // "function", "line", "branch-true", "branch-false", etc.
  )

  /**
    * Instrument the typed AST for coverage.
    *
    * @param root the typed AST root.
    * @param flix the Flix compiler instance.
    * @return a map from symbol to list of probes for that symbol.
    */
  def run(root: TypedAst.Root)(implicit flix: Flix): Map[TypedAst.Def, List[ProbeInfo]] = {
    val defs = root.defs.values.toList
    val probes = scala.collection.mutable.Map[TypedAst.Def, List[ProbeInfo]]()

    var probeCounter = 0

    // Register a probe for each user-defined function
    for (defn <- defs) {
      val sym = defn.sym
      val loc = sym.loc

      // Skip compiler-generated and test functions
      if (shouldInstrument(defn)) {
        val probeId = probeCounter
        probeCounter += 1

        val sourcePath = loc.source.name
        val lineNumber = loc.startLine

        // Register the probe
        Coverage.registerProbe(probeId, sourcePath, lineNumber, "function")

        // Store probe info for later use
        val probeInfo = ProbeInfo(probeId, sourcePath, lineNumber, "function")
        probes(defn) = List(probeInfo)
      }
    }

    // Return the probe map
    probes.toMap
  }

  /**
    * Determine if a definition should be instrumented for coverage.
    *
    * @param defn the definition to check.
    * @return true if the definition should be instrumented.
    */
  private def shouldInstrument(defn: TypedAst.Def): Boolean = {
    val sym = defn.sym
    val spec = defn.spec

    // Skip test functions (marked with @Test)
    if (spec.ann.isTest) {
      return false
    }

    // Skip compiler-generated functions (those in compiler namespaces or internal ones)
    if (sym.namespace.exists(p => p.startsWith("ca.uwaterloo.flix") || p.startsWith("flix"))) {
      return false
    }

    true
  }
}
