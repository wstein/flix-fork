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

import ca.uwaterloo.flix.api.{Bootstrap, ProgramRunner}
import ca.uwaterloo.flix.util.{Build, Options}
import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * Running a compiled program, and the classpath that makes it possible.
  *
  * Gated behind `flix.testBsp` because every test here starts a second JVM. The reported classpath is
  * *executed* rather than inspected: a path list that looks right is exactly the artifact that rots,
  * and the failure it hides -- a program that cannot start -- is one a client discovers instead of a
  * test.
  */
@DoNotDiscover
class TestBspRun extends AnyFunSuite {

  private val Timeout: Long = 180

  test("the program runs and its output reaches the client") {
    withSession("""def main(): Unit \ IO = println("PROGRAM-RAN")""") { s =>
      val result = s.run()

      assert(result.getStatusCode == StatusCode.OK, s"unexpected status: ${result.getStatusCode}")
      // The program's own output, as log messages rather than diagnostics: it is not a problem with
      // the code, and a client shows the two in different places.
      assert(s.logs.exists(_.contains("PROGRAM-RAN")), s"the program's output never arrived: ${s.logs}")
    }
  }

  test("the program receives its arguments") {
    withSession(
      """use Sys.Env
        |
        |def main(): Unit \ { Env, IO } =
        |    Env.getArgs() |> List.forEach(a -> println("ARG:${a}"))
        |""".stripMargin) { s =>
      // Unverified until now, and about to become the default path for `flix run` as well: a forked
      // program gets its arguments on the command line, and the generated `Main` hands them to
      // `Global.setArgs` before calling into the program. If that link were broken, `Env.getArgs()`
      // would quietly return an empty list and every argument a user passed would vanish.
      val result = s.run(List("alpha", "beta gamma"))

      assert(result.getStatusCode == StatusCode.OK, s"unexpected status: ${result.getStatusCode}")
      assert(s.logs.exists(_.contains("ARG:alpha")), s"the first argument did not arrive: ${s.logs}")
      // With a space in it, so that a runner joining arguments into one string is caught rather than
      // passing by luck.
      assert(s.logs.exists(_.contains("ARG:beta gamma")), s"the second argument did not arrive: ${s.logs}")
    }
  }

  test("a program that fails reports a failure") {
    // The exit status is what a client reports, and a run that returned OK for a program that failed
    // would be worse than useless.
    withSession(
      """use Sys.Exit
        |
        |def main(): Unit \ { Exit, IO } =
        |    println("PROGRAM-RAN");
        |    Exit.exit(3)
        |""".stripMargin) { s =>
      assert(s.run().getStatusCode == StatusCode.ERROR, "a program that exited 3 was reported as OK")
      // Falsifiable only with this. A program that never compiled also reports `ERROR`, so the earlier
      // version of this test passed against a fixture whose syntax was wrong -- it proved that
      // something went wrong, not that the program ran and failed.
      assert(s.logs.exists(_.contains("PROGRAM-RAN")), s"the program never ran: ${s.logs}")
    }
  }

  test("a project with no main is refused, not silently ignored") {
    withSession("""def helper(): Int32 = 42""") { s =>
      val result = s.run()

      // `Bootstrap.run` returns silently in this case, which is defensible for a command and useless
      // to a client: "nothing happened" and "there was nothing to happen" need different messages.
      assert(result.getStatusCode == StatusCode.ERROR, "a project with no main reported success")
      assert(
        s.shown.exists(_.toLowerCase.contains("no main")),
        s"nothing explained why it did not run: ${s.shown}")
    }
  }

  test("a program that does not compile is not run") {
    withSession("""def main(): Unit \ IO = println(undefinedFunction())""") { s =>
      assert(s.run().getStatusCode == StatusCode.ERROR)
      assert(s.diagnostics.nonEmpty, "a run that failed to compile published no diagnostics")
      assert(!s.logs.exists(_.contains("PROGRAM-RAN")), "a program that did not compile was run anyway")
    }
  }

  test("a run after a compile that had nothing to do still finds main") {
    withSession("""def main(): Unit \ IO = println("PROGRAM-RAN")""") { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)
      // The second compile has nothing to do, so it produces no typed AST to ask about the entry
      // point -- the answer comes from what the recorded build wrote down. Getting that wrong reports
      // "there is no main function" for a program that has one, which is the failure this pins.
      assert(s.compile().getStatusCode == StatusCode.OK)

      val result = s.run()
      assert(result.getStatusCode == StatusCode.OK, s"unexpected status: ${result.getStatusCode}")
      assert(s.logs.exists(_.contains("PROGRAM-RAN")), s"the program did not run: ${s.logs}")
      assert(s.jvmRunEnvironment().getMainClasses.asScala.map(_.getClassName).toList == List(ProgramRunner.MainClass),
        "the main class was forgotten by a compile that had nothing to do")
    }
  }

  test("stopping a program stops what it started") {
    // Not through a Flix program, on purpose: the mechanism is what is under test, and a shell gives a
    // child whose lifetime can be observed exactly. `/bin/sh` is present on both platforms this suite
    // runs on; the assertion is skipped rather than faked anywhere else.
    assume(java.io.File.separatorChar == '/')

    val marker = Files.createTempFile("flix-child-", ".txt")
    val process = new ProcessBuilder(
      "/bin/sh", "-c", s"( while true; do echo alive >> '$marker'; sleep 0.1; done ) & wait")
      .start()
    try {
      // The child is writing, so the marker grows. That is what makes "still running" observable.
      val grew = waitUntil(30) {
        val first = Files.size(marker)
        Thread.sleep(300)
        Files.size(marker) > first
      }
      assert(grew, "the child never started writing, so this test would prove nothing")

      ProgramRunner.terminateTree(process, java.time.Duration.ofSeconds(5))

      // Killing only the root would leave the loop running and the file growing: the shell's child is
      // reparented rather than killed, which is exactly the leak a client sees as output arriving after
      // the task it belonged to reported that it had stopped.
      val size = Files.size(marker)
      Thread.sleep(1000)
      assert(Files.size(marker) == size, "a descendant survived and kept writing")
      assert(!process.isAlive, "the root process survived")
    } finally {
      process.destroyForcibly()
      Files.deleteIfExists(marker)
    }
  }

  test("a cancelled run stops its program") {
    withSession(
      """def main(): Unit \ IO = loop()
        |
        |def loop(): Unit \ IO = loop()
        |""".stripMargin) { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)

      // The compile above announced a task of its own, so this waits for one *more* -- the run's. A
      // cancellation that arrives before the handler is dispatched is dropped before the body runs,
      // which is correct and reports nothing, and would make this test about a different path.
      val before = s.taskStarts.size
      val running = s.runFuture()
      val waitingSince = System.nanoTime()
      while (s.taskStarts.sizeIs <= before &&
        java.time.Duration.ofNanos(System.nanoTime() - waitingSince).toSeconds < 60) {
        Thread.sleep(50)
      }
      assert(s.taskStarts.sizeIs > before, "the run never started")

      running.cancel(true)

      // The evidence, and the reason this is not a timing test: the program's timeout is ten minutes and
      // it holds the build lock while it runs, so a compile that answers at all proves the process was
      // killed rather than waited for.
      assert(s.compile().getStatusCode == StatusCode.OK, "the session was still held by a cancelled run")
      assert(s.taskFinishes.exists(_.getStatus == StatusCode.CANCELLED),
        s"no task reported the cancellation: ${s.taskFinishes.map(_.getStatus)}")
    }
  }

  test("a program that never ends is stopped by the timeout") {
    // Silent and endless, which is the case the supervision has to survive: draining output to
    // end-of-stream before consulting the clock is a timeout that can never fire, because a program
    // that prints nothing holds the reader forever.
    withSession(
      """def main(): Unit \ IO = loop()
        |
        |def loop(): Unit \ IO = loop()
        |""".stripMargin) { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)

      val started = System.nanoTime()
      val outcome = BspRunner.run(s.view, Build.Development, Nil, _ => (), java.time.Duration.ofSeconds(3))
      val elapsed = java.time.Duration.ofNanos(System.nanoTime() - started)

      assert(outcome.timedOut, "an endless program was not reported as timed out")
      assert(!outcome.isSuccess, "a program that was killed was reported as successful")
      // Generously bounded: the point is that it returned at all, on the timeout rather than never.
      assert(elapsed.toSeconds < 60, s"the timeout took ${elapsed.toSeconds}s to fire")
    }
  }

  test("output with no newline is reported, and does not hold the timeout open") {
    // The other half of the same defect: a line that is never terminated is a read that never returns.
    withSession(
      """use Sys.Console
        |
        |def main(): Unit \ { Console, IO } =
        |    Console.print("UNTERMINATED");
        |    loop()
        |
        |def loop(): Unit \ IO = loop()
        |""".stripMargin) { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)

      val lines = new ConcurrentLinkedQueue[String]()
      val outcome = BspRunner.run(s.view, Build.Development, Nil, lines.add(_), java.time.Duration.ofSeconds(3))

      assert(outcome.timedOut, "the program was not stopped")
      // Reported even though it was never terminated: a program killed mid-line has still said
      // something, and it is usually the thing a user is looking for.
      assert(lines.asScala.exists(_.contains("UNTERMINATED")), s"the partial line was dropped: ${lines.asScala.toList}")
    }
  }

  test("the reported classpath actually starts the program") {
    withSession("""def main(): Unit \ IO = println("PROGRAM-RAN")""") { s =>
      s.compile()
      val item = s.jvmRunEnvironment()

      val entries = item.getClasspath.asScala.toList
      assert(entries.nonEmpty, "the reported classpath is empty")
      val paths = entries.map(uri => BspUri.toPath(uri).getOrElse(fail(s"not a file uri: $uri")))
      assert(paths.forall(Files.exists(_)), s"the classpath names something that is not there: $paths")

      // The criterion, and the reason this suite forks: a list that looks right proves nothing. Run
      // it. Dropping the class directory from it must break this.
      val classpath = paths.map(_.toAbsolutePath.toString).mkString(File.pathSeparator)
      val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val process = new ProcessBuilder(java, "-cp", classpath, ProgramRunner.MainClass)
        .redirectErrorStream(true).start()
      val output = new String(process.getInputStream.readAllBytes())
      assert(process.waitFor(Timeout, TimeUnit.SECONDS))
      assert(process.exitValue() == 0, s"the reported classpath could not start the program:\n$output")
      assert(output.contains("PROGRAM-RAN"), s"unexpected output:\n$output")
    }
  }

  test("the compiler's own jar is not on the program's classpath") {
    withSession("""def main(): Unit \ IO = println("PROGRAM-RAN")""") { s =>
      s.compile()
      val entries = s.jvmRunEnvironment().getClasspath.asScala.toList

      // Not tidiness. `flix.jar` ships a *mock* `dev.flix.runtime.Global` whose `setArgs` throws
      // "should not be called on the mock class", so a program that finds the compiler ahead of its
      // own classes on the classpath dies before reaching `main`.
      assert(
        !entries.exists(e => e.endsWith(".jar") && e.contains("assembly")),
        s"the compiler's jar is on the program's classpath: $entries")
      assert(
        entries.exists(_.endsWith("/build/development/class/")),
        s"the class directory is missing from the classpath: $entries")
    }
  }

  test("the run environment names the main class only when there is one") {
    withSession("""def helper(): Int32 = 42""") { s =>
      s.compile()
      assert(
        s.jvmRunEnvironment().getMainClasses.asScala.isEmpty,
        "a project with no main named a main class")
    }

    withSession("""def main(): Unit \ IO = println("PROGRAM-RAN")""") { s =>
      s.compile()
      val mains = s.jvmRunEnvironment().getMainClasses.asScala.toList
      assert(mains.map(_.getClassName) == List(ProgramRunner.MainClass), s"unexpected main classes: $mains")
    }
  }

  test("the test environment is the run environment, because there is no test classpath") {
    withSession("""def main(): Unit \ IO = println("PROGRAM-RAN")""") { s =>
      s.compile()
      val run = s.jvmRunEnvironment().getClasspath.asScala.toList
      val test = s.jvmTestEnvironment().getClasspath.asScala.toList
      // `@Test` definitions are entry points compiled into the same output as everything else, so
      // reporting a different list would be inventing a distinction this compiler does not have.
      assert(run == test, s"the two environments disagree:\n$run\n$test")
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  /** Returns `true` if `condition` held within `seconds`, polling. */
  private def waitUntil(seconds: Long)(condition: => Boolean): Boolean = {
    val deadline = System.nanoTime() + java.time.Duration.ofSeconds(seconds).toNanos
    while (System.nanoTime() < deadline) {
      if (condition) return true
      Thread.sleep(100)
    }
    false
  }

  /**
    * The two interfaces a Flix build server implements, as one.
    *
    * `Launcher` proxies exactly the interface it is given, and the JVM endpoints live on
    * `JvmBuildServer` rather than on `BuildServer` -- so a proxy typed as the latter cannot reach
    * them. A real client combines them the same way.
    */
  private trait FlixBuildServerProxy extends BuildServer with JvmBuildServer

  private class Session(val project: Path, client: FlixBuildServerProxy, target: BuildTargetIdentifier, received: Received) {
    def compile(): CompileResult =
      client.buildTargetCompile(new CompileParams(List(target).asJava)).get(Timeout, TimeUnit.SECONDS)

    def run(arguments: List[String] = Nil): RunResult =
      runFuture(arguments).get(Timeout, TimeUnit.SECONDS)

    /** Issues a run without waiting for it, so that it can be cancelled. */
    def runFuture(arguments: List[String] = Nil): java.util.concurrent.CompletableFuture[RunResult] = {
      val params = new RunParams(target)
      if (arguments.nonEmpty) params.setArguments(arguments.asJava)
      client.buildTargetRun(params)
    }

    def taskStarts: List[TaskStartParams] = received.taskStarts.asScala.toList

    def taskFinishes: List[TaskFinishParams] = received.taskFinishes.asScala.toList

    def jvmRunEnvironment(): JvmEnvironmentItem =
      client.buildTargetJvmRunEnvironment(new JvmRunEnvironmentParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head

    def jvmTestEnvironment(): JvmEnvironmentItem =
      client.buildTargetJvmTestEnvironment(new JvmTestEnvironmentParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head

    /** A snapshot of the project, for a test that drives the runner directly. */
    def view: ca.uwaterloo.flix.api.ProjectView =
      Bootstrap.bootstrap(project, None)(ca.uwaterloo.flix.util.Formatter.NoFormatter, System.out).unsafeGet.view

    def logs: List[String] = received.logs.asScala.toList

    def shown: List[String] = received.shown.asScala.toList

    def diagnostics: List[PublishDiagnosticsParams] = received.diagnostics.asScala.toList
  }

  private class Received {
    val taskStarts = new ConcurrentLinkedQueue[TaskStartParams]()
    val taskFinishes = new ConcurrentLinkedQueue[TaskFinishParams]()
    val logs = new ConcurrentLinkedQueue[String]()
    val shown = new ConcurrentLinkedQueue[String]()
    val diagnostics = new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
  }

  /** Runs `f` against an initialised server whose project's `Main.flix` is `source`. */
  private def withSession(source: String)(f: Session => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-run-")
    Bootstrap.init(project)(System.out).unsafeGet
    Files.writeString(project.resolve("src").resolve("Main.flix"), source + "\n")

    val channel = BspTestChannel.open()

    // Cached, like the server's own pool: a handler can block for the length of a build, and a
    // joiner waits on the build it shares, so a small fixed pool can leave the owner queued behind
    // its own joiners.
    val executor = Executors.newCachedThreadPool((r: Runnable) => {
      val t = new Thread(r, "bsp-run")
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
      val launcher = new Launcher.Builder[FlixBuildServerProxy]()
        .setLocalService(new RecordingClient(received))
        .setRemoteInterface(classOf[FlixBuildServerProxy])
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
      f(new Session(project, client, target, received))
    } finally {
      channel.close()
      executor.shutdownNow()
    }
  }

  private class RecordingClient(received: Received) extends BuildClient {
    override def onBuildLogMessage(params: LogMessageParams): Unit = received.logs.add(params.getMessage)
    override def onBuildShowMessage(params: ShowMessageParams): Unit = received.shown.add(params.getMessage)
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = received.diagnostics.add(params)
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = received.taskStarts.add(params)
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = received.taskFinishes.add(params)
  }
}
