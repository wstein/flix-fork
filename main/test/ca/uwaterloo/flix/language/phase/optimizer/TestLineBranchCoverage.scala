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
import org.json4s.{DefaultFormats, jvalue2extractable, jvalue2monadic}
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
  private implicit val formats: org.json4s.Formats = DefaultFormats

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

  test("line probes are deduplicated without reusing probe IDs") {
    Coverage.clear()

    val program =
      """
        |def main(): Unit \ IO =
        |    let x = 1; let y = 2;
        |    println(x + y)
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
    val lineProbes = metadata.collect { case (id, pm) if pm.kind == ProbeKind.Line => id -> pm }
    assert(lineProbes.values.groupBy(pm => (pm.qualifiedName, pm.source, pm.line)).values.forall(_.size == 1),
      s"Expected at most one line probe per source line, got: $lineProbes")
    assert(lineProbes.keySet.subsetOf(Coverage.snapshot().keySet),
      s"Executed line probes should retain their registered IDs: $lineProbes")
  }

  test("nested call expressions on their own line are covered") {
    Coverage.clear()
    val program =
      """
        |def increment(x: Int32): Int32 = x + 1
        |
        |def compute(x: Int32): Int32 =
        |    increment(
        |        increment(x)
        |    )
        |
        |def main(): Unit \ IO = println(compute(40))
        |""".stripMargin
    val nestedCallLine = program.linesIterator.indexWhere(_.contains("increment(x)")) + 1
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)

    val result = flix.compile().toResult match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }
    result.getMain.fold(fail("No main function"))(_(Array()))

    val nestedCallProbes = Coverage.getProbeMetadata.collect {
      case (id, metadata) if metadata.kind == ProbeKind.Line && metadata.line == nestedCallLine => id
    }.toSet
    assert(nestedCallProbes.nonEmpty, s"Expected a line probe at nested call line $nestedCallLine")
    assert((nestedCallProbes intersect Coverage.snapshot().keySet).nonEmpty,
      "The nested call line probe should be hit when compute executes")
  }

  test("lambda, closure application, tuple, and operator expressions are covered") {
    Coverage.clear()
    val program =
      """
        |import java.lang.Object
        |
        |def main(): Unit \ IO = {
        |    let f =
        |        x -> x + 1;
        |    let pair =
        |        (
        |            f(40),
        |            1 + f(1)
        |        );
        |    let (a, b) = pair;
        |    println(a + b)
        |}
        |""".stripMargin
    val expectedLines = Set(
      program.linesIterator.indexWhere(_.contains("x -> x + 1")) + 1,
      program.linesIterator.indexWhere(_.trim == "(") + 1,
      program.linesIterator.indexWhere(_.contains("f(40)")) + 1,
      program.linesIterator.indexWhere(_.contains("1 + f(1)")) + 1
    )
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)

    val result = flix.compile().toResult match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }
    result.getMain.fold(fail("No main function"))(_(Array()))

    val metadata = Coverage.getProbeMetadata
    val hitProbeIds = Coverage.snapshot().keySet
    expectedLines.foreach { line =>
      val lineProbeIds = metadata.collect {
        case (id, probe) if probe.kind == ProbeKind.Line && probe.line == line => id
      }.toSet
      assert(lineProbeIds.nonEmpty, s"Expected a line probe at line $line")
      assert((lineProbeIds intersect hitProbeIds).nonEmpty, s"Expected line $line to be hit")
    }
  }

  test("record and array expressions are covered") {
    Coverage.clear()
    val program =
      """
        |def main(): Unit \ IO = {
        |    let r = { value = 42 };
        |    let value = r#value;
        |    let array = Array#{value} @ Static;
        |    println(Array.length(array))
        |}
        |""".stripMargin
    val expectedLines = Set(
      program.linesIterator.indexWhere(_.contains("r#value")) + 1,
      program.linesIterator.indexWhere(_.contains("Array#{value}")) + 1
    )
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    val result = flix.compile().toResult match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }
    result.getMain.fold(fail("No main function"))(_(Array()))

    val metadata = Coverage.getProbeMetadata
    val hits = Coverage.snapshot().keySet
    expectedLines.foreach { line =>
      val ids = metadata.collect { case (id, probe) if probe.kind == ProbeKind.Line && probe.line == line => id }.toSet
      assert(ids.nonEmpty, s"Expected a line probe at line $line")
      assert((ids intersect hits).nonEmpty, s"Expected line $line to be hit")
    }
  }

  test("cast and handler expressions are covered") {
    Coverage.clear()
    val program =
      """
        |import java.lang.Object
        |
        |eff Ping {
        |    def ping(): Unit
        |}
        |
        |def main(): Unit \ IO = {
        |    run { Ping.ping() } with handler Ping { def ping(_cont) = () };
        |    let _ = unchecked_cast(null as Object);
        |    println("ok")
        |}
        |""".stripMargin
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    val handlerLine = program.linesIterator.indexWhere(_.contains("run { Ping.ping")) + 1
    val castLine = program.linesIterator.indexWhere(_.contains("unchecked_cast")) + 1
    flix.compile().toResult match {
      case Result.Ok(result) =>
        result.getMain.fold(fail("No main function"))(_(Array()))
        val metadata = Coverage.getProbeMetadata
        val hits = Coverage.snapshot().keySet
        val handlerIds = metadata.collect { case (id, probe) if probe.kind == ProbeKind.Line && probe.line == handlerLine => id }.toSet
        val castIds = metadata.collect { case (id, probe) if probe.kind == ProbeKind.Line && probe.line == castLine => id }.toSet
        assert(handlerIds.nonEmpty && (handlerIds intersect hits).nonEmpty, "Handler line should be registered and hit")
        assert(castIds.nonEmpty, "Unchecked-cast line should remain in coverage metadata")
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }
  }

  test("match rules register selected and unselected branch probes") {
    Coverage.clear()
    val program =
      """
        |def classify(x: Int32): String = match x {
        |    case 1 => "one"
        |    case _ => "other"
        |}
        |
        |def main(): Unit \ IO = println(classify(1))
        |""".stripMargin
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    val result = flix.compile().toResult match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }
    result.getMain.fold(fail("No main function"))(_(Array()))
    val ruleIds = Coverage.getProbeMetadata.collect { case (id, pm) if pm.kind == ProbeKind.BranchRule => id }.toSet
    assert(ruleIds.size == 2, s"Expected two match rule probes, got: $ruleIds")
    assert((ruleIds intersect Coverage.snapshot().keySet).size == 1, "Exactly the selected match rule should be hit")
    val selectedLine = program.linesIterator.indexWhere(_.contains("\"one\"")) + 1
    val unselectedLine = program.linesIterator.indexWhere(_.contains("\"other\"")) + 1
    val metadata = Coverage.getProbeMetadata
    val hits = Coverage.snapshot().keySet
    val selectedLineIds = metadata.collect { case (id, pm) if pm.kind == ProbeKind.Line && pm.line == selectedLine => id }.toSet
    val unselectedLineIds = metadata.collect { case (id, pm) if pm.kind == ProbeKind.Line && pm.line == unselectedLine => id }.toSet
    assert(selectedLineIds.nonEmpty && (selectedLineIds intersect hits).nonEmpty, "Selected match body line should be hit")
    assert(unselectedLineIds.nonEmpty && (unselectedLineIds intersect hits).isEmpty, "Unselected match body line should remain uncovered")
  }

  test("choose rules register selected and unselected branch probes") {
    Coverage.clear()
    val program =
      """
        |restrictable enum Choice[s] { case First, Second }
        |
        |def main(): Unit \ IO = {
        |    choose Choice.First {
        |        case Choice.First => println("first")
        |        case Choice.Second => println("second")
        |    }
        |}
        |""".stripMargin
    val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    val result = flix.compile().toResult match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Compilation failed: $errors")
    }
    result.getMain.fold(fail("No main function"))(_(Array()))
    val ruleIds = Coverage.getProbeMetadata.collect { case (id, pm) if pm.kind == ProbeKind.BranchRule => id }.toSet
    assert(ruleIds.size == 2, s"Expected two choose rule probes, got: $ruleIds")
    assert((ruleIds intersect Coverage.snapshot().keySet).size == 1, "Exactly the selected choose rule should be hit")
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

  test("JSON report integration: write, read, and validate structure") {
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

    // Write the JSON report to a temporary file
    val reportPath = java.nio.file.Files.createTempFile("coverage-", ".json")
    try {
      ca.uwaterloo.flix.tools.CoverageReporter.writeJsonReport(reportPath)

      // Read the JSON file
      val jsonContent = java.nio.file.Files.readString(reportPath, java.nio.charset.StandardCharsets.UTF_8)
      assert(jsonContent.nonEmpty, "Report file should not be empty")

      // Verify JSON is valid by parsing
      val parsed = org.json4s.native.JsonMethods.parse(jsonContent)

      // Verify top-level structure
      val summary = parsed \ "summary"
      assert(summary != org.json4s.JNull, "Report should have 'summary' section")

      // Verify summary has coverage sections with numeric values
      val functions = summary \ "functions"
      val lines = summary \ "lines"
      val branches = summary \ "branches"

      assert(functions != org.json4s.JNull, "Summary should have 'functions' coverage")
      assert(lines != org.json4s.JNull, "Summary should have 'lines' coverage")
      assert(branches != org.json4s.JNull, "Summary should have 'branches' coverage")

      // Extract and validate numeric values from each section
      val funcCovered = (functions \ "covered").extractOpt[Int]
      val funcTotal = (functions \ "total").extractOpt[Int]
      assert(funcCovered.isDefined && funcTotal.isDefined, "Functions should have numeric covered and total")
      assert(funcCovered.get >= 0 && funcTotal.get > 0, "Functions coverage should be non-zero")
      assert(funcCovered.get <= funcTotal.get, "Functions covered should be <= total")

      val lineCovered = (lines \ "covered").extractOpt[Int]
      val lineTotal = (lines \ "total").extractOpt[Int]
      assert(lineCovered.isDefined && lineTotal.isDefined, "Lines should have numeric covered and total")
      assert(lineCovered.get >= 0 && lineTotal.get > 0, "Lines coverage should be non-zero")
      assert(lineCovered.get <= lineTotal.get, "Lines covered should be <= total")

      val branchCovered = (branches \ "covered").extractOpt[Int]
      val branchTotal = (branches \ "total").extractOpt[Int]
      assert(branchCovered.isDefined && branchTotal.isDefined, "Branches should have numeric covered and total")
      assert(branchCovered.get >= 0 && branchTotal.get > 0, "Branches coverage should be non-zero")
      assert(branchCovered.get <= branchTotal.get, "Branches covered should be <= total")

      // Verify files array exists and has entries
      val files = parsed \ "files"
      assert(files != org.json4s.JNull, "Report should have 'files' array")

      val filesList = files.asInstanceOf[org.json4s.JArray].arr
      assert(filesList.nonEmpty, "Files array should have entries for instrumented source")

      // Verify at least one file has line coverage data
      val filesWithLineData = filesList.filter { f =>
        val lines = f \ "lines"
        lines != org.json4s.JNull && lines.asInstanceOf[org.json4s.JObject].values.nonEmpty
      }
      assert(filesWithLineData.nonEmpty, "At least one file should have line coverage data")

      // Critical: Verify distinct probes are not deduplicated away
      val metadata = Coverage.getProbeMetadata
      val branchProbes = metadata.values.filter(pm =>
        pm.kind == ProbeKind.BranchTrue || pm.kind == ProbeKind.BranchFalse
      )
      // Check we have both true and false branch probes (not deduplicated together)
      val hasTrue = branchProbes.exists(_.kind == ProbeKind.BranchTrue)
      val hasFalse = branchProbes.exists(_.kind == ProbeKind.BranchFalse)
      assert(hasTrue && hasFalse, "Both branch-true and branch-false probes must exist (not deduplicated together)")

    } finally {
      // Clean up temp file
      java.nio.file.Files.deleteIfExists(reportPath)
    }
  }

}
