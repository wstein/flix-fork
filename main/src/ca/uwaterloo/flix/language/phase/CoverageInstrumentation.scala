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
  * For each user-defined non-test function in project source (not bundled libraries),
  * we:
  * 1. Assign a unique probe ID
  * 2. Register the probe in the Coverage registry with (source, line, "function")
  * 3. Wrap the function body with TypedAst.Expr.CoverageHit(probeId) followed by the original body
  *
  * The CoverageHit node is marked with Pure effect to preserve function type signatures.
  * It's a compiler-internal operation that prevents optimization removal while leaving the
  * observable purity of the function unchanged. The actual Coverage.hit(probeId) call is emitted
  * during JVM code generation and executes invisibly as a side effect.
  *
  * Filtering Strategy:
  * ==================
  * Source ownership is the primary filter. Coverage is enabled only for:
  *  - Input.RealFile: Project source files on the filesystem
  *  - Input.VirtualFile: In-memory user code (REPL, test harness)
  *
  * Coverage is disabled for:
  *  - Input.BundledLibraryFile: Bundled Flix standard library and core
  *  - Input.VirtualUri: Other virtual sources
  *  - Input.PkgFile, Input.FileInPackage: Packages and dependencies
  *  - Input.Unknown: Unknown sources
  *
  * This avoids brittle path/namespace guessing and relies on explicit Input types
  * that already encode source provenance.
  *
  * Probe Lifecycle:
  * ================
  * - During check(): Coverage.clear() clears metadata/counters from prior compilations
  * - During CoverageInstrumentation: probes are registered and inserted into AST
  * - During JVM emission: Coverage.hit(probeId) bytecode is emitted
  * - During execution: calls to Coverage.hit() increment atomic counters
  * - During reporting: Coverage.snapshot() + getProbeMetadata() generate JSON report
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
        // Note: CoverageHit is Pure effect to preserve observable function purity.
        // Coverage execution is compiler-internal and invisible to the type system.
        val instrumentedBody = TypedAst.Expr.Stm(
          List(TypedAst.Expr.CoverageHit(probeId, defn.loc)),
          defn.exp,
          defn.exp.tpe,
          defn.exp.eff,
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
    * Only instrument user-provided source code (Input.RealFile and Input.VirtualFile).
    * Exclude bundled libraries, packages, and compiler-internal code.
    *
    * @param defn the definition to check.
    * @return true if the definition should be instrumented.
    */
  private def shouldInstrument(defn: TypedAst.Def): Boolean = {
    val spec = defn.spec

    // Skip test functions (marked with @Test)
    if (spec.ann.isTest) {
      return false
    }

    // Instrument only user-provided source code (project code).
    // Exclude bundled libraries, packages, and unknown inputs.
    defn.loc.source.input match {
      case Input.RealFile(_, _) => true        // User project file from filesystem
      case Input.VirtualFile(_, _, _) => true  // User in-memory file (e.g., REPL, test)
      case Input.BundledLibraryFile(_, _, _) => false  // Bundled stdlib/core - excluded
      case Input.VirtualUri(_, _, _) => false  // Other virtual sources - excluded
      case Input.PkgFile(_, _) => false        // Package file - excluded
      case Input.FileInPackage(_, _, _, _) => false  // File in package - excluded
      case Input.Unknown => false              // Unknown - excluded
    }
  }
}
