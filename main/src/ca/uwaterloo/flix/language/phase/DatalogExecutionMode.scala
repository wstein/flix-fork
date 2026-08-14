/*
 * Copyright 2026 Magnus Madsen, Werner Stein
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
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugTypedAst
import ca.uwaterloo.flix.util.{DatalogExecution, InternalCompilerException, LibLevel}

/**
  * Rewrites compile-time Datalog options in the TypedAst.
  *
  * When datalog execution mode is Sequential, replaces `Fixpoint3.Options.enableParallelExecution`
  * with `false`.
  */
object DatalogExecutionMode {

  private val EnableParallelExecutionSym = Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution")

  def run(root: TypedAst.Root)(implicit flix: Flix): TypedAst.Root = flix.phase("DatalogExecutionMode") {
    if (flix.options.xdatalogExecution == DatalogExecution.Sequential) {
      root.defs.get(EnableParallelExecutionSym) match {
        case Some(defn) =>
          val newExp = TypedAst.Expr.Cst(Constant.Bool(false), Type.Bool, defn.loc.asSynthetic)
          val newDef = defn.copy(exp = newExp)
          root.copy(defs = root.defs + (EnableParallelExecutionSym -> newDef))
        case None if flix.options.lib != LibLevel.All =>
          // Fixpoint3 is only available with the full standard library. Without it there is no
          // Datalog solver to configure, so there is nothing to rewrite.
          root
        case None =>
          throw InternalCompilerException(s"The definition '$EnableParallelExecutionSym' is not defined.", SourceLocation.Unknown)
      }
    } else {
      root
    }
  }
}
