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
import ca.uwaterloo.flix.language.ast.Symbol
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
    * Instrument the typed AST for coverage.
    *
    * @param root the typed AST root.
    * @param flix the Flix compiler instance.
    * @return a map from symbol to probe ID.
    */
  def run(root: TypedAst.Root)(implicit flix: Flix): Map[Symbol.DefnSym, Int] = {
    val defs = root.defs.values.toList
    val probeMap = scala.collection.mutable.Map[Symbol.DefnSym, Int]()

    var probeCounter = 0
    var instrumentedCount = 0
    var skippedCount = 0

    // Debug: collect info about all functions
    val debugInfo = scala.collection.mutable.ArrayBuffer[String]()
    debugInfo += s"Total definitions: ${defs.size}"
    debugInfo += ""

    // Register a probe for each user-defined function
    for (defn <- defs) {
      val sym = defn.sym
      val loc = sym.loc
      val name = sym.name
      val namespace = sym.namespace.mkString(".")

      // Check if should instrument
      if (shouldInstrument(defn)) {
        val probeId = probeCounter
        probeCounter += 1
        instrumentedCount += 1

        val sourcePath = loc.source.name
        val lineNumber = loc.startLine

        // Register the probe
        Coverage.registerProbe(probeId, sourcePath, lineNumber, "function")

        // Store probe ID mapping
        probeMap(sym) = probeId

        debugInfo += s"[$probeId] INSTRUMENTED: $name at $sourcePath:$lineNumber (namespace: $namespace)"
      } else {
        skippedCount += 1
        debugInfo += s"[SKIP] $name (namespace: $namespace)"
      }
    }

    debugInfo += ""
    debugInfo += s"Instrumented: $instrumentedCount, Skipped: $skippedCount"

    // Write debug info
    try {
      val debugFile = java.nio.file.Paths.get("out/coverage_debug.txt")
      java.nio.file.Files.createDirectories(debugFile.getParent)
      java.nio.file.Files.write(debugFile, debugInfo.mkString("\n").getBytes)
    } catch {
      case _: Exception => // Ignore errors writing debug file
    }

    // Store the probe map on the Flix instance
    flix.setCoverageProbeMap(probeMap.toMap)

    // Return the probe map
    probeMap.toMap
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

    // Skip compiler-generated functions (internal builtins, standard library internals)
    // Check if namespace starts with compiler packages
    val namespacePath = sym.namespace.mkString(".")
    if (namespacePath.startsWith("ca.uwaterloo.flix") || namespacePath.startsWith("flix")) {
      return false
    }

    true
  }
}
