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

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{ExecutionMode, Options, Result}
import ca.uwaterloo.flix.{BytecodeInspection, TestUtils}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}

/**
  * Checks the standard library's *publicly callable* parallel operations, one call at a time.
  *
  * The other reachability suites compile programs that reach parallelism the way a normal program
  * does, through `Map`, `Set`, and the solver. That leaves a gap: `BPlusTree.count`,
  * `BPlusTree.parForEach`, and the `RedBlackTree.par*` family are public, so a program can call
  * them directly and spawn threads without going through any of those. For `BPlusTree` that is not
  * merely surprising -- `--Xsequential` also removes that tree's locks, so a parallel traversal of
  * it would be a race introduced by the option itself.
  */
class TestPublicParallelApis extends AnyFunSuite with TestUtils with BytecodeInspection {

  private val Sequential = Options.Default.copy(
    xdatalogExecution = ExecutionMode.Sequential,
    xcollectionExecution = ExecutionMode.Sequential,
    xassumeSingleThreaded = true
  )

  /**
    * A program per publicly reachable parallel operation, each printing a value so that the call
    * survives dead-code elimination and can be compared across builds.
    */
  private val Programs: List[(String, String)] = List(
    "BPlusTree.count" ->
      """
        |def main(): Unit \ IO = region rc {
        |    let t = BPlusTree.empty(rc);
        |    List.forEach(i -> BPlusTree.put(i, i, t), List.range(0, 3000));
        |    println(BPlusTree.count(k -> _ -> k > 10, t))
        |}
      """.stripMargin,
    "RedBlackTree.parCount" -> overTree("RedBlackTree.parCount((k, _) -> k > 10, t)"),
    "RedBlackTree.parExists" -> overTree("RedBlackTree.parExists((k, _) -> k == 2999, t)"),
    "RedBlackTree.parForAll" -> overTree("RedBlackTree.parForAll((k, _) -> k >= 0, t)"),
    "RedBlackTree.parSumWith" -> overTree("RedBlackTree.parSumWith((k, _) -> k, t)"),
    "RedBlackTree.parMinimumBy" -> overTree("RedBlackTree.parMinimumBy((k1, _, k2, _) -> k1 <=> k2, t)"),
    "RedBlackTree.parMaximumBy" -> overTree("RedBlackTree.parMaximumBy((k1, _, k2, _) -> k1 <=> k2, t)"),
    "DelayMap.toMap" ->
      """
        |def main(): Unit \ IO = {
        |    let m = List.foldLeft((acc, i) -> DelayMap.insert(i, i, acc), DelayMap.empty(), List.range(0, 3000));
        |    println(Map.size(DelayMap.toMap(m)))
        |}
      """.stripMargin
  )

  /**
    * Returns a program applying `call` to the tree underlying a large `Map`.
    */
  private def overTree(call: String): String =
    s"""
       |def main(): Unit \\ IO = {
       |    let m = List.range(0, 3000) |> List.map(i -> (i, i)) |> List.toMap;
       |    let Map.Map(t) = m;
       |    println($call)
       |}
     """.stripMargin

  ///
  /// The positive control. Without it, an operation that never spawned in the first place would
  /// look like a successful erasure.
  ///
  test("PublicParallelApis.Default.Spawn") {
    for ((name, program) <- Programs) {
      val spawns = compileAndScan(program, Options.Default).spawnSites()
      assert(spawns.nonEmpty, s"Expected '$name' to spawn in a default build.")
    }
  }

  test("PublicParallelApis.Sequential.DoNotSpawn") {
    for ((name, program) <- Programs) {
      val spawns = compileAndScan(program, Sequential).spawnSites()
      assert(spawns.isEmpty, s"Found reachable thread spawning from '$name' under --Xsequential: $spawns")
    }
  }

  test("PublicParallelApis.Sequential.ProduceTheSameResult") {
    for ((name, program) <- Programs) {
      val expected = run(program, Options.Default)
      val actual = run(program, Sequential)
      assert(expected == actual, s"'$name' returned '$actual' under --Xsequential, but '$expected' by default.")
      assert(expected.nonEmpty, s"Expected '$name' to print something.")
    }
  }

  /**
    * Compiles and runs `program` under `options`, returning what it printed.
    */
  private def run(program: String, options: Options): String = {
    val flix = new Flix().setOptions(options.copy(entryPoint = Some(Symbol.mkDefnSym("main"))))
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    flix.compile() match {
      case Result.Ok(result) =>
        val buffer = new ByteArrayOutputStream()
        val original = System.out
        try {
          System.setOut(new PrintStream(buffer, true))
          JvmLoader.load(result).main.get.apply(Array.empty)
        } finally {
          System.setOut(original)
        }
        buffer.toString.trim
      case Result.Err(errors) => fail(s"Compilation failed with errors: $errors")
    }
  }
}
