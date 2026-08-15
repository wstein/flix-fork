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

  test("CLI.Parse.Sequential.Default") {
    val cmdOpts = Main.parseCmdOpts(Array("build")).get
    assert(!cmdOpts.xsequential)
  }

  test("CLI.Parse.Sequential.SetsEveryAxis") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xsequential", "build")).get
    assert(cmdOpts.xsequential)
  }

  test("CLI.Parse.Sequential.IsTheOnlyKnob") {
    // The library switches are set together or not at all. Exposing an option per switch would make
    // the unsound combination -- locks elided while something still runs in parallel -- reachable,
    // and reachable by flag order alone, since a later flag would undo what `--Xsequential` set.
    for (rejected <- List("--Xdatalog-execution=sequential", "--Xcollection-execution=sequential",
      "--Xassume-single-threaded", "--Xlock-elision=on")) {
      assert(Main.parseCmdOpts(Array(rejected, "build")).isEmpty, s"Expected '$rejected' to be rejected.")
    }
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

  test("AST.Rewrite.LockElision.RequiresBothModesSequential") {
    // Asserting the precondition is not enough on its own: eliding the locks while anything can
    // still run in parallel is exactly the race the option exists to avoid. The compiler API can
    // express that combination, so it must be inert rather than obeyed.
    for (partial <- List(
      Options.TestWithLibAll.copy(xassumeSingleThreaded = true),
      Options.TestWithLibAll.copy(xassumeSingleThreaded = true, xdatalogExecution = ExecutionMode.Sequential),
      Options.TestWithLibAll.copy(xassumeSingleThreaded = true, xcollectionExecution = ExecutionMode.Sequential)
    )) {
      assert(!partial.isSingleThreaded)
      assertBody(check(partial), LockingSym, expected = true)
    }
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
