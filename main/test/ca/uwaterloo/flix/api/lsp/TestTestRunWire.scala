/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.tools.Tester
import ca.uwaterloo.flix.util.{Options, Result}
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Paths
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class TestTestRunWire extends AnyFunSuite {

  private val serverClass = Class.forName("ca.uwaterloo.flix.api.lsp.LspServer$FlixLanguageServer")

  test("test run and cancellation are registered requests") {
    val methods = ServiceEndpoints.getSupportedMethods(serverClass).keySet().asScala.toSet
    assert(methods.contains("flix/test/run"))
    assert(methods.contains("flix/test/cancel"))
  }

  test("test progress is a registered client notification") {
    val methods = ServiceEndpoints.getSupportedMethods(classOf[FlixLanguageClient]).keySet().asScala.toSet
    assert(methods.contains("flix/test/event"))
  }

  test("the LSP sink carries run identity, locations, outcomes, and cancellation") {
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(Paths.get("Wire.flix").toAbsolutePath,
      """mod Wire {
        |  @Test def passes(): Unit = ()
        |}
        |""".stripMargin, sctx)
    val compilation = flix.compile() match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(errors.map(_.summary).mkString(", "))
    }
    val client = new RecordingClient
    val cancelled = new AtomicBoolean(false)
    val sink = new LspTestEventSink("run-7", client, cancelled)

    val loaded = JvmLoader.load(compilation)
    val result = try Tester.run(Nil, loaded, sink, Tester.CancellationToken.Never, compilation.getCoverageSession)
    finally loaded.coverage.foreach(_.close())

    assert(result == Result.Ok(()))
    assert(client.events.map(_.event) == List("start", "before", "passed", "coverage", "finished"))
    assert(client.events.forall(_.runId == "run-7"))
    assert(client.events.head.tests.asScala.map(_.name).toList == List("Wire.passes"))
    assert(client.events(1).test.file.endsWith("Wire.flix"))
    assert(client.events(3).coverageJson.contains("\"formatVersion\":1"))
    assert(!client.events(3).partial)
    assert(client.events.last.protocolVersion == TestRunProtocol.Version)
    assert(!client.events.last.cancelled)
  }

  private class RecordingClient extends FlixLanguageClient {
    val events: mutable.Buffer[TestRunEvent] = mutable.Buffer.empty
    override def testEvent(params: TestRunEvent): Unit = events += params
    override def telemetryEvent(`object`: Any): Unit = ()
    override def publishDiagnostics(diagnostics: PublishDiagnosticsParams): Unit = ()
    override def showMessage(messageParams: MessageParams): Unit = ()
    override def showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture[MessageActionItem] =
      CompletableFuture.completedFuture(null)
    override def logMessage(message: MessageParams): Unit = ()
  }
}
