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
import ca.uwaterloo.flix.language.ast.{SourceLocation, Type, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.Input
import ca.uwaterloo.flix.runtime.{Coverage, ProbeKind}

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
    // Track which (qualifiedName, source, line) combinations have line probes
    // to prevent duplicate line probes on the same line
    val registeredLineProbes = scala.collection.mutable.Set[(String, String, Int)]()

    // Transform each definition that should be instrumented
    val instrumentedDefs = defs.map { defn =>
      if (shouldInstrument(defn)) {
        val probeId = probeCounter
        probeCounter += 1

        val sourcePath = defn.loc.source.name
        val lineNumber = defn.loc.startLine
        val qualifiedName = defn.sym.toString

        // Register the function-level probe (only if location is real)
        if (defn.loc.isReal) {
          Coverage.registerProbe(probeId, sourcePath, lineNumber, ProbeKind.Function, qualifiedName)
        }

        // Instrument the function body for line and branch coverage
        val (instrumentedBody, newCounter) = instrumentExpression(
          defn.exp, qualifiedName, probeCounter, registeredLineProbes
        )
        probeCounter = newCounter

        // Wrap with function-level probe
        val wrappedBody = TypedAst.Expr.Stm(
          List(TypedAst.Expr.CoverageHit(probeId, defn.loc)),
          instrumentedBody,
          instrumentedBody.tpe,
          instrumentedBody.eff,
          defn.loc
        )

        defn.copy(exp = wrappedBody)
      } else {
        defn
      }
    }

    root.copy(defs = instrumentedDefs.map(d => d.sym -> d).toMap)
  }

  /**
    * Recursively instrument an expression for line and branch coverage.
    *
    * PHASE 2.1 - Let-Binding/Statement-Entry Coverage (INCOMPLETE):
    * ===============================================================
    * Currently instruments only Let-binding expressions with line probes.
    * Line probes mark the entry point of statement-level expressions.
    *
    * NOT YET instrumented: function-return expressions, conditions, branch bodies,
    * function calls, or ordinary statement expressions (Stm entries).
    * TODO: Extend to cover all executable expressions for complete line coverage.
    *
    * PHASE 2.2 - If-Expression Branch Coverage (COMPLETE):
    * =======================================================
    * Instruments IfThenElse expressions with exactly two branch probes:
    * - ProbeKind.BranchTrue for then-branch entry (uses exp2.loc)
    * - ProbeKind.BranchFalse for else-branch entry (uses exp3.loc)
    * Both branch probes are recorded regardless of execution path. The snapshot
    * only contains hit probes; unexecuted branches appear in metadata but not snapshot.
    *
    * Compiled-Code Coverage Semantics (KNOWN ISSUE):
    * ================================================
    * Instrumentation occurs BEFORE constant folding and dead-code elimination.
    * If optimizer later removes a branch (e.g., if (true) -> removes false branch),
    * the false branch probe remains in metadata even though its generated code was removed.
    *
    * This represents PRE-OPTIMIZATION reachable source coverage, NOT post-optimization
    * compiled-code coverage. To achieve compiled-code-only semantics, instrumentation
    * must be moved to AFTER optimization, which requires preserving source locations
    * through the optimizer pipeline (not yet implemented).
    *
    * @param exp the expression to instrument
    * @param qualifiedName the qualified name of the containing function
    * @param startProbeId the starting probe ID for new probes
    * @return tuple of (instrumented expression, new probe counter)
    */
  private def instrumentExpression(
    exp: TypedAst.Expr,
    qualifiedName: String,
    startProbeId: Int,
    registeredLineProbes: scala.collection.mutable.Set[(String, String, Int)]
  ): (TypedAst.Expr, Int) = {
    var probeId = startProbeId

    val result = exp match {
      // Instrument if-then-else with branch probes
      case e @ TypedAst.Expr.IfThenElse(exp1, exp2, exp3, tpe, eff, loc) =>
        // Recursively instrument condition
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1

        // Register and wrap true branch (use then-branch location, not if-expression location)
        val trueBranchProbeId = probeId
        probeId += 1
        if (exp2.loc.isReal) {
          Coverage.registerProbe(trueBranchProbeId, exp2.loc.source.name, exp2.loc.startLine, ProbeKind.BranchTrue, qualifiedName)
        }

        // Recursively instrument then expression
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, probeId, registeredLineProbes)
        probeId = pc2

        // Wrap then branch with probe
        val wrappedExp2 = if (exp2.loc.isReal) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(trueBranchProbeId, exp2.loc)),
            instExp2,
            instExp2.tpe,
            instExp2.eff,
            exp2.loc
          )
        } else {
          instExp2
        }

        // Register and wrap false branch (use else-branch location, not if-expression location)
        val falseBranchProbeId = probeId
        probeId += 1
        if (exp3.loc.isReal) {
          Coverage.registerProbe(falseBranchProbeId, exp3.loc.source.name, exp3.loc.startLine, ProbeKind.BranchFalse, qualifiedName)
        }

        // Recursively instrument else expression
        val (instExp3, pc3) = instrumentExpression(exp3, qualifiedName, probeId, registeredLineProbes)
        probeId = pc3

        // Wrap else branch with probe
        val wrappedExp3 = if (exp3.loc.isReal) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(falseBranchProbeId, exp3.loc)),
            instExp3,
            instExp3.tpe,
            instExp3.eff,
            exp3.loc
          )
        } else {
          instExp3
        }

        (e.copy(exp1 = instExp1, exp2 = wrappedExp2, exp3 = wrappedExp3), probeId)

      // Instrument let-expressions with line probes
      case e @ TypedAst.Expr.Let(sym, exp1, exp2, tpe, eff, loc) =>
        // Register line probe for this let-binding (only once per unique line)
        val lineProbeId = probeId
        val lineProbeKey = (qualifiedName, loc.source.name, loc.startLine)
        if (loc.isReal && !registeredLineProbes.contains(lineProbeKey)) {
          probeId += 1
          Coverage.registerProbe(lineProbeId, loc.source.name, loc.startLine, ProbeKind.Line, qualifiedName)
          registeredLineProbes.add(lineProbeKey)
        } else if (!loc.isReal) {
          probeId += 1  // Skip probe ID even if not registering (to maintain consistency)
        } else {
          // Line probe already registered for this line, skip
        }

        // Recursively instrument bound expression
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1

        // Wrap bound expression with probe (only if we registered one)
        val wrappedExp1 = if (loc.isReal && registeredLineProbes.contains(lineProbeKey)) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(lineProbeId, loc)),
            instExp1,
            instExp1.tpe,
            instExp1.eff,
            loc
          )
        } else {
          instExp1
        }

        // Recursively instrument body
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, probeId, registeredLineProbes)
        probeId = pc2

        (e.copy(exp1 = wrappedExp1, exp2 = instExp2), probeId)

      // Instrument statement expressions (blocks)
      case e @ TypedAst.Expr.Stm(exps, finalExp, tpe, eff, loc) =>
        // Process each statement
        val instExps = exps.map { stmExp =>
          val (instExp, pc) = instrumentExpression(stmExp, qualifiedName, probeId, registeredLineProbes)
          probeId = pc
          instExp
        }

        // Process final expression
        val (instFinalExp, pc) = instrumentExpression(finalExp, qualifiedName, probeId, registeredLineProbes)
        probeId = pc

        (e.copy(exps = instExps, exp = instFinalExp), probeId)

      // For other expressions, recurse into structure without adding probes
      case _ => (exp, probeId)
    }

    result
  }

  /**
    * Determine if a definition should be instrumented for coverage.
    *
    * Only instrument user-provided source code (Input.RealFile and Input.VirtualFile).
    * Exclude bundled libraries, packages, and compiler-internal code.
    *
    * Note: Individual probes are additionally filtered by loc.isReal before registration
    * to exclude synthetic/generated code even within instrumented functions.
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
