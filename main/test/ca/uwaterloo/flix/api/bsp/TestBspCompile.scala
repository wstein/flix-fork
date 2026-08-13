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
import ca.uwaterloo.flix.util.{FileOps, Options}
import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * Compiling a real project over a real connection.
  *
  * The interesting assertions are not that a compile works; they are about what a client is told
  * afterwards. A marker that never clears is the complaint build servers earn most often, and it is
  * invisible to a test that only ever compiles a broken project once.
  */
class TestBspCompile extends AnyFunSuite {

  private val Timeout: Long = 120

  test("a clean project compiles, and says so in one task start and one finish") {
    withSession { s =>
      val result = s.compile()

      assert(result.getStatusCode == StatusCode.OK, s"unexpected status: ${result.getStatusCode}")
      assert(s.diagnostics.isEmpty, s"a clean project produced diagnostics: ${s.diagnostics}")

      // Exactly one of each. A missing finish leaves a client's progress indicator turning forever
      // and reports no error anywhere, which is why the pairing is asserted rather than assumed.
      assert(s.taskStarts.sizeIs == 1, s"expected one task start, got ${s.taskStarts.size}")
      assert(s.taskFinishes.sizeIs == 1, s"expected one task finish, got ${s.taskFinishes.size}")
      assert(s.taskStarts.head.getTaskId.getId == s.taskFinishes.head.getTaskId.getId)
      assert(s.taskFinishes.head.getStatus == StatusCode.OK)
      assert(s.taskStarts.head.getDataKind == TaskStartDataKind.COMPILE_TASK)
      assert(s.taskFinishes.head.getDataKind == TaskFinishDataKind.COMPILE_REPORT)
    }
  }

  test("a compile writes class files, because a compile means class files") {
    withSession { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)

      // `buildTarget/compile` is a build, not a typecheck. Answering it from `check` alone would be
      // cheaper and would leave a client that then runs the program with nothing to run.
      val classes = s.project.resolve("build").resolve("development").resolve("class")
      assert(Files.isDirectory(classes), s"no class directory at $classes")
      assert(
        FileOps.getFilesWithExtIn(classes, "class", Int.MaxValue).nonEmpty,
        "a successful compile produced no class files")
    }
  }

  test("an error is reported at its own range, keyed by its stable code") {
    withSession { s =>
      s.write("src/Main.flix", "def main(): Unit \\ IO = println(undefinedFunction())\n")
      val result = s.compile()

      assert(result.getStatusCode == StatusCode.ERROR)
      val reports = s.diagnostics
      assert(reports.sizeIs >= 1, "a broken program produced no diagnostics")

      val forMain = reports.find(_.getTextDocument.getUri.endsWith("Main.flix"))
      assert(forMain.isDefined, s"nothing was reported for Main.flix: ${reports.map(_.getTextDocument.getUri)}")
      val diagnostic = forMain.get.getDiagnostics.asScala.head

      assert(diagnostic.getSeverity == DiagnosticSeverity.ERROR)
      assert(diagnostic.getSource == "flix")
      // The stable identifier, not the category. `lsp.Diagnostic.code` carries the *kind* --
      // "Resolution Error" -- which hundreds of distinct errors share and nothing can key on. Copying
      // the language server's field here is the obvious mistake, so it is asserted against.
      // `Diagnostic.code` is an `Either[String, Integer]` in this protocol version, matching LSP: a
      // server may key on a number instead. Ours is always the string form, which is the assertion.
      assert(diagnostic.getCode.isLeft, s"code is not a string: ${diagnostic.getCode}")
      assert(
        diagnostic.getCode.getLeft.matches("E\\d+"),
        s"code is '${diagnostic.getCode.getLeft}', which is not a stable error code")
      // Zero-based, which is the whole reason `CliContract` chose zero-based ranges -- they cross a
      // BSP hop untranslated. `lsp.Position` is one-indexed internally, so reading it without the
      // conversion reports every diagnostic one line low; the error here is on the first line.
      assert(diagnostic.getRange.getStart.getLine == 0, s"unexpected line: ${diagnostic.getRange.getStart.getLine}")
      assert(forMain.get.getReset, "a report must replace what the client holds, not add to it")
    }
  }

  test("fixing an error clears the marker it left") {
    withSession { s =>
      s.write("src/Main.flix", "def main(): Unit \\ IO = println(undefinedFunction())\n")
      assert(s.compile().getStatusCode == StatusCode.ERROR)
      val brokenUri = s.diagnostics.map(_.getTextDocument.getUri).find(_.endsWith("Main.flix"))
      assert(brokenUri.isDefined, "nothing was reported to clear")

      s.clear()
      s.write("src/Main.flix", "def main(): Unit \\ IO = println(\"fixed\")\n")
      assert(s.compile().getStatusCode == StatusCode.OK)

      // The assertion every naive implementation fails. A client shows what it was last told, so a
      // file that stops having errors needs an explicit empty report -- otherwise the marker sits
      // there until the editor is restarted.
      val cleared = s.diagnostics.find(_.getTextDocument.getUri == brokenUri.get)
      assert(cleared.isDefined, s"no report cleared ${brokenUri.get}")
      assert(cleared.get.getDiagnostics.isEmpty, "the report was not empty")
      assert(cleared.get.getReset, "a clearing report must replace what the client holds")
    }
  }

  test("a failed compile does not clear markers it could not speak for") {
    withSession { s =>
      // Two broken files. A compile that stops early has not shown the other one to be clean, and
      // clearing it would hide a real error until the next success.
      s.write("src/Main.flix", "def main(): Unit \\ IO = println(brokenOne())\n")
      s.write("src/Other.flix", "def other(): Unit = brokenTwo()\n")
      assert(s.compile().getStatusCode == StatusCode.ERROR)
      val first = s.diagnostics.map(_.getTextDocument.getUri).toSet
      assert(first.nonEmpty)

      s.clear()
      assert(s.compile().getStatusCode == StatusCode.ERROR)

      val cleared = s.diagnostics.filter(_.getDiagnostics.isEmpty).map(_.getTextDocument.getUri).toSet
      assert(cleared.isEmpty, s"a failed compile cleared $cleared, which it had not shown to be clean")
    }
  }

  test("a compile after a source is created sees it") {
    withSession { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)

      // A created file is the case timestamp polling misses: it updates paths it already knows, and a
      // new one is not among them. Nothing here would notice if source membership were not
      // reconciled.
      s.write("src/Added.flix", "def added(): Unit = brokenReference()\n")
      s.clear()
      assert(s.compile().getStatusCode == StatusCode.ERROR, "a newly created source was not compiled")
      assert(
        s.diagnostics.exists(_.getTextDocument.getUri.endsWith("Added.flix")),
        s"nothing was reported for the new file: ${s.diagnostics.map(_.getTextDocument.getUri)}")
    }
  }

  test("a compile after a source is deleted sees that too") {
    withSession { s =>
      s.write("src/Doomed.flix", "def doomed(): Unit = brokenReference()\n")
      assert(s.compile().getStatusCode == StatusCode.ERROR)

      Files.delete(s.project.resolve("src").resolve("Doomed.flix"))
      s.clear()
      val after = s.compile()
      assert(after.getStatusCode == StatusCode.OK,
        s"a deleted source was still compiled; diagnostics: " +
          s"${s.diagnostics.map(r => r.getTextDocument.getUri + " -> " + r.getDiagnostics.asScala.map(_.getMessage))}")
    }
  }

  test("a compile with nothing changed does no work") {
    withSession { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)
      val stamps = classStamps(s)
      assert(stamps.nonEmpty, "the first compile wrote no class files")

      // What an editor asks for most often. The answer is the same either way, so the output is the
      // only place the difference shows: a build that had nothing to do rewrites nothing.
      assert(s.compile().getStatusCode == StatusCode.OK)
      assert(classStamps(s) == stamps, "a compile with nothing to do rewrote the class files")
    }
  }

  test("a compile with nothing to do still clears the markers of a failure") {
    withSession { s =>
      val main = s.project.resolve("src").resolve("Main.flix")
      val original = Files.readString(main)
      assert(s.compile().getStatusCode == StatusCode.OK)

      // A failed compile writes no manifest, so restoring the sources byte for byte puts the project
      // back into the state the last *successful* build recorded -- and the next compile therefore has
      // nothing to do. The markers from the failure are still on the client's screen, and only this
      // path can take them off.
      s.write("src/Main.flix", "def main(): Unit = undefinedFunction()\n")
      assert(s.compile().getStatusCode == StatusCode.ERROR)
      assert(s.diagnostics.exists(_.getDiagnostics.asScala.nonEmpty), "the failure published nothing")

      s.clear()
      Files.writeString(main, original)
      assert(s.compile().getStatusCode == StatusCode.OK)

      val cleared = s.diagnostics.filter(_.getDiagnostics.asScala.isEmpty).map(_.getTextDocument.getUri)
      assert(cleared.exists(_.endsWith("Main.flix")),
        s"a compile with nothing to do left the error marker in place: ${s.diagnostics.map(_.getTextDocument.getUri)}")
    }
  }

  test("concurrent compiles that arrive before a build starts share it") {
    withSession { s =>
      // Broken on purpose: a diagnostic batch is published once per build that actually ran, which is
      // the only count of builds a client can observe. A clean project publishes nothing and would
      // make this untestable from the outside.
      s.write("src/Main.flix", "def main(): Unit = undefinedFunction()\n")

      val requests = List.fill(6)(s.compileFuture())
      val results = requests.map(_.get(Timeout, TimeUnit.SECONDS))

      // Every request is answered, and answered with the truth. A collapsed request is not a dropped
      // one: it reports the outcome of the build that superseded it.
      assert(results.forall(_.getStatusCode == StatusCode.ERROR), s"unexpected: ${results.map(_.getStatusCode)}")

      val builds = s.diagnostics.count(_.getDiagnostics.asScala.nonEmpty)
      assert(builds >= 1, "no build published anything")
      // Two, and the number is the design rather than an observation: the first request builds, the
      // second claims the next slot, and everything arriving while that one waits joins it. A three
      // would mean a build finished before the last request arrived, which a whole-program compile of
      // the standard library does not do; anything larger means the sharing stopped working.
      assert(builds <= 2, s"${requests.size} requests caused $builds builds")

      // And each request is still its own task. A client waits on the one it started, so collapsing
      // the builds must not collapse the progress reporting.
      assert(s.taskStarts.sizeIs == requests.size, s"expected a task per request, got ${s.taskStarts.size}")
      assert(s.taskFinishes.sizeIs == requests.size, s"expected a finish per request, got ${s.taskFinishes.size}")
    }
  }

  test("a compile issued after a build started gets its own build") {
    withSession { s =>
      s.write("src/Main.flix", "def main(): Unit = undefinedFunction()\n")
      assert(s.compile().getStatusCode == StatusCode.ERROR)
      s.clear()

      // The condition that makes sharing honest: only a build that has not begun may be joined. A
      // request that arrives while one is running cannot be answered from it, because that build may
      // have read the sources before the edit this request is about.
      assert(s.compile().getStatusCode == StatusCode.ERROR)
      assert(s.diagnostics.count(_.getDiagnostics.asScala.nonEmpty) >= 1,
        "a sequential compile published nothing, so it did not run its own build")
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  /** The class files of the project, with the time each was last written. */
  private def classStamps(s: Session): Map[String, Long] = {
    val classDir = s.project.resolve("build").resolve("development").resolve("class")
    FileOps.getFilesWithExtIn(classDir, "class", Int.MaxValue)
      .map(f => classDir.relativize(f.normalize()).toString -> f.toFile.lastModified())
      .toMap
  }

  /** A connected client, with everything the server sent it. */
  private class Session(val project: Path, val client: BuildServer, val target: BuildTargetIdentifier,
                        received: Received) {

    def compile(): CompileResult = compileFuture().get(Timeout, TimeUnit.SECONDS)

    /** Issues a compile without waiting for it, so that several can be in flight at once. */
    def compileFuture(): java.util.concurrent.CompletableFuture[CompileResult] =
      client.buildTargetCompile(new CompileParams(List(target).asJava))

    def diagnostics: List[PublishDiagnosticsParams] = received.diagnostics.asScala.toList

    def taskStarts: List[TaskStartParams] = received.taskStarts.asScala.toList

    def taskFinishes: List[TaskFinishParams] = received.taskFinishes.asScala.toList

    /** Forgets what was received, so the next compile can be asserted on by itself. */
    def clear(): Unit = received.clear()

    /** Writes `content` to `relative`, creating parents. */
    def write(relative: String, content: String): Unit = {
      val file = project.resolve(relative)
      Files.createDirectories(file.getParent)
      Files.writeString(file, content)
    }
  }

  private class Received {
    val diagnostics = new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
    val taskStarts = new ConcurrentLinkedQueue[TaskStartParams]()
    val taskFinishes = new ConcurrentLinkedQueue[TaskFinishParams]()

    def clear(): Unit = {
      diagnostics.clear()
      taskStarts.clear()
      taskFinishes.clear()
    }
  }

  /** Runs `f` against an initialised server serving a fresh project over a pair of pipes. */
  private def withSession(f: Session => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-compile-")
    Bootstrap.init(project)(System.out).unsafeGet

    val channel = BspTestChannel.open()

    // Cached, like the server's own pool: a handler can block for the length of a build, and a
    // joiner waits on the build it shares, so a small fixed pool can leave the owner queued behind
    // its own joiners.
    val executor = Executors.newCachedThreadPool((r: Runnable) => {
      val t = new Thread(r, "bsp-compile")
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
      val session = new Session(project, client, target, received)
      session.clear()
      f(session)
    } finally {
      // Streams before the executor: interrupting a listener blocked in a pipe read makes lsp4j log a
      // stack trace that buries the suite's own output.
      channel.close()
      executor.shutdownNow()
    }
  }

  /** Records the notifications the server sends. */
  private class RecordingClient(received: Received) extends BuildClient {
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = received.diagnostics.add(params)
    override def onBuildTaskStart(params: TaskStartParams): Unit = received.taskStarts.add(params)
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = received.taskFinishes.add(params)
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
    override def onRunPrintStdout(params: PrintParams): Unit = ()
    override def onRunPrintStderr(params: PrintParams): Unit = ()
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
  }
}
