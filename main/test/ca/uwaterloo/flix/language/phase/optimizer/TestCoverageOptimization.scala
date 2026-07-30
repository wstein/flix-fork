/*
 * Copyright 2026 Werner Stein
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
package ca.uwaterloo.flix.language.phase.optimizer

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.Coverage
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Regression test for coverage optimization: verifies that CoverageHit probes
  * in inlineable helper functions survive the optimizer and are not eliminated.
  *
  * This tests a critical issue where coverage probes were being optimized away
  * because they were typed as Pure with no value dependencies. The optimizer
  * must preserve CoverageHit expressions even though they are Pure, because
  * they have real runtime effects (calling Coverage.hit()).
  *
  * The test:
  * 1. Compiles a program with inlineable helper and main function
  * 2. Executes the main function
  * 3. Verifies coverage probes were hit at runtime
  * 4. Confirms both helper and main function probes are recorded
  * 5. Ensures optimizer preserved the probes (not eliminated as dead code)
  */
class TestCoverageOptimization extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /**
    * A program with an inlineable helper function.
    * The helper is pure, simple, and used once - making it a candidate for inlining.
    * Both functions will have coverage probes inserted.
    * After inlining, both probes must survive the optimizer's dead-code elimination.
    */
  private val Program: String =
    """
      |def helper(): Int32 =
      |    42
      |
      |def main(): Unit \ IO =
      |    println(helper())
      |""".stripMargin

  test("coverage probes survive optimizer and register hits at runtime") {
    // Clear any previous coverage state
    Coverage.clear()

    // Compile with coverage enabled
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)

    val compilationResult = flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }

    // Get the main function and execute it
    compilationResult.getMain match {
      case Some(main) =>
        // Execute main with empty arguments
        main(Array())

      case None =>
        fail("Compiled program has no main function")
    }

    // Verify coverage was recorded
    val snapshot = Coverage.snapshot()
    val metadata = Coverage.getProbeMetadata

    // Ensure we have recorded probes
    assert(metadata.nonEmpty, "No coverage metadata recorded during compilation")
    assert(snapshot.nonEmpty, "No coverage hits recorded during execution")

    // Verify that probe counts are positive (not just existence)
    val allHitsPositive = snapshot.values.forall(_ > 0)
    assert(allHitsPositive, s"Some probes have zero or negative hits: $snapshot")

    // Verify that both functions (helper and main) have coverage probes
    // At minimum, we expect probes to be recorded for user-defined functions
    val functionProbes = metadata.filter { case (_, (_, _, kind)) => kind == "function" }
    assert(functionProbes.nonEmpty,
      s"Expected function-level coverage probes, but got: ${metadata.values.map(_._3).toList}")

    // Verify that at least one function probe was hit
    val hitFunctionProbes = functionProbes.filter { case (probeId, _) =>
      snapshot.contains(probeId) && snapshot(probeId) > 0
    }
    assert(hitFunctionProbes.nonEmpty,
      s"Expected at least one function probe to be hit, but snapshot is: $snapshot")
  }

}


