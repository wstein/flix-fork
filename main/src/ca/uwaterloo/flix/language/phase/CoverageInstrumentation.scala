/*
 * Copyright 2026 Werner Stein
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
import ca.uwaterloo.flix.language.ast.shared.Constant
import ca.uwaterloo.flix.language.ast.{SourceLocation, Type, TypedAst}
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugTypedAst
import ca.uwaterloo.flix.runtime.{CoverageProbe, CoverageProbeKind, CoverageSession}

import scala.collection.mutable

/** Inserts the source-level probes owned by one compilation session. */
object CoverageInstrumentation {

  def run(root: TypedAst.Root)(implicit flix: Flix): (TypedAst.Root, CoverageSession) = flix.phase("CoverageInstrumentation") {
    val sessionId = CoverageSession.freshId()
    val probes = mutable.ArrayBuffer.empty[CoverageProbe]
    val registeredLines = mutable.HashSet.empty[(String, String, Int)]

    def register(kind: CoverageProbeKind, qualifiedName: String, loc: SourceLocation): Int = {
      val id = probes.size
      probes += CoverageProbe(id, loc.source.name, loc.startLine, kind, qualifiedName)
      id
    }

    def hit(from: TypedAst.Expr, probeId: Int, loc: SourceLocation, role: String): TypedAst.Expr =
      flix.jvmOrigins.synthetic(from, TypedAst.Expr.CoverageHit(sessionId, probeId, loc), role)

    def wrap(from: TypedAst.Expr, exp: TypedAst.Expr, probeId: Int, loc: SourceLocation, role: String): TypedAst.Expr = {
      val probe = hit(from, probeId, loc, s"$role-hit")
      flix.jvmOrigins.synthetic(from, TypedAst.Expr.Stm(List(probe), exp, exp.tpe, exp.eff, loc), role)
    }

    def visitAll(exps: List[TypedAst.Expr], qualifiedName: String): List[TypedAst.Expr] =
      exps.map(visit(_, qualifiedName))

    def visit(exp0: TypedAst.Expr, qualifiedName: String): TypedAst.Expr = {
      // Reserve the line before descending. The outermost executable expression on a source line
      // owns its probe, so an unselected nested branch cannot accidentally own the whole line.
      val lineProbe = {
        val key = (qualifiedName, exp0.loc.source.name, exp0.loc.startLine)
        if (exp0.loc.isReal && registeredLines.add(key)) Some(register(CoverageProbeKind.Line, qualifiedName, exp0.loc))
        else None
      }

      def visitBranch(exp: TypedAst.Expr, kind: CoverageProbeKind, role: String): TypedAst.Expr = {
        val instrumented = visit(exp, qualifiedName)
        if (exp.loc.isReal) wrap(exp, instrumented, register(kind, qualifiedName, exp.loc), exp.loc, role)
        else instrumented
      }

      def visitGuard(exp: TypedAst.Expr): TypedAst.Expr = {
        val instrumented = visit(exp, qualifiedName)
        if (!exp.loc.isReal) instrumented
        else {
          val trueProbe = register(CoverageProbeKind.BranchTrue, qualifiedName, exp.loc)
          val falseProbe = register(CoverageProbeKind.BranchFalse, qualifiedName, exp.loc)
          val trueValue = flix.jvmOrigins.synthetic(exp,
            TypedAst.Expr.Cst(Constant.Bool(true), Type.Bool, exp.loc), "coverage-guard-true-value")
          val falseValue = flix.jvmOrigins.synthetic(exp,
            TypedAst.Expr.Cst(Constant.Bool(false), Type.Bool, exp.loc), "coverage-guard-false-value")
          val trueBranch = wrap(exp, trueValue, trueProbe, exp.loc, "coverage-guard-true")
          val falseBranch = wrap(exp, falseValue, falseProbe, exp.loc, "coverage-guard-false")
          flix.jvmOrigins.synthetic(exp,
            TypedAst.Expr.IfThenElse(instrumented, trueBranch, falseBranch, Type.Bool, instrumented.eff, exp.loc),
            "coverage-guard-branch")
        }
      }

      val rebuilt: TypedAst.Expr = exp0 match {
        case _: TypedAst.Expr.Cst | _: TypedAst.Expr.Var | _: TypedAst.Expr.Hole |
             _: TypedAst.Expr.GetStaticField | _: TypedAst.Expr.FixpointConstraintSet |
             _: TypedAst.Expr.Error => exp0
        case e: TypedAst.Expr.CoverageHit => e
        case e: TypedAst.Expr.HoleWithExp => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.OpenAs => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Use => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Lambda => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.ApplyClo => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.ApplyDef => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.ApplyLocalDef => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.ApplyOp => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.ApplySig => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.Unary => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Binary => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.Let => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.LocalDef => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.Region => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.IfThenElse => e.copy(
          exp1 = visit(e.exp1, qualifiedName),
          exp2 = visitBranch(e.exp2, CoverageProbeKind.BranchTrue, "coverage-if-true"),
          exp3 = visitBranch(e.exp3, CoverageProbeKind.BranchFalse, "coverage-if-false"))
        case e: TypedAst.Expr.Stm => e.copy(exps = visitAll(e.exps, qualifiedName), exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Discard => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Match => e.copy(
          exp = visit(e.exp, qualifiedName),
          rules = e.rules.map(r => r.copy(
            guard = r.guard.map(visitGuard),
            exp = visitBranch(r.exp, CoverageProbeKind.BranchRule, "coverage-match-rule"))))
        case e: TypedAst.Expr.RestrictableChoose => e.copy(
          exp = visit(e.exp, qualifiedName),
          rules = e.rules.map(r => r.copy(exp = visitBranch(r.exp, CoverageProbeKind.BranchRule, "coverage-choose-rule"))))
        case e: TypedAst.Expr.ExtMatch => e.copy(
          exp = visit(e.exp, qualifiedName),
          rules = e.rules.map(r => r.copy(exp = visitBranch(r.exp, CoverageProbeKind.BranchRule, "coverage-ext-match-rule"))))
        case e: TypedAst.Expr.Tag => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.RestrictableTag => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.ExtTag => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.Tuple => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.RecordSelect => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.RecordExtend => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.RecordRestrict => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.ArrayLit => e.copy(exps = visitAll(e.exps, qualifiedName), exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.ArrayNew => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName), exp3 = visit(e.exp3, qualifiedName))
        case e: TypedAst.Expr.ArrayLoad => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.ArrayLength => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.ArrayStore => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName), exp3 = visit(e.exp3, qualifiedName))
        case e: TypedAst.Expr.StructNew => e.copy(fields = e.fields.map { case (sym, exp) => sym -> visit(exp, qualifiedName) }, region = e.region.map(visit(_, qualifiedName)))
        case e: TypedAst.Expr.StructGet => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.StructPut => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.VectorLit => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.VectorLoad => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.VectorLength => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Ascribe => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.InstanceOf => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.CheckedCast => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.UncheckedCast => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Unsafe => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.TryCatch => e.copy(
          exp = visit(e.exp, qualifiedName),
          rules = e.rules.map(r => r.copy(exp = visitBranch(r.exp, CoverageProbeKind.BranchRule, "coverage-catch-rule"))))
        case e: TypedAst.Expr.Throw => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Handler => e.copy(
          rules = e.rules.map(r => r.copy(exp = visitBranch(r.exp, CoverageProbeKind.BranchRule, "coverage-handler-rule"))))
        case e: TypedAst.Expr.RunWith => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.InvokeConstructor => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.InvokeSuperConstructor => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.InvokeMethod => e.copy(exp = visit(e.exp, qualifiedName), exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.InvokeSuperMethod => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.InvokeStaticMethod => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.GetField => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.PutField => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.PutStaticField => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.NewObject => e.copy(constructors = e.constructors.map(c => c.copy(exp = visit(c.exp, qualifiedName))), methods = e.methods.map(m => m.copy(exp = visit(m.exp, qualifiedName))))
        case e: TypedAst.Expr.NewChannel => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.GetChannel => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.PutChannel => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.SelectChannel => e.copy(rules = e.rules.map(r => r.copy(chan = visit(r.chan, qualifiedName), exp = visit(r.exp, qualifiedName))), default = e.default.map(visit(_, qualifiedName)))
        case e: TypedAst.Expr.Spawn => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.ParYield => e.copy(frags = e.frags.map(f => f.copy(exp = visit(f.exp, qualifiedName))), exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Lazy => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.Force => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.FixpointLambda => e.copy(exp = visit(e.exp, qualifiedName))
        case e: TypedAst.Expr.FixpointMerge => e.copy(exp1 = visit(e.exp1, qualifiedName), exp2 = visit(e.exp2, qualifiedName))
        case e: TypedAst.Expr.FixpointQueryWithProvenance => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.FixpointQueryWithSelect => e.copy(exps = visitAll(e.exps, qualifiedName), queryExp = visit(e.queryExp, qualifiedName), selects = visitAll(e.selects, qualifiedName), where = visitAll(e.where, qualifiedName))
        case e: TypedAst.Expr.FixpointSolveWithProject => e.copy(exps = visitAll(e.exps, qualifiedName))
        case e: TypedAst.Expr.FixpointInjectInto => e.copy(exps = visitAll(e.exps, qualifiedName))
      }

      val attributed = if (rebuilt eq exp0) rebuilt else flix.jvmOrigins.transfer(exp0, rebuilt, "coverage-line-traversal")
      lineProbe.fold(attributed)(probeId => wrap(exp0, attributed, probeId, exp0.loc, "coverage-line"))
    }

    val definitions = root.defs.values.toList.filter(shouldInstrument)
      .sortBy(defn => (defn.loc.source.name, defn.loc.startLine, defn.sym.toString))
    val instrumentedBySymbol = definitions.iterator.map { defn =>
      val qualifiedName = defn.sym.toString
      val functionProbe = register(CoverageProbeKind.Function, qualifiedName, defn.loc)
      val instrumentedBody = visit(defn.exp, qualifiedName)
      val body = wrap(defn.exp, instrumentedBody, functionProbe, defn.loc, "coverage-function")
      defn.sym -> defn.copy(exp = body)
    }.toMap
    val instrumented = root.defs.map { case (sym, defn) => sym -> instrumentedBySymbol.getOrElse(sym, defn) }
    val session = CoverageSession(sessionId, probes.toVector)
    (root.copy(defs = instrumented), session)
  }

  private def shouldInstrument(defn: TypedAst.Def): Boolean =
    defn.loc.isReal && defn.loc.source.origin.isUser && !defn.spec.ann.isTest
}
