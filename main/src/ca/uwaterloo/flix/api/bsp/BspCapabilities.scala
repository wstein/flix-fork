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

import scala.jdk.CollectionConverters.*

/**
  * A request this server may or may not serve.
  *
  * Named so that advertisement and dispatch can be driven from one set. The alternative -- a
  * `setXProvider(true)` here and an implementation over there -- is how a server comes to advertise
  * something it does not do, and the language server in this repository has shown what that costs:
  * a feature was implemented but never announced, so it was unreachable in every client, and
  * nothing reported it.
  *
  * Only the *optional* requests appear. `buildTarget/sources`, the lifecycle and
  * `workspace/buildTargets` are mandatory and carry no flag, so there is nothing to get out of step.
  */
sealed trait BspFeature

object BspFeature {
  case object Compile extends BspFeature
  case object Run extends BspFeature
  case object Test extends BspFeature
  case object Debug extends BspFeature
  case object InverseSources extends BspFeature
  case object DependencySources extends BspFeature
  case object DependencyModules extends BspFeature
  case object Resources extends BspFeature
  case object OutputPaths extends BspFeature
  case object JvmRunEnvironment extends BspFeature
  case object JvmTestEnvironment extends BspFeature
  case object Reload extends BspFeature
  case object BuildTargetChanged extends BspFeature

  /** Every optional request, so a test can walk the ones that are off as well as the ones that are on. */
  val All: List[BspFeature] = List(
    Compile, Run, Test, Debug, InverseSources, DependencySources, DependencyModules, Resources,
    OutputPaths, JvmRunEnvironment, JvmTestEnvironment, Reload, BuildTargetChanged)
}

/**
  * What this server tells a client it can do.
  *
  * [[Implemented]] is the only place that decides. A capability is advertised when, and only when,
  * the request behind it is served: advertising ahead of the implementation is worse than saying
  * nothing, because a client believes it and fails at the point of use -- which is the moment the
  * handshake existed to get ahead of. The same rule governs `CliContract`.
  *
  * The set is small because the server is being built in phases, and each phase adds its request and
  * its flag together.
  */
object BspCapabilities {

  /**
    * The requests this server serves today.
    *
    * Everything in [[BspFeature.All]] that is absent here is refused with `MethodNotFound`, and
    * `TestBspCapabilities` holds the two in step. The lifecycle, `workspace/buildTargets` and
    * `buildTarget/sources` are mandatory and carry no flag, so they are not listed.
    */
  val Implemented: Set[BspFeature] = Set(
    BspFeature.Compile,
    BspFeature.InverseSources,
    BspFeature.DependencySources,
    BspFeature.DependencyModules,
    BspFeature.Resources,
    BspFeature.OutputPaths,
    BspFeature.Run,
    BspFeature.JvmRunEnvironment,
    BspFeature.JvmTestEnvironment)

  /** Returns `true` if `feature` is served. */
  def implemented(feature: BspFeature): Boolean = Implemented.contains(feature)

  /** Returns what to send in the initialize result. */
  def mkServerCapabilities(): BuildServerCapabilities = {
    val languages = List(BuildTargets.LanguageId).asJava
    val c = new BuildServerCapabilities()

    if (implemented(BspFeature.Compile)) c.setCompileProvider(new CompileProvider(languages))
    if (implemented(BspFeature.Run)) c.setRunProvider(new RunProvider(languages))
    if (implemented(BspFeature.Test)) c.setTestProvider(new TestProvider(languages))
    if (implemented(BspFeature.Debug)) c.setDebugProvider(new DebugProvider(languages))

    c.setInverseSourcesProvider(implemented(BspFeature.InverseSources))
    c.setDependencySourcesProvider(implemented(BspFeature.DependencySources))
    c.setDependencyModulesProvider(implemented(BspFeature.DependencyModules))
    c.setResourcesProvider(implemented(BspFeature.Resources))
    c.setOutputPathsProvider(implemented(BspFeature.OutputPaths))
    c.setJvmRunEnvironmentProvider(implemented(BspFeature.JvmRunEnvironment))
    c.setJvmTestEnvironmentProvider(implemented(BspFeature.JvmTestEnvironment))
    c.setCanReload(implemented(BspFeature.Reload))
    c.setBuildTargetChangedProvider(implemented(BspFeature.BuildTargetChanged))

    c
  }

  /**
    * Returns what the one target can do, which is the same question one level down.
    *
    * `canCompile` and the rest stay false until the phase that implements them, so a client is never
    * told a target can do something the server would refuse.
    */
  def mkTargetCapabilities(): BuildTargetCapabilities = {
    val c = new BuildTargetCapabilities()
    c.setCanCompile(implemented(BspFeature.Compile))
    c.setCanRun(implemented(BspFeature.Run))
    c.setCanTest(implemented(BspFeature.Test))
    c.setCanDebug(implemented(BspFeature.Debug))
    c
  }
}
