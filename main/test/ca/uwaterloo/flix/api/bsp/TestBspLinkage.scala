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

import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util
import java.util.concurrent.{CompletableFuture, Executors, LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * Holds the one claim `build.mill` makes about `bsp4j`: that it works against the `lsp4j.jsonrpc`
  * this compiler runs.
  *
  * `bsp4j` 2.1.1 asks for jsonrpc 0.20.1 and gets 1.0.0, because the language server needs 1.0.0
  * and only one version can win. Reading the two jars says the types it references still exist;
  * that is necessary and nowhere near sufficient. What actually has to hold is that a real
  * `Launcher` reflects over `bsp4j`'s annotated interfaces, that gson serialises `bsp4j`'s models
  * through jsonrpc's own `MessageJsonHandler`, and that the objects survive being printed. Each of
  * those is a separate way for a version skew to fail, and every one of them would fail at runtime
  * in a server, not at compile time here.
  *
  * So this suite talks to itself over a pair of pipes rather than asserting on types. Nothing here
  * touches the Flix compiler; it is a linkage gate, and it runs in the ordinary suite because it is
  * fast and because a dependency bump is exactly when someone needs to be told.
  */
class TestBspLinkage extends AnyFunSuite {

  /** How long to wait for a round trip before calling it a failure rather than hanging the suite. */
  private val Timeout: Long = 30

  test("a real launcher completes an initialize round trip") {
    withConnection { connection =>
      val client = connection.client
      val params = new InitializeBuildParams(
        "test-client", "1.2.3", Bsp4j.PROTOCOL_VERSION, "file:///tmp/project",
        new BuildClientCapabilities(List("flix").asJava))

      val result = client.buildInitialize(params).get(Timeout, TimeUnit.SECONDS)

      // Every field crossed the wire and came back: the request was dispatched by reflection over
      // `@JsonRequest` on `BuildServer`, and both the parameter and result objects went through
      // gson in jsonrpc 1.0.0.
      assert(result.getDisplayName == "flix-under-test")
      assert(result.getBspVersion == Bsp4j.PROTOCOL_VERSION)
      assert(result.getCapabilities != null)
      assert(result.getCapabilities.getCompileProvider.getLanguageIds.asScala.toList == List("flix"))
    }
  }

  test("an int-valued enum is written to the wire as its number") {
    // `StatusCode` and `DiagnosticSeverity` carry an `int`, and the protocol is defined in terms of
    // that number, so gson has to route them through jsonrpc's `EnumTypeAdapter` rather than writing
    // the constant's name.
    //
    // Asserting on the round trip cannot see whether it does. Both ends here share one jsonrpc, so
    // if both fell back to name-based serialisation the value would still arrive as `StatusCode.OK`
    // and this would pass while the wire was wrong -- and a real client, on its own version, would
    // read nothing. So it reads the bytes that actually crossed.
    withConnection { connection =>
      val ok = connection.client.buildTargetCompile(new CompileParams(List(target).asJava)).get(Timeout, TimeUnit.SECONDS)
      assert(ok.getStatusCode == StatusCode.OK)

      val frames = connection.wire
      assert(frames.contains("\"statusCode\":1"), s"statusCode did not cross as a number:\n$frames")
      assert(!frames.contains("\"statusCode\":\"OK\""), s"statusCode crossed as a name:\n$frames")
      // The same question one level down, on a field of a nested object.
      assert(frames.contains("\"severity\":1"), s"severity did not cross as a number:\n$frames")
    }
  }

  test("a diagnostic notification arrives with every field intact") {
    // The payload this server exists to deliver, sent the other way down the same connection: a
    // notification dispatched by reflection over `@JsonNotification` on `BuildClient`, carrying a
    // nested object graph and an int-valued enum.
    withConnection { connection =>
      val client = connection.client
      val received = connection.received
      client.buildTargetCompile(new CompileParams(List(target).asJava)).get(Timeout, TimeUnit.SECONDS)

      val params = received.poll(Timeout, TimeUnit.SECONDS)
      assert(params != null, "no diagnostic notification arrived")
      assert(params.getTextDocument.getUri == "file:///tmp/project/src/Main.flix")
      assert(params.getBuildTarget.getUri == target.getUri)
      assert(params.getReset)

      val diagnostics = params.getDiagnostics.asScala.toList
      assert(diagnostics.lengthIs == 1)
      val d = diagnostics.head
      assert(d.getSeverity == DiagnosticSeverity.ERROR)
      // An `Either[String, Integer]` in this protocol version, matching LSP, and gson has to carry the
      // choice as well as the value: a round trip that lost which side it was would put a number where a
      // client expects a code.
      assert(d.getCode.isLeft, s"the code arrived as ${d.getCode}")
      assert(d.getCode.getLeft == "E2136")
      assert(d.getSource == "flix")
      assert(d.getMessage == "an example message")
      // Zero-based, and unchanged by the crossing. `CliContract` chose zero-based ranges precisely
      // so they pass through a BSP hop untranslated, and this is where that is checked.
      assert(d.getRange.getStart.getLine == 3)
      assert(d.getRange.getStart.getCharacter == 7)
      assert(d.getRange.getEnd.getLine == 3)
      assert(d.getRange.getEnd.getCharacter == 12)
    }
  }

  test("printing a protocol object does not throw") {
    // `bsp4j`'s generated `toString` calls xtext's `ToStringBuilder`, which arrives only with the
    // `lsp4j.generator` dependency that `build.mill` excludes -- so `build.mill` adds
    // `xbase.lib` back for this one class. Without it every one of these throws
    // `NoClassDefFoundError`, and the first place anyone would find out is a log line in a running
    // server. Cheap to assert, invisible otherwise.
    assert(new BuildTargetIdentifier("file:///x/?id=main").toString.contains("file:///x/?id=main"))
    assert(new CompileResult(StatusCode.OK).toString.nonEmpty)
    assert(new Position(1, 2).toString.nonEmpty)
    assert(diagnosticParams.toString.nonEmpty)
  }

  /** The single target these fixtures talk about. */
  private def target: BuildTargetIdentifier = new BuildTargetIdentifier("file:///tmp/project/?id=main")

  /** A populated notification payload, shaped like the one a real compile would send. */
  private def diagnosticParams: PublishDiagnosticsParams = {
    val range = new Range(new Position(3, 7), new Position(3, 12))
    val d = new Diagnostic(range, "an example message")
    d.setSeverity(DiagnosticSeverity.ERROR)
    d.setCode("E2136")
    d.setSource("flix")
    new PublishDiagnosticsParams(
      new TextDocumentIdentifier("file:///tmp/project/src/Main.flix"), target, List(d).asJava, true)
  }

  /**
    * A client proxy, the diagnostics the client was sent, and the bytes the server wrote.
    *
    * `wire` is the only way to check the *format* rather than the round trip: a symmetric mistake
    * made by both ends of one shared library version is invisible from the objects alone.
    */
  private case class Connection(client: BuildServer,
                                received: LinkedBlockingQueue[PublishDiagnosticsParams],
                                serverOutput: ByteArrayOutputStream) {
    /** What the server has written so far, framing and all. */
    def wire: String = serverOutput.synchronized(serverOutput.toString(StandardCharsets.UTF_8))
  }

  /** Copies everything written through it, so a test can read the bytes that crossed. */
  private class TeeOutputStream(to: OutputStream, copy: ByteArrayOutputStream) extends OutputStream {
    override def write(b: Int): Unit = {
      copy.synchronized(copy.write(b))
      to.write(b)
    }

    override def write(bytes: Array[Byte], off: Int, len: Int): Unit = {
      copy.synchronized(copy.write(bytes, off, len))
      to.write(bytes, off, len)
    }

    override def flush(): Unit = to.flush()

    override def close(): Unit = to.close()
  }

  /**
    * Runs `f` against a `BuildServer` proxy wired to a stub server over two pipes.
    *
    * Both ends are real `Launcher`s, so the framing, the reflection over the annotated interfaces
    * and the gson handler are the same code a client would drive.
    */
  private def withConnection(f: Connection => Unit): Unit = {
    val channel = BspTestChannel.open()

    val serverOutput = new ByteArrayOutputStream()
    val received = new LinkedBlockingQueue[PublishDiagnosticsParams]()
    // Cached, like the server's own pool: a handler can block for the length of a build, and a
    // joiner waits on the build it shares, so a small fixed pool can leave the owner queued behind
    // its own joiners.
    val executor = Executors.newCachedThreadPool((r: Runnable) => {
      val t = new Thread(r, "bsp-linkage")
      t.setDaemon(true)
      t
    })

    val stubServer = new StubServer

    try {
      val serverLauncher = new Launcher.Builder[BuildClient]()
        .setLocalService(stubServer)
        .setRemoteInterface(classOf[BuildClient])
        .setInput(channel.serverIn).setOutput(new TeeOutputStream(channel.serverOut, serverOutput))
        .setExecutorService(executor)
        .create()

      val clientLauncher = new Launcher.Builder[BuildServer]()
        .setLocalService(new StubClient(received))
        .setRemoteInterface(classOf[BuildServer])
        .setInput(channel.clientIn).setOutput(channel.clientOut)
        .setExecutorService(executor)
        .create()

      // The stub answers `buildTargetCompile` by notifying the client, so it needs the proxy before
      // anything is dispatched. Set on the instance rather than on a shared object: a field shared
      // between tests is safe only while the suite runs serially, which is a property of the build
      // rather than of this file.
      stubServer.connect(serverLauncher.getRemoteProxy)
      serverLauncher.startListening()
      clientLauncher.startListening()

      f(Connection(clientLauncher.getRemoteProxy, received, serverOutput))
    } finally {
      // Streams first, executor second. The other order interrupts a listener thread blocked in a
      // read, which lsp4j logs as an `InterruptedIOException` with a stack trace through
      // `java.util.logging` -- `slf4j-nop` silences JLine, not this. Closing first gives the listener
      // a clean end of stream and the suite output stays readable.
      channel.close()
      executor.shutdownNow()
    }
  }

  /**
    * The smallest server that exercises a request, a response and a notification.
    *
    * It implements only what these tests call and fails everything else, which is the same rule the
    * real server follows for a request it does not serve.
    */
  private class StubServer extends BuildServer {

    /** The client to notify, set before listening starts and read on dispatch threads after. */
    @volatile private var client: BuildClient = _

    /** Attaches the proxy this stub notifies. */
    def connect(c: BuildClient): Unit = client = c

    override def buildInitialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] = {
      val capabilities = new BuildServerCapabilities()
      capabilities.setCompileProvider(new CompileProvider(params.getCapabilities.getLanguageIds))
      CompletableFuture.completedFuture(
        new InitializeBuildResult("flix-under-test", "0.0.0", Bsp4j.PROTOCOL_VERSION, capabilities))
    }

    override def buildTargetCompile(params: CompileParams): CompletableFuture[CompileResult] = {
      client.onBuildPublishDiagnostics(diagnosticParams)
      CompletableFuture.completedFuture(new CompileResult(StatusCode.OK))
    }

    override def onBuildInitialized(): Unit = ()
    override def buildShutdown(): CompletableFuture[Object] = CompletableFuture.completedFuture(null)
    override def onBuildExit(): Unit = ()
    override def onRunReadStdin(params: ReadParams): Unit = ()

    override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] = unsupported
    override def workspaceReload(): CompletableFuture[Object] = unsupported
    override def buildTargetSources(params: SourcesParams): CompletableFuture[SourcesResult] = unsupported
    override def buildTargetInverseSources(params: InverseSourcesParams): CompletableFuture[InverseSourcesResult] = unsupported
    override def buildTargetDependencySources(params: DependencySourcesParams): CompletableFuture[DependencySourcesResult] = unsupported
    override def buildTargetDependencyModules(params: DependencyModulesParams): CompletableFuture[DependencyModulesResult] = unsupported
    override def buildTargetResources(params: ResourcesParams): CompletableFuture[ResourcesResult] = unsupported
    override def buildTargetOutputPaths(params: OutputPathsParams): CompletableFuture[OutputPathsResult] = unsupported
    override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] = unsupported
    override def buildTargetTest(params: TestParams): CompletableFuture[TestResult] = unsupported
    override def debugSessionStart(params: DebugSessionParams): CompletableFuture[DebugSessionAddress] = unsupported
    override def buildTargetCleanCache(params: CleanCacheParams): CompletableFuture[CleanCacheResult] = unsupported

    private def unsupported[T]: CompletableFuture[T] = {
      val f = new CompletableFuture[T]()
      f.completeExceptionally(new UnsupportedOperationException("not part of the linkage gate"))
      f
    }
  }

  /** Records the diagnostics it is sent and ignores everything else. */
  private class StubClient(received: util.Queue[PublishDiagnosticsParams]) extends BuildClient {
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = received.add(params)
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
    override def onRunPrintStdout(params: PrintParams): Unit = ()
    override def onRunPrintStderr(params: PrintParams): Unit = ()
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
  }
}
