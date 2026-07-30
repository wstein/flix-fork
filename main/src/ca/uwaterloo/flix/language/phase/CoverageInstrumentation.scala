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
import ca.uwaterloo.flix.language.ast.{Type, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.Input
import ca.uwaterloo.flix.runtime.Coverage

/**
  * Instrument Flix source code for coverage analysis by inserting CoverageHit AST nodes.
  *
  * Function-entry probes only.
  * ===============================================
  * For each user-defined non-test function in project source (not stdlib), we:
  * 1. Assign a unique probe ID
  * 2. Register the probe in the Coverage registry with (source, line, "function")
  * 3. Wrap the function body with TypedAst.Expr.CoverageHit(probeId) followed by the original body
  *
  * The CoverageHit node is marked with Pure effect to preserve function type signatures.
  * It's a compiler-internal operation that prevents optimization removal while leaving the
  * observable purity of the function unchanged. The actual Coverage.hit(probeId) call is emitted
  * during JVM code generation and executes invisibly as a side effect.
  *
  * Probe Lifecycle:
  * - During check(): Coverage.clear() clears metadata/counters from prior compilations
  * - During CoverageInstrumentation: probes are registered and inserted into AST
  * - During JVM emission: Coverage.hit(probeId) bytecode is emitted
  * - During execution: calls to Coverage.hit() increment atomic counters
  * - During reporting: Coverage.snapshot() + getProbeMetadata() generate JSON report
  *
  * Filtering:
  * - Excludes @Test-marked functions (tested separately)
  * - Excludes compiler internal packages (ca.uwaterloo.flix.*, flix.*)
  * - Excludes standard library modules (Prelude, Array, List, etc.)
  * - Excludes functions from stdlib source files
  * This ensures reports focus on user/project code, not compiler/library overhead.
  */
object CoverageInstrumentation {

  /**
    * Instrument the typed AST for coverage.
    *
    * @param root the typed AST root.
    * @param flix the Flix compiler instance.
    * @return the root with instrumented function bodies.
    */
  def run(root: TypedAst.Root)(implicit flix: Flix): TypedAst.Root = {
    val defs = root.defs.values.toList
    var probeCounter = 0

    // Transform each definition that should be instrumented
    val instrumentedDefs = defs.map { defn =>
      if (shouldInstrument(defn)) {
        val probeId = probeCounter
        probeCounter += 1

        val sourcePath = defn.loc.source.name
        val lineNumber = defn.loc.startLine

        // Register the probe in the Coverage runtime
        Coverage.registerProbe(probeId, sourcePath, lineNumber, "function")

        // Wrap the function body with CoverageHit
        val instrumentedBody = TypedAst.Expr.Stm(
          List(TypedAst.Expr.CoverageHit(probeId, defn.loc)),
          defn.exp,
          defn.exp.tpe,
          Type.mkUnion(Type.IO :: defn.exp.eff :: Nil, defn.loc),
          defn.loc
        )

        // Return the instrumented definition
        defn.copy(exp = instrumentedBody)
      } else {
        defn
      }
    }

    // Rebuild the root with instrumented definitions
    root.copy(defs = instrumentedDefs.map(d => d.sym -> d).toMap)
  }

  /**
    * Determine if a definition should be instrumented for coverage.
    *
    * Only instrument user-provided source code, not standard library or compiler-internal functions.
    *
    * @param defn the definition to check.
    * @return true if the definition should be instrumented.
    */
  private def shouldInstrument(defn: TypedAst.Def): Boolean = {
    val sym = defn.sym
    val spec = defn.spec
    val sourceFileName = defn.loc.source.name

    // Skip test functions (marked with @Test)
    if (spec.ann.isTest) {
      return false
    }

    // Only project inputs are part of a project's coverage report.
    defn.loc.source.input match {
      case Input.RealFile(_, _) => ()
      case _ => return false
    }

    // Skip compiler-generated functions (internal builtins).
    val namespacePath = sym.namespace.mkString(".")

    // Skip compiler packages
    if (namespacePath.startsWith("ca.uwaterloo.flix") || namespacePath.startsWith("flix")) {
      return false
    }

    // Skip standard library modules (typically have capital first letter in namespace)
    val stdlibModules = Set(
      "Prelude", "Array", "List", "Option", "Result", "Map", "Set", "String",
      "Vector", "Chain", "Queue", "Stack", "Nec", "Nel", "OrderedMap",
      "OrderedSet", "HashMap", "HashSet", "MutableList", "MutableMap",
      "MutableSet", "Choice", "Lazy", "Validation", "Try", "Error",
      "Sys", "File", "Path", "IO", "Time", "Time/Duration", "Time/Instant",
      "Random", "Time/Timestamp", "Env", "Process", "Hash", "Crypto",
      "Digest", "Json", "Url", "Http", "Net", "Dns", "Tcp", "Udp",
      "Dev", "Regex", "Format", "Console", "Debug", "Bench", "Test",
      "Logger", "Applicative", "Monad", "Functor", "Enum", "Order",
      "Show", "Eq", "Hash", "Semigroupal", "Category", "Comparable",
      "Parallel", "Foldable", "Traversable", "Reducible"
    )

    val topLevelModule = sym.namespace.headOption.getOrElse("")
    if (stdlibModules.contains(topLevelModule)) {
      return false
    }

    // Skip files that appear to be from the standard library or examples
    if (sourceFileName.contains("Prelude") ||
        sourceFileName.contains("stdlib") ||
        sourceFileName.contains("examples/") ||
        sourceFileName.contains("library/")) {
      return false
    }

    true
  }
}
