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
  * The questions a client asks to understand a project, rather than to build it.
  *
  * All of them are answered from `ProjectView`, which holds only what is known *without* compiling.
  * That is the property worth testing: a client asks these before the first build and while the
  * project is broken, which is exactly when it needs them, and an implementation that reached for a
  * compiled program would answer none of them.
  */
class TestBspQueries extends AnyFunSuite {

  private val Timeout: Long = 60

  test("discovery answers for a project that does not compile") {
    withServer(withBrokenSource = true) { (project, client, target) =>
      // Nothing here has compiled, and nothing here needs to. An implementation that derived any of
      // this from a typed program would fail precisely when a user most needs their project mapped.
      val sources = client.buildTargetSources(new SourcesParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head
      assert(sources.getSources.asScala.nonEmpty)

      val outputs = client.buildTargetOutputPaths(new OutputPathsParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head
      assert(outputs.getOutputPaths.asScala.nonEmpty)

      val resources = client.buildTargetResources(new ResourcesParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head
      assert(resources.getResources.asScala.nonEmpty)
    }
  }

  test("inverseSources claims the project's own files and disclaims others") {
    withServer() { (project, client, target) =>
      val own = BspUri.ofFile(project.resolve("src").resolve("Main.flix"))
      val owned = client.buildTargetInverseSources(new InverseSourcesParams(new TextDocumentIdentifier(own)))
        .get(Timeout, TimeUnit.SECONDS).getTargets.asScala.toList
      assert(owned.map(_.getUri) == List(target.getUri), s"the project disclaimed its own file: $owned")

      // An empty list, not an error: a client asks about whatever the user opened, including files
      // from another project, and "no target owns this" is the true answer rather than a failure.
      val foreign = BspUri.ofFile(Path.of("/tmp/somewhere/else/Other.flix"))
      val disowned = client.buildTargetInverseSources(new InverseSourcesParams(new TextDocumentIdentifier(foreign)))
        .get(Timeout, TimeUnit.SECONDS).getTargets.asScala.toList
      assert(disowned.isEmpty, s"a foreign file was claimed: $disowned")
    }
  }

  test("outputPaths names the class directory, not the whole build directory") {
    withServer() { (project, client, target) =>
      val paths = client.buildTargetOutputPaths(new OutputPathsParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head.getOutputPaths.asScala.toList

      assert(paths.sizeIs == 1, s"expected one output path, got $paths")
      val uri = paths.head.getUri
      assert(paths.head.getKind == OutputPathItemKind.DIRECTORY)
      // `build/` also holds generated documentation and coverage reports. A client told to exclude
      // the lot would exclude more than the build's output.
      assert(uri.endsWith("/build/development/class/"), s"unexpected output path: $uri")
    }
  }

  test("resources names the resources directory even before it exists") {
    withServer() { (project, client, target) =>
      assert(!Files.exists(project.resolve("resources")), "the fixture unexpectedly has a resources directory")

      val resources = client.buildTargetResources(new ResourcesParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head.getResources.asScala.toList

      // Reported anyway, because a client uses this to decide what to watch: a project whose
      // resources arrive tomorrow still wants them noticed.
      assert(resources.sizeIs == 1, s"expected one resources directory, got $resources")
      assert(resources.head.endsWith("/resources/"), s"unexpected resources uri: ${resources.head}")
    }
  }

  test("dependency queries are empty for a project with no dependencies") {
    withServer() { (project, client, target) =>
      val sources = client.buildTargetDependencySources(new DependencySourcesParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head
      assert(sources.getSources.isEmpty, s"unexpected dependency sources: ${sources.getSources}")

      val modules = client.buildTargetDependencyModules(new DependencyModulesParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head
      assert(modules.getModules.isEmpty, s"unexpected dependency modules: ${modules.getModules}")
    }
  }

  test("a maven dependency is reported as a maven module with its coordinate") {
    // Declared in the manifest and never resolved: these queries read what the project *declares*,
    // which is what a client wants to render, and is answerable without the network.
    val manifest =
      s"""[package]
         |name = "queries"
         |description = "test"
         |version = "0.1.0"
         |flix = "${ca.uwaterloo.flix.api.Version.CurrentVersion}"
         |authors = ["flix"]
         |
         |[mvn-dependencies]
         |"org.apache.commons:commons-lang3" = "3.20.0"
         |""".stripMargin

    withServer(manifest = Some(manifest)) { (project, client, target) =>
      val modules = client.buildTargetDependencyModules(new DependencyModulesParams(List(target).asJava))
        .get(Timeout, TimeUnit.SECONDS).getItems.asScala.head.getModules.asScala.toList

      assert(modules.sizeIs == 1, s"expected one module, got $modules")
      val module = modules.head
      assert(module.getName == "org.apache.commons:commons-lang3", s"unexpected name: ${module.getName}")
      assert(module.getVersion == "3.20.0")
      assert(module.getDataKind == DependencyModuleDataKind.MAVEN, s"unexpected data kind: ${module.getDataKind}")
    }
  }

  test("every query refuses a target it does not have") {
    withServer() { (project, client, _) =>
      val stale = List(new BuildTargetIdentifier("file:///nowhere/?id=main")).asJava

      val codes = List(
        errorCodeOf(client.buildTargetResources(new ResourcesParams(stale))),
        errorCodeOf(client.buildTargetOutputPaths(new OutputPathsParams(stale))),
        errorCodeOf(client.buildTargetDependencySources(new DependencySourcesParams(stale))),
        errorCodeOf(client.buildTargetDependencyModules(new DependencyModulesParams(stale))))

      // An empty answer would tell a client with a stale cache that the project has nothing.
      assert(
        codes.forall(_.contains(ResponseErrorCode.InvalidParams.getValue)),
        s"expected every query to refuse an unknown target, got $codes")
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

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
    * Runs `f` against an initialised server serving a fresh project.
    *
    * @param withBrokenSource leave the project unable to compile, to show these answers do not
    *                         depend on it compiling.
    * @param manifest         replace `flix.toml`, for the dependency queries.
    */
  private def withServer(withBrokenSource: Boolean = false, manifest: Option[String] = None)
                        (f: (Path, BuildServer, BuildTargetIdentifier) => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-queries-")
    Bootstrap.init(project)(System.out).unsafeGet
    if (withBrokenSource) {
      Files.writeString(project.resolve("src").resolve("Main.flix"), "def main(): Unit = this is not flix\n")
    }
    manifest.foreach(m => Files.writeString(project.resolve("flix.toml"), m))

    val channel = BspTestChannel.open()

    // Cached, like the server's own pool: a handler can block for the length of a build, and a
    // joiner waits on the build it shares, so a small fixed pool can leave the owner queued behind
    // its own joiners.
    val executor = Executors.newCachedThreadPool((r: Runnable) => {
      val t = new Thread(r, "bsp-queries")
      t.setDaemon(true)
      t
    })

    val serverThread = new Thread(
      () => BspServer.serve(Options.DefaultTest, project, new BspLogStream(), channel.serverIn, channel.serverOut, executor),
      "bsp-server-under-test")
    serverThread.setDaemon(true)

    try {
      serverThread.start()

      val launcher = new Launcher.Builder[BuildServer]()
        .setLocalService(new SilentClient)
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
      f(project, client, target)
    } finally {
      channel.close()
      executor.shutdownNow()
    }
  }

  /** A client that accepts every notification: these tests assert on responses. */
  private class SilentClient extends BuildClient {
    override def onBuildShowMessage(params: ShowMessageParams): Unit = ()
    override def onRunPrintStdout(params: PrintParams): Unit = ()
    override def onRunPrintStderr(params: PrintParams): Unit = ()
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
  }
}
