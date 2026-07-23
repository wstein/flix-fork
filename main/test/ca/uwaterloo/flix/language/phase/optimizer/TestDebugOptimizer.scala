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
package ca.uwaterloo.flix.language.phase.optimizer

import ca.uwaterloo.flix.api.{CompilerConstants, Flix, FlixEvent, FlixListener}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.ast.{ReducedAst, Symbol}
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests that `--Xdebug` retains the let-bindings a programmer wrote.
  *
  * Without it the inliner substitutes a binding that is pure and used once, so by the time
  * bytecode is generated the binding no longer exists and a debugger has nothing to name.
  *
  * `compute` is marked `@DontInline` so that the test observes the treatment of its bindings
  * rather than the disappearance of the whole function. Bindings inside an inlined callee are
  * substituted away even under `--Xdebug`, which is deliberate: nobody can step into them.
  */
class TestDebugOptimizer extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** Every binding here is pure and used once, so all three are substituted away by default. */
  private val Program: String =
    """
      |@DontInline
      |pub def compute(a: Int32, b: Int32): Int32 =
      |    let x = a + 1;
      |    let y = b + 2;
      |    let z = x * y;
      |    z - a
      |
      |def main(): Unit \ IO =
      |    println(compute(3, 4))
      |""".stripMargin

  private val Compute: Symbol.DefnSym = Symbol.mkDefnSym("compute")

  /** Returns the names bound by the leading run of let-bindings in `compute`. */
  private def bindersOfCompute(xdebug: Boolean): List[String] = {
    var result: Option[List[String]] = None

    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = xdebug))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
    flix.addListener(new FlixListener {
      override def notify(e: FlixEvent): Unit = e match {
        case FlixEvent.AfterTailPos(root) =>
          result = root.defs.get(Compute).map(defn => leadingBinders(defn.exp))
        case _ => ()
      }
    })

    flix.compile().toResult match {
      case Result.Ok(_) => ()
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }

    result.getOrElse(fail("compute was not present after TailPos"))
  }

  /** Returns the names bound by the leading run of let-bindings in `exp`. */
  private def leadingBinders(exp: ReducedAst.Expr): List[String] = exp match {
    case ReducedAst.Expr.Let(sym, _, body, _) => sym.text :: leadingBinders(body)
    case _ => Nil
  }

  test("without --Xdebug the bindings are substituted away") {
    assertResult(Nil)(bindersOfCompute(xdebug = false))
  }

  test("with --Xdebug the bindings are retained, in source order and under their source names") {
    assertResult(List("x", "y", "z"))(bindersOfCompute(xdebug = true))
  }

}
