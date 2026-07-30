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
import org.json4s._
import org.json4s.native.JsonMethods._
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, TimeUnit}
import scala.jdk.CollectionConverters._
import scala.util.Using

class TestCoverageCLI extends AnyFunSuite {

  private val ProjectPrefix = "flix-cov-test-"

  /**
    * Runs a Flix CLI subprocess with the given arguments, draining stdout and stderr concurrently
    * to prevent buffer-based deadlocks.  The temp project directory is always cleaned up.
    *
    * @return (exitCode, stdout, stderr)
    */
  private def runCliSubprocess(args: Array[String], projectDir: Path): (Int, String, String) = {
    val javaBin  = System.getProperty("java.home") + "/bin/java"
    val classpath = System.getProperty("java.class.path")
    val cmd      = List(javaBin, "-cp", classpath, "ca.uwaterloo.flix.Main") ++ args.toList

    val processBuilder = new ProcessBuilder(cmd.asJava)
    processBuilder.directory(projectDir.toFile)
    processBuilder.redirectErrorStream(false)

    val process = processBuilder.start()
    val pool    = Executors.newFixedThreadPool(2)

    @volatile var stdout = ""
    @volatile var stderr = ""

    val stdoutFuture = pool.submit(new Runnable {
      override def run(): Unit =
        stdout = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    })
    val stderrFuture = pool.submit(new Runnable {
      override def run(): Unit =
        stderr = new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    })

    val completed = process.waitFor(120, TimeUnit.SECONDS)
    if (!completed) process.destroyForcibly()
    pool.shutdown()
    pool.awaitTermination(5, TimeUnit.SECONDS)

    stdoutFuture.get()
    stderrFuture.get()

    (process.exitValue(), stdout, stderr)
  }

  private def withProject[A](body: Path => A): A = {
    val p = Files.createTempDirectory(ProjectPrefix)
    try {
      Bootstrap.init(p)(System.out)
      body(p)
    } finally {
      // Best-effort cleanup
      deleteRecursively(p)
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Files.list(path).forEach(deleteRecursively)
    }
    Files.deleteIfExists(path)
  }

  /** Parses JSON and asserts that `summary.functions.total > 0` and `files` is non-empty. */
  private def assertJsonValid(jsonPath: Path): Unit = {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val raw    = Files.readString(jsonPath, StandardCharsets.UTF_8)
    assert(raw.trim.nonEmpty, s"JSON at $jsonPath must be non-empty")
    val parsed = parse(raw)
    val total  = (parsed \ "summary" \ "functions" \ "total").extractOrElse[Int](0)
    assert(total > 0, s"summary.functions.total should be > 0 in $jsonPath, got $total\n$raw")
    val files  = (parsed \ "files").extractOrElse[List[JValue]](Nil)
    assert(files.nonEmpty, s"files array should be non-empty in $jsonPath")
  }

  /** Parses an LCOV tracefile and asserts the presence of mandatory record types. */
  private def assertLcovValid(lcovPath: Path): Unit = {
    val lines = Files.readAllLines(lcovPath, StandardCharsets.UTF_8).asScala.toList
    assert(lines.nonEmpty, s"LCOV at $lcovPath must be non-empty")
    val required = Seq("SF:", "FN:", "FNDA:", "DA:", "end_of_record")
    for (key <- required) {
      assert(lines.exists(_.startsWith(key)),
        s"LCOV at $lcovPath must contain '$key' records\n${lines.take(30).mkString("\n")}")
    }
  }

  test("CLI E2E: flix test --coverage generates JSON and LCOV reports") {
    withProject { p =>
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub def compute(x: Int32): Int32 = x + 1
           |def main(): Unit \ IO = println(compute(41))
           |""".stripMargin)
      Files.writeString(p.resolve("test/TestMain.flix"),
        """|@Test
           |def test01(): Unit \ Assert = Assert.assertEq(expected = 42, compute(41))
           |""".stripMargin)

      val (exitCode, stdout, stderr) = runCliSubprocess(Array("test", "--coverage"), p)
      assert(exitCode == 0, s"Expected exit 0, got $exitCode.\nSTDERR:\n$stderr\nSTDOUT:\n$stdout")

      val jsonReport = p.resolve("build/coverage.json")
      val lcovReport = p.resolve("build/coverage.info")
      assert(Files.exists(jsonReport), "build/coverage.json should be generated")
      assert(Files.exists(lcovReport), "build/coverage.info should be generated")
      assert(stdout.contains("Coverage:"), "Terminal output should contain coverage summary")

      assertJsonValid(jsonReport)
      assertLcovValid(lcovReport)
    }
  }

  test("CLI E2E: flix test --coverage with custom output paths") {
    withProject { p =>
      val customJson = "custom/my_cov.json"
      val customLcov = "custom/my_cov.info"

      val (exitCode, _, stderr) = runCliSubprocess(
        Array("test", "--coverage", "--coverage-output", customJson, "--coverage-lcov-output", customLcov), p)
      assert(exitCode == 0, s"Expected exit 0, got $exitCode.\nSTDERR:\n$stderr")

      assertJsonValid(p.resolve(customJson))
      assertLcovValid(p.resolve(customLcov))
    }
  }

  test("CLI E2E: flix run --coverage generates reports") {
    withProject { p =>
      val (exitCode, _, stderr) = runCliSubprocess(Array("run", "--coverage"), p)
      assert(exitCode == 0, s"Expected exit 0, got $exitCode.\nSTDERR:\n$stderr")

      assertJsonValid(p.resolve("build/coverage.json"))
      assertLcovValid(p.resolve("build/coverage.info"))
    }
  }

  test("CLI E2E: test failure with --coverage generates report and exits with code 1") {
    withProject { p =>
      Files.writeString(p.resolve("test/TestMain.flix"),
        """|@Test
           |def testFailing(): Unit \ Assert = Assert.assertEq(expected = 100, 42)
           |""".stripMargin)

      val (exitCode, _, _) = runCliSubprocess(Array("test", "--coverage"), p)
      assert(exitCode == 1, s"Expected exit 1 on test failure, got $exitCode")

      assertJsonValid(p.resolve("build/coverage.json"))
      assertLcovValid(p.resolve("build/coverage.info"))
    }
  }
}
