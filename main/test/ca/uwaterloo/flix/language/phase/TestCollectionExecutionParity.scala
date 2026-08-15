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

class TestCollectionExecutionParity extends AnyFunSuite with TestUtils {

  ///
  /// The collections built below hold 2000 elements, comfortably above the size at which
  /// `useParallelEvaluation` switches to the parallel implementation. Smaller collections would
  /// take the sequential path in both modes and prove nothing.
  ///
  private val Size = 2000

  private def runWithMode(src: String, mode: ExecutionMode): Unit =
    run(src, Options.TestWithLibAll.copy(xcollectionExecution = mode), mode.toString)

  private def run(src: String, options: Options, label: String): Unit = {
    val flix = new Flix().setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, src)
    flix.compile() match {
      case Result.Ok(res) =>
        val tests = JvmLoader.load(res).tests
        val (_, testFn) = tests.headOption.getOrElse(fail("No @Test found in compilation result"))
        testFn.run()
      case Result.Err(errors) =>
        fail(s"Compilation failed under mode $label with errors: $errors")
    }
  }

  private def assertParity(src: String): Unit = {
    runWithMode(src, ExecutionMode.Parallel)
    runWithMode(src, ExecutionMode.Sequential)
    runFullySequential(src)
  }

  ///
  /// Runs `src` under `--Xsequential`, which additionally erases the locks in `BPlusTree`.
  ///
  private def runFullySequential(src: String): Unit = {
    val options = Options.TestWithLibAll.copy(
      xdatalogExecution = ExecutionMode.Sequential,
      xcollectionExecution = ExecutionMode.Sequential,
      xassumeSingleThreaded = true
    )
    run(src, options, "fully sequential")
  }

  test("Parity.Map.Count") {
    assertParity(
      s"""
         |use Assert.assertEq;
         |
         |@Test
         |def testMapCount(): Unit \\ Assert = {
         |    let m = List.range(0, $Size) |> List.map(i -> (i, i * 2)) |> List.toMap;
         |    assertEq(expected = ${Size - 11}, Map.count((k, _) -> k > 10, m))
         |}
      """.stripMargin)
  }

  test("Parity.Map.SumWith") {
    assertParity(
      s"""
         |use Assert.assertEq;
         |
         |@Test
         |def testMapSumWith(): Unit \\ Assert = {
         |    let m = List.range(0, $Size) |> List.map(i -> (i, i * 2)) |> List.toMap;
         |    assertEq(expected = ${(Size - 1) * Size / 2}, Map.sumWith((k, _) -> k, m))
         |}
      """.stripMargin)
  }

  test("Parity.Map.Exists") {
    assertParity(
      s"""
         |use Assert.{assertEq, assertTrue};
         |
         |@Test
         |def testMapExists(): Unit \\ Assert = {
         |    let m = List.range(0, $Size) |> List.map(i -> (i, i * 2)) |> List.toMap;
         |    assertTrue(Map.exists((k, _) -> k == ${Size - 1}, m));
         |    assertEq(expected = false, Map.exists((k, _) -> k == $Size, m))
         |}
      """.stripMargin)
  }

  test("Parity.Map.Map") {
    assertParity(
      s"""
         |use Assert.assertEq;
         |
         |@Test
         |def testMapMap(): Unit \\ Assert = {
         |    let m = List.range(0, $Size) |> List.map(i -> (i, i)) |> List.toMap;
         |    let doubled = Map.map(v -> v * 2, m);
         |    assertEq(expected = $Size, Map.size(doubled));
         |    assertEq(expected = Some(${(Size - 1) * 2}), Map.get(${Size - 1}, doubled));
         |    assertEq(expected = ${(Size - 1) * Size}, Map.sumWith((_, v) -> v, doubled))
         |}
      """.stripMargin)
  }

  test("Parity.Set.CountAndExists") {
    assertParity(
      s"""
         |use Assert.{assertEq, assertTrue};
         |
         |@Test
         |def testSet(): Unit \\ Assert = {
         |    let s = List.range(0, $Size) |> List.toSet;
         |    assertEq(expected = ${Size - 11}, Set.count(x -> x > 10, s));
         |    assertTrue(Set.exists(x -> x == ${Size - 1}, s))
         |}
      """.stripMargin)
  }

  test("Parity.DelayMap.ForcesTheSameValues") {
    // `DelayMap` forces its lazy values in parallel, through a different code path than `Map` and
    // `Set`, so the switch has to be exercised here separately.
    assertParity(
      s"""
         |use Assert.assertEq;
         |
         |@Test
         |def testDelayMap(): Unit \\ Assert = {
         |    let m = List.foldLeft((acc, i) -> DelayMap.insert(i, i * 2, acc), DelayMap.empty(), List.range(0, $Size));
         |    assertEq(expected = $Size, DelayMap.size(m));
         |    let forced = DelayMap.toMap(m);
         |    assertEq(expected = $Size, Map.size(forced));
         |    assertEq(expected = Some(${(Size - 1) * 2}), Map.get(${Size - 1}, forced));
         |    assertEq(expected = ${(Size - 1) * Size}, Map.sumWith((_, v) -> v, forced))
         |}
      """.stripMargin)
  }

  test("Parity.Map.ImpureFunctionIsUnaffected") {
    // An impure function always takes the sequential path, in both modes.
    assertParity(
      s"""
         |use Assert.assertEq;
         |
         |@Test
         |def testImpure(): Unit \\ Assert = region rc {
         |    let counter = Ref.fresh(rc, 0);
         |    let m = List.range(0, $Size) |> List.map(i -> (i, i)) |> List.toMap;
         |    let n = Map.count((k, _) -> { Ref.put(Ref.get(counter) + 1, counter); k > 10 }, m);
         |    assertEq(expected = ${Size - 11}, n);
         |    assertEq(expected = $Size, Ref.get(counter))
         |}
      """.stripMargin)
  }
}
