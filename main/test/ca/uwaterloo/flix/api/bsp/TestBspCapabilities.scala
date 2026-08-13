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
import org.scalatest.funsuite.AnyFunSuite

/**
  * Holds advertisement against implementation, in both directions.
  *
  * The language server in this repository is why this exists. A feature there was implemented and
  * never announced, so no client could reach it and nothing said so; the reverse -- announced and not
  * implemented -- is worse, because a client believes the announcement and fails at the point of use,
  * which is the moment a handshake exists to get ahead of. `CliContract` states the rule and this is
  * the mechanical check of it.
  */
class TestBspCapabilities extends AnyFunSuite {

  test("run/printStdout is offered only to a client that can receive it") {
    // Measured, not assumed: `BuildClient` in bsp4j 2.1.1 declares `onBuildLogMessage` and no
    // `onRunPrintStdout`, so a 2.1 client loses a program's output entirely rather than showing it
    // somewhere else. The version a client declares in `build/initialize` is the only thing that says
    // which it is.
    assert(BspSession.hasPrintStdout(Some("2.2.0")))
    assert(BspSession.hasPrintStdout(Some("2.2.0-M2")))
    assert(BspSession.hasPrintStdout(Some("3.0.0")))
    // Compared as numbers: "2.10" sorts before "2.2" as text, and is later.
    assert(BspSession.hasPrintStdout(Some("2.10.0")))

    assert(!BspSession.hasPrintStdout(Some("2.1.0")))
    assert(!BspSession.hasPrintStdout(Some("2.0.0")))
    // Absent or unreadable is treated as old, because `build/logMessage` has existed in every version:
    // being wrong that way costs a message in the wrong window, the other way costs the message.
    assert(!BspSession.hasPrintStdout(None))
    assert(!BspSession.hasPrintStdout(Some("")))
    assert(!BspSession.hasPrintStdout(Some("nonsense")))
  }

  test("every advertised capability is one the server implements") {
    val advertised = BspCapabilities.mkServerCapabilities()

    // Read back out of the result rather than out of the set that produced it, so a `setXProvider`
    // wired to the wrong feature is caught rather than confirmed.
    def check(feature: BspFeature, isAdvertised: Boolean): Unit =
      assert(
        isAdvertised == BspCapabilities.implemented(feature),
        s"$feature is advertised=$isAdvertised but implemented=${BspCapabilities.implemented(feature)}")

    check(BspFeature.Compile, advertised.getCompileProvider != null)
    check(BspFeature.Run, advertised.getRunProvider != null)
    check(BspFeature.Test, advertised.getTestProvider != null)
    check(BspFeature.Debug, advertised.getDebugProvider != null)
    check(BspFeature.InverseSources, advertised.getInverseSourcesProvider)
    check(BspFeature.DependencySources, advertised.getDependencySourcesProvider)
    check(BspFeature.DependencyModules, advertised.getDependencyModulesProvider)
    check(BspFeature.Resources, advertised.getResourcesProvider)
    check(BspFeature.OutputPaths, advertised.getOutputPathsProvider)
    check(BspFeature.JvmRunEnvironment, advertised.getJvmRunEnvironmentProvider)
    check(BspFeature.JvmTestEnvironment, advertised.getJvmTestEnvironmentProvider)
    check(BspFeature.JvmCompileClasspath, advertised.getJvmCompileClasspathProvider)
    check(BspFeature.Reload, advertised.getCanReload)
    check(BspFeature.BuildTargetChanged, advertised.getBuildTargetChangedProvider)
  }

  test("the target claims only what the server implements") {
    val target = BspCapabilities.mkTargetCapabilities()
    assert(target.getCanCompile == BspCapabilities.implemented(BspFeature.Compile))
    assert(target.getCanRun == BspCapabilities.implemented(BspFeature.Run))
    assert(target.getCanTest == BspCapabilities.implemented(BspFeature.Test))
    assert(target.getCanDebug == BspCapabilities.implemented(BspFeature.Debug))
  }

  test("debugging is never claimed") {
    // Not a phase away, unlike the others: Flix has no debug adapter, so there is no address to hand
    // back and `debugSessionStart` can only fail. Advertising it would be the exact mistake the rule
    // above forbids, so it is asserted rather than left to a future edit.
    assert(!BspCapabilities.implemented(BspFeature.Debug), "debugging cannot be implemented")
    assert(!BspCapabilities.mkTargetCapabilities().getCanDebug)
    assert(BspCapabilities.mkServerCapabilities().getDebugProvider == null)
  }

  test("every optional request is accounted for") {
    // `BspFeature.All` is what the tests above walk. A feature added to the enum and forgotten there
    // would be advertised, or not, with nothing checking which.
    val counted: Set[BspFeature] = Set(
      BspFeature.Compile, BspFeature.Run, BspFeature.Test, BspFeature.Debug, BspFeature.InverseSources,
      BspFeature.DependencySources, BspFeature.DependencyModules, BspFeature.Resources,
      BspFeature.OutputPaths, BspFeature.JvmRunEnvironment, BspFeature.JvmTestEnvironment,
      BspFeature.JvmCompileClasspath, BspFeature.Reload, BspFeature.BuildTargetChanged)
    val missing = BspFeature.All.filterNot(counted.contains)
    assert(missing.isEmpty, s"these features are in BspFeature.All but checked nowhere: $missing")
  }
}
