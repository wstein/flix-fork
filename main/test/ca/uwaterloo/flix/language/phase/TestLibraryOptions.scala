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

class TestLibraryOptions extends AnyFunSuite with TestUtils {

  /**
    * The library options rewritten by the phase, and the compiler option that decides each.
    */
  private val DatalogSym = Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution")
  private val CollectionSym = Symbol.mkDefnSym("Concurrent.Options.enableParallelEvaluation")
  private val LockingSym = Symbol.mkDefnSym("Concurrent.Options.enableLocking")

  test("CLI.Parse.DatalogExecution.Default") {
    val cmdOpts = Main.parseCmdOpts(Array("build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.DatalogExecution.Parallel") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=parallel", "build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.DatalogExecution.Sequential") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=sequential", "build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Sequential)
  }

  test("CLI.Parse.DatalogExecution.Invalid") {
    val result = Main.parseCmdOpts(Array("--Xdatalog-execution=invalid", "build"))
    assert(result.isEmpty)
  }

  test("CLI.Parse.CollectionExecution.Default") {
    val cmdOpts = Main.parseCmdOpts(Array("build")).get
    assert(cmdOpts.xcollectionExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.CollectionExecution.Parallel") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xcollection-execution=parallel", "build")).get
    assert(cmdOpts.xcollectionExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.CollectionExecution.Sequential") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xcollection-execution=sequential", "build")).get
    assert(cmdOpts.xcollectionExecution == ExecutionMode.Sequential)
  }

  test("CLI.Parse.CollectionExecution.Invalid") {
    val result = Main.parseCmdOpts(Array("--Xcollection-execution=invalid", "build"))
    assert(result.isEmpty)
  }

  test("CLI.Parse.BothOptions.AreIndependent") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=sequential", "--Xcollection-execution=parallel", "build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Sequential)
    assert(cmdOpts.xcollectionExecution == ExecutionMode.Parallel)
  }

  test("CLI.Parse.Sequential.SetsAllThree") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xsequential", "build")).get
    assert(cmdOpts.xdatalogExecution == ExecutionMode.Sequential)
    assert(cmdOpts.xcollectionExecution == ExecutionMode.Sequential)
    assert(cmdOpts.xsequential)
  }

  test("CLI.Parse.LockElision.HasNoOptionOfItsOwn") {
    // Lock elision is sound only where nothing can create a thread, so it must not be settable on
    // its own. `--Xsequential` is the only way to ask for it.
    assert(Main.parseCmdOpts(Array("--Xassume-single-threaded", "build")).isEmpty)
    assert(Main.parseCmdOpts(Array("--Xlock-elision=on", "build")).isEmpty)
    assert(Main.parseCmdOpts(Array("--Xcollection-execution=sequential", "build")).get.xsequential == false)
  }

  test("AST.Rewrite.Parallel") {
    val root = check(Options.TestWithLibAll)
    assertBody(root, DatalogSym, expected = true)
    assertBody(root, CollectionSym, expected = true)
    assertBody(root, LockingSym, expected = true)
  }

  test("AST.Rewrite.Datalog.Sequential") {
    val root = check(Options.TestWithLibAll.copy(xdatalogExecution = ExecutionMode.Sequential))
    assertBody(root, DatalogSym, expected = false)
    assertBody(root, CollectionSym, expected = true)
  }

  test("AST.Rewrite.Collection.Sequential") {
    val root = check(Options.TestWithLibAll.copy(xcollectionExecution = ExecutionMode.Sequential))
    assertBody(root, DatalogSym, expected = true)
    assertBody(root, CollectionSym, expected = false)
  }

  test("AST.Rewrite.Both.Sequential") {
    val root = check(Options.TestWithLibAll.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential
    ))
    assertBody(root, DatalogSym, expected = false)
    assertBody(root, CollectionSym, expected = false)
    // The two mode options do not imply lock elision.
    assertBody(root, LockingSym, expected = true)
  }

  test("AST.Rewrite.Sequential.Umbrella") {
    val root = check(Options.TestWithLibAll.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential,
      xassumeSingleThreaded = true
    ))
    assertBody(root, DatalogSym, expected = false)
    assertBody(root, CollectionSym, expected = false)
    assertBody(root, LockingSym, expected = false)
  }

  test("AST.Rewrite.Sequential.WithoutStandardLibrary") {
    // Both options live in the standard library, so there is nothing to rewrite without it.
    val root = check(Options.TestWithLibMin.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential,
      xassumeSingleThreaded = true
    ))
    assert(root.defs.get(DatalogSym).isEmpty)
    assert(root.defs.get(CollectionSym).isEmpty)
    assert(root.defs.get(LockingSym).isEmpty)
  }

  test("Incremental.ModeChange.TakesEffectOnWarmCaches") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(incremental = true))
    val (root1, errors1) = flix.check()
    assert(errors1.isEmpty)
    assertBody(root1.get, DatalogSym, expected = true)
    assertBody(root1.get, CollectionSym, expected = true)

    // The caches are now warm.
    assert(flix.getParsedAst.units.nonEmpty)

    // Switching the modes must take effect on the very next run, even though the incremental
    // caches are kept: the rewrite is recomputed from the current options on every run.
    flix.setOptions(flix.options.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential
    ))
    assert(flix.getParsedAst.units.nonEmpty, "changing the mode must not discard the incremental caches")

    val (root2, errors2) = flix.check()
    assert(errors2.isEmpty)
    assertBody(root2.get, DatalogSym, expected = false)
    assertBody(root2.get, CollectionSym, expected = false)

    // ... and switching back must restore the original bodies.
    flix.setOptions(flix.options.copy(
      xdatalogExecution = ExecutionMode.Parallel,
      xcollectionExecution = ExecutionMode.Parallel
    ))
    val (root3, errors3) = flix.check()
    assert(errors3.isEmpty)
    assertBody(root3.get, DatalogSym, expected = true)
    assertBody(root3.get, CollectionSym, expected = true)
  }

  /**
    * Returns the root obtained by compiling nothing but the library under `options`.
    */
  private def check(options: Options): TypedAst.Root = {
    val (optRoot, errors) = new Flix().setOptions(options).check()
    assert(errors.isEmpty, s"Expected a successful compilation, got: $errors")
    optRoot.get
  }

  /**
    * Asserts that the body of the definition `sym` in `root` is the constant `expected`.
    */
  private def assertBody(root: TypedAst.Root, sym: Symbol.DefnSym, expected: Boolean): Unit = {
    root.defs(sym).exp match {
      case TypedAst.Expr.Cst(Constant.Bool(b), _, _) if b == expected => ()
      case other => fail(s"Expected the body of '$sym' to be $expected, but got: $other")
    }
  }
}
