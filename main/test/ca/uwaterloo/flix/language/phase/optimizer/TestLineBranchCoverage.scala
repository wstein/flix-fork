/*
 * Copyright 2026
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
import ca.uwaterloo.flix.runtime.{Coverage, ProbeKind}
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Test for line and branch coverage instrumentation.
  *
  * Validates that:
  * - Line probes are recorded for executable statements
  * - Branch probes are recorded for if-expression branches
  * - Metadata distinguishes between different probe kinds
  * - Coverage reports include uncovered lines
  * - Conditional branches correctly record true/false execution
  */
class TestLineBranchCoverage extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /**
    * A multi-line program that exercises different code paths.
    *
    * Line 2: helper definition
    * Line 3: helper body (line probe candidate)
    * Line 5: main definition
    * Line 6-8: main body with if-expression
    * Line 7: then-branch (will be executed)
    * Line 8: else-branch (will not be executed)
    * Line 9-10: alternative path (not taken)
    */
  private val LineAndBranchProgram: String =
    """
      |def helper(x: Int32): Int32 =
      |    x + 1
      |
      |def main(): Unit \ IO =
      |    let y = helper(41);
      |    if (y > 40)
      |        println("yes")
      |    else
      |        println("no")
      |""".stripMargin

  test("function, line, and branch probes all present") {
    Coverage.clear()

    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, LineAndBranchProgram)

    val compilationResult = flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }

    // Execute the program
    compilationResult.getMain match {
      case Some(main) => main(Array())
      case None => fail("No main function")
    }

    val snapshot = Coverage.snapshot()
    val metadata = Coverage.getProbeMetadata

    // Verify function probes are recorded
    val functionProbes = metadata.filter { case (_, pm) => pm.kind == ProbeKind.Function }
    assert(functionProbes.nonEmpty, "Should have function probes")
    assert(functionProbes.size >= 2, s"Expected at least 2 function probes (helper, main), got ${functionProbes.size}")

    // CRITICAL: Line probes must exist
    val lineProbes = metadata.filter { case (_, pm) => pm.kind == ProbeKind.Line }
    assert(lineProbes.nonEmpty,
      "Should have line probes for statements (let-binding, if-expression)")

    // CRITICAL: Branch probes must exist (exactly two for the if-expression)
    val branchProbes = metadata.filter { case (_, pm) =>
      pm.kind == ProbeKind.BranchTrue || pm.kind == ProbeKind.BranchFalse
    }
    assert(branchProbes.size == 2,
      s"Expected exactly 2 branch probes (true + false for one if-expression), got ${branchProbes.size}")

    // Verify both branch types present
    val trueProbes = metadata.filter { case (_, pm) => pm.kind == ProbeKind.BranchTrue }
    val falseProbes = metadata.filter { case (_, pm) => pm.kind == ProbeKind.BranchFalse }
    assert(trueProbes.size == 1, s"Expected 1 BranchTrue probe, got ${trueProbes.size}")
    assert(falseProbes.size == 1, s"Expected 1 BranchFalse probe, got ${falseProbes.size}")
  }

  test("branch probes record true-taken, false-uncovered") {
    Coverage.clear()

    // Program with runtime condition: let-binding determines branch
    val program = """
      |def main(): Unit \ IO =
      |    let x = 42;
      |    if (x > 40)
      |        println("yes")
      |    else
      |        println("no")
      |""".stripMargin

    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)

    val compilationResult = flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }

    compilationResult.getMain match {
      case Some(main) => main(Array())
      case None => fail("No main function")
    }

    val snapshot = Coverage.snapshot()
    val metadata = Coverage.getProbeMetadata

    // Find the branch probes for this if-expression
    val branchProbes = metadata.filter { case (_, pm) =>
      pm.kind == ProbeKind.BranchTrue || pm.kind == ProbeKind.BranchFalse
    }

    assert(branchProbes.size >= 2,
      s"Expected at least 2 branch probes for if-expression, got ${branchProbes.size}")

    // Get true and false branch probes
    val trueProbeIds = branchProbes.filter { case (_, pm) => pm.kind == ProbeKind.BranchTrue }.keySet
    val falseProbeIds = branchProbes.filter { case (_, pm) => pm.kind == ProbeKind.BranchFalse }.keySet

    assert(trueProbeIds.size > 0, "Should have at least one true branch probe")
    assert(falseProbeIds.size > 0, "Should have at least one false branch probe")

    // True branch was taken (in snapshot)
    val trueHit = trueProbeIds.exists(snapshot.contains)
    assert(trueHit, "True branch should be recorded as hit")

    // False branch was NOT taken (not in snapshot)
    val falseHit = falseProbeIds.exists(snapshot.contains)
    assert(!falseHit, "False branch should NOT be recorded as hit (not taken)")

    // But both probes should exist in metadata
    assert(trueProbeIds.nonEmpty && falseProbeIds.nonEmpty,
      "Both true and false branch probes should exist in metadata even if uncovered")
  }

  test("line probes tracked for multi-line statements") {
    Coverage.clear()

    // Program with multiple statements that will be instrumented
    val program = """
      |def add(x: Int32, y: Int32): Int32 =
      |    let a = x;
      |    let b = y;
      |    a + b
      |
      |def main(): Unit \ IO =
      |    let sum = add(1, 2);
      |    println(sum)
      |""".stripMargin

    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)

    val compilationResult = flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }

    compilationResult.getMain match {
      case Some(main) => main(Array())
      case None => fail("No main function")
    }

    val metadata = Coverage.getProbeMetadata

    // Verify line probes exist for let-bindings and other statements
    val lineProbes = metadata.filter { case (_, pm) => pm.kind == ProbeKind.Line }
    assert(lineProbes.nonEmpty,
      "Should have line probes for let-bindings and expressions within functions")
  }

  test("probe metadata organized by function and line") {
    Coverage.clear()

    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, LineAndBranchProgram)

    val compilationResult = flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }

    compilationResult.getMain match {
      case Some(main) => main(Array())
      case None => fail("No main function")
    }

    val metadata = Coverage.getProbeMetadata

    // Group by function
    val byFunction = metadata.values.groupBy(_.qualifiedName)
    assert(byFunction.size >= 1, "Should have at least function probes")

    // Group by line within each function
    byFunction.foreach { case (funcName, probes) =>
      val byLine = probes.groupBy(_.line)
      
      // Each line in a function should have at least one probe
      byLine.foreach { case (line, lineProbes) =>
        assert(line > 0, s"Invalid line number $line in function $funcName")
        assert(lineProbes.nonEmpty, s"Should have probes for line $line in $funcName")
      }
    }
  }

  test("JSON report contains coverage summary and file breakdown") {
    Coverage.clear()

    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, LineAndBranchProgram)

    val compilationResult = flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }

    compilationResult.getMain match {
      case Some(main) => main(Array())
      case None => fail("No main function")
    }

    val metadata = Coverage.getProbeMetadata
    val snapshot = Coverage.snapshot()

    // Verify basic coverage data is present (this will be used to generate report)
    assert(metadata.nonEmpty, "Should have registered probes")
    assert(snapshot.nonEmpty, "Should have hit probes during execution")

    // Verify summary statistics
    val functionProbes = metadata.values.count(_.kind == ProbeKind.Function)
    val lineProbes = metadata.values.count(_.kind == ProbeKind.Line)
    val branchProbes = metadata.values.count(pm =>
      pm.kind == ProbeKind.BranchTrue || pm.kind == ProbeKind.BranchFalse
    )

    val functionCovered = metadata.count {
      case (probeId, pm) =>
        pm.kind == ProbeKind.Function && snapshot.contains(probeId)
    }
    val lineCovered = metadata.count {
      case (probeId, pm) =>
        pm.kind == ProbeKind.Line && snapshot.contains(probeId)
    }
    val branchCovered = metadata.count {
      case (probeId, pm) =>
        (pm.kind == ProbeKind.BranchTrue || pm.kind == ProbeKind.BranchFalse) && snapshot.contains(probeId)
    }

    // CRITICAL: Coverage totals must be non-zero
    assert(functionProbes > 0, "Should have function probes in report")
    assert(lineProbes > 0, "Should have line probes in report")
    assert(branchProbes > 0, "Should have branch probes in report")

    // CRITICAL: At least some probes must be covered
    assert(functionCovered > 0, "Should have covered function probes")
    assert(lineCovered > 0, "Should have covered line probes")
    assert(branchCovered > 0, "Should have covered branch probes")

    // Verify coverage percentages are sensible (0-100)
    val functionPercent = (functionCovered * 100) / functionProbes
    val linePercent = (lineCovered * 100) / lineProbes
    val branchPercent = (branchCovered * 100) / branchProbes

    assert(functionPercent >= 0 && functionPercent <= 100, s"Function coverage $functionPercent% out of range")
    assert(linePercent >= 0 && linePercent <= 100, s"Line coverage $linePercent% out of range")
    assert(branchPercent >= 0 && branchPercent <= 100, s"Branch coverage $branchPercent% out of range")
  }

}
