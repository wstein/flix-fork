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

import ca.uwaterloo.flix.api.Bootstrap
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, TimeUnit}
import scala.jdk.CollectionConverters._
import scala.util.Using

class TestDocCLI extends AnyFunSuite {

  private val ProjectPrefix = "flix-doc-cli-test-"

  private def runCliSubprocess(args: Array[String], projectDir: Path): (Int, String, String) = {
    val javaBin   = System.getProperty("java.home") + "/bin/java"
    val classpath = System.getProperty("java.class.path")
    val cmd       = List(javaBin, "-cp", classpath, "ca.uwaterloo.flix.Main") ++ args.toList

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

    try {
      val completed = process.waitFor(120, TimeUnit.SECONDS)
      if (!completed) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
      }
      stdoutFuture.get(15, TimeUnit.SECONDS)
      stderrFuture.get(15, TimeUnit.SECONDS)

      if (!completed) {
        fail(s"Subprocess timed out after 120 seconds!\nCmd: ${cmd.mkString(" ")}\nCwd: $projectDir\nSTDOUT:\n$stdout\nSTDERR:\n$stderr")
      }

      (process.exitValue(), stdout, stderr)
    } finally {
      pool.shutdownNow()
    }
  }

  private def withProject[A](body: Path => A): A = {
    val p = Files.createTempDirectory(ProjectPrefix)
    try {
      Bootstrap.init(p)(System.out)
      body(p)
    } finally {
      deleteRecursively(p)
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Using(Files.list(path)) { stream =>
        stream.forEach(deleteRecursively)
      }
    }
    Files.deleteIfExists(path)
  }

  test("CLI E2E: flix doc --doc-format md generates Markdown documentation") {
    withProject { p =>
      val (exitCode, stdout, stderr) = runCliSubprocess(Array("doc", "--doc-format", "md"), p)
      assert(exitCode == 0, s"Expected exit 0, got $exitCode.\nSTDERR:\n$stderr\nSTDOUT:\n$stdout")

      val docDir = p.resolve("build/doc")
      assert(Files.exists(docDir.resolve("index.md")), "build/doc/index.md should exist")
    }
  }

  test("CLI E2E: flix doc --doc-format all generates both HTML and Markdown documentation") {
    withProject { p =>
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub enum Shape { case Circle }
           |""".stripMargin)

      val (exitCode, stdout, stderr) = runCliSubprocess(Array("doc", "--doc-format", "all"), p)
      assert(exitCode == 0, s"Expected exit 0, got $exitCode.\nSTDERR:\n$stderr\nSTDOUT:\n$stdout")

      val docDir = p.resolve("build/doc")
      assert(Files.exists(docDir.resolve("index.html")), "build/doc/index.html should exist")
      assert(Files.exists(docDir.resolve("index.md")), "build/doc/index.md should exist")
      assert(Files.exists(docDir.resolve("Shape.html")), "build/doc/Shape.html should exist")
      assert(Files.exists(docDir.resolve("Shape.md")), "build/doc/Shape.md should exist")
    }
  }

  test("CLI E2E: flix doc --doc-format all cleans stale generated HTML and Markdown files symmetrically") {
    withProject { p =>
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub enum First { case A }
           |""".stripMargin)

      val (exitCode1, _, stderr1) = runCliSubprocess(Array("doc", "--doc-format", "all"), p)
      assert(exitCode1 == 0, s"Expected exit 0, got $exitCode1.\nSTDERR:\n$stderr1")

      val docDir = p.resolve("build/doc")
      val firstMd = docDir.resolve("First.md")
      val firstHtml = docDir.resolve("First.html")
      val notesFile = docDir.resolve("NOTES.txt")
      val handWrittenHtml = docDir.resolve("NOTES.html")

      assert(Files.exists(firstMd))
      assert(Files.exists(firstHtml))
      Files.writeString(notesFile, "User hand-written notes")
      Files.writeString(handWrittenHtml, "<h1>Custom User Page</h1>")

      // Change code to remove First and add Second
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub enum Second { case B }
           |""".stripMargin)

      val (exitCode2, _, stderr2) = runCliSubprocess(Array("doc", "--doc-format", "all"), p)
      assert(exitCode2 == 0, s"Expected exit 0, got $exitCode2.\nSTDERR:\n$stderr2")

      assert(!Files.exists(firstMd), "Stale generated page First.md should be deleted")
      assert(!Files.exists(firstHtml), "Stale generated page First.html should be deleted")
      assert(Files.exists(docDir.resolve("Second.md")), "Newly generated page Second.md should exist")
      assert(Files.exists(docDir.resolve("Second.html")), "Newly generated page Second.html should exist")
      assert(Files.exists(notesFile), "Non-generated user file NOTES.txt should be preserved")
      assert(Files.exists(handWrittenHtml), "Non-generated user file NOTES.html without marker should be preserved")
    }
  }

  test("CLI E2E negative: invalid --doc-format option exits with non-zero exit code") {
    withProject { p =>
      Files.writeString(p.resolve("src/Main.flix"), "pub def f(): Int32 = 1")
      val (exitCode, _, _) = runCliSubprocess(Array("doc", "--doc-format", "invalid-format"), p)
      assert(exitCode != 0, "Invalid doc-format option should fail")
    }
  }

  test("CLI E2E negative: compilation errors in project exit with non-zero exit code") {
    withProject { p =>
      Files.writeString(p.resolve("src/Main.flix"), "pub def f(): Int32 = \"type-error\" + 42")
      val (exitCode, _, _) = runCliSubprocess(Array("doc", "--doc-format", "md"), p)
      assert(exitCode != 0, "Compilation errors should cause flix doc to exit with non-zero code")
    }
  }

  test("CLI E2E: flix doc generates standalone SVG diagrams and cleans stale SVG files") {
    withProject { p =>
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub trait Parent[a] { pub def p(x: a): Bool }
           |pub trait Child[a] with Parent[a] { pub def c(x: a): Bool }
           |""".stripMargin)

      val (exitCode1, _, stderr1) = runCliSubprocess(Array("doc", "--doc-format", "all"), p)
      assert(exitCode1 == 0, s"Expected exit 0, got $exitCode1.\nSTDERR:\n$stderr1")

      val diagramsDir = p.resolve("build/doc/diagrams")
      val childSvg = diagramsDir.resolve("Child.svg")
      assert(Files.exists(childSvg), "build/doc/diagrams/Child.svg should exist")

      // Change code to remove Child and keep Parent
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub trait Parent[a] { pub def p(x: a): Bool }
           |""".stripMargin)

      val (exitCode2, _, stderr2) = runCliSubprocess(Array("doc", "--doc-format", "all"), p)
      assert(exitCode2 == 0, s"Expected exit 0, got $exitCode2.\nSTDERR:\n$stderr2")

      assert(!Files.exists(childSvg), "Stale generated SVG diagram Child.svg should be deleted")
    }
  }

  test("CLI E2E: flix doc --extended emits extended Datalog relation schemas with content assertions and recursive cleanup") {
    withProject { p =>
      // 1. Verify no datalog/ output without --extended
      Files.writeString(p.resolve("src/Main.flix"),
        """|pub def relParent(x: Int32): Bool = x > 0
           |pub def relChild(x: Int32): Bool = x > 10
           |""".stripMargin)

      val (exitCode1, _, stderr1) = runCliSubprocess(Array("doc"), p)
      assert(exitCode1 == 0, s"Expected exit 0, got $exitCode1.\nSTDERR:\n$stderr1")
      val datalogSvg = p.resolve("build/doc/diagrams/datalog/DatalogSchema.svg")
      assert(!Files.exists(datalogSvg), "datalog/DatalogSchema.svg should NOT exist without --extended flag")

      // 2. Run flix doc --extended and verify content assertions & GeneratedMarker
      val (exitCode2, _, stderr2) = runCliSubprocess(Array("doc", "--extended"), p)
      assert(exitCode2 == 0, s"Expected exit 0, got $exitCode2.\nSTDERR:\n$stderr2")
      assert(Files.exists(datalogSvg), "datalog/DatalogSchema.svg should exist with --extended option")

      val svgText = Files.readString(datalogSvg, StandardCharsets.UTF_8)
      assert(svgText.contains("<!-- Generated by the Flix compiler. Do not edit. -->"), "Must contain GeneratedMarker")
      assert(svgText.contains("<svg") && svgText.contains("</svg>"), "Must contain valid SVG tag structure")
      assert(svgText.contains("relParent") || svgText.contains("relChild"), "Must contain actual Datalog relation names from fixture")

      val xml = scala.xml.XML.loadString(svgText)
      assert(xml.label == "svg", "Generated Datalog SVG must be valid XML")

      // 3. Add handwritten user file in datalog/ directory
      val userHandwritten = p.resolve("build/doc/diagrams/datalog/CustomUserDiagram.svg")
      Files.writeString(userHandwritten, "<svg>User Custom Diagram</svg>", StandardCharsets.UTF_8)

      // 4. Run flix doc (without --extended) and verify recursive cleanup of generated datalog SVG while preserving user file
      val (exitCode3, _, stderr3) = runCliSubprocess(Array("doc"), p)
      assert(exitCode3 == 0, s"Expected exit 0, got $exitCode3.\nSTDERR:\n$stderr3")
      assert(!Files.exists(datalogSvg), "Stale nested generated DatalogSchema.svg should be deleted recursively when --extended is omitted")
      assert(Files.exists(userHandwritten), "Handwritten user diagram CustomUserDiagram.svg without marker should be preserved")
    }
  }
}
