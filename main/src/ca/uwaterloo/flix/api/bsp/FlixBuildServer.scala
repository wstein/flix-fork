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

  override def buildTargetInverseSources(params: InverseSourcesParams): CompletableFuture[InverseSourcesResult] =
    refuse(BspFeature.InverseSources)

  override def buildTargetDependencySources(params: DependencySourcesParams): CompletableFuture[DependencySourcesResult] =
    refuse(BspFeature.DependencySources)

  override def buildTargetDependencyModules(params: DependencyModulesParams): CompletableFuture[DependencyModulesResult] =
    refuse(BspFeature.DependencyModules)

  override def buildTargetResources(params: ResourcesParams): CompletableFuture[ResourcesResult] =
    refuse(BspFeature.Resources)

  override def buildTargetOutputPaths(params: OutputPathsParams): CompletableFuture[OutputPathsResult] =
    refuse(BspFeature.OutputPaths)

  override def buildTargetCompile(params: CompileParams): CompletableFuture[CompileResult] =
    refuse(BspFeature.Compile)

  override def buildTargetRun(params: RunParams): CompletableFuture[RunResult] =
    refuse(BspFeature.Run)

  override def buildTargetTest(params: TestParams): CompletableFuture[TestResult] =
    refuse(BspFeature.Test)

  /**
    * Refused by name, not by feature: `buildTarget/cleanCache` has no capability flag in the
    * protocol, so there is no advertisement for it to be out of step with. Routing it through
    * `Compile`'s feature would make it claim to be a bug in the server the moment compiling is
    * implemented and this is not.
    */
  override def buildTargetCleanCache(params: CleanCacheParams): CompletableFuture[CleanCacheResult] =
    refuseByName("buildTarget/cleanCache")

  override def workspaceReload(): CompletableFuture[Object] =
    refuse(BspFeature.Reload)

  override def buildTargetJvmRunEnvironment(params: JvmRunEnvironmentParams): CompletableFuture[JvmRunEnvironmentResult] =
    refuse(BspFeature.JvmRunEnvironment)

  override def buildTargetJvmTestEnvironment(params: JvmTestEnvironmentParams): CompletableFuture[JvmTestEnvironmentResult] =
    refuse(BspFeature.JvmTestEnvironment)

  /**
    * Refused unconditionally, and it is the one method here that never becomes available by adding a
    * feature: Flix has no debug adapter, so there is no address to hand back. `canDebug` is false
    * for the same reason.
    */
  override def debugSessionStart(params: DebugSessionParams): CompletableFuture[DebugSessionAddress] =
    refuse(BspFeature.Debug)

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Returns the project's sources as protocol items, in a stable order. */
  private def sourceItems(view: ProjectView): List[SourceItem] =
    view.sourcePaths.map { p =>
      // Not generated: every path here is a file the user wrote. The compiler's own bundled library
      // sources are not project sources and do not appear.
      new SourceItem(BspUri.ofFile(p), SourceItemKind.FILE, false)
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
