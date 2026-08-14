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

import ca.uwaterloo.flix.BytecodeInspection.Bytecode
import ca.uwaterloo.flix.{BytecodeInspection, TestUtils}
import ca.uwaterloo.flix.util.{ExecutionMode, Options}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Asserts what `--Xsequential` promises as a whole: a program that cannot create a thread and
  * needs no lock.
  *
  * The individual options are covered by [[TestDatalogReachability]] and
  * [[TestCollectionReachability]]; what is only observable here is the lock elision, which the
  * other two deliberately leave in place.
  */
class TestSequentialReachability extends AnyFunSuite with TestUtils with BytecodeInspection {

  ///
  /// A program that exercises both the solver and the collections, so that every construct the
  /// option removes is reachable to begin with.
  ///
  private val Program =
    """
      |def main(): Unit \ IO = {
      |    let p = #{
      |        Edge(1, 2). Edge(1, 3). Edge(2, 3). Edge(3, 4).
      |        Path(x, y) :- Edge(x, y).
      |        Path(x, z) :- Path(x, y), Edge(y, z).
      |    };
      |    let res = solve p;
      |    let q = query res select (x, y) from Path(x, y);
      |    let m = List.range(0, 2000) |> List.map(i -> (i, i)) |> List.toMap;
      |    println(Vector.length(q) + Map.count((k, _) -> k > 10, m))
      |}
    """.stripMargin

  private lazy val DefaultBuild: Bytecode =
    compileAndScan(Program, Options.Default)

  private lazy val SequentialBuild: Bytecode =
    compileAndScan(Program, Options.Default.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential,
      xassumeSingleThreaded = true
    ))

  ///
  /// The positive control. See [[TestDatalogReachability]] for why this test must exist.
  ///
  test("Reachability.Default.RetainsThreadsAndLocks") {
    assert(DefaultBuild.spawnSites().nonEmpty, "Expected the default build to spawn threads.")
    assert(stampedLockCalls(DefaultBuild).nonEmpty, "Expected the default build to use a StampedLock.")
  }

  test("Reachability.Sequential.RemovesEveryThreadAndLock") {
    val spawns = SequentialBuild.spawnSites()
    assert(spawns.isEmpty, s"Found reachable thread spawning under --Xsequential: $spawns")

    val locks = stampedLockCalls(SequentialBuild)
    assert(locks.isEmpty, s"Found reachable locking under --Xsequential: $locks")
  }

  test("Reachability.Sequential.LocksSurviveWithoutTheUmbrella") {
    // The lock elision is what `--Xsequential` adds over setting the two modes by hand. Asserting
    // that the locks survive without it keeps the test above honest about which option removes
    // them.
    val withoutUmbrella = compileAndScan(Program, Options.Default.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential
    ))
    assert(withoutUmbrella.spawnSites().isEmpty, "Expected the two mode options alone to remove every thread spawn.")
    assert(stampedLockCalls(withoutUmbrella).nonEmpty, "Expected the two mode options alone to leave the locks in place.")
  }

  /**
    * Returns the calls into `StampedLock` in `bytecode`.
    */
  private def stampedLockCalls(bytecode: Bytecode): Set[(String, String, String)] =
    bytecode.calls.filter { case (_, owner, _) => owner.contains("StampedLock") }
}
