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

import ca.uwaterloo.flix.Main
import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.Constant
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.util.{ExecutionMode, Options}
import org.scalatest.funsuite.AnyFunSuite

class TestDatalogExecutionMode extends AnyFunSuite with TestUtils {

  test("CLI.Parse.ExecutionMode.Default") {
    val cmdOpts = Main.parseCmdOpts(Array("build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.ExecutionMode.Parallel") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=parallel", "build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.ExecutionMode.Sequential") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=sequential", "build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Sequential)
  }

  test("CLI.Parse.ExecutionMode.Invalid") {
    val result = Main.parseCmdOpts(Array("--Xdatalog-execution=invalid", "build"))
    assert(result.isEmpty)
  }

  test("AST.Rewrite.Parallel") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xdatalogExecution = ExecutionMode.Parallel))
    val (optRoot, errors) = flix.check()
    assert(errors.isEmpty)
    assertBody(optRoot.get, expected = true)
  }

  test("AST.Rewrite.Sequential") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xdatalogExecution = ExecutionMode.Sequential))
    val (optRoot, errors) = flix.check()
    assert(errors.isEmpty)
    assertBody(optRoot.get, expected = false)
  }

  test("AST.Rewrite.Sequential.WithoutStandardLibrary") {
    // Fixpoint3 is unavailable without the full standard library, so there is nothing to rewrite.
    val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xdatalogExecution = ExecutionMode.Sequential))
    val (optRoot, errors) = flix.check()
    assert(errors.isEmpty)
    assert(optRoot.get.defs.get(Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution")).isEmpty)
  }

  test("Incremental.ModeChange.TakesEffectOnWarmCaches") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(incremental = true, xdatalogExecution = ExecutionMode.Parallel))
    val (root1, errors1) = flix.check()
    assert(errors1.isEmpty)
    assert(root1.isDefined)
    assertBody(root1.get, expected = true)

    // The caches are now warm.
    assert(flix.getParsedAst.units.nonEmpty)

    // Switching the mode must take effect on the very next run, even though the incremental
    // caches are kept: the rewrite is recomputed from the current options on every run.
    flix.setOptions(flix.options.copy(xdatalogExecution = ExecutionMode.Sequential))
    assert(flix.getParsedAst.units.nonEmpty, "changing the mode must not discard the incremental caches")

    val (root2, errors2) = flix.check()
    assert(errors2.isEmpty)
    assertBody(root2.get, expected = false)

    // ... and switching back must restore the original body.
    flix.setOptions(flix.options.copy(xdatalogExecution = ExecutionMode.Parallel))
    val (root3, errors3) = flix.check()
    assert(errors3.isEmpty)
    assertBody(root3.get, expected = true)
  }

  /**
    * Asserts that the body of `Fixpoint3.Options.enableParallelExecution` in `root` is `expected`.
    */
  private def assertBody(root: TypedAst.Root, expected: Boolean): Unit = {
    val defn = root.defs(Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution"))
    defn.exp match {
      case TypedAst.Expr.Cst(Constant.Bool(b), _, _) if b == expected => ()
      case other => fail(s"Expected Constant.Bool($expected), but got: $other")
    }
  }
}
