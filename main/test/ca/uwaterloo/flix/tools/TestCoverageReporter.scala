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
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.runtime.{CoverageProbe, CoverageProbeKind, CoverageSession, CoverageSnapshot}
import org.json4s.{DefaultFormats, jvalue2extractable, jvalue2monadic}
import org.json4s.native.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

class TestCoverageReporter extends AnyFunSuite {

  private implicit val formats: DefaultFormats.type = DefaultFormats

  private val snapshot = CoverageSnapshot(
    CoverageSession(42L, Vector(
      CoverageProbe(0, "src/B.flix", 7, CoverageProbeKind.Function, "B.run"),
      CoverageProbe(1, "src/A.flix", 3, CoverageProbeKind.Function, "A.choose"),
      CoverageProbe(2, "src/A.flix", 4, CoverageProbeKind.Line, "A.choose"),
      CoverageProbe(3, "src/A.flix", 5, CoverageProbeKind.Line, "A.choose"),
      CoverageProbe(4, "src/A.flix", 4, CoverageProbeKind.BranchTrue, "A.choose"),
      CoverageProbe(5, "src/A.flix", 5, CoverageProbeKind.BranchFalse, "A.choose")
    )),
    Vector(0L, 1L, 3L, 0L, 3L, 0L),
    partial = false,
    testFilters = List("A\\.test.*")
  )

  test("JSON report is hierarchical, deterministic, and records filtered-run context") {
    val json = CoverageReporter.renderJson(snapshot)
    val parsed = parse(json)

    assert((parsed \ "formatVersion").extract[Int] == 1)
    assert(!(parsed \ "partial").extract[Boolean])
    assert((parsed \ "testFilters").extract[List[String]] == List("A\\.test.*"))
    assert((parsed \ "summary" \ "functions" \ "covered").extract[Int] == 1)
    assert((parsed \ "summary" \ "functions" \ "total").extract[Int] == 2)
    assert((parsed \ "summary" \ "lines" \ "covered").extract[Int] == 1)
    assert((parsed \ "summary" \ "branches" \ "covered").extract[Int] == 1)
    assert((parsed \ "files" \ "path").extract[List[String]] == List("src/A.flix", "src/B.flix"))
    assert(CoverageReporter.renderJson(snapshot) == json)
  }

  test("LCOV report includes zero-hit probes and stable file ordering") {
    val lcov = CoverageReporter.renderLcov(snapshot)
    assert(lcov.indexOf("SF:src/A.flix") < lcov.indexOf("SF:src/B.flix"))
    assert(lcov.contains("FN:3,A.choose"))
    assert(lcov.contains("FNDA:1,A.choose"))
    assert(lcov.contains("DA:4,3"))
    assert(lcov.contains("DA:5,0"))
    assert(lcov.contains("BRDA:4,0,0,3"))
    assert(lcov.contains("BRDA:5,0,0,-"))
    assert(lcov.contains("BRF:2"))
    assert(lcov.contains("BRH:1"))
  }

  test("summary identifies partial filtered runs") {
    val partial = snapshot.copy(partial = true)
    val summary = CoverageReporter.formatSummary(partial)
    assert(summary.contains("partial"))
    assert(summary.contains("filtered by A\\.test.*"))
    assert(summary.contains("Functions: 50.0% (1/2)"))
    assert(summary.contains("Lines: 50.0% (1/2)"))
    assert(summary.contains("Branches: 50.0% (1/2)"))
  }

}
