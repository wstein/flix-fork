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
  * Tests the observable behavior of a build whose locks have been compiled out.
  *
  * `--Xsequential` removes the locks from `BPlusTree`. What is left has to stay honest about it:
  * code that cannot answer a question about a lock must say so rather than answer wrongly.
  */
class TestLockElision extends AnyFunSuite with TestUtils {

  private val Sequential = Options.TestWithLibAll.copy(
    xdatalogExecution = ExecutionMode.Sequential,
    xcollectionExecution = ExecutionMode.Sequential,
    xassumeSingleThreaded = true
  )

  ///
  /// Asks whether a lock is held, which is exactly the question a lockless build cannot answer.
  ///
  private val IsLockedProgram =
    """
      |use Assert.assertEq;
      |
      |@Test
      |def testIsLocked(): Unit \ Assert = region rc {
      |    let l = BPlusTree.Lock.mkLock(rc);
      |    assertEq(expected = false, BPlusTree.Lock.isLocked(l))
      |}
    """.stripMargin

  ///
  /// Checks a tree invariant, which is useful in both builds: the structural part of the invariant
  /// holds regardless of whether there are locks.
  ///
  private val InvariantProgram =
    """
      |use Assert.assertTrue;
      |
      |@Test
      |def testInvariant(): Unit \ Assert = region rc {
      |    let t = BPlusTree.empty(rc);
      |    BPlusTree.put(1, 2, t);
      |    BPlusTree.put(3, 4, t);
      |    assertTrue(BPlusTree.assertTreeInvariant(t))
      |}
    """.stripMargin

  test("LockElision.IsLocked.AnswersWhenLocked") {
    // The positive control: with locks, the question has an answer.
    run(IsLockedProgram, Options.TestWithLibAll)
  }

  test("LockElision.IsLocked.AbortsWhenElided") {
    // Answering `false` would let an invariant of the form `not isLocked(..)` pass vacuously.
    //
    // Note: `bug!` writes its message to stderr and then panics through `?panic`, so the reason is
    // on the console rather than on the exception. What is asserted here is that the program
    // aborted, and not that the assertion inside it merely failed.
    val e = intercept[Throwable](run(IsLockedProgram, Sequential))
    assert(
      e.getClass.getName.contains("HoleError"),
      s"Expected the program to abort, but got: $e"
    )
  }

  test("LockElision.TreeInvariant.StillHoldsWhenElided") {
    // The checker must remain usable: its lock conjunct is skipped, not asked.
    run(InvariantProgram, Options.TestWithLibAll)
    run(InvariantProgram, Sequential)
  }

  /**
    * Compiles `src` under `options` and runs its test.
    */
  private def run(src: String, options: Options): Unit = {
    val flix = new Flix().setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = src)
    flix.compile() match {
      case Result.Ok(res) =>
        val (_, testFn) = JvmLoader.load(res).tests.headOption.getOrElse(fail("No @Test found in compilation result"))
        testFn.run()
      case Result.Err(errors) => fail(s"Compilation failed with errors: $errors")
    }
  }
}
