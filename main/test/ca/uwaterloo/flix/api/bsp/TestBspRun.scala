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
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import java.io.{File, PipedInputStream, PipedOutputStream}
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

  test("a program that fails reports a failure") {
    // `System.exit(3)` through Java interop: the exit status is what a client reports, and a run that
    // returned OK for a program that failed would be worse than useless.
    withSession(
      """import java.lang.System
        |def main(): Unit \ IO = unsafe System.exit(3)
        |""".stripMargin) { s =>
      assert(s.run().getStatusCode == StatusCode.ERROR, "a program that exited 3 was reported as OK")
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
      val process = new ProcessBuilder(java, "-cp", classpath, BspRunner.MainClass)
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
      assert(mains.map(_.getClassName) == List(BspRunner.MainClass), s"unexpected main classes: $mains")
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

    def run(): RunResult =
      client.buildTargetRun(new RunParams(target)).get(Timeout, TimeUnit.SECONDS)

    def jvmRunEnvironment(): JvmEnvironmentItem =
      client.buildTargetJvmRunEnvironment(new JvmRunEnvironmentParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head

    def jvmTestEnvironment(): JvmEnvironmentItem =
      client.buildTargetJvmTestEnvironment(new JvmTestEnvironmentParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head

    def logs: List[String] = received.logs.asScala.toList

    def shown: List[String] = received.shown.asScala.toList

    def diagnostics: List[PublishDiagnosticsParams] = received.diagnostics.asScala.toList
  }

  private class Received {
    val logs = new ConcurrentLinkedQueue[String]()
    val shown = new ConcurrentLinkedQueue[String]()
    val diagnostics = new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
  }

  /** Runs `f` against an initialised server whose project's `Main.flix` is `source`. */
  private def withSession(source: String)(f: Session => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-run-")
    Bootstrap.init(project)(System.out).unsafeGet
    Files.writeString(project.resolve("src").resolve("Main.flix"), source + "\n")

    val bufferSize = 256 * 1024
    val clientToServer = new PipedOutputStream()
    val serverToClient = new PipedOutputStream()
    val serverIn = new PipedInputStream(clientToServer, bufferSize)
    val clientIn = new PipedInputStream(serverToClient, bufferSize)

    val executor = Executors.newFixedThreadPool(6, (r: Runnable) => {
      val t = new Thread(r, "bsp-run")
      t.setDaemon(true)
      t
    })

    val serverThread = new Thread(
      () => BspServer.serve(Options.DefaultTest, project, new BspLogStream(), serverIn, serverToClient, executor),
      "bsp-server-under-test")
    serverThread.setDaemon(true)

    try {
      serverThread.start()

      val received = new Received
      val launcher = new Launcher.Builder[FlixBuildServerProxy]()
        .setLocalService(new RecordingClient(received))
        .setRemoteInterface(classOf[FlixBuildServerProxy])
        .setInput(clientIn).setOutput(clientToServer)
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
      List[AutoCloseable](clientToServer, serverToClient, serverIn, clientIn).foreach { c =>
        try c.close() catch { case _: Exception => () }
      }
      executor.shutdownNow()
    }
  }

  private class RecordingClient(received: Received) extends BuildClient {
    override def onBuildLogMessage(params: LogMessageParams): Unit = received.logs.add(params.getMessage)
    override def onBuildShowMessage(params: ShowMessageParams): Unit = received.shown.add(params.getMessage)
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = received.diagnostics.add(params)
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
  }
}
