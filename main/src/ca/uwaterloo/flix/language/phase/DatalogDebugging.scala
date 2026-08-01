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
import ca.uwaterloo.flix.language.ast.{Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugTypedAst
import ca.uwaterloo.flix.util.DatalogDebug

/**
  * Enables the Datalog solver's tracing hooks selected by [[Flix.options.xdatalogDebug]].
  *
  * The Datalog subset of Flix is lowered into values that the `Fixpoint` solver interprets, so a
  * debugger cannot step through a rule. The solver instead traces itself, guarding each trace with
  * a switch in `Fixpoint3.Options`.
  *
  * Those switches are ordinary Flix functions returning a constant, which the optimizer folds and
  * whose guarded code it then eliminates. That is what makes tracing free when it is switched off,
  * but it also means the switches cannot be read at runtime -- when tracing is disabled the code is
  * not merely skipped, it is absent from the compiled program. Selecting a trace is therefore a
  * compile-time decision, made here by rewriting the switch to return `true` before the optimizer
  * runs.
  *
  * This phase is a no-op unless a trace is requested, since the switches already return `false`.
  */
object DatalogDebugging {

  /** The switch guarding each kind of trace, by the option that enables it. */
  private val Switches: Map[DatalogDebug, Symbol.DefnSym] = Map(
    DatalogDebug.Rules -> Symbol.mkDefnSym("Fixpoint3.Options.enableDebugRules"),
    DatalogDebug.Facts -> Symbol.mkDefnSym("Fixpoint3.Options.enableDebugFacts"),
    DatalogDebug.Ram -> Symbol.mkDefnSym("Fixpoint3.Options.enableDebugRam"),
  )

  /**
    * Returns `root` with the switch of every requested trace rewritten to return `true`.
    *
    * A switch that is absent from `root` is left alone: the program does not use the solver, so
    * there is nothing to trace.
    */
  def run(root: TypedAst.Root)(implicit flix: Flix): TypedAst.Root = flix.phase("DatalogDebugging") {
    if (flix.options.xdatalogDebug.isEmpty) {
      return root
    }

    val enabled = flix.options.xdatalogDebug.flatMap(Switches.get)
    val newDefs = enabled.foldLeft(root.defs) {
      case (defs, sym) => defs.get(sym) match {
        case None => defs
        case Some(defn) => defs.updated(sym, enable(defn))
      }
    }
    root.copy(defs = newDefs)
  }

  /** Returns `defn` with its body replaced by `true`. */
  private def enable(defn: TypedAst.Def): TypedAst.Def = {
    val exp = TypedAst.Expr.Cst(Constant.Bool(true), Type.Bool, defn.exp.loc)
    defn.copy(exp = exp)
  }

}
