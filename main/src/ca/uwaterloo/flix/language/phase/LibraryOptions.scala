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
import ca.uwaterloo.flix.util.{ExecutionMode, InternalCompilerException, LibLevel}

/**
  * Rewrites the standard library's compile-time options to reflect the compiler options.
  *
  * Each such library option is a zero-argument function returning `true`. Setting its body to
  * `false` lets the optimizer fold every branch that tests it, after which the tree shaker removes
  * the code that only those branches kept alive. A runtime flag would not do: both branches would
  * stay reachable.
  *
  * The rewrite must run after `Redundancy`, since replacing a body can leave a formal parameter
  * unused, and the caches must be written before it, so that the rewrite is recomputed from the
  * current options on every run. See `Flix.check`.
  */
object LibraryOptions {

  /**
    * Guards the parallel evaluator in `Fixpoint3.Interpreter`.
    */
  private val EnableParallelExecutionSym = Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution")

  /**
    * Guards the parallel evaluation of pure operations on `Map`, `Set`, `DelayMap`, and
    * `RedBlackTree`.
    */
  private val EnableParallelEvaluationSym = Symbol.mkDefnSym("Concurrent.Options.enableParallelEvaluation")

  /**
    * Guards the locking in `BPlusTree` and `Fixpoint3`.
    *
    * Unlike the two above, disabling this gives up thread safety rather than selecting another
    * implementation of the same contract. It is only sound in a program where nothing is left that
    * could run in parallel, which is why `Options.isSingleThreaded` requires the other two to be disabled
    * as well, and why `--Xsequential` is the only option that asks for it.
    */
  private val EnableLockingSym = Symbol.mkDefnSym("Concurrent.Options.enableLocking")

  /**
    * Returns `true` for a compiler-owned library switch whose body is a compile-time constant.
    *
    * Debug builds may inline these definitions so that the ordinary optimizer can still remove
    * disabled sequential/parallel branches. User definitions are deliberately not included: their
    * generated classes are the locations to which source breakpoints bind.
    */
  def isCompilerSwitch(sym: Symbol.DefnSym): Boolean =
    sym == EnableParallelExecutionSym ||
      sym == EnableParallelEvaluationSym ||
      sym == EnableLockingSym

  def run(root: TypedAst.Root)(implicit flix: Flix): TypedAst.Root = flix.phase("LibraryOptions") {
    val disabled = List(
      EnableParallelExecutionSym -> (flix.options.xdatalogExecution == ExecutionMode.Sequential),
      EnableParallelEvaluationSym -> (flix.options.xcollectionExecution == ExecutionMode.Sequential),
      EnableLockingSym -> flix.options.isSingleThreaded
    ).collect { case (sym, true) => sym }

    disabled.foldLeft(root)(setToFalse)
  }

  /**
    * Returns `root` with the body of the definition `sym` replaced by `false`.
    */
  private def setToFalse(root: TypedAst.Root, sym: Symbol.DefnSym)(implicit flix: Flix): TypedAst.Root = {
    root.defs.get(sym) match {
      case Some(defn) =>
        val exp = TypedAst.Expr.Cst(Constant.Bool(false), Type.Bool, defn.loc.asSynthetic)
        root.copy(defs = root.defs + (sym -> defn.copy(exp = exp)))
      case None if flix.options.lib != LibLevel.All =>
        // The definition lives in the standard library. Without it there is nothing to rewrite.
        root
      case None =>
        throw InternalCompilerException(s"The definition '$sym' is not defined.", SourceLocation.Unknown)
    }
  }
}
