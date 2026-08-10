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
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, ExecutionException, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
  * Reloading a project, and clearing what a build left behind.
  *
  * These are the two requests that change the session rather than read it, and both are about what
  * happens when they *fail*: a reload that half-applied would answer some questions from the old
  * project and some from the new, and a cache clean that deleted more than it was asked to would
  * destroy work nobody mentioned.
  */
class TestBspReload extends AnyFunSuite {

  private val Timeout: Long = 180

  test("a manifest change takes effect on reload, and the client is told") {
    withSession() { s =>
      assert(s.targetName == "test-project", s"unexpected name: ${s.targetName}")

      // A source change is picked up by the next compile on its own. A manifest change is what needs
      // this request: it can add, drop or move a dependency, so every answer may change.
      s.rewriteManifestName("renamed-project")
      assert(s.targetName == "test-project", "the manifest was re-read without being asked to")

      s.reload()

      assert(s.targetName == "renamed-project", "the reload did not take effect")

      // Without this a client keeps serving whatever it cached about the target and the reload is
      // invisible to it.
      val events = s.targetChanges.flatMap(c => c.getChanges.asScala.toList)
      assert(events.sizeIs == 1, s"expected one target event, got $events")
      assert(events.head.getKind == BuildTargetEventKind.CHANGED, s"unexpected kind: ${events.head.getKind}")
      assert(events.head.getTarget == s.target, "the event named a target the client has not seen")
    }
  }

  test("a reload that fails leaves the previous configuration serving") {
    withSession() { s =>
      Files.writeString(s.project.resolve("flix.toml"), "this is not toml at all\n")

      val error = intercept[ExecutionException](s.reload())
      assert(error.getCause.isInstanceOf[ResponseErrorException], s"unexpected failure: ${error.getCause}")

      // The point of the whole exercise: a typo in a manifest must not leave an editor connected to a
      // server that can no longer answer anything.
      assert(s.targetName == "test-project", "a failed reload replaced the working configuration")
      assert(s.compile().getStatusCode == StatusCode.OK, "a failed reload left the session unable to build")
      assert(s.shown.exists(_.contains("still in use")), s"the failure was not explained: ${s.shown}")
    }
  }

  test("a reload clears the markers it can no longer speak for") {
    withSession(source = "def main(): Unit = undefinedFunction()") { s =>
      assert(s.compile().getStatusCode == StatusCode.ERROR)
      val published = s.diagnostics.filter(_.getDiagnostics.asScala.nonEmpty)
      assert(published.nonEmpty, "the broken project published no diagnostics")

      s.rewriteManifestName("renamed-project")
      s.reload()

      // A file the reload dropped from the project cannot be spoken for by any later compile, so a
      // marker left behind would stay until the editor restarted. The next compile republishes
      // whatever is still wrong -- which the count below confirms is not nothing.
      val cleared = s.diagnostics.filter(_.getDiagnostics.asScala.isEmpty).map(_.getTextDocument.getUri)
      assert(cleared.nonEmpty, "the reload cleared nothing")
      assert(cleared.toSet == published.map(_.getTextDocument.getUri).toSet,
        s"the reload cleared the wrong documents: $cleared")

      assert(s.compile().getStatusCode == StatusCode.ERROR)
      assert(s.diagnostics.count(_.getDiagnostics.asScala.nonEmpty) == published.size + published.size,
        "the error was not republished after the reload")
    }
  }

  test("cleaning the cache empties the class directory and nothing else") {
    withSession() { s =>
      assert(s.compile().getStatusCode == StatusCode.OK)
      val classDir = s.project.resolve("build").resolve("development").resolve("class")
      assert(classFilesIn(classDir).nonEmpty, "the compile wrote no class files")

      // Documentation is what distinguishes this from `Bootstrap.clean`, which deletes it. A client
      // asking to clear a target's cache has not asked for the API documentation to go with it.
      val docPage = s.project.resolve("build").resolve("doc").resolve("index.html")
      Files.createDirectories(docPage.getParent)
      Files.writeString(docPage, "<html></html>")

      val result = s.cleanCache()
      assert(result.getCleaned, s"the cache was not cleaned: ${result.getMessage}")
      assert(classFilesIn(classDir).isEmpty, s"class files survived: ${classFilesIn(classDir)}")
      assert(Files.exists(docPage), "cleaning the cache deleted the documentation")

      // And the state it leaves is one a build recovers from rather than trusts.
      assert(!Files.exists(s.project.resolve("build").resolve("development").resolve("build-manifest.json")),
        "the build manifest outlived the products it describes")
      assert(s.compile().getStatusCode == StatusCode.OK, "the project did not build after a clean")
      assert(classFilesIn(classDir).nonEmpty, "the rebuild wrote no class files")
    }
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  private def classFilesIn(dir: Path): List[Path] =
    if (!Files.isDirectory(dir)) Nil
    else {
      val stream = Files.walk(dir)
      try stream.filter(p => p.toString.endsWith(".class")).toList.asScala.toList
      finally stream.close()
    }

  private class Session(val project: Path, client: BuildServer, val target: BuildTargetIdentifier, received: Received) {
    def compile(): CompileResult =
      client.buildTargetCompile(new CompileParams(List(target).asJava)).get(Timeout, TimeUnit.SECONDS)

    def reload(): Unit = client.workspaceReload().get(Timeout, TimeUnit.SECONDS)

    def cleanCache(): CleanCacheResult =
      client.buildTargetCleanCache(new CleanCacheParams(List(target).asJava)).get(Timeout, TimeUnit.SECONDS)

    /** The one target's display name, which is the manifest's package name. */
    def targetName: String =
      client.workspaceBuildTargets().get(Timeout, TimeUnit.SECONDS).getTargets.asScala.head.getDisplayName

    /** Rewrites `flix.toml`'s package name, the smallest manifest change with an observable effect. */
    def rewriteManifestName(to: String): Unit = {
      val manifest = project.resolve("flix.toml")
      val rewritten = Files.readString(manifest).replaceFirst("""name\s*=\s*"[^"]*"""", s"""name = "$to"""")
      Files.writeString(manifest, rewritten)
    }

    def diagnostics: List[PublishDiagnosticsParams] = received.diagnostics.asScala.toList

    def targetChanges: List[DidChangeBuildTarget] = received.targetChanges.asScala.toList

    def shown: List[String] = received.shown.asScala.toList
  }

  private class Received {
    val diagnostics = new ConcurrentLinkedQueue[PublishDiagnosticsParams]()
    val targetChanges = new ConcurrentLinkedQueue[DidChangeBuildTarget]()
    val shown = new ConcurrentLinkedQueue[String]()
  }

  /** Runs `f` against an initialised server whose `src/Main.flix` is `source`. */
  private def withSession(source: String = """def main(): Unit \ IO = println("hello")""")(f: Session => Unit): Unit = {
    val project = Files.createTempDirectory("flix-bsp-reload-")
    Bootstrap.init(project)(System.out).unsafeGet
    Files.writeString(project.resolve("src").resolve("Main.flix"), source + "\n")

    // The generated manifest names the temporary directory, which is not a name a test can assert on.
    val manifest = project.resolve("flix.toml")
    Files.writeString(manifest, Files.readString(manifest).replaceFirst("""name\s*=\s*"[^"]*"""", """name = "test-project""""))

    val channel = BspTestChannel.open()

    val executor = Executors.newFixedThreadPool(6, (r: Runnable) => {
      val t = new Thread(r, "bsp-reload")
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
    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = received.diagnostics.add(params)
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = received.targetChanges.add(params)
    override def onBuildShowMessage(params: ShowMessageParams): Unit = received.shown.add(params.getMessage)
    override def onBuildLogMessage(params: LogMessageParams): Unit = ()
    override def onBuildTaskStart(params: TaskStartParams): Unit = ()
    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTaskFinish(params: TaskFinishParams): Unit = ()
  }
}
