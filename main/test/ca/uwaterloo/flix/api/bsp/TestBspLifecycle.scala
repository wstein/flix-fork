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
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.{Launcher, ResponseErrorException}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.util.concurrent.{ExecutionException, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * The lifecycle, driven over a real connection.
  *
  * This is the part of BSP a server gets wrong invisibly. A request answered before
  * `build/initialize` is answered about a project nobody has loaded; a request answered after
  * `build/shutdown` is answered by a server that promised to stop. Both look like they work, and both
  * leave a client acting on a reply it should never have had. So each transition is asserted, and by
  * its error *code*, because that is what a client branches on -- `ServerNotInitialized` is something
  * it retries, and everything else is not.
  *
  * A real `Launcher` pair over pipes, not a direct call on the server object, because the codes have
  * to survive being turned into a JSON-RPC error response and read back.
  */
class TestBspLifecycle extends AnyFunSuite {

  private val Timeout: Long = 60

  test("a request before initialize is refused as not initialized") {
    withProject { project =>
      withServer(project) { client =>
        val code = errorCodeOf(client.workspaceBuildTargets())
        // -32002. A client distinguishes "not ready" from "broken" by this number and retries the
        // first, so answering with anything else -- including an empty target list -- is a different
        // instruction to the client.
        assert(code.contains(ResponseErrorCode.ServerNotInitialized.getValue), s"got $code")
      }
    }
  }

  test("initialize answers with what this compiler is and what it can do") {
    withProject { project =>
      withServer(project) { client =>
        val result = initialize(client, project)
        assert(result.getDisplayName == BspSession.ServerName)
        assert(result.getBspVersion == Bsp4j.PROTOCOL_VERSION)
        assert(result.getVersion == ca.uwaterloo.flix.api.Version.CurrentVersion.toString)
        assert(result.getCapabilities != null)
      }
    }
  }

  test("initialize refuses a project this server was not started for") {
    withProject { project =>
      withServer(project) { client =>
        val elsewhere = Files.createTempDirectory("flix-other-")
        val code = errorCodeOf(client.buildInitialize(initializeParams(elsewhere)))
        // Serving it would answer about a project whose dependencies were never resolved. Silently
        // substituting one project for another is the failure mode worth refusing.
        assert(code.contains(ResponseErrorCode.InvalidParams.getValue), s"got $code")
      }
    }
  }

  test("a rootUri that spells the project differently is still the project") {
    withProject { project =>
      // A link to the project, which is how an editor routinely names a directory: on macOS every
      // temporary directory is under `/var`, itself a link to `/private/var`, so the path the JVM
      // reports and the path the user opened are different strings for one directory. Comparing
      // normalised paths refuses a correct client here, because `normalize` is textual and cannot
      // see a link. This was a real defect, found by running the server as a process.
      val link = Files.createTempDirectory("flix-bsp-link-").resolve("project")
      Files.createSymbolicLink(link, project)

      withServer(project) { client =>
        val params = initializeParams(project)
        params.setRootUri(BspUri.ofDirectory(link))
        val result = client.buildInitialize(params).get(Timeout, TimeUnit.SECONDS)
        assert(result.getDisplayName == BspSession.ServerName)
      }
    }
  }

  test("initialize twice is refused rather than quietly reloading") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        val code = errorCodeOf(client.buildInitialize(initializeParams(project)))
        assert(code.contains(ResponseErrorCode.InvalidRequest.getValue), s"got $code")
      }
    }
  }

  test("the project has one target, tagged as a library and marked as a jvm target") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        val targets = client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.toList
        assert(targets.lengthIs == 1, s"expected one target, got ${targets.map(_.getId.getUri)}")

        val target = targets.head
        assert(target.getLanguageIds.asScala.toList == List("flix"))
        // Not `test`: a client turns that into a test source root, and with one target that would put
        // every source in the project under test scope.
        assert(target.getTags.asScala.toList == List(BuildTargetTag.LIBRARY))
        // How a client knows to pick a JDK at all.
        assert(target.getDataKind == BuildTargetDataKind.JVM)
        // `file:///`, not `file:/`. A client that computes the id itself compares strings.
        assert(target.getId.getUri.startsWith("file:///"), s"unexpected id: ${target.getId.getUri}")
        assert(target.getId.getUri.endsWith("?id=main"), s"unexpected id: ${target.getId.getUri}")
      }
    }
  }

  test("a client that does not speak flix is told about no targets") {
    withProject { project =>
      withServer(project) { client =>
        val params = initializeParams(project)
        params.setCapabilities(new BuildClientCapabilities(List("scala").asJava))
        client.buildInitialize(params).get(Timeout, TimeUnit.SECONDS)
        client.onBuildInitialized()

        // Required rather than polite: a server must not answer with targets for a language the
        // client did not advertise.
        val targets = client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets
        assert(targets.isEmpty, s"a scala-only client was offered $targets")
      }
    }
  }

  test("sources are file uris under the project's own roots") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        val target = client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.head.getId

        val item = client.buildTargetSources(new SourcesParams(List(target).asJava))
          .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head

        val names = item.getSources.asScala.map(s => Path.of(new java.net.URI(s.getUri)).getFileName.toString).toSet
        assert(names == Set("Main.flix", "TestMain.flix"), s"unexpected sources: $names")
        assert(item.getSources.asScala.forall(_.getKind == SourceItemKind.FILE))
        // Roots, so a client knows where to watch for a file that does not exist yet.
        val roots = item.getRoots.asScala.map(_.stripSuffix("/")).map(u => Path.of(new java.net.URI(u)).getFileName.toString).toSet
        assert(roots == Set("src", "test"), s"unexpected roots: $roots")
      }
    }
  }

  test("an unknown target is refused rather than answered with nothing") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        val stale = new BuildTargetIdentifier("file:///nowhere/?id=main")
        val code = errorCodeOf(client.buildTargetSources(new SourcesParams(List(stale).asJava)))
        // An empty answer would tell a client with a stale cache that the project is empty.
        assert(code.contains(ResponseErrorCode.InvalidParams.getValue), s"got $code")
      }
    }
  }

  test("a request after shutdown is refused") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        client.buildShutdown().get(Timeout, TimeUnit.SECONDS)

        val code = errorCodeOf(client.workspaceBuildTargets())
        assert(code.contains(ResponseErrorCode.InvalidRequest.getValue), s"got $code")
      }
    }
  }

  test("a request before the client acknowledges initialize is refused") {
    withProject { project =>
      withServer(project) { client =>
        client.buildInitialize(initializeParams(project)).get(Timeout, TimeUnit.SECONDS)

        // The specification does not allow a client to send anything between the reply to
        // `build/initialize` and its own `build/initialized`. Serving here anyway would accept a
        // sequence no client may send, which is how a client's bug reaches production undetected.
        val code = errorCodeOf(client.workspaceBuildTargets())
        assert(code.contains(ResponseErrorCode.ServerNotInitialized.getValue), s"got $code")

        // And the same request is served once the handshake is finished.
        client.onBuildInitialized()
        assert(client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.sizeIs == 1)
      }
    }
  }

  test("a duplicate acknowledgement is reported and changes nothing") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        client.onBuildInitialized()

        // A notification has no reply to put an error in, so the only honest thing is to say so on the
        // one channel it has -- and to leave the state alone, so that a stray acknowledgement cannot
        // resurrect a session that has been shut down.
        client.buildShutdown().get(Timeout, TimeUnit.SECONDS)
        client.onBuildInitialized()
        val code = errorCodeOf(client.workspaceBuildTargets())
        assert(code.contains(ResponseErrorCode.InvalidRequest.getValue),
          s"an acknowledgement after shutdown revived the session: $code")
      }
    }
  }

  test("a client that advertises no language at all is told about no targets") {
    withProject { project =>
      withServer(project) { client =>
        // Empty is *no* languages, not all of them: absent from an empty list is every language. It
        // reads like a client saying "whatever you have", and the specification says the opposite.
        val params = new InitializeBuildParams(
          "test-client", "1.0", Bsp4j.PROTOCOL_VERSION, BspUri.ofDirectory(project),
          new BuildClientCapabilities(List.empty[String].asJava))
        client.buildInitialize(params).get(Timeout, TimeUnit.SECONDS)
        client.onBuildInitialized()

        val targets = client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.toList
        assert(targets.isEmpty, s"a client that advertised nothing was given $targets")
      }
    }
  }

  test("a request the server does not implement is refused, not answered emptily") {
    withProject { project =>
      withServer(project) { client =>
        initialize(client, project)
        val target = client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.head.getId

        // One request is left, and unlike the others that were here it is not waiting for a phase:
        // Flix has no debug adapter, so there is no address `debugSessionStart` could return. A
        // refusal is the honest answer -- an empty result is indistinguishable from a real one, and a
        // client would draw a conclusion from it.
        val code = errorCodeOf(client.debugSessionStart(new DebugSessionParams(List(target).asJava)))

        assert(
          code.contains(ResponseErrorCode.MethodNotFound.getValue),
          s"expected debugSessionStart to be refused, got $code")
      }
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  /** Runs `f` with a freshly initialised Flix project in a temporary directory. */
  private def withProject(f: Path => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-")
    Bootstrap.init(project)(System.out).unsafeGet
    f(project)
  }

  private def initializeParams(project: Path): InitializeBuildParams =
    new InitializeBuildParams(
      "test-client", "1.0", Bsp4j.PROTOCOL_VERSION, BspUri.ofDirectory(project),
      new BuildClientCapabilities(List("flix").asJava))

  /**
    * Completes the whole handshake: the request, and the acknowledgement a client owes afterwards.
    *
    * Both halves, because the server serves requests only once the acknowledgement arrives -- which is
    * what the specification requires of a client and what this helper used to skip.
    */
  private def initialize(client: BuildServer, project: Path): InitializeBuildResult = {
    val result = client.buildInitialize(initializeParams(project)).get(Timeout, TimeUnit.SECONDS)
    client.onBuildInitialized()
    result
  }

  /** Returns the JSON-RPC error code a failed request carried, or `None` if it succeeded. */
  private def errorCodeOf(future: java.util.concurrent.CompletableFuture[?]): Option[Int] =
    try {
      future.get(Timeout, TimeUnit.SECONDS)
      None
    } catch {
      case e: ExecutionException => e.getCause match {
        case r: ResponseErrorException => Some(r.getResponseError.getCode)
        case other => fail(s"expected a protocol error, got $other")
      }
    }

  /**
    * Runs `f` against a real server serving `project` over a pair of pipes.
    *
    * Deliberately goes through `BspServer.serve` rather than constructing the pieces, so the wiring
    * under test is the wiring the command uses. `serve` blocks, so it runs on its own thread.
    */
  private def withServer(project: Path)(f: BuildServer => Unit): Unit = {
    val channel = BspTestChannel.open()

    // Cached, like the server's own pool: a handler can block for the length of a build, and a
    // joiner waits on the build it shares, so a small fixed pool can leave the owner queued behind
    // its own joiners.
    val executor = Executors.newCachedThreadPool((r: Runnable) => {
      val t = new Thread(r, "bsp-lifecycle")
      t.setDaemon(true)
      t
    })

    val serverThread = new Thread(
      () => BspServer.serve(Options.DefaultTest, project, new BspLogStream(), channel.serverIn, channel.serverOut, executor),
      "bsp-server-under-test")
    serverThread.setDaemon(true)

    try {
      serverThread.start()

      val clientLauncher = new Launcher.Builder[BuildServer]()
        .setLocalService(new SilentClient)
        .setRemoteInterface(classOf[BuildServer])
        .setInput(channel.clientIn).setOutput(channel.clientOut)
        .setExecutorService(executor)
        .create()
      clientLauncher.startListening()

      f(clientLauncher.getRemoteProxy)
    } finally {
      // Streams before the executor: interrupting a listener blocked in a pipe read makes lsp4j log
      // an `InterruptedIOException` with a stack trace, which buries the suite's own output.
      channel.close()
      executor.shutdownNow()
    }
  }

  /** A client that accepts every notification and records none: these tests assert on responses. */
  private class SilentClient extends BuildClient {
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
  }
}
