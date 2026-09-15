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

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{ExecutionMode, Options, Result}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Covers the parts of the generated runtime that `--Xsequential` replaces, rather than the parts
  * of the standard library that it rewrites.
  *
  * The replacements are in the JVM runtime generators and are emitted
  * for every program, so their behavior cannot be reached through the library: it has to be run.
  */
class TestSequentialRuntime extends AnyFunSuite with TestUtils {

  private val Sequential: Options = Options.TestWithLibAll.copy(
    xdatalogExecution = ExecutionMode.Sequential,
    xcollectionExecution = ExecutionMode.Sequential,
    xassumeSingleThreaded = true
  )

  /**
    * Compiles `src` under `options` and runs its first `@Test`.
    */
  private def run(src: String, options: Options): Unit = {
    val flix = new Flix().setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, src)
    flix.compile() match {
      case Result.Ok(res) =>
        val (_, testFn) = JvmLoader.load(res).tests.headOption.getOrElse(fail("No @Test found in compilation result"))
        testFn.run()
      case Result.Err(errors) =>
        fail(s"Compilation failed with errors: $errors")
    }
  }

  /**
    * Asserts that `src` behaves the same under the default options and under `--Xsequential`.
    */
  private def assertParity(src: String): Unit = {
    run(src, Options.TestWithLibAll)
    run(src, Sequential)
  }

  test("Runtime.Region.HoldsItsAllocations") {
    // A region still allocates, and its values are still readable inside it, without the
    // bookkeeping that only tracked child threads.
    assertParity(
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testRegion(): Unit \ Assert = region rc {
        |    let a = Array#{1, 2, 3} @ rc;
        |    Array.put(42, 0, a);
        |    assertEq(expected = 47, Array.sum(a))
        |}
      """.stripMargin)
  }

  test("Runtime.Region.NestedRegionsExitInOrder") {
    assertParity(
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testNested(): Unit \ Assert = region outer {
        |    let a = Array#{0} @ outer;
        |    region inner {
        |        let b = Array#{7} @ inner;
        |        Array.put(Array.get(0, b), 0, a)
        |    };
        |    assertEq(expected = 7, Array.get(0, a))
        |}
      """.stripMargin)
  }

  test("Runtime.Lazy.ForcesToTheSameValue") {
    // A thunk must be pure, so whether it ran once or twice is not observable from the program.
    // What is observable is that repeated and nested forcing still yield the value, which is what
    // the field the lock guarded is for.
    assertParity(
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testLazy(): Unit \ Assert = {
        |    let l = lazy (List.range(0, 100) |> List.sum);
        |    assertEq(expected = 4950, force l);
        |    assertEq(expected = 4950, force l);
        |    let nested = lazy (lazy (force l + 1));
        |    assertEq(expected = 4951, force (force nested))
        |}
      """.stripMargin)
  }

  test("Runtime.BPlusTree.CountsItsEntries") {
    // The tree's size is kept in a counter that is an atomic in one build and a cell in the other.
    assertParity(
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testBPlusTree(): Unit \ Assert = region rc {
        |    let t = BPlusTree.empty(rc);
        |    List.forEach(i -> BPlusTree.put(i, i * 2, t), List.range(0, 500));
        |    assertEq(expected = 500, BPlusTree.size(t));
        |    BPlusTree.put(0, 1, t);
        |    assertEq(expected = 500, BPlusTree.size(t));
        |    assertEq(expected = Some(998), BPlusTree.get(499, t))
        |}
      """.stripMargin)
  }

  test("Runtime.ParYield.YieldsTheSameValue") {
    // `par (...) yield` is lowered to one channel and one thread per fragment, or, under the
    // option, to a binding per fragment on the current thread. Both must yield the same value.
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testParYield(): Unit \ Assert = {
        |    let r = par (a <- List.range(0, 100) |> List.sum; (b, c) <- (2, 3); d <- 4) yield a + b + c + d;
        |    assertEq(expected = 4959, r);
        |    let nested = par (x <- par (y <- 1; z <- 2) yield y + z; w <- 4) yield x * w;
        |    assertEq(expected = 12, nested)
        |}
      """.stripMargin

    assertParity(src)
    run(src, Sequential.copy(xnewmono = true))
  }

  test("Runtime.Region.SpawnIsRejected") {
    // The option asserts that the program creates no thread. When it does anyway, the region says
    // so at the spawn rather than running the child on the current thread, which would deadlock as
    // soon as the child waited for its parent.
    val src =
      """
        |@Test
        |def testSpawn(): Unit = region rc {
        |    spawn () @ rc
        |}
      """.stripMargin

    run(src, Options.TestWithLibAll)

    val e = intercept[UnsupportedOperationException](run(src, Sequential))
    assert(e.getMessage.contains("--Xsequential"), s"Expected the failure to name the option, got: ${e.getMessage}")
  }
}
