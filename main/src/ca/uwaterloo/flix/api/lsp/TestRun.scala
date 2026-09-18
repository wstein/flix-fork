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

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.tools.Tester

import java.util.concurrent.atomic.AtomicBoolean

import scala.beans.BeanProperty

object TestRunProtocol {
  val Version: Int = 1
}

/** The client chooses `runId`, so events can be correlated even if one races the request reply. */
class TestRunParams {
  @BeanProperty var protocolVersion: Int = 0
  @BeanProperty var runId: String = _
  @BeanProperty var filters: java.util.List[String] = new java.util.ArrayList[String]()
}

class TestCancelParams {
  @BeanProperty var protocolVersion: Int = 0
  @BeanProperty var runId: String = _
}

class TestRunResult {
  @BeanProperty var protocolVersion: Int = TestRunProtocol.Version
  @BeanProperty var status: String = _
  @BeanProperty var runId: String = _
  @BeanProperty var reason: String = _
}

object TestRunResult {
  def accepted(runId: String): TestRunResult = result("accepted", runId, null)
  def cancelled(runId: String): TestRunResult = result("cancelled", runId, null)
  def rejected(runId: String, reason: String): TestRunResult = result("rejected", runId, reason)

  private def result(status: String, runId: String, reason: String): TestRunResult = {
    val r = new TestRunResult
    r.status = status
    r.runId = runId
    r.reason = reason
    r
  }
}

class TestRunTest {
  @BeanProperty var name: String = _
  @BeanProperty var skip: Boolean = false
  @BeanProperty var file: String = _
  @BeanProperty var startLine: Int = 0
  @BeanProperty var startCol: Int = 0
  @BeanProperty var endLine: Int = 0
  @BeanProperty var endCol: Int = 0
}

/** One streamed event from a `flix/test/run` request. */
class TestRunEvent {
  @BeanProperty var protocolVersion: Int = TestRunProtocol.Version
  @BeanProperty var runId: String = _
  @BeanProperty var event: String = _
  @BeanProperty var test: TestRunTest = _
  @BeanProperty var tests: java.util.List[TestRunTest] = new java.util.ArrayList[TestRunTest]()
  @BeanProperty var nanos: Long = 0L
  @BeanProperty var output: java.util.List[String] = new java.util.ArrayList[String]()
  @BeanProperty var diagnostics: java.util.List[String] = new java.util.ArrayList[String]()
  @BeanProperty var cancelled: Boolean = false
}

/** Custom notifications emitted by the Flix language server. */
trait FlixLanguageClient extends LanguageClient {
  @JsonNotification("flix/test/event")
  def testEvent(params: TestRunEvent): Unit
}

/** Maps the compiler's test events onto the versioned LSP notification contract. */
private[lsp] final class LspTestEventSink(runId: String, client: FlixLanguageClient, cancellation: AtomicBoolean) extends Tester.TestEventSink {
  override def start(tests: Vector[Tester.TestCase])(implicit flix: Flix): Unit = {
    val event = base("start")
    tests.foreach(t => event.tests.add(test(t.sym, t.skip)))
    client.testEvent(event)
  }

  override def accept(value: Tester.TestEvent)(implicit flix: Flix): Unit = {
    val event = value match {
      case Tester.TestEvent.Before(sym) => withTest("before", sym)
      case Tester.TestEvent.Success(sym, elapsed) =>
        val e = withTest("passed", sym); e.nanos = elapsed.d; e
      case Tester.TestEvent.Failure(sym, output, elapsed) =>
        val e = withTest("failed", sym); e.nanos = elapsed.d; output.foreach(e.output.add); e
      case Tester.TestEvent.Skip(sym) => withTest("skipped", sym)
      case Tester.TestEvent.Finished(elapsed) =>
        val e = base("finished"); e.nanos = elapsed.d; e.cancelled = cancellation.get(); e
    }
    client.testEvent(event)
  }

  def diagnostics(values: List[String]): Unit = {
    val event = base("diagnostics")
    values.foreach(event.diagnostics.add)
    client.testEvent(event)
  }

  private def withTest(kind: String, sym: Symbol.DefnSym): TestRunEvent = {
    val event = base(kind)
    event.test = test(sym, skip = false)
    event
  }

  private def base(kind: String): TestRunEvent = {
    val event = new TestRunEvent
    event.runId = runId
    event.event = kind
    event
  }

  private def test(sym: Symbol.DefnSym, skip: Boolean): TestRunTest = {
    val result = new TestRunTest
    result.name = sym.toString
    result.skip = skip
    val loc = sym.loc
    if (loc.isReal) {
      result.file = loc.source.name
      result.startLine = loc.startLine
      result.startCol = loc.startCol
      result.endLine = loc.endLine
      result.endCol = loc.endCol
    }
    result
  }
}
