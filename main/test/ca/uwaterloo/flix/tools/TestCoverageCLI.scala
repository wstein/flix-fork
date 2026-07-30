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
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

class TestCoverageCLI extends AnyFunSuite {

  private val ProjectPrefix = "flix-cov-test-"

  private def runCliSubprocess(args: Array[String], projectDir: Path): (Int, String, String) = {
    val javaBin = System.getProperty("java.home") + "/bin/java"
    val classpath = System.getProperty("java.class.path")
    val cmd = List(javaBin, "-cp", classpath, "ca.uwaterloo.flix.Main") ++ args

    val processBuilder = new ProcessBuilder(cmd.asJava)
    processBuilder.directory(projectDir.toFile)

    val process = processBuilder.start()
    val stdout = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val stderr = new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    val exitCode = process.waitFor()

    (exitCode, stdout, stderr)
  }

  test("CLI E2E: flix test --coverage generates JSON and LCOV reports") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    // Write source file and test file
    val srcFile = p.resolve("src/Main.flix")
    Files.writeString(srcFile,
      """
        |pub def compute(x: Int32): Int32 = x + 1
        |def main(): Unit \ IO = println(compute(41))
        |""".stripMargin)

    val testFile = p.resolve("test/TestMain.flix")
    Files.writeString(testFile,
      """
        |@Test
        |def test01(): Unit \ Assert = Assert.assertEq(expected = 42, compute(41))
        |""".stripMargin)

    val (exitCode, stdout, stderr) = runCliSubprocess(Array("test", "--coverage"), p)

    assert(exitCode == 0, s"Expected exit code 0, got $exitCode. Stderr: $stderr")
    val jsonReport = p.resolve("build/coverage.json")
    val lcovReport = p.resolve("build/coverage.info")

    assert(Files.exists(jsonReport), "build/coverage.json should be generated")
    assert(Files.exists(lcovReport), "build/coverage.info should be generated")
    assert(stdout.contains("Coverage:"), "Terminal output should contain coverage summary")
  }

  test("CLI E2E: flix test --coverage with custom output paths") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val customJson = "custom/my_cov.json"
    val customLcov = "custom/my_cov.info"

    val (exitCode, stdout, stderr) = runCliSubprocess(
      Array("test", "--coverage", "--coverage-output", customJson, "--coverage-lcov-output", customLcov),
      p
    )

    assert(exitCode == 0, s"Expected exit code 0, got $exitCode. Stderr: $stderr")
    assert(Files.exists(p.resolve(customJson)), "Custom JSON coverage report should exist")
    assert(Files.exists(p.resolve(customLcov)), "Custom LCOV coverage report should exist")
  }

  test("CLI E2E: flix run --coverage generates reports") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val (exitCode, stdout, stderr) = runCliSubprocess(Array("run", "--coverage"), p)

    assert(exitCode == 0, s"Expected exit code 0, got $exitCode. Stderr: $stderr")
    assert(Files.exists(p.resolve("build/coverage.json")), "build/coverage.json should exist")
    assert(Files.exists(p.resolve("build/coverage.info")), "build/coverage.info should exist")
  }

  test("CLI E2E: test failure with --coverage generates report and exits with code 1") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val testFile = p.resolve("test/TestMain.flix")
    Files.writeString(testFile,
      """
        |@Test
        |def testFailing(): Unit \ Assert = Assert.assertEq(expected = 100, 42)
        |""".stripMargin)

    val (exitCode, stdout, stderr) = runCliSubprocess(Array("test", "--coverage"), p)

    assert(exitCode == 1, s"Expected exit code 1 on test failure, got $exitCode")
    assert(Files.exists(p.resolve("build/coverage.json")), "build/coverage.json should exist even after test failure")
    assert(Files.exists(p.resolve("build/coverage.info")), "build/coverage.info should exist even after test failure")
  }
}
