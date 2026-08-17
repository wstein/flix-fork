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
package ca.uwaterloo.flix.tools.pkg

import org.scalatest.DoNotDiscover
import org.scalatest.Suites

/**
  * The package manager's network-dependent tests, run only via `./mill flix.testPackageManager`.
  * `@DoNotDiscover` keeps them out of the default `flix.test` pass -- but it is also the only door
  * to `TestBootstrap`'s build/clean/jar coverage, so skipping this suite isn't free.
  *
  * Anonymous REST traffic is capped at 60 requests an hour per IP, and this suite alone can spend
  * that. CI sets `GITHUB_CI_RUNNER_TOKEN` (see [[PkgTestUtils.gitHubToken]]); a local run usually
  * does not, and without it a rate limit and a real regression report the same red test -- the
  * failure count even varies between identical runs. Set the token before trusting either.
  */
@DoNotDiscover
class PackageManagerSuite extends Suites(
  new TestBootstrap,
  new TestManifestParser,
  new TestFlixPackageManager,
  new TestJarPackageManager
)
