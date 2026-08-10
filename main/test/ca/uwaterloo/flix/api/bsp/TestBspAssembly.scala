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
package ca.uwaterloo.flix.api.bsp

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
  * Holds the reachability contract that `build.mill` states in two places: that bsp4j's
  * parameter rendering reaches xtext's `ToStringBuilder`, that `ToStringBuilder` reaches
  * `com.google.common.base.Strings`, and that everything else in guava is unreachable and
  * therefore excluded from the jar.
  *
  * Nothing in the ordinary suite can check this. Those tests run against the full classpath, where
  * the excluded classes are all still present; only the assembled jar can answer, and only from a
  * JVM that holds nothing else. So the code here is fed to `jshell` with `--class-path` naming the
  * jar and nothing besides -- not a compiled helper on a second classpath entry, because a second
  * entry is exactly the hole this is meant to close.
  *
  * The path it drives is the one that fails in production rather than a convenient stand-in:
  * lsp4j renders a request's params when it logs a failed request, so a `RequestMessage` carrying a
  * BSP object is printed. When this contract breaks, the symptom is a `NoClassDefFoundError` raised
  * while reporting some other error, which is the worst place to discover it.
  */
class TestBspAssembly extends AnyFunSuite {

  /** Where `./mill flix.assembly` leaves the jar, relative to the repository root. */
  private val AssemblyJar: Path = Paths.get("out", "flix", "assembly.dest", "out.jar")

  /** The only guava package the contract says is reachable. */
  private val ReachableGuava: String = "com/google/common/base/"

  test("the assembled jar renders protocol objects with nothing but itself on the classpath") {
    val jar = requireAssembly()
    val jshell = Paths.get(System.getProperty("java.home"), "bin", "jshell")
    if (!Files.isExecutable(jshell)) cancel(s"no jshell at $jshell")

    // Every statement is chosen to walk one more step of the contract, and each throws on
    // failure so that jshell reports it and the marker never prints.
    val snippet =
      """import ch.epfl.scala.bsp4j.*;
        |import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
        |
        |var target = new BuildTargetIdentifier("file:///tmp/p/?id=main");
        |if (!target.toString().contains("file:///tmp/p/?id=main")) throw new AssertionError("toString lost the uri");
        |
        |var d = new Diagnostic(new Range(new Position(1, 2), new Position(3, 4)), "example");
        |d.setSeverity(DiagnosticSeverity.ERROR);
        |var params = new PublishDiagnosticsParams(
        |  new TextDocumentIdentifier("file:///tmp/p/src/Main.flix"), target, java.util.List.of(d), true);
        |if (params.toString().isEmpty()) throw new AssertionError("params do not print");
        |
        |// The production path: this is what lsp4j does when it logs a request it could not serve.
        |var message = new RequestMessage();
        |message.setId("1");
        |message.setMethod("buildTarget/compile");
        |message.setParams(new CompileParams(java.util.List.of(target)));
        |if (!message.toString().contains("buildTarget/compile")) throw new AssertionError("message does not print");
        |
        |if (StatusCode.OK.getValue() != 1) throw new AssertionError("StatusCode.OK is not 1");
        |
        |System.out.println("BSP-ASSEMBLY-OK");
        |/exit
        |""".stripMargin

    val process = new ProcessBuilder(
      jshell.toString, "--class-path", jar.toAbsolutePath.toString, "--no-startup", "-s", "-")
      .redirectErrorStream(true)
      .start()

    Using.resource(process.getOutputStream)(_.write(snippet.getBytes("UTF-8")))
    val output = new String(process.getInputStream.readAllBytes())
    assert(process.waitFor(120, TimeUnit.SECONDS), "jshell did not finish")

    assert(
      !output.contains("NoClassDefFoundError"),
      s"""a class the protocol reaches is missing from the assembled jar.
         |assemblyRules excluded something reachable -- see the guava rule and the bsp4j
         |dependency comment in build.mill:
         |$output""".stripMargin)
    assert(output.contains("BSP-ASSEMBLY-OK"), s"the jar could not render a protocol object:\n$output")
  }

  test("the assembled jar keeps only the guava the contract reaches") {
    val jar = requireAssembly()

    val guava = Using.resource(new ZipFile(jar.toFile)) { zip =>
      zip.entries().asScala
        .filterNot(_.isDirectory) // a directory entry carries no class, and `com/google/common/` survives as one
        .map(_.getName)
        .filter(_.startsWith("com/google/common/"))
        .toList
    }

    // Both directions matter. Something outside `base/` means the exclusion regressed and the jar
    // is carrying megabytes it never loads; nothing at all means it went too far, and the test
    // above would then be the only thing standing between that and a broken log line.
    val unreachable = guava.filterNot(_.startsWith(ReachableGuava))
    assert(
      unreachable.isEmpty,
      s"${unreachable.length} guava entries outside $ReachableGuava are in the jar: ${unreachable.take(5)}")
    assert(
      guava.exists(_ == s"${ReachableGuava}Strings.class"),
      s"$ReachableGuava Strings.class is missing, which is the one guava class the protocol reaches")
  }

  /** Returns the assembled jar, or cancels: it is built by a separate task, not by the suite. */
  private def requireAssembly(): Path = {
    if (!Files.isRegularFile(AssemblyJar)) {
      cancel(s"no assembled jar at $AssemblyJar -- run './mill flix.assembly' first")
    }
    AssemblyJar
  }
}
