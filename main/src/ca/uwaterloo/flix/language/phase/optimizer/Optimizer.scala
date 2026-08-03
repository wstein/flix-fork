/*
 * Copyright 2025 Jakob Schneider Villumsen
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
package ca.uwaterloo.flix.language.phase.optimizer

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.MonoAst
import ca.uwaterloo.flix.language.dbg.AstPrinter.DebugMonoAst

object Optimizer {

  /**
    * Returns an optimized version of the given AST `root`.
    *
    * Under `--Xdebug` the root is returned unoptimized, because inlining and debugging cannot both
    * be served by one build.
    *
    * A function whose body is folded into its caller gets no class of its own, so there is nothing
    * for a breakpoint to bind to -- and the line does not survive at the call site either, since
    * the inlined body starts at the call site's own bytecode offset and only one entry per offset
    * reaches the class file (see [[ca.uwaterloo.flix.language.phase.jvm.LineNumbers]]). The line
    * then exists nowhere in the program. That is the fate of every single-expression helper, which
    * is most of them: `def maxDemo(): Int32 \ IO = Math.max(10, 20)` could never take a breakpoint.
    * Measured with `ReferenceType.locationsOfLine` against a live VM, which is the only authority
    * on what a debugger can bind to.
    *
    * This is the trade every other toolchain makes -- a debug build is not an optimized one. It
    * costs debug-session throughput, and `--Xdebug` is set only when launching a debug session, so
    * nothing anyone runs or ships is affected.
    */
  def run(root: MonoAst.Root)(implicit flix: Flix): MonoAst.Root = flix.phase("Optimizer") {
    if (flix.options.xdebug) {
      return root
    }
    var currentRoot = root
    var currentDelta = currentRoot.defs.keys.toSet
    for (_ <- 0 until CompilerConstants.MaxOptimizerRounds) {
      if (currentDelta.nonEmpty) {
        val afterOccurrenceAnalyzer = OccurrenceAnalyzer.run(currentRoot, currentDelta)
        val (newRoot, newDelta) = Inliner.run(afterOccurrenceAnalyzer)
        currentRoot = newRoot
        currentDelta = newDelta
      }
    }
    currentRoot
  }

}
