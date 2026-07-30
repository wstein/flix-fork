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
package ca.uwaterloo.flix.runtime

import ca.uwaterloo.flix.tools.CoverageReporter
import org.json4s.{DefaultFormats, jvalue2extractable, jvalue2monadic}
import org.json4s.native.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
  * Unit tests for Coverage probe metadata registration and reporting.
  *
  * Verifies that:
  * - Probes are registered with complete metadata
  * - Zero-hit probes remain in metadata (not just snapshot)
  * - Metadata is properly indexed by probe ID
  * - ProbeKind ADT works correctly
  */
class TestCoverageMetadata extends AnyFunSuite {

  private implicit val formats: org.json4s.Formats = DefaultFormats

  test("register and retrieve function probe metadata") {
    Coverage.clear()

    // Register a function probe
    Coverage.registerProbe(0, "Test.flix", 10, ProbeKind.Function, "Test.myFunction")

    val metadata = Coverage.getProbeMetadata
    assert(metadata.size == 1, "Expected 1 probe registered")
    assert(metadata(0).source == "Test.flix")
    assert(metadata(0).line == 10)
    assert(metadata(0).kind == ProbeKind.Function)
    assert(metadata(0).qualifiedName == "Test.myFunction")
  }

  test("register multiple probes with different kinds") {
    Coverage.clear()

    Coverage.registerProbe(0, "Test.flix", 5, ProbeKind.Function, "Test.add")
    Coverage.registerProbe(1, "Test.flix", 6, ProbeKind.Line, "Test.add")
    Coverage.registerProbe(2, "Test.flix", 6, ProbeKind.BranchTrue, "Test.add")
    Coverage.registerProbe(3, "Test.flix", 6, ProbeKind.BranchFalse, "Test.add")

    val metadata = Coverage.getProbeMetadata
    assert(metadata.size == 4, "Expected 4 probes registered")
    assert(metadata(0).kind == ProbeKind.Function)
    assert(metadata(1).kind == ProbeKind.Line)
    assert(metadata(2).kind == ProbeKind.BranchTrue)
    assert(metadata(3).kind == ProbeKind.BranchFalse)
  }

  test("zero-hit probes remain in metadata") {
    Coverage.clear()

    // Register three probes but only hit the middle one
    Coverage.registerProbe(0, "Test.flix", 10, ProbeKind.Line, "Test.foo")
    Coverage.registerProbe(1, "Test.flix", 11, ProbeKind.Line, "Test.foo")
    Coverage.registerProbe(2, "Test.flix", 12, ProbeKind.Line, "Test.foo")

    Coverage.hit(1)

    val metadata = Coverage.getProbeMetadata
    val snapshot = Coverage.snapshot()

    // All probes in metadata, but only 1 in snapshot
    assert(metadata.size == 3, "Metadata should contain all 3 probes (including zero-hit)")
    assert(snapshot.size == 1, "Snapshot should contain only 1 hit probe")
    assert(snapshot.contains(1), "Snapshot should contain probe 1")
    assert(!snapshot.contains(0) && !snapshot.contains(2), "Snapshot should not contain zero-hit probes")
  }

  test("probe kind string serialization") {
    assert(ProbeKind.Function.asString == "function")
    assert(ProbeKind.Line.asString == "line")
    assert(ProbeKind.BranchTrue.asString == "branch-true")
    assert(ProbeKind.BranchFalse.asString == "branch-false")
    assert(ProbeKind.BranchRule.asString == "branch-rule")
  }

  test("probe kind deserialization from string") {
    assert(ProbeKind.fromString("function") == Some(ProbeKind.Function))
    assert(ProbeKind.fromString("line") == Some(ProbeKind.Line))
    assert(ProbeKind.fromString("branch-true") == Some(ProbeKind.BranchTrue))
    assert(ProbeKind.fromString("branch-false") == Some(ProbeKind.BranchFalse))
    assert(ProbeKind.fromString("branch-rule") == Some(ProbeKind.BranchRule))
    assert(ProbeKind.fromString("unknown") == None)
  }

  test("source files with only uncovered probes remain in metadata") {
    Coverage.clear()

    // Register probes in two files
    Coverage.registerProbe(0, "File1.flix", 10, ProbeKind.Line, "File1.foo")
    Coverage.registerProbe(1, "File1.flix", 11, ProbeKind.Line, "File1.foo")
    Coverage.registerProbe(2, "File2.flix", 20, ProbeKind.Line, "File2.bar")

    Coverage.hit(2)

    val metadata = Coverage.getProbeMetadata
    val snapshot = Coverage.snapshot()

    // Both files represented in metadata
    val file1Probes = metadata.filter(_._2.source == "File1.flix")
    val file2Probes = metadata.filter(_._2.source == "File2.flix")

    assert(file1Probes.size == 2, "File1 should have 2 probes in metadata")
    assert(file2Probes.size == 1, "File2 should have 1 probe in metadata")

    // Only File2 probes in snapshot
    val file1InSnapshot = snapshot.keys.exists(metadata(_).source == "File1.flix")
    val file2InSnapshot = snapshot.keys.exists(metadata(_).source == "File2.flix")

    assert(!file1InSnapshot, "File1 should not appear in snapshot (all zero-hit)")
    assert(file2InSnapshot, "File2 should appear in snapshot")
  }

  test("qualified function names are preserved in metadata") {
    Coverage.clear()

    val testCases = List(
      ("Math.add", 0),
      ("List.map", 1),
      ("Option.flatMap", 2),
      ("Prelude.println", 3)
    )

    testCases.foreach { case (qName, probeId) =>
      Coverage.registerProbe(probeId, "Std.flix", 100 + probeId, ProbeKind.Function, qName)
    }

    val metadata = Coverage.getProbeMetadata
    testCases.foreach { case (qName, probeId) =>
      assert(metadata(probeId).qualifiedName == qName,
        s"Expected qualified name '$qName' for probe $probeId")
    }
  }

  test("hit count tracking across multiple calls") {
    Coverage.clear()

    Coverage.registerProbe(0, "Test.flix", 10, ProbeKind.Line, "Test.foo")

    Coverage.hit(0)
    var snapshot = Coverage.snapshot()
    assert(snapshot(0) == 1, "Probe should have 1 hit")

    Coverage.hit(0)
    snapshot = Coverage.snapshot()
    assert(snapshot(0) == 2, "Probe should have 2 hits")

    Coverage.hit(0)
    snapshot = Coverage.snapshot()
    assert(snapshot(0) == 3, "Probe should have 3 hits")
  }

  test("report snapshot returns coherent metadata and hits") {
    Coverage.clear()
    Coverage.registerProbe(0, "Test.flix", 10, ProbeKind.Line, "Test.foo")
    Coverage.hit(0)

    val (metadata, hits) = Coverage.reportSnapshot()
    assert(metadata.keySet == Set(0))
    assert(hits == Map(0 -> 1L))
  }

  test("report preserves same-line rule probes independently") {
    Coverage.clear()
    Coverage.registerProbe(0, "Test.flix", 10, ProbeKind.BranchRule, "Test.classify")
    Coverage.registerProbe(1, "Test.flix", 10, ProbeKind.BranchRule, "Test.classify")
    Coverage.hit(0)

    val reportPath = Files.createTempFile("coverage-", ".json")
    try {
      CoverageReporter.writeJsonReport(reportPath)
      val report = parse(Files.readString(reportPath, StandardCharsets.UTF_8))
      val files = (report \ "files").asInstanceOf[org.json4s.JArray].arr
      val branches = (files.head \ "branches").asInstanceOf[org.json4s.JArray].arr
      val probes = (branches.head \ "branches").asInstanceOf[org.json4s.JArray].arr

      assert(probes.size == 2, "Both same-line rule probes must be represented")
      assert(probes.map(p => (p \ "id").extract[Int]) == List(0, 1))
      assert(probes.map(p => (p \ "covered").extract[Boolean]) == List(true, false))
      assert(probes.forall(p => (p \ "kind").extract[String] == "branch-rule"))
      assert(probes.forall(p => (p \ "function").extract[String] == "Test.classify"))
    } finally {
      Files.deleteIfExists(reportPath)
    }
  }

}
