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
import ca.uwaterloo.flix.language.ast.{Type, TypedAst}
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugTypedAst
import ca.uwaterloo.flix.runtime.{CoverageProbe, CoverageProbeKind, CoverageSession}

/** Inserts the source-level probes owned by one compilation session. */
object CoverageInstrumentation {

  def run(root: TypedAst.Root)(implicit flix: Flix): (TypedAst.Root, CoverageSession) = flix.phase("CoverageInstrumentation") {
    val definitions = root.defs.values.toList
      .filter(shouldInstrument)
      .sortBy(defn => (defn.loc.source.name, defn.loc.startLine, defn.sym.toString))
    val probes = definitions.zipWithIndex.map { case (defn, probeId) =>
      CoverageProbe(probeId, defn.loc.source.name, defn.loc.startLine, CoverageProbeKind.Function, defn.sym.toString)
    }.toVector
    val session = CoverageSession.fresh(probes)
    val probeIds = definitions.iterator.zipWithIndex.map { case (defn, probeId) => defn.sym -> probeId }.toMap
    val instrumented = root.defs.map { case (sym, defn) =>
      probeIds.get(sym) match {
        case None => sym -> defn
        case Some(probeId) =>
          val hit = flix.jvmOrigins.synthetic(defn.exp,
            TypedAst.Expr.CoverageHit(session.sessionId, probeId, defn.loc), "coverage-function-hit")
          val body = flix.jvmOrigins.synthetic(defn.exp,
            TypedAst.Expr.Stm(List(hit), defn.exp, defn.exp.tpe,
              Type.mkUnion(hit.eff, defn.exp.eff, defn.loc), defn.loc), "coverage-function-body")
          sym -> defn.copy(exp = body)
      }
    }
    (root.copy(defs = instrumented), session)
  }

  private def shouldInstrument(defn: TypedAst.Def): Boolean =
    defn.loc.isReal && defn.loc.source.origin.isUser && !defn.spec.ann.isTest
}
