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

  ///
  /// A program with no concurrency of any kind, not even through the library.
  ///
  /// A region is emitted for every program, so its thread bookkeeping is reachable from every
  /// program. This is where removing it is visible on its own.
  ///
  private val TrivialProgram =
    """
      |def main(): Unit \ IO = println(List.range(0, 10) |> List.sum)
    """.stripMargin

  ///
  /// A program whose only concurrency is its own `par (...) yield`, which the option lowers to
  /// bindings on the current thread rather than to a channel and a thread per fragment.
  ///
  private val ParYieldProgram =
    """
      |def main(): Unit \ IO = {
      |    let r = par (a <- List.range(0, 100) |> List.sum; b <- 2) yield a + b;
      |    println(r)
      |}
    """.stripMargin

  private val Sequential: Options = Options.Default.copy(
    xdatalogExecution = ExecutionMode.Sequential,
    xcollectionExecution = ExecutionMode.Sequential,
    xassumeSingleThreaded = true
  )

  private lazy val DefaultBuild: Bytecode =
    compileAndScan(Program, Options.Default)

  private lazy val SequentialBuild: Bytecode =
    compileAndScan(Program, Sequential)

  ///
  /// The positive control. See [[TestDatalogReachability]] for why this test must exist.
  ///
  test("Reachability.Default.RetainsThreadsAndLocks") {
    assert(DefaultBuild.spawnSites().nonEmpty, "Expected the default build to spawn threads.")
    assert(stampedLockCalls(DefaultBuild).nonEmpty, "Expected the default build to use a StampedLock.")
  }

  test("Reachability.Sequential.RemovesEveryThreadAndLock") {
    val concurrency = concurrencyCalls(SequentialBuild)
    assert(concurrency.isEmpty, s"Found reachable concurrency support under --Xsequential: $concurrency")

    // Note that asserting the absence of spawn *sites* would prove nothing here: under this option
    // the backend compiles every `spawn` into a rejection, so no build ever has one. What has to be
    // absent instead is the rejection, which is where a `spawn` the library still reached would
    // show up.
    val rejected = rejectedSpawns(SequentialBuild)
    assert(rejected.isEmpty, s"Found a reachable spawn that was compiled into a rejection: $rejected")
  }

  ///
  /// The positive control for the test below.
  ///
  test("Reachability.Default.CarriesConcurrencySupportIntoEveryProgram") {
    val res = compileAndScan(TrivialProgram, Options.Default)
    assert(concurrencyCalls(res).nonEmpty, "Expected a program without concurrency to still carry the runtime's concurrency support.")
  }

  test("Reachability.Sequential.RemovesConcurrencySupportFromEveryProgram") {
    // The runtime's concurrency support is not reached through the library: a region, a lazy
    // value, and the counter behind a fresh identity are emitted for every program, and each held
    // a thread, a lock, or an atomic. Under the option a program touches `java.util.concurrent`
    // nowhere at all, which is the property a target without threads needs.
    val res = compileAndScan(TrivialProgram, Sequential)
    val concurrency = concurrencyCalls(res)
    assert(concurrency.isEmpty, s"Found reachable concurrency support under --Xsequential: $concurrency")
  }

  ///
  /// The positive control for the test below.
  ///
  test("Reachability.Default.LowersParYieldToChannelsAndThreads") {
    val res = compileAndScan(ParYieldProgram, Options.Default)
    assert(res.spawnSites().nonEmpty, "Expected a par yield to spawn threads by default.")
    assert(res.classesOfNamespace("Concurrent/Channel").nonEmpty, "Expected a par yield to use channels by default.")
  }

  test("Reachability.Sequential.LowersParYieldWithoutChannelsOrThreads") {
    // A program's own `par (...) yield` is not the library's, so no library switch reaches it. It
    // is lowered as a sequence of bindings instead, which is one of the schedules it allows.
    val res = compileAndScan(ParYieldProgram, Sequential)

    val channels = res.classesOfNamespace("Concurrent/Channel")
    assert(channels.isEmpty, s"Found reachable channels under --Xsequential: ${channels.take(5)}")

    val rejected = rejectedSpawns(res)
    assert(rejected.isEmpty, s"Found a reachable spawn that was compiled into a rejection: $rejected")

    val concurrency = concurrencyCalls(res)
    assert(concurrency.isEmpty, s"Found reachable concurrency support under --Xsequential: $concurrency")
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

  /**
    * Returns the sites in `bytecode` where a `spawn` was compiled into a rejection.
    *
    * `RecordEmpty$` throws the same exception for an unrelated reason -- an empty record has no
    * field to look up -- so it is not counted.
    */
  private def rejectedSpawns(bytecode: Bytecode): Set[(String, String, String)] =
    bytecode.calls.filter {
      case (caller, owner, _) =>
        owner == "java/lang/UnsupportedOperationException" && caller != "RecordEmpty$"
    }

  /**
    * Returns the calls in `bytecode` that only exist to make the program safe to run on more than
    * one thread, or to put it on one.
    */
  private def concurrencyCalls(bytecode: Bytecode): Set[(String, String, String)] =
    bytecode.calls.filter {
      case (_, owner, _) => owner.startsWith("java/util/concurrent") || owner == "java/lang/Thread"
    }
}
