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

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.Constant
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.util.{DatalogDebug, Options}
import org.scalatest.funsuite.AnyFunSuite

class TestDatalogDebugging extends AnyFunSuite with TestUtils {

  /** A program that pulls the Datalog solver, and therefore its switches, into the root. */
  private val Program: String =
    """
      |def main(): Unit \ IO =
      |    let db = #{ Edge(1, 2). Path(x, y) :- Edge(x, y). };
      |    println(query db select (x, y) from Path(x, y))
      |""".stripMargin

  private val Rules: Symbol.DefnSym = Symbol.mkDefnSym("Fixpoint3.Options.enableDebugRules")
  private val Facts: Symbol.DefnSym = Symbol.mkDefnSym("Fixpoint3.Options.enableDebugFacts")
  private val Ram: Symbol.DefnSym = Symbol.mkDefnSym("Fixpoint3.Options.enableDebugRam")

  /** Returns the root of [[Program]] after running the phase with `choices` requested. */
  private def runPhase(choices: Set[DatalogDebug]): TypedAst.Root = {
    val opts = Options.DefaultTest.copy(xdatalogDebug = choices)
    val (root, errors) = check(Program, opts)
    assert(errors.isEmpty, "the test program must compile")
    implicit val flix: Flix = new Flix().setOptions(opts)
    DatalogDebugging.run(root.get)
  }

  /** Returns whether the switch `sym` is enabled in `root`. */
  private def isEnabled(root: TypedAst.Root, sym: Symbol.DefnSym): Boolean =
    root.defs(sym).exp match {
      case TypedAst.Expr.Cst(Constant.Bool(b), _, _) => b
      case other => fail(s"expected a boolean constant for $sym, but found $other")
    }

  test("the switches default to disabled") {
    val root = runPhase(Set.empty)
    assert(!isEnabled(root, Rules))
    assert(!isEnabled(root, Facts))
    assert(!isEnabled(root, Ram))
  }

  test("requesting rules enables only the rules switch") {
    val root = runPhase(Set(DatalogDebug.Rules))
    assert(isEnabled(root, Rules))
    assert(!isEnabled(root, Facts))
    assert(!isEnabled(root, Ram))
  }

  test("requesting facts enables only the facts switch") {
    val root = runPhase(Set(DatalogDebug.Facts))
    assert(!isEnabled(root, Rules))
    assert(isEnabled(root, Facts))
    assert(!isEnabled(root, Ram))
  }

  test("requesting ram enables only the ram switch") {
    val root = runPhase(Set(DatalogDebug.Ram))
    assert(!isEnabled(root, Rules))
    assert(!isEnabled(root, Facts))
    assert(isEnabled(root, Ram))
  }

  test("choices combine") {
    val root = runPhase(Set(DatalogDebug.Rules, DatalogDebug.Facts))
    assert(isEnabled(root, Rules))
    assert(isEnabled(root, Facts))
    assert(!isEnabled(root, Ram))
  }

  test("DatalogDebug.All enables every switch") {
    val root = runPhase(DatalogDebug.All)
    assert(isEnabled(root, Rules))
    assert(isEnabled(root, Facts))
    assert(isEnabled(root, Ram))
  }

  test("the phase leaves other definitions alone") {
    val before = runPhase(Set.empty)
    val after = runPhase(DatalogDebug.All)
    val untouched = Symbol.mkDefnSym("Fixpoint3.Options.debugFileName")
    assertResult(before.defs(untouched).exp.toString)(after.defs(untouched).exp.toString)
  }

}
