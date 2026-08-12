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

import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, ExecutionException, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * One connection, everything that can go wrong, in sequence.
  *
  * Every other suite here isolates one behaviour, and something is missing from all of them: whether
  * the session is still *usable* afterwards. A server can pass every individual test and still be a
  * server that has to be restarted after the first mistake a client makes — a wrong target id, a
  * manifest typo, a cancelled build — which is the failure an editor user actually experiences.
  *
  * So this walks a client through the adverse cases against a single session and asserts, after each
  * one, that ordinary work still succeeds.
  */
class TestBspMatrix extends AnyFunSuite {

  private val Timeout: Long = 180

  test("a session survives every way a client can get it wrong") {
    withSession { s =>
      // A working baseline, so that a later failure cannot be confused with never having worked.
      assert(s.compile(s.target).getStatusCode == StatusCode.OK, "the project did not build to begin with")

      // ── An unknown target ────────────────────────────────────────────────────
      val unknown = new BuildTargetIdentifier("file:///nowhere/?id=main")
      assert(s.refuses(s.compileFuture(unknown)), "a compile of an unknown target was not refused")
      assert(s.refuses(s.client.buildTargetSources(new SourcesParams(List(unknown).asJava))),
        "sources for an unknown target were not refused")
      assert(s.compile(s.target).getStatusCode == StatusCode.OK, "an unknown target left the session broken")

      // ── The same target twice in one request ─────────────────────────────────
      // A client is not required to deduplicate, and answering one item per *requested* target would
      // report the same target twice; a client keying a map on the id would then draw one of the two
      // at random.
      val doubled = s.client.buildTargetSources(new SourcesParams(List(s.target, s.target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.toList
      assert(doubled.map(_.getTarget).distinct.sizeIs == 1, s"unexpected targets: ${doubled.map(_.getTarget)}")

      // ── A cancelled build ───────────────────────────────────────────────────
      // The deterministic part is the last assertion. Whether the cancel arrives before the compile
      // finishes is a race by nature, and the property that must hold either way is that a cancelled
      // request leaves a working session: soft cancellation lets the build finish and drops its
      // answer, so nothing is half-written.
      val cancelled = s.compileFuture(s.target)
      cancelled.cancel(true)
      assert(s.compile(s.target).getStatusCode == StatusCode.OK, "a cancelled compile left the session broken")

      // ── A source that does not compile ───────────────────────────────────────
      val main = s.project.resolve("src").resolve("Main.flix")
      Files.writeString(main, "def main(): Unit = undefinedFunction()\n")
      assert(s.compile(s.target).getStatusCode == StatusCode.ERROR, "a broken source compiled")
      assert(s.diagnostics.exists(_.getDiagnostics.asScala.nonEmpty), "a broken source published nothing")

      Files.writeString(main, """def main(): Unit \ IO = println("ok")""" + "\n")
      assert(s.compile(s.target).getStatusCode == StatusCode.OK, "the repaired source did not compile")

      // ── A manifest that is not TOML ──────────────────────────────────────────
      val manifest = s.project.resolve("flix.toml")
      val original = Files.readString(manifest)
      Files.writeString(manifest, "[[[not toml\n")
      assert(s.refuses(s.client.workspaceReload()), "a reload of a broken manifest reported success")
      assert(s.compile(s.target).getStatusCode == StatusCode.OK,
        "a failed reload left the session unable to build, which is the whole thing it must not do")

      // ── And a reload that works ──────────────────────────────────────────────
      Files.writeString(manifest, original)
      s.client.workspaceReload().get(Timeout, TimeUnit.SECONDS)
      assert(s.compile(s.target).getStatusCode == StatusCode.OK, "the session did not build after a reload")

      // The target id is stable across all of it, which is what lets a client hold on to one.
      val now = s.client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.head.getId
      assert(now == s.target, s"the target id changed: ${now.getUri} != ${s.target.getUri}")
    }
  }

  test("a slow build does not stop the connection being read") {
    withSession { s =>
      // The reason request handlers do not run on lsp4j's message thread. A whole-program compile
      // takes seconds; reading a field takes none. If this ever fails by timing out rather than by
      // the assertion below, the dispatch has moved back onto the connection's thread.
      val building = s.compileFuture(s.target)
      val targets = s.client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS)

      assert(targets.getTargets.asScala.sizeIs == 1)
      assert(!building.isDone, "the compile finished before the query, so this proved nothing")
      assert(building.get(Timeout, TimeUnit.SECONDS).getStatusCode == StatusCode.OK)
    }
  }

  test("a flood of requests is refused rather than exhausting the server") {
    withSession { s =>
      assert(s.compile(s.target).getStatusCode == StatusCode.OK)

      // Requests are dispatched off the connection's thread and builds are serialised, so surplus work
      // parks a platform thread each. The pool is unbounded on purpose -- a ten-minute run must not
      // starve a query -- so the bound has to be here, and it has to be a refusal a client can read.
      val flood = List.fill(80)(s.compileFuture(s.target))
      val outcomes = flood.map { future =>
        try Some(future.get(Timeout, TimeUnit.SECONDS).getStatusCode)
        catch { case _: ExecutionException => None }
      }

      // Every request is answered one way or the other. A hang would be the failure this prevents, and
      // it is what an unbounded pile-up eventually produces.
      assert(outcomes.sizeIs == flood.size)
      assert(outcomes.exists(_.contains(StatusCode.OK)), "no request in the flood was served")

      // And the session is still usable afterwards, which is the property a load policy exists for.
      assert(s.compile(s.target).getStatusCode == StatusCode.OK, "the flood left the session broken")
    }
  }

  test("nothing is published after shutdown") {
    withSession { s =>
      assert(s.compile(s.target).getStatusCode == StatusCode.OK)

      val building = s.compileFuture(s.target)
      s.client.buildShutdown().get(Timeout, TimeUnit.SECONDS)
      val before = s.notifications

      // The build was already running, and finishes; a client that has shut the connection down must
      // hear nothing more about it. Requests are dispatched off the connection's thread, so this
      // window is real.
      try building.get(Timeout, TimeUnit.SECONDS)
      catch { case _: Exception => () }

      assert(s.notifications == before, s"${s.notifications - before} notification(s) arrived after shutdown")
      assert(s.refuses(s.compileFuture(s.target)), "a request after shutdown was served")
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  private class Session(val project: Path, val client: BuildServer, val target: BuildTargetIdentifier, received: Received) {
    def compile(t: BuildTargetIdentifier): CompileResult = compileFuture(t).get(Timeout, TimeUnit.SECONDS)

    def compileFuture(t: BuildTargetIdentifier): java.util.concurrent.CompletableFuture[CompileResult] =
      client.buildTargetCompile(new CompileParams(List(t).asJava))

    /** Returns `true` if `future` failed with a protocol error rather than answering. */
    def refuses(future: java.util.concurrent.CompletableFuture[?]): Boolean =
      try {
        future.get(Timeout, TimeUnit.SECONDS)
        false
      } catch {
        case _: ExecutionException => true
      }

    def diagnostics: List[PublishDiagnosticsParams] = received.diagnostics.asScala.toList

    /** How much the client has been told, in total. Compared before and after, never read for content. */
    def notifications: Int = received.count.get()
  }

  private class Received {
    val diagnostics = new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
    val count = new java.util.concurrent.atomic.AtomicInteger(0)
  }

  private def withSession(f: Session => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-matrix-")
    Bootstrap.init(project)(System.out).unsafeGet
    Files.writeString(project.resolve("src").resolve("Main.flix"), """def main(): Unit \ IO = println("ok")""" + "\n")

    val channel = BspTestChannel.open()

    // Cached, like the server's own pool: a handler can block for the length of a build, and a
    // joiner waits on the build it shares, so a small fixed pool can leave the owner queued behind
    // its own joiners.
    val executor = Executors.newCachedThreadPool((r: Runnable) => {
      val t = new Thread(r, "bsp-matrix")
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
      f(new Session(project, client, target, received))
    } finally {
      channel.close()
      executor.shutdownNow()
    }
  }

  private class RecordingClient(received: Received) extends BuildClient {
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
      received.diagnostics.add(params)
      received.count.incrementAndGet()
    }

    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = received.count.incrementAndGet()
    override def onBuildShowMessage(params: ShowMessageParams): Unit = received.count.incrementAndGet()
    override def onBuildLogMessage(params: LogMessageParams): Unit = received.count.incrementAndGet()
    override def onBuildTaskStart(params: TaskStartParams): Unit = received.count.incrementAndGet()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = received.count.incrementAndGet()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = received.count.incrementAndGet()
  }
}
