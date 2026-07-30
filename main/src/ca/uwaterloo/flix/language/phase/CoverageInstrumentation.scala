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
  * Function, line, and branch probes.
  * ==================================
  * For each user-defined non-test function in project source (not bundled libraries),
  * we:
  * 1. Assign a unique probe ID
  * 2. Register the probe in the Coverage registry with (source, line, "function")
  * 3. Wrap the function body with TypedAst.Expr.CoverageHit(sessionId, probeId) followed by the original body
  *
  * The CoverageHit node is marked with Pure effect to preserve function type signatures.
  * It's a compiler-internal operation that prevents optimization removal while leaving the
  * observable purity of the function unchanged. The actual Coverage.hit(sessionId, probeId) call is emitted
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
  * - During check(): Coverage.createSession() creates a fresh per-compilation session
  * - During CoverageInstrumentation: probes are registered and inserted into AST
  * - During JVM emission: Coverage.hit(sessionId, probeId) bytecode is emitted
  * - During execution: calls to Coverage.hit() increment atomic counters in the target session
  * - During reporting: Coverage.reportSnapshot() generates JSON/LCOV reports
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
    val currentSession = Coverage.createSession()
    implicit val session: Coverage.Session = currentSession
    implicit val sessionId: Long = currentSession.sessionId
    val defs = root.defs.values.toList.sortBy(d => (d.loc.source.name, d.loc.startLine, d.sym.toString))
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
          currentSession.registerProbe(probeId, sourcePath, lineNumber, ProbeKind.Function, qualifiedName)
        }

        // Every compiled function contributes an executable line probe at the
        // source location of its body. This covers expression-bodied functions
        // that contain no let-binding.
        val bodyLineProbe = probeCounter
        val bodyLineKey = (qualifiedName, defn.exp.loc.source.name, defn.exp.loc.startLine)
        val hasBodyLineProbe = defn.exp.loc.isReal && registeredLineProbes.add(bodyLineKey)
        if (hasBodyLineProbe) {
          probeCounter += 1
          currentSession.registerProbe(bodyLineProbe, defn.exp.loc.source.name, defn.exp.loc.startLine, ProbeKind.Line, qualifiedName)
        }

        // Instrument the function body for line and branch coverage
        val (instrumentedBody, newCounter) = instrumentExpression(
          defn.exp, qualifiedName, probeCounter, registeredLineProbes
        )
        probeCounter = newCounter

        val lineInstrumentedBody = if (hasBodyLineProbe) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(sessionId, bodyLineProbe, defn.exp.loc)),
            instrumentedBody,
            instrumentedBody.tpe,
            instrumentedBody.eff,
            defn.exp.loc
          )
        } else {
          instrumentedBody
        }

        // Wrap with function-level probe
        val wrappedBody = TypedAst.Expr.Stm(
          List(TypedAst.Expr.CoverageHit(sessionId, probeId, defn.loc)),
          lineInstrumentedBody,
          lineInstrumentedBody.tpe,
          lineInstrumentedBody.eff,
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
    */
  private def instrumentExpression(
    exp: TypedAst.Expr,
    qualifiedName: String,
    startProbeId: Int,
    registeredLineProbes: scala.collection.mutable.Set[(String, String, Int)]
  )(implicit session: Coverage.Session, sessionId: Long): (TypedAst.Expr, Int) = {
    var probeId = startProbeId

    val result = exp match {
      // Instrument if-then-else with branch probes
      case e @ TypedAst.Expr.IfThenElse(exp1, exp2, exp3, tpe, eff, loc) =>
        // Recursively instrument condition
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1
        val (lineExp1, pc2) = instrumentLine(instExp1, qualifiedName, probeId, registeredLineProbes)
        probeId = pc2

        // Register and wrap true branch (use then-branch location, not if-expression location)
        val trueBranchProbeId = probeId
        probeId += 1
        if (exp2.loc.isReal) {
          session.registerProbe(trueBranchProbeId, exp2.loc.source.name, exp2.loc.startLine, ProbeKind.BranchTrue, qualifiedName)
        }

        // Recursively instrument then expression
        val (instExp2, pc3) = instrumentExpression(exp2, qualifiedName, probeId, registeredLineProbes)
        probeId = pc3
        val (lineExp2, pc4) = instrumentLine(instExp2, qualifiedName, probeId, registeredLineProbes)
        probeId = pc4

        // Wrap then branch with probe
        val wrappedExp2 = if (exp2.loc.isReal) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(sessionId, trueBranchProbeId, exp2.loc)),
            lineExp2,
            lineExp2.tpe,
            lineExp2.eff,
            exp2.loc
          )
        } else {
          instExp2
        }

        // Register and wrap false branch (use else-branch location, not if-expression location)
        val falseBranchProbeId = probeId
        probeId += 1
        if (exp3.loc.isReal) {
          session.registerProbe(falseBranchProbeId, exp3.loc.source.name, exp3.loc.startLine, ProbeKind.BranchFalse, qualifiedName)
        }

        // Recursively instrument else expression
        val (instExp3, pc5) = instrumentExpression(exp3, qualifiedName, probeId, registeredLineProbes)
        probeId = pc5
        val (lineExp3, pc6) = instrumentLine(instExp3, qualifiedName, probeId, registeredLineProbes)
        probeId = pc6

        // Wrap else branch with probe
        val wrappedExp3 = if (exp3.loc.isReal) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(sessionId, falseBranchProbeId, exp3.loc)),
            lineExp3,
            lineExp3.tpe,
            lineExp3.eff,
            exp3.loc
          )
        } else {
          instExp3
        }

        (e.copy(exp1 = lineExp1, exp2 = wrappedExp2, exp3 = wrappedExp3), probeId)

      // A match rule contributes one branch when its body is selected.
      case e @ TypedAst.Expr.Match(selector, rules, tpe, eff, loc) =>
        val (instSelector, pc1) = instrumentExpression(selector, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1
        val instRules = rules.map { rule =>
          val instGuardOpt = rule.guard.map { guard =>
            val (result, nextProbeId) = instrumentExpression(guard, qualifiedName, probeId, registeredLineProbes)
            probeId = nextProbeId
            val (lineGuard, nextLineProbeId) = instrumentLine(result, qualifiedName, probeId, registeredLineProbes)
            probeId = nextLineProbeId

            if (guard.loc.isReal) {
              val trueProbeId = probeId
              probeId += 1
              session.registerProbe(trueProbeId, guard.loc.source.name, guard.loc.startLine, ProbeKind.BranchTrue, qualifiedName)

              val falseProbeId = probeId
              probeId += 1
              session.registerProbe(falseProbeId, guard.loc.source.name, guard.loc.startLine, ProbeKind.BranchFalse, qualifiedName)

              val trueBranch = TypedAst.Expr.Stm(
                List(TypedAst.Expr.CoverageHit(sessionId, trueProbeId, guard.loc)),
                TypedAst.Expr.Cst(ca.uwaterloo.flix.language.ast.shared.Constant.Bool(true), Type.Bool, guard.loc),
                Type.Bool,
                Type.Pure,
                guard.loc
              )
              val falseBranch = TypedAst.Expr.Stm(
                List(TypedAst.Expr.CoverageHit(sessionId, falseProbeId, guard.loc)),
                TypedAst.Expr.Cst(ca.uwaterloo.flix.language.ast.shared.Constant.Bool(false), Type.Bool, guard.loc),
                Type.Bool,
                Type.Pure,
                guard.loc
              )
              TypedAst.Expr.IfThenElse(lineGuard, trueBranch, falseBranch, Type.Bool, lineGuard.eff, guard.loc)
            } else {
              lineGuard
            }
          }

          val (instBody, nextProbeId) = instrumentExpression(rule.exp, qualifiedName, probeId, registeredLineProbes)
          probeId = nextProbeId
          val (lineBody, nextLineProbeId) = instrumentLine(instBody, qualifiedName, probeId, registeredLineProbes)
          probeId = nextLineProbeId

          val hitsToPrepend = if (rule.exp.loc.isReal) {
            val ruleProbeId = probeId
            probeId += 1
            session.registerProbe(ruleProbeId, rule.exp.loc.source.name, rule.exp.loc.startLine, ProbeKind.BranchRule, qualifiedName)
            List(TypedAst.Expr.CoverageHit(sessionId, ruleProbeId, rule.exp.loc))
          } else Nil

          val wrappedBody = if (hitsToPrepend.nonEmpty) {
            TypedAst.Expr.Stm(hitsToPrepend, lineBody, lineBody.tpe, lineBody.eff, rule.exp.loc)
          } else {
            lineBody
          }

          rule.copy(guard = instGuardOpt, exp = wrappedBody)
        }
        (e.copy(exp = instSelector, rules = instRules), probeId)

      case e @ TypedAst.Expr.RestrictableChoose(star, selector, rules, tpe, eff, loc) =>
        val (instSelector, pc1) = instrumentExpression(selector, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1
        val instRules = rules.map { rule =>
          val (instBody, nextProbeId) = instrumentExpression(rule.exp, qualifiedName, probeId, registeredLineProbes)
          probeId = nextProbeId
          val (lineBody, nextLineProbeId) = instrumentLine(instBody, qualifiedName, probeId, registeredLineProbes)
          probeId = nextLineProbeId
          if (rule.exp.loc.isReal) {
            val ruleProbeId = probeId
            probeId += 1
            session.registerProbe(ruleProbeId, rule.exp.loc.source.name, rule.exp.loc.startLine, ProbeKind.BranchRule, qualifiedName)
            rule.copy(exp = TypedAst.Expr.Stm(List(TypedAst.Expr.CoverageHit(sessionId, ruleProbeId, rule.exp.loc)), lineBody, lineBody.tpe, lineBody.eff, rule.exp.loc))
          } else rule.copy(exp = lineBody)
        }
        (e.copy(exp = instSelector, rules = instRules), probeId)

      // Instrument let-expressions with line probes
      case e @ TypedAst.Expr.Let(sym, exp1, exp2, tpe, eff, loc) =>
        // Register line probe for this let-binding (only once per unique line)
        val lineProbeId = probeId
        val lineProbeKey = (qualifiedName, loc.source.name, loc.startLine)
        val registeredLineProbe = loc.isReal && registeredLineProbes.add(lineProbeKey)
        if (registeredLineProbe) {
          probeId += 1
          session.registerProbe(lineProbeId, loc.source.name, loc.startLine, ProbeKind.Line, qualifiedName)
        }

        // Recursively instrument bound expression
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1

        // Wrap the bound expression only when this expression registered a probe.
        val wrappedExp1 = if (registeredLineProbe) {
          TypedAst.Expr.Stm(
            List(TypedAst.Expr.CoverageHit(sessionId, lineProbeId, loc)),
            instExp1,
            instExp1.tpe,
            instExp1.eff,
            loc
          )
        } else {
          instExp1
        }

        // Recursively instrument body expression
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, probeId, registeredLineProbes)
        probeId = pc2

        (e.copy(exp1 = wrappedExp1, exp2 = instExp2), probeId)

      // Instrument statement expressions (blocks)
      case e @ TypedAst.Expr.Stm(exps, finalExp, tpe, eff, loc) =>
        val instExps = exps.map { stmExp =>
          val (instExp, pc) = instrumentExpression(stmExp, qualifiedName, probeId, registeredLineProbes)
          probeId = pc
          val (lineExp, nextProbeId) = instrumentLine(instExp, qualifiedName, probeId, registeredLineProbes)
          probeId = nextProbeId
          lineExp
        }

        val (instFinalExp, pc) = instrumentExpression(finalExp, qualifiedName, probeId, registeredLineProbes)
        probeId = pc
        val (lineFinalExp, nextProbeId) = instrumentLine(instFinalExp, qualifiedName, probeId, registeredLineProbes)
        probeId = nextProbeId

        (e.copy(exps = instExps, exp = lineFinalExp), probeId)

      case e @ TypedAst.Expr.TryCatch(exp0, rules, tpe, eff, loc) =>
        val (instExp0, pc1) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        probeId = pc1
        val instRules = rules.map { rule =>
          val (instRuleExp, pcRule) = instrumentExpression(rule.exp, qualifiedName, probeId, registeredLineProbes)
          probeId = pcRule
          val (lineRuleExp, pcLine) = instrumentLine(instRuleExp, qualifiedName, probeId, registeredLineProbes)
          probeId = pcLine

          val wrappedBody = if (rule.exp.loc.isReal) {
            val ruleProbeId = probeId
            probeId += 1
            session.registerProbe(ruleProbeId, rule.exp.loc.source.name, rule.exp.loc.startLine, ProbeKind.BranchRule, qualifiedName)
            TypedAst.Expr.Stm(List(TypedAst.Expr.CoverageHit(sessionId, ruleProbeId, rule.exp.loc)), lineRuleExp, lineRuleExp.tpe, lineRuleExp.eff, rule.exp.loc)
          } else lineRuleExp

          rule.copy(exp = wrappedBody)
        }
        instrumentLine(e.copy(exp = instExp0, rules = instRules), qualifiedName, probeId, registeredLineProbes)

      case e @ TypedAst.Expr.Throw(exp0, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exp = instExp0), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.Handler(symUse, rules, bodyType, bodyEff, handledEff, tpe, loc) =>
        val instRules = rules.map { rule =>
          val (instRuleExp, pcRule) = instrumentExpression(rule.exp, qualifiedName, probeId, registeredLineProbes)
          probeId = pcRule
          val (lineRuleExp, pcLine) = instrumentLine(instRuleExp, qualifiedName, probeId, registeredLineProbes)
          probeId = pcLine

          val wrappedBody = if (rule.exp.loc.isReal) {
            val ruleProbeId = probeId
            probeId += 1
            session.registerProbe(ruleProbeId, rule.exp.loc.source.name, rule.exp.loc.startLine, ProbeKind.BranchRule, qualifiedName)
            TypedAst.Expr.Stm(List(TypedAst.Expr.CoverageHit(sessionId, ruleProbeId, rule.exp.loc)), lineRuleExp, lineRuleExp.tpe, lineRuleExp.eff, rule.exp.loc)
          } else lineRuleExp

          rule.copy(exp = wrappedBody)
        }
        instrumentLine(e.copy(rules = instRules), qualifiedName, probeId, registeredLineProbes)

      case e @ TypedAst.Expr.Spawn(exp1, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        instrumentLine(e.copy(exp1 = instExp1, exp2 = instExp2), qualifiedName, pc2, registeredLineProbes)

      case e @ TypedAst.Expr.Lazy(exp0, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exp = instExp0), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.Force(exp0, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exp = instExp0), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.StructNew(sym, fields, region, _, _, _) =>
        val (instFields, pc1) = fields.foldLeft((List.empty[(ca.uwaterloo.flix.language.ast.shared.SymUse.StructFieldSymUse, TypedAst.Expr)], probeId)) {
          case ((acc, currentProbeId), (symField, expField)) =>
            val (instField, nextProbeId) = instrumentExpression(expField, qualifiedName, currentProbeId, registeredLineProbes)
            ((symField, instField) :: acc, nextProbeId)
        }
        probeId = pc1
        val (instRegion, pc2) = region match {
          case Some(r) =>
            val (iR, pR) = instrumentExpression(r, qualifiedName, probeId, registeredLineProbes)
            (Some(iR), pR)
          case None => (None, probeId)
        }
        instrumentLine(e.copy(fields = instFields.reverse, region = instRegion), qualifiedName, pc2, registeredLineProbes)

      case e @ TypedAst.Expr.StructGet(exp0, _, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exp = instExp0), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.StructPut(exp1, symUse, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        instrumentLine(e.copy(exp1 = instExp1, exp2 = instExp2), qualifiedName, pc2, registeredLineProbes)

      case e @ TypedAst.Expr.VectorLit(exps, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exps = instExps), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.VectorLoad(exp1, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        instrumentLine(e.copy(exp1 = instExp1, exp2 = instExp2), qualifiedName, pc2, registeredLineProbes)

      case e @ TypedAst.Expr.VectorLength(exp0, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exp = instExp0), qualifiedName, nextProbeId, registeredLineProbes)

      // Traversed-child expression forms
      case e @ TypedAst.Expr.ApplyDef(_, exps, _, _, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exps = instExps), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.ApplyClo(exp1, exp2, _, _, _, loc) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        val sourceLoc = firstRealLocation(loc, exp1.loc, exp2.loc)
        instrumentLineAt(e.copy(exp1 = instExp1, exp2 = instExp2), sourceLoc, qualifiedName, pc2, registeredLineProbes)

      case e @ TypedAst.Expr.Lambda(fparam, body, _, loc) =>
        val (instBody, nextProbeId) = instrumentExpression(body, qualifiedName, probeId, registeredLineProbes)
        val sourceLoc = if (loc.isReal) loc else fparam.loc
        instrumentLineAt(e.copy(exp = instBody), sourceLoc, qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.Tuple(exps, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        instrumentLine(e.copy(exps = instExps), qualifiedName, nextProbeId, registeredLineProbes)

      case e @ TypedAst.Expr.LocalDef(_, _, _, exp1, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        (e.copy(exp1 = instExp1, exp2 = instExp2), pc2)

      case e @ TypedAst.Expr.ApplyLocalDef(_, exps, _, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.ApplyOp(_, exps, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.ApplySig(_, exps, _, _, _, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.Region(_, _, exp0, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exp = instExp0), nextProbeId)

      case e @ TypedAst.Expr.Discard(exp0, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exp = instExp0), nextProbeId)

      case e @ TypedAst.Expr.ExtMatch(selector, rules, _, _, _) =>
        val (instSelector, pc1) = instrumentExpression(selector, qualifiedName, probeId, registeredLineProbes)
        var pc = pc1
        val instRules = rules.map { rule =>
          val (instBody, nextProbeId) = instrumentExpression(rule.exp, qualifiedName, pc, registeredLineProbes)
          pc = nextProbeId
          rule.copy(exp = instBody)
        }
        (e.copy(exp = instSelector, rules = instRules), pc)

      case e @ TypedAst.Expr.ExtTag(_, exps, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.Unsafe(exp0, _, _, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exp = instExp0), nextProbeId)

      case e @ TypedAst.Expr.NewChannel(exp0, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exp = instExp0), nextProbeId)

      case e @ TypedAst.Expr.GetChannel(exp0, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exp = instExp0), nextProbeId)

      case e @ TypedAst.Expr.PutChannel(exp1, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        (e.copy(exp1 = instExp1, exp2 = instExp2), pc2)

      case e @ TypedAst.Expr.SelectChannel(rules, default, _, _, _) =>
        var pc = probeId
        val instRules = rules.map { rule =>
          val (instChan, pc1) = instrumentExpression(rule.chan, qualifiedName, pc, registeredLineProbes)
          val (instExp, pc2) = instrumentExpression(rule.exp, qualifiedName, pc1, registeredLineProbes)
          pc = pc2
          rule.copy(chan = instChan, exp = instExp)
        }
        val (instDefault, finalPc) = default match {
          case Some(d) =>
            val (iD, pD) = instrumentExpression(d, qualifiedName, pc, registeredLineProbes)
            (Some(iD), pD)
          case None => (None, pc)
        }
        (e.copy(rules = instRules, default = instDefault), finalPc)

      case e @ TypedAst.Expr.InvokeSuperConstructor(_, exps, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.InvokeSuperMethod(_, exps, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.ParYield(frags, exp0, _, _, _) =>
        var pc = probeId
        val instFrags = frags.map { frag =>
          val (instExp, pc1) = instrumentExpression(frag.exp, qualifiedName, pc, registeredLineProbes)
          pc = pc1
          frag.copy(exp = instExp)
        }
        val (instExp0, finalPc) = instrumentExpression(exp0, qualifiedName, pc, registeredLineProbes)
        (e.copy(frags = instFrags, exp = instExp0), finalPc)

      case e @ TypedAst.Expr.FixpointLambda(_, exp0, _, _, _) =>
        val (instExp0, nextProbeId) = instrumentExpression(exp0, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exp = instExp0), nextProbeId)

      case e @ TypedAst.Expr.FixpointMerge(exp1, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        (e.copy(exp1 = instExp1, exp2 = instExp2), pc2)

      case e @ TypedAst.Expr.FixpointQueryWithProvenance(exps, _, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.FixpointQueryWithSelect(exps, queryExp, selects, _, where, _, _, _, _) =>
        val (instExps, pc1) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        val (instQueryExp, pc2) = instrumentExpression(queryExp, qualifiedName, pc1, registeredLineProbes)
        val (instSelects, pc3) = instrumentExpressions(selects, qualifiedName, pc2, registeredLineProbes)
        val (instWhere, pc4) = instrumentExpressions(where, qualifiedName, pc3, registeredLineProbes)
        (e.copy(exps = instExps, queryExp = instQueryExp, selects = instSelects, where = instWhere), pc4)

      case e @ TypedAst.Expr.FixpointSolveWithProject(exps, _, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.FixpointInjectInto(exps, _, _, _, _) =>
        val (instExps, nextProbeId) = instrumentExpressions(exps, qualifiedName, probeId, registeredLineProbes)
        (e.copy(exps = instExps), nextProbeId)

      case e @ TypedAst.Expr.RunWith(exp1, exp2, _, _, _) =>
        val (instExp1, pc1) = instrumentExpression(exp1, qualifiedName, probeId, registeredLineProbes)
        val (instExp2, pc2) = instrumentExpression(exp2, qualifiedName, pc1, registeredLineProbes)
        (e.copy(exp1 = instExp1, exp2 = instExp2), pc2)

      // Leave expression forms without explicit execution semantics uninstrumented.
      case _ => (exp, probeId)
    }

    result
  }

  /** Recursively instruments a list of expressions without changing their evaluation order. */
  private def instrumentExpressions(
    exps: List[TypedAst.Expr],
    qualifiedName: String,
    startProbeId: Int,
    registeredLineProbes: scala.collection.mutable.Set[(String, String, Int)]
  )(implicit session: Coverage.Session, sessionId: Long): (List[TypedAst.Expr], Int) = {
    val (reversedExps, nextProbeId) = exps.foldLeft((List.empty[TypedAst.Expr], startProbeId)) {
      case ((acc, probeId), exp) =>
        val (instExp, nextProbeId) = instrumentExpression(exp, qualifiedName, probeId, registeredLineProbes)
        (instExp :: acc, nextProbeId)
    }
    (reversedExps.reverse, nextProbeId)
  }

  /** Registers and inserts one line probe for a real source expression. */
  private def instrumentLine(
    exp: TypedAst.Expr,
    qualifiedName: String,
    probeId: Int,
    registeredLineProbes: scala.collection.mutable.Set[(String, String, Int)]
  )(implicit session: Coverage.Session, sessionId: Long): (TypedAst.Expr, Int) = {
    instrumentLineAt(exp, exp.loc, qualifiedName, probeId, registeredLineProbes)
  }

  /** Registers and inserts one line probe using an explicit real-source location. */
  private def instrumentLineAt(
    exp: TypedAst.Expr,
    loc: SourceLocation,
    qualifiedName: String,
    probeId: Int,
    registeredLineProbes: scala.collection.mutable.Set[(String, String, Int)]
  )(implicit session: Coverage.Session, sessionId: Long): (TypedAst.Expr, Int) = {
    val key = (qualifiedName, loc.source.name, loc.startLine)
    if (loc.isReal && registeredLineProbes.add(key)) {
      session.registerProbe(probeId, loc.source.name, loc.startLine, ProbeKind.Line, qualifiedName)
      val wrapped = TypedAst.Expr.Stm(List(TypedAst.Expr.CoverageHit(sessionId, probeId, loc)), exp, exp.tpe, exp.eff, loc)
      (wrapped, probeId + 1)
    } else {
      (exp, probeId)
    }
  }

  /** Returns the first real location, or the primary location if all candidates are synthetic. */
  private def firstRealLocation(primary: SourceLocation, alternatives: SourceLocation*): SourceLocation =
    (primary :: alternatives.toList).find(_.isReal).getOrElse(primary)

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
