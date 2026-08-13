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
import java.util.concurrent.{CompletableFuture, Executor, RejectedExecutionException, Semaphore}
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
  * ==Requests do not run on the connection's thread==
  *
  * Every request that does work is dispatched to `executor`. lsp4j reads and dispatches messages on
  * one thread, so a handler that ran there would stop the connection being read for as long as it
  * took -- and a whole-program compile takes seconds. Nothing else could be answered in the meantime,
  * including `build/shutdown` and `$/cancelRequest`, which is to say the server would look wedged
  * exactly when a client most wants to talk to it. Builds are still serialised, by a lock in
  * [[BspSession]] rather than by the transport.
  *
  * @param onExit   run when the client sends `build/exit`, to stop listening.
  * @param executor where request handlers run. The connection's own executor, so its threads are
  *                 daemons and end with the process.
  */
class FlixBuildServer(session: BspSession, onExit: () => Unit, executor: Executor) extends BuildServer with JvmBuildServer {

  /**
    * How many build requests may be in flight at once.
    *
    * Requests are dispatched off the connection's thread and builds are serialised, so surplus work
    * parks a platform thread each -- and the pool is unbounded on purpose, because a ten-minute run must
    * not starve a query. Something has to be the bound. Generous: no editor comes close, and the
    * coalescing in `BspSession` already collapses a burst of compiles into two builds.
    */
  private val MaxBuildRequestsInFlight: Int = 32

  /** Permits for [[MaxBuildRequestsInFlight]], taken before a request is submitted. */
  private val admission: Semaphore = new Semaphore(MaxBuildRequestsInFlight)

  /** Progress notifications, which read the client through the session so a late connect is fine. */
  private val tasks: BspTasks = new BspTasks(() => session.currentClient)

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  override def buildInitialize(params: InitializeBuildParams): CompletableFuture[InitializeBuildResult] =
    completing(session.initialize(params))

  override def onBuildInitialized(): Unit = session.initialized()

  /**
    * Ends the session.
    *
    * Answered on this thread rather than on the executor, deliberately: it must not be able to queue
    * behind the very work it is stopping. The state check is the session's, and its failure is mapped
    * the way every other request's is -- a shutdown that cannot be served has to say so, not succeed.
    */
  override def buildShutdown(): CompletableFuture[Object] = {
    val future = new CompletableFuture[Object]()
    try {
      session.shutdown()
      future.complete(null)
    } catch {
      case e: ResponseErrorException => future.completeExceptionally(e)
      case e: Exception => future.completeExceptionally(internalError(e))
    }
    future
  }

  /**
    * Stops listening.
    *
    * A client is entitled to send this without a preceding `shutdown`, and a server that only exited
    * from the orderly path would hang around forever after an editor crashed.
    */
  override def onBuildExit(): Unit = {
    // Recorded before the listener stops, because it decides the process's exit status and there is
    // nobody left to ask afterwards.
    session.exited()
    onExit()
  }

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
  override def buildTargetCompile(params: CompileParams): CompletableFuture[CompileResult] = admitted("a compile") {
    val view = session.requireView()
    val targets = requireKnownTargets(view, params.getTargets)
    val target = targets.headOption.getOrElse(BuildTargets.id(view))
    val originId = Option(params.getOriginId)

    tasks.bracket[BspSession.CompileAnswer](
      message = s"Compiling ${view.packageName}",
      startData = _ => Some((TaskStartDataKind.COMPILE_TASK, new CompileTask(target))),
      finishData = (_, answer) => Some((TaskFinishDataKind.COMPILE_REPORT, compileReport(target, answer, originId))),
      statusOf = _.result.getStatusCode
    )(_ => session.compile(target, originId)).result
  }

  /**
    * Builds the program and runs it in a JVM of its own.
    *
    * Bracketed like a compile so a client can show that something is happening, and the program's own
    * output arrives as log messages rather than as diagnostics -- it is not a problem with the code.
    */
  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] = admittedCancellable("a run") { cancellation =>
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
    )(_ => session.run(target, arguments, originId, cancellation))
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
  override def buildTargetTest(params: TestParams): CompletableFuture[TestResult] = admittedCancellable("a test run") { cancellation =>
    val view = session.requireView()
    val targets = requireKnownTargets(view, params.getTargets)
    val target = targets.headOption.getOrElse(BuildTargets.id(view))
    val filters = Option(params.getArguments).map(_.asScala.toList).getOrElse(Nil).map(_.r)

    session.test(target, filters, Option(params.getOriginId), cancellation)
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
    * Refused: there is no separate compile classpath to report.
    *
    * The protocol asks for the jars a *compilation* needs, which for a JVM language is where the
    * javac-visible dependencies live. A Flix build resolves its own dependencies from `flix.toml` and
    * compiles Flix, so the only classpath a client can act on is the runtime one -- and answering with
    * that under this name would be describing something else. `jvmCompileClasspathProvider` stays false.
    */
  override def buildTargetJvmCompileClasspath(params: JvmCompileClasspathParams): CompletableFuture[JvmCompileClasspathResult] =
    refuse(BspFeature.JvmCompileClasspath)

  /**
    * Ignored, with a line in the client's log saying so.
    *
    * `run/readStdin` exists for a client to type into a running program. Honouring it means keeping the
    * program's standard input open for the length of the run, and today it is closed immediately and on
    * purpose: a program that reads input then sees end-of-stream and proceeds, where one waiting on input
    * a client may never send would hang until the run's timeout. Trading a clean end-of-stream for a
    * possible hang is not an improvement, so this stays a notification that reports itself unsupported --
    * a notification has no reply to refuse with.
    */
  override def onRunReadStdin(params: ReadParams): Unit =
    session.logMessage("run/readStdin is not supported: a run's standard input is closed when it starts.")

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
    * The counts are what a client puts in its status bar, so `errors` is the number of *diagnostics*
    * rather than of files that have them: a client summing across targets wants the former, and it used
    * to be 1 for a file with forty errors because the report was built from the status alone.
    *
    * Warnings are always zero because `CompilationMessage` has no severity and `Flix.check` returns only
    * errors -- reporting a warning here would be inventing one.
    */
  private def compileReport(target: BuildTargetIdentifier,
                            answer: BspSession.CompileAnswer,
                            originId: Option[String]): CompileReport = {
    val report = new CompileReport(target, answer.diagnostics, 0)
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
    requireTargetsOffered()
    if (!BuildTargets.isKnown(view, target)) {
      throw new ResponseErrorException(new ResponseError(
        ResponseErrorCode.InvalidParams,
        s"unknown build target: ${Option(target).map(_.getUri).getOrElse("none")}",
        null))
    }
  }

  /** Returns `targets`, or fails naming the ones this server does not have. */
  private def requireKnownTargets(view: ProjectView, targets: java.util.List[BuildTargetIdentifier]): List[BuildTargetIdentifier] = {
    requireTargetsOffered()
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
  /**
    * As [[completing]], but refused when too much build work is already in flight.
    *
    * The permit is taken *before* the work is submitted, and that ordering is the whole point. Taking it
    * inside the body -- which is where this started -- means the thread has already been created and the
    * task already queued by the time the limit is consulted, so a flood costs exactly what the limit was
    * meant to prevent and the refusals arrive after the damage. Here a request that cannot be admitted
    * never reaches the pool.
    *
    * Released on completion however the request ended, including cancellation: `whenComplete` runs for
    * every terminal state, and a permit leaked on one path would shrink the server's capacity for the
    * life of the connection.
    */
  private def admitted[T](what: String)(body: => T): CompletableFuture[T] = {
    if (!admission.tryAcquire()) {
      val future = new CompletableFuture[T]()
      future.completeExceptionally(new ResponseErrorException(new ResponseError(
        ResponseErrorCode.InvalidRequest,
        s"too many build requests in flight ($MaxBuildRequestsInFlight); $what was refused rather than queued",
        null)))
      return future
    }
    val future = completing(body)
    future.whenComplete((_, _) => admission.release())
    future
  }

  /**
    * As [[admitted]], for work that also has to be told when the client gives up.
    */
  private def admittedCancellable[T](what: String)(body: Cancellation => T): CompletableFuture[T] = {
    val cancellation = new Cancellation
    val future = admitted(what)(body(cancellation))
    future.whenComplete { (_, _) =>
      if (future.isCancelled) {
        cancellation.cancel()
      }
    }
    future
  }

  /**
    * As [[completing]], for work that has to be told when the client gives up.
    *
    * The distinction is not decoration. Dropping a reply is enough for a compile, which has to finish
    * anyway; it is not enough for a request that started a process or a test run, where cancelling has
    * to reach the work. lsp4j cancels the future, this turns that into a signal the body can act on.
    */
  private def completingCancellable[T](body: Cancellation => T): CompletableFuture[T] = {
    val cancellation = new Cancellation
    val future = completing(body(cancellation))
    // Fires on cancellation as well as on an ordinary completion, which is why it asks which happened.
    future.whenComplete { (_, _) =>
      if (future.isCancelled) {
        cancellation.cancel()
      }
    }
    future
  }

  private def completing[T](body: => T): CompletableFuture[T] = {
    val future = new CompletableFuture[T]()
    val work: Runnable = { () =>
      // A client that has already given up gets no work done on its behalf.
      if (!future.isCancelled) {
        try {
          val result = body
          // Soft cancellation, and the softness is deliberate. A cancel that arrived while the work
          // was running does not interrupt it: the ForkJoin pool the compiler uses and the writes
          // `JvmWriter` makes are not interrupt-safe, and the class directory must be reconciled and
          // its manifest written or the build directory describes nothing. A late answer is
          // recoverable; a half-reconciled output directory is what `compileProject` exists to
          // prevent. So the work finishes and its result is dropped -- lsp4j answers the cancelled
          // request with `RequestCancelled` on its own.
          if (!future.isCancelled) {
            future.complete(result)
          }
        } catch {
          case e: ResponseErrorException => future.completeExceptionally(e)
          case e: Exception => future.completeExceptionally(internalError(e))
        }
      }
    }

    try {
      executor.execute(work)
    } catch {
      // The executor is shut down, which is what `build/exit` and a closed connection do. A future
      // that is never completed is a client waiting forever, so this path has to answer rather than
      // drop the request -- and it is not hypothetical: the shutdown and the request race.
      case _: RejectedExecutionException =>
        future.completeExceptionally(new ResponseErrorException(new ResponseError(
          ResponseErrorCode.InternalError, "this server is shutting down and cannot serve the request", null)))
      case e: Exception => future.completeExceptionally(internalError(e))
    }
    future
  }

  /** Wraps a failure that is nobody's protocol error, so a client gets a message rather than silence. */
  private def internalError(e: Exception): ResponseErrorException =
    new ResponseErrorException(
      new ResponseError(ResponseErrorCode.InternalError, Option(e.getMessage).getOrElse(e.toString), null))

  /**
    * Returns the directory `uri` names, or fails if it is not one.
    *
    * Checked rather than passed through: `ProcessBuilder` reports a missing working directory as a
    * generic failure to start the program, which a user reads as "the build server cannot run my code".
    * Naming the directory instead turns it into a request the client got wrong.
    */
  private def requireDirectory(uri: String): Option[Path] = Option(uri).filter(_.nonEmpty).map { given =>
    val path = BspUri.toPath(given).getOrElse(
      throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidParams,
        s"workingDirectory is not a file uri: $given", null)))
    if (!Files.isDirectory(path)) {
      throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidParams,
        s"workingDirectory is not a directory: $path", null))
    }
    path
  }

  /**
    * Fails unless this client was offered a target.
    *
    * The language filter shapes `workspace/buildTargets`, and a client that was told about no targets can
    * still derive the id -- it is built from the project path -- and ask for a compile with it. Without
    * this the filter would be a formality that changed one reply and nothing else.
    */
  private def requireTargetsOffered(): Unit = {
    if (!session.servesTargets) {
      throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidParams,
        s"this client advertised no support for '${BuildTargets.LanguageId}', so it was offered no target to operate on",
        null))
    }
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
