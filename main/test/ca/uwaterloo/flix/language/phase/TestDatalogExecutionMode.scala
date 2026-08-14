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
import ca.uwaterloo.flix.util.{DatalogExecution, Options}
import org.scalatest.funsuite.AnyFunSuite

class TestDatalogExecutionMode extends AnyFunSuite with TestUtils {

  test("CLI.Parse.DatalogExecution.Default") {
    val cmdOpts = Main.parseCmdOpts(Array("build")).get
    assert(cmdOpts.xdatalogExecution == DatalogExecution.Parallel)
  }

  test("CLI.Parse.DatalogExecution.Parallel") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=parallel", "build")).get
    assert(cmdOpts.xdatalogExecution == DatalogExecution.Parallel)
  }

  test("CLI.Parse.DatalogExecution.Sequential") {
    val cmdOpts = Main.parseCmdOpts(Array("--Xdatalog-execution=sequential", "build")).get
    assert(cmdOpts.xdatalogExecution == DatalogExecution.Sequential)
  }

  test("CLI.Parse.DatalogExecution.Invalid") {
    val result = Main.parseCmdOpts(Array("--Xdatalog-execution=invalid", "build"))
    assert(result.isEmpty)
  }

  test("AST.Rewrite.Parallel") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xdatalogExecution = DatalogExecution.Parallel))
    val (optRoot, errors) = flix.check()
    assert(errors.isEmpty)
    val root = optRoot.get
    val defn = root.defs(Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution"))
    defn.exp match {
      case TypedAst.Expr.Cst(Constant.Bool(true), _, _) => ()
      case other => fail(s"Expected Constant.Bool(true), but got: $other")
    }
  }

  test("AST.Rewrite.Sequential") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xdatalogExecution = DatalogExecution.Sequential))
    val (optRoot, errors) = flix.check()
    assert(errors.isEmpty)
    val root = optRoot.get
    val defn = root.defs(Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution"))
    defn.exp match {
      case TypedAst.Expr.Cst(Constant.Bool(false), _, _) => ()
      case other => fail(s"Expected Constant.Bool(false), but got: $other")
    }
  }

  test("Incremental.CacheInvalidation.OnModeChange") {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(incremental = true, xdatalogExecution = DatalogExecution.Parallel))
    val (root1, errors1) = flix.check()
    assert(errors1.isEmpty)
    assert(root1.isDefined)

    // Verify cache has been populated
    assert(flix.getParsedAst.units.nonEmpty)

    // Changing xdatalogExecution mode must invalidate caches
    flix.setOptions(flix.options.copy(xdatalogExecution = DatalogExecution.Sequential))
    assert(flix.getParsedAst.units.isEmpty)

    val (root2, errors2) = flix.check()
    assert(errors2.isEmpty)
    val defn = root2.get.defs(Symbol.mkDefnSym("Fixpoint3.Options.enableParallelExecution"))
    defn.exp match {
      case TypedAst.Expr.Cst(Constant.Bool(false), _, _) => ()
      case other => fail(s"Expected Constant.Bool(false) after mode change, but got: $other")
    }
  }
}
