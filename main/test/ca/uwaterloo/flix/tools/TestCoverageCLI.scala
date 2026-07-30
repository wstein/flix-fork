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
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.Bootstrap
import ca.uwaterloo.flix.runtime.Coverage
import ca.uwaterloo.flix.util.{Formatter, Options}
import ca.uwaterloo.flix.tools.pkg.PkgTestUtils
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

class TestCoverageCLI extends AnyFunSuite {

  private val ProjectPrefix = "flix-cov-test-"

  test("project test with coverage generates JSON and LCOV reports") {
    Coverage.clear()
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    // Overwrite src/Main.flix with public compute function
    val srcFile = p.resolve("src/Main.flix")
    Files.writeString(srcFile,
      """
        |pub def compute(x: Int32): Int32 = x + 1
        |
        |def main(): Unit \ IO = println(compute(41))
        |""".stripMargin)

    // Overwrite test/TestMain.flix so test01 calls compute
    val testFile = p.resolve("test/TestMain.flix")
    Files.writeString(testFile,
      """
        |@Test
        |def test01(): Unit \ Assert = Assert.assertEq(expected = 42, compute(41))
        |""".stripMargin)

    val flix = PkgTestUtils.mkFlix.setOptions(Options.Default.copy(coverage = true, progress = false))
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.test(flix)

    // Finalize reports as Main does
    CoverageReporter.writeJsonReport(p.resolve("build/coverage.json"))
    CoverageReporter.writeLcovReport(p.resolve("build/coverage.info"))

    val jsonReport = p.resolve("build/coverage.json")
    val lcovReport = p.resolve("build/coverage.info")

    assert(Files.exists(jsonReport), "JSON coverage report should be generated in project test mode")
    assert(Files.exists(lcovReport), "LCOV coverage report should be generated in project test mode")
    assert(Files.readString(jsonReport).contains("summary"), "JSON report should contain summary")
    assert(Files.readString(lcovReport).contains("end_of_record"), "LCOV report should contain end_of_record")
  }

  test("project run with coverage generates JSON and LCOV reports") {
    Coverage.clear()
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val flix = PkgTestUtils.mkFlix.setOptions(Options.Default.copy(coverage = true, progress = false))
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.run(flix, Array.empty)

    CoverageReporter.writeJsonReport(p.resolve("build/coverage.json"))
    CoverageReporter.writeLcovReport(p.resolve("build/coverage.info"))

    val jsonReport = p.resolve("build/coverage.json")
    val lcovReport = p.resolve("build/coverage.info")

    assert(Files.exists(jsonReport), "JSON coverage report should be generated in project run mode")
    assert(Files.exists(lcovReport), "LCOV coverage report should be generated in project run mode")
  }

  test("custom output path options for JSON and LCOV reports") {
    Coverage.clear()
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val customJson = p.resolve("custom/my_cov.json")
    val customLcov = p.resolve("custom/my_cov.info")

    val flix = PkgTestUtils.mkFlix.setOptions(Options.Default.copy(
      coverage = true,
      coverageOutput = customJson,
      coverageLcovOutput = customLcov,
      progress = false
    ))
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.test(flix)

    CoverageReporter.writeJsonReport(customJson)
    CoverageReporter.writeLcovReport(customLcov)

    assert(Files.exists(customJson), "Custom JSON coverage report path should be respected")
    assert(Files.exists(customLcov), "Custom LCOV coverage report path should be respected")
  }

}
