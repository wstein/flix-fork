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

class TestCollectionReachability extends AnyFunSuite with TestUtils with BytecodeInspection {

  ///
  /// A program that reaches the `@ParallelWhenPure` operations on `Map` and `Set` with a pure
  /// function, which is what makes their parallel branch reachable.
  ///
  private val CollectionProgram =
    """
      |def main(): Unit \ IO = {
      |    let m = List.range(0, 2000) |> List.map(i -> (i, i * 2)) |> List.toMap;
      |    let s = List.range(0, 2000) |> List.toSet;
      |    let n = Map.count((k, _) -> k > 10, m)
      |          + Map.sumWith((k, _) -> k, m)
      |          + Map.size(Map.map(v -> v + 1, m))
      |          + Set.count(x -> x > 10, s);
      |    println(n + (if (Map.exists((k, _) -> k == 5, m)) 1 else 0))
      |}
    """.stripMargin

  ///
  /// The `RedBlackTree` operations that evaluate in parallel, and that back the `Map` and `Set`
  /// operations above.
  ///
  private val ParallelTreeDefs = List("parCount", "parExists", "parMapWithKey", "parSumWith")

  ///
  /// The concurrency machinery that `par (...) yield` lowers to. `Lowering.mkParChannels` turns
  /// each fragment into a channel plus a `Static` spawn, which is the only thing that makes the
  /// `Concurrent` locking layer reachable from a program that never mentions a channel.
  ///
  private val ChannelNamespaces = List(
    "Concurrent/Channel",
    "Concurrent/ReentrantLock",
    "Concurrent/CyclicBarrier",
    "Concurrent/Condition"
  )

  ///
  /// The two builds under comparison. Only `xcollectionExecution` differs.
  ///
  private lazy val ParallelBuild: Bytecode =
    compileAndScan(CollectionProgram, Options.Default.copy(xcollectionExecution = ExecutionMode.Parallel))

  private lazy val SequentialBuild: Bytecode =
    compileAndScan(CollectionProgram, Options.Default.copy(xcollectionExecution = ExecutionMode.Sequential))

  ///
  /// The positive control. See [[TestDatalogReachability]] for why this test must exist.
  ///
  test("Reachability.ParallelMode.RetainsCollectionParallelism") {
    val res = ParallelBuild

    for (name <- ParallelTreeDefs) {
      assert(res.classesOfDef("RedBlackTree", name).nonEmpty, s"Expected 'RedBlackTree.$name' to be present in a parallel build.")
    }
    for (ns <- ChannelNamespaces) {
      assert(res.classesOfNamespace(ns).nonEmpty, s"Expected '$ns' to be present in a parallel build.")
    }
    assert(res.spawnSites().nonEmpty, "Expected the program to spawn threads in a parallel build.")
  }

  test("Reachability.SequentialMode.ErasesCollectionParallelism") {
    val res = SequentialBuild

    // 1. The parallel tree traversals are gone.
    for (name <- ParallelTreeDefs) {
      val classes = res.classesOfDef("RedBlackTree", name)
      assert(classes.isEmpty, s"Found reachable 'RedBlackTree.$name': $classes")
    }

    // 2. So is the channel machinery they were the only user of. This is the point of the option:
    //    the `Concurrent` locking layer is not an independent dependency but a consequence of
    //    `par (...) yield`, and it goes away with it.
    for (ns <- ChannelNamespaces) {
      val classes = res.classesOfNamespace(ns)
      assert(classes.isEmpty, s"Found reachable '$ns': ${classes.take(5)}")
    }

    // 3. Nothing in the program spawns a thread any more, anywhere.
    val spawns = res.spawnSites()
    assert(spawns.isEmpty, s"Found reachable thread spawning: $spawns")
  }
}
