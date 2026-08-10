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

import ca.uwaterloo.flix.api.ProjectView
import ca.uwaterloo.flix.util.Build
import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{ResponseError, ResponseErrorCode}

import java.nio.file.{Files, Path}
import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters.*

/**
  * The protocol surface: one method per request, each of them thin.
  *
  * Everything that decides anything lives elsewhere -- the lifecycle in [[BspSession]], the target
  * model in [[BuildTargets]], what is served in [[BspCapabilities]]. This class exists to be the
  * shape bsp4j's `Launcher` reflects over, and keeping it thin is what makes the parts behind it
  * testable without a connection.
  *
  * A request whose feature is not in [[BspCapabilities.Implemented]] is refused with
  * `MethodNotFound` rather than answered with something empty. An empty answer is indistinguishable
  * from a real one, so a client would draw a conclusion from it; a refusal is a fact it can act on.
  *
  * @param onExit run when the client sends `build/exit`, to stop listening.
  */
class FlixBuildServer(session: BspSession, onExit: () => Unit) extends BuildServer with JvmBuildServer {

  /** Progress notifications, which read the client through the session so a late connect is fine. */
  private val tasks: BspTasks = new BspTasks(() => session.currentClient)

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  override def buildInitialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] =
    completing(session.initialize(params))

  override def onBuildInitialized(): Unit = session.initialized()

  override def buildShutdown(): CompletableFuture[Object] = {
    session.shutdown()
    CompletableFuture.completedFuture(null)
  }

  /**
    * Stops listening.
    *
    * A client is entitled to send this without a preceding `shutdown`, and a server that only exited
    * from the orderly path would hang around forever after an editor crashed.
    */
  override def onBuildExit(): Unit = onExit()

  // ── Discovery ────────────────────────────────────────────────────────────────

  override def workspaceBuildTargets(): CompletableFuture[WorkspaceBuildTargetsResult] =
    completing(new WorkspaceBuildTargetsResult(session.buildTargets().asJava))

  /**
    * Lists the sources of each requested target.
    *
    * Mandatory, so there is no capability to check -- but the target ids still are. An unknown id is
    * refused rather than answered with an empty list: a client asking about a target this server
    * never advertised has a stale cache, and telling it "that target has no sources" invites it to
    * believe the project is empty.
    */
  override def buildTargetSources(params: SourcesParams): CompletableFuture[SourcesResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map { target =>
      val item = new SourcesItem(target, sourceItems(view).asJava)
      item.setRoots(view.sourceRoots.map(BspUri.ofDirectory).asJava)
      item
    }
    new SourcesResult(items.asJava)
  }

  // ── Not served yet ───────────────────────────────────────────────────────────
  //
  // Each is refused by the feature it belongs to, so a method and its advertisement cannot drift:
  // the phase that implements one adds it to `BspCapabilities.Implemented` and the refusal stops
  // firing.

  /**
    * Says which target a document belongs to.
    *
    * A document the project does not declare gets an empty list rather than an error: unlike an
    * unknown *target*, an unknown document is an ordinary question -- a client asks about whatever
    * file the user opened, including files from other projects -- and "no target owns this" is the
    * true answer rather than a failure.
    */
  override def buildTargetInverseSources(params: InverseSourcesParams): CompletableFuture[InverseSourcesResult] = completing {
    val view = session.requireView()
    val owned = BspUri.toPath(params.getTextDocument.getUri).exists(view.declaresSource)
    val targets = if (owned) List(BuildTargets.id(view)) else Nil
    new InverseSourcesResult(targets.asJava)
  }

  /**
    * Lists the sources of the project's dependencies.
    *
    * The `.fpkg` archives, and only those. A Maven or url jar is compiled Java with no Flix source to
    * show, and the standard library has no file on this machine at all -- reporting either would name
    * something a client cannot open and call it a source.
    */
  override def buildTargetDependencySources(params: DependencySourcesParams): CompletableFuture[DependencySourcesResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map { target =>
      new DependencySourcesItem(target, view.flixPackagePaths.map(BspUri.ofFile).asJava)
    }
    new DependencySourcesResult(items.asJava)
  }

  /**
    * Lists the project's dependencies as modules.
    *
    * Read from the manifest rather than from the resolved jars, because the manifest is what names a
    * dependency: a jar in `lib/cache` has a file name, and a client wants the coordinate.
    */
  override def buildTargetDependencyModules(params: DependencyModulesParams): CompletableFuture[DependencyModulesResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map { target =>
      new DependencyModulesItem(target, dependencyModules(view).asJava)
    }
    new DependencyModulesResult(items.asJava)
  }

  /**
    * Lists the directories holding the project's resources.
    *
    * Reported whether or not `resources/` exists yet: a client uses this to decide what to watch, and
    * a project whose resources arrive tomorrow still wants them noticed.
    */
  override def buildTargetResources(params: ResourcesParams): CompletableFuture[ResourcesResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map { target =>
      new ResourcesItem(target, List(BspUri.ofDirectory(view.resourcesDirectory)).asJava)
    }
    new ResourcesResult(items.asJava)
  }

  /**
    * Says where the build writes.
    *
    * The class directory of the development mode, not `build/` itself -- that also holds generated
    * documentation and coverage reports, and a client that excluded the lot from its source scanning
    * would be excluding more than the build's output. Production output is not reported because no
    * target is advertised for it.
    */
  override def buildTargetOutputPaths(params: OutputPathsParams): CompletableFuture[OutputPathsResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map { target =>
      val classes = new OutputPathItem(
        BspUri.ofDirectory(view.classDirectories(Build.Development)), OutputPathItemKind.DIRECTORY)
      new OutputPathsItem(target, List(classes).asJava)
    }
    new OutputPathsResult(items.asJava)
  }

  /**
    * Compiles the project and publishes what the compiler said.
    *
    * Bracketed by task notifications so a client can show progress: a compile takes seconds, and one
    * that reports nothing looks like a hang. The status of the finish comes from the result, so a
    * compile that found errors finishes as `ERROR` while still being a request that was served.
    */
  override def buildTargetCompile(params: CompileParams): CompletableFuture[CompileResult] = completing {
    val view = session.requireView()
    val targets = requireKnownTargets(view, params.getTargets)
    val target = targets.headOption.getOrElse(BuildTargets.id(view))
    val originId = Option(params.getOriginId)

    tasks.bracket[CompileResult](
      message = s"Compiling ${view.packageName}",
      startData = _ => Some((TaskStartDataKind.COMPILE_TASK, new CompileTask(target))),
      finishData = (_, result) => Some((TaskFinishDataKind.COMPILE_REPORT, compileReport(target, result, originId))),
      statusOf = _.getStatusCode
    )(_ => session.compile(target, originId))
  }

  /**
    * Builds the program and runs it in a JVM of its own.
    *
    * Bracketed like a compile so a client can show that something is happening, and the program's own
    * output arrives as log messages rather than as diagnostics -- it is not a problem with the code.
    */
  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] = completing {
    val view = session.requireView()
    requireKnownTarget(view, params.getTarget)
    val target = params.getTarget
    val originId = Option(params.getOriginId)
    val arguments = Option(params.getArguments).map(_.asScala.toList).getOrElse(Nil)

    tasks.bracket[RunResult](
      message = s"Running ${view.packageName}",
      startData = _ => None,
      finishData = (_, _) => None,
      statusOf = _.getStatusCode
    )(_ => session.run(target, arguments, originId))
  }

  /**
    * Builds and runs the project's tests, reporting each one as it happens.
    *
    * The task pair is opened by the session rather than here, because a test run's notifications
    * interleave with the events that drive them -- `bracket` is for work that is a block, and this is
    * not.
    *
    * `params.getArguments` is read as regular expressions selecting which tests to run, which is what
    * `Tester` already accepts; a client that sends none runs them all.
    */
  override def buildTargetTest(params: TestParams): CompletableFuture[TestResult] = completing {
    val view = session.requireView()
    val targets = requireKnownTargets(view, params.getTargets)
    val target = targets.headOption.getOrElse(BuildTargets.id(view))
    val filters = Option(params.getArguments).map(_.asScala.toList).getOrElse(Nil).map(_.r)

    session.test(target, filters, Option(params.getOriginId))
  }

  /**
    * Empties the target's build output and forgets what was cached about it.
    *
    * This one is not driven by [[BspCapabilities.Implemented]], and cannot be: `buildTarget/cleanCache`
    * has no capability flag in the protocol, so there is no advertisement for it to be in step with.
    */
  override def buildTargetCleanCache(params: CleanCacheParams): CompletableFuture[CleanCacheResult] = completing {
    val view = session.requireView()
    val targets = requireKnownTargets(view, params.getTargets)

    session.cleanCache(targets.headOption.getOrElse(BuildTargets.id(view)))
  }

  /**
    * Re-reads `flix.toml` and the project layout.
    *
    * Returns `null`, which is what the protocol specifies for this request -- it carries no result, and
    * a client waits on it only to know the reload has happened before it asks anything else.
    */
  override def workspaceReload(): CompletableFuture[Object] = completing {
    session.reload()
    null
  }

  /**
    * Describes what a client needs to run the program itself.
    *
    * This is the escape hatch that makes the limits of `buildTarget/run` acceptable: a client that
    * wants its own console, its own environment or its own debugger forks the program with this rather
    * than asking the server to.
    */
  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams): CompletableFuture[JvmRunEnvironmentResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map(session.jvmEnvironment)
    new JvmRunEnvironmentResult(items.asJava)
  }

  /**
    * The same environment, for running the tests.
    *
    * Identical to the run environment, and that is not laziness: `@Test` definitions are entry points
    * compiled into the same output as everything else, so there is no test-only classpath to report.
    * Answering with the same list is the truth about this compiler.
    */
  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams): CompletableFuture[JvmTestEnvironmentResult] = completing {
    val view = session.requireView()
    val items = requireKnownTargets(view, params.getTargets).map(session.jvmEnvironment)
    new JvmTestEnvironmentResult(items.asJava)
  }

  /**
    * Refused unconditionally, and it is the one method here that never becomes available by adding a
    * feature: Flix has no debug adapter, so there is no address to hand back. `canDebug` is false
    * for the same reason.
    */
  override def debugSessionStart(params: DebugSessionParams): CompletableFuture[DebugSessionAddress] =
    refuse(BspFeature.Debug)

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /**
    * Returns the report that ends a compile task.
    *
    * The counts are what a client puts in its status bar. Warnings are always zero because
    * `CompilationMessage` has no severity and `Flix.check` returns only errors -- reporting a warning
    * here would be inventing one.
    */
  private def compileReport(target: BuildTargetIdentifier,
                            result: CompileResult,
                            originId: Option[String]): CompileReport = {
    val errors = if (result.getStatusCode == StatusCode.OK) 0 else 1
    val report = new CompileReport(target, errors, 0)
    originId.foreach(report.setOriginId)
    report
  }

  /**
    * Returns the project's dependencies as protocol modules.
    *
    * Maven dependencies carry their coordinate under the `maven` data kind, which is the shape a
    * client knows how to render and resolve. A Flix package and a url jar have no such standard
    * shape, so they are reported plainly rather than dressed as something they are not.
    */
  private def dependencyModules(view: ProjectView): List[DependencyModule] =
    view.manifest.toList.flatMap { manifest =>
      val maven = manifest.mavenDependencies.map { d =>
        val module = new DependencyModule(d.identifier, d.versionTag)
        module.setDataKind(DependencyModuleDataKind.MAVEN)
        module.setData(new MavenDependencyModule(
          d.groupId, d.artifactId, d.versionTag, List.empty[MavenDependencyModuleArtifact].asJava))
        module
      }
      val flix = manifest.flixDependencies.map(d => new DependencyModule(d.identifier, d.version.toString))
      val jars = manifest.jarDependencies.map(d => new DependencyModule(d.identifier, d.url))
      maven ::: flix ::: jars
    }

  /** Returns the project's sources as protocol items, in a stable order. */
  private def sourceItems(view: ProjectView): List[SourceItem] =
    view.sourcePaths.map { p =>
      // Not generated: every path here is a file the user wrote. The compiler's own bundled library
      // sources are not project sources and do not appear.
      new SourceItem(BspUri.ofFile(p), SourceItemKind.FILE, false)
    }

  /** Fails unless `target` is one this server has. */
  private def requireKnownTarget(view: ProjectView, target: BuildTargetIdentifier): Unit = {
    if (!BuildTargets.isKnown(view, target)) {
      throw new ResponseErrorException(new ResponseError(
        ResponseErrorCode.InvalidParams,
        s"unknown build target: ${Option(target).map(_.getUri).getOrElse("none")}",
        null))
    }
  }

  /** Returns `targets`, or fails naming the ones this server does not have. */
  private def requireKnownTargets(view: ProjectView, targets: java.util.List[BuildTargetIdentifier]): List[BuildTargetIdentifier] = {
    val asked = Option(targets).map(_.asScala.toList).getOrElse(Nil)
    val unknown = asked.filterNot(BuildTargets.isKnown(view, _))
    if (unknown.nonEmpty) {
      throw new ResponseErrorException(new ResponseError(
        ResponseErrorCode.InvalidParams,
        s"unknown build target(s): ${unknown.map(_.getUri).mkString(", ")}",
        null))
    }
    asked
  }

  /**
    * Runs `body` and completes with it, turning a thrown protocol error into a failed response.
    *
    * Without this an exception from a handler becomes an `InternalError` with a stack trace, and the
    * `ServerNotInitialized` and `InvalidParams` codes the lifecycle depends on never reach the
    * client.
    */
  private def completing[T](body: => T): CompletableFuture[T] = {
    val future = new CompletableFuture[T]()
    try {
      future.complete(body)
    } catch {
      case e: ResponseErrorException => future.completeExceptionally(e)
      case e: Exception =>
        future.completeExceptionally(new ResponseErrorException(
          new ResponseError(ResponseErrorCode.InternalError, Option(e.getMessage).getOrElse(e.toString), null)))
    }
    future
  }

  /** Refuses a request whose feature is not served, and reports one that is as the bug it is. */
  private def refuse[T](feature: BspFeature): CompletableFuture[T] =
    if (BspCapabilities.implemented(feature)) {
      // Advertised and unimplemented: the state `BspCapabilities` exists to prevent. Say so rather
      // than returning an empty result that a client would trust.
      refuseByName(s"$feature (advertised but not implemented -- this is a bug in the server)")
    } else {
      refuseByName(s"$feature")
    }

  /** Refuses a request that has no capability flag to be measured against. */
  private def refuseByName[T](what: String): CompletableFuture[T] = {
    val future = new CompletableFuture[T]()
    future.completeExceptionally(new ResponseErrorException(
      new ResponseError(ResponseErrorCode.MethodNotFound, s"$what is not implemented by this server", null)))
    future
  }
}
