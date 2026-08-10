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

import ca.uwaterloo.flix.api.Bootstrap
import ca.uwaterloo.flix.util.Options
import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * Running tests, and reporting each one.
  *
  * The point of interest is not that tests run — `flix test` already does that — but that a client is
  * told about each one individually, with a status it can render as a tree and a location it can click.
  * The events come from the same runner the console rendering watches, so the two cannot come to
  * disagree about whether a test passed.
  */
class TestBspTest extends AnyFunSuite {

  private val Timeout: Long = 180

  /** One of each outcome, which is what makes the counts in a report meaningful. */
  private val MixedTests: String =
    """use Assert.assertEq
      |
      |@Test
      |def testPasses(): Unit \ Assert = assertEq(expected = 2, 1 + 1)
      |
      |@Test
      |def testFails(): Unit \ Assert = assertEq(expected = 3, 1 + 1)
      |
      |@Test
      |@Skip
      |def testSkipped(): Unit \ Assert = assertEq(expected = 1, 1)
      |""".stripMargin

  test("a run reports every test, individually and in a tree") {
    withSession(MixedTests) { s =>
      val result = s.test()

      // One session, several properties: each assertion here is about the *same* run, and compiling
      // the standard library once per property would cost minutes to learn nothing more.

      // A run with a failing test is a failed run, even though the request itself was served.
      assert(result.getStatusCode == StatusCode.ERROR, s"unexpected status: ${result.getStatusCode}")

      val finishes = s.testFinishes
      assert(finishes.sizeIs == 3, s"expected three test results, got ${finishes.map(_.getDisplayName)}")

      val byName = finishes.map(f => f.getDisplayName.split('.').last -> f.getStatus).toMap
      assert(byName.get("testPasses").contains(TestStatus.PASSED), s"unexpected: $byName")
      assert(byName.get("testFails").contains(TestStatus.FAILED), s"unexpected: $byName")
      assert(byName.get("testSkipped").contains(TestStatus.SKIPPED), s"unexpected: $byName")

      val report = s.testReport.getOrElse(fail("no test report arrived"))
      assert(report.getPassed == 1, s"passed: ${report.getPassed}")
      assert(report.getFailed == 1, s"failed: ${report.getFailed}")
      assert(report.getSkipped == 1, s"skipped: ${report.getSkipped}")

      // Without this a client shows that a test failed and nothing about why, which is the half that
      // matters. It is the same text the console rendering would have printed.
      val failure = finishes.find(_.getStatus == TestStatus.FAILED).getOrElse(fail("no failure"))
      assert(failure.getMessage != null && failure.getMessage.nonEmpty,
        "the failure carried no explanation")

      // Every result is clickable, which is what `Symbol.DefnSym.loc` is for.
      for (finish <- finishes) {
        val location = finish.getLocation
        assert(location != null, s"${finish.getDisplayName} has no location")
        assert(location.getUri.endsWith("TestMain.flix"), s"unexpected uri: ${location.getUri}")
        // Zero-based, like every other position this server reports.
        assert(location.getRange.getStart.getLine >= 0)
      }

      // A client builds its tree from the parent links. Without them every test would appear as a
      // separate top-level task.
      val runTask = s.taskStarts.find(_.getDataKind == TaskStartDataKind.TEST_TASK)
        .getOrElse(fail("the run itself was never announced"))
      val parents = s.taskStarts.filter(_.getDataKind == TaskStartDataKind.TEST_START)
        .map(t => Option(t.getTaskId.getParents).map(_.asScala.toList).getOrElse(Nil))
      // Three, including the skipped one: the runner emits no `Before` for a skip, so a sink that
      // opened its pair there would send a finish with no start and leave a client with a row it
      // cannot place.
      assert(parents.sizeIs == 3, s"expected a task per test, got ${parents.size}")
      assert(parents.forall(_ == List(runTask.getTaskId.getId)), s"a test is nested wrongly: $parents")

      // The run's own duration, which only its terminal event carries.
      assert(report.getTime != null && report.getTime >= 0, s"unexpected time: ${report.getTime}")
    }
  }

  test("a filter selects which tests run") {
    withSession(MixedTests) { s =>
      val result = s.test(List("testPasses"))

      // Only the passing one was selected, so the run succeeds despite the project containing a
      // failing test. `Bootstrap.test` used to hard-code an empty filter list, which made this
      // impossible to ask for.
      assert(result.getStatusCode == StatusCode.OK, s"unexpected status: ${result.getStatusCode}")
      val names = s.testFinishes.map(_.getDisplayName.split('.').last)
      assert(names == List("testPasses"), s"unexpected tests ran: $names")
    }
  }

  test("a project that does not compile runs no tests") {
    withSession(MixedTests, brokenSource = true) { s =>
      val result = s.test()

      assert(result.getStatusCode == StatusCode.ERROR)
      assert(s.testFinishes.isEmpty, s"tests ran for a project that does not compile: ${s.testFinishes}")
      assert(s.diagnostics.nonEmpty, "nothing said why the tests did not run")
    }
  }

  test("a project with no tests succeeds and reports nothing") {
    withSession("") { s =>
      val result = s.test()

      // Vacuously true rather than an error: a project may legitimately have no tests yet, and
      // failing would make an editor's test button permanently red.
      assert(result.getStatusCode == StatusCode.OK, s"unexpected status: ${result.getStatusCode}")
      assert(s.testFinishes.isEmpty)
      assert(s.testReport.exists(r => r.getPassed == 0 && r.getFailed == 0))
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  private class Session(client: BuildServer, target: BuildTargetIdentifier, received: Received) {
    def test(filters: List[String] = Nil): TestResult = {
      val params = new TestParams(List(target).asJava)
      if (filters.nonEmpty) params.setArguments(filters.asJava)
      client.buildTargetTest(params).get(Timeout, TimeUnit.SECONDS)
    }

    def taskStarts: List[TaskStartParams] = received.taskStarts.asScala.toList

    /** The `test-finish` payloads, in the order they arrived. */
    def testFinishes: List[TestFinish] =
      received.taskFinishes.asScala.toList
        .filter(_.getDataKind == TaskFinishDataKind.TEST_FINISH)
        .flatMap(f => payloadOf(f.getData, classOf[TestFinish]))

    /** The one `test-report` payload, if the run produced it. */
    def testReport: Option[TestReport] =
      received.taskFinishes.asScala.toList
        .filter(_.getDataKind == TaskFinishDataKind.TEST_REPORT)
        .flatMap(f => payloadOf(f.getData, classOf[TestReport]))
        .lastOption

    def diagnostics: List[PublishDiagnosticsParams] = received.diagnostics.asScala.toList

    /**
      * Reads a notification's `data` as `T`.
      *
      * It arrives as a gson tree rather than as the class, because `data` is typed `Object` and gson
      * has nothing telling it what to build. A real client does the same conversion.
      */
    private def payloadOf[T](data: Object, clazz: Class[T]): Option[T] = data match {
      case element: com.google.gson.JsonElement => Option(new com.google.gson.Gson().fromJson(element, clazz))
      case _ => None
    }
  }

  private class Received {
    val taskStarts = new ConcurrentLinkedQueue[TaskStartParams]()
    val taskFinishes = new ConcurrentLinkedQueue[TaskFinishParams]()
    val diagnostics = new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
  }

  /** Runs `f` against an initialised server whose `test/TestMain.flix` is `tests`. */
  private def withSession(tests: String, brokenSource: Boolean = false)(f: Session => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-test-")
    Bootstrap.init(project)(System.out).unsafeGet
    Files.writeString(project.resolve("test").resolve("TestMain.flix"), tests)
    if (brokenSource) {
      Files.writeString(project.resolve("src").resolve("Main.flix"), "def main(): Unit = undefinedFunction()\n")
    }

    val channel = BspTestChannel.open()

    val executor = Executors.newFixedThreadPool(6, (r: Runnable) => {
      val t = new Thread(r, "bsp-test")
      t.setDaemon(true)
      t
    })

    val serverThread = new Thread(
      () => BspServer.serve(Options.DefaultTest, project, new BspLogStream(), channel.serverIn, channel.serverOut, executor),
      "bsp-server-under-test")
    serverThread.setDaemon(true)

    try {
      serverThread.start()

      val received = new Received
      val launcher = new Launcher.Builder[BuildServer]()
        .setLocalService(new RecordingClient(received))
        .setRemoteInterface(classOf[BuildServer])
        .setInput(channel.clientIn).setOutput(channel.clientOut)
        .setExecutorService(executor)
        .create()
      launcher.startListening()

      val client = launcher.getRemoteProxy
      client.buildInitialize(new InitializeBuildParams(
        "test-client", "1.0", Bsp4j.PROTOCOL_VERSION, BspUri.ofDirectory(project),
        new BuildClientCapabilities(List("flix").asJava))).get(Timeout, TimeUnit.SECONDS)
      client.onBuildInitialized()

      val target = client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.head.getId
      f(new Session(client, target, received))
    } finally {
      channel.close()
      executor.shutdownNow()
    }
  }

  private class RecordingClient(received: Received) extends BuildClient {
    override def onBuildTaskStart(params: TaskStartParams): Unit = received.taskStarts.add(params)
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = received.taskFinishes.add(params)
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = received.diagnostics.add(params)
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
  }
}
