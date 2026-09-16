/*
 * Copyright 2015-2016 Magnus Madsen
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

package ca.uwaterloo.flix.util

import ca.uwaterloo.flix.language.ast.Symbol

object Options {
  // Enable constraint-based monomorphization if "XNEWMONO" env var is set.
  private def EnableMono2: Boolean = sys.env.get("XNEWMONO").contains("1")

  /**
    * Default options.
    */
  val Default: Options = Options(
    lib = LibLevel.All,
    build = Build.Development,
    compilerTop = false,
    entryPoint = None,
    githubToken = None,
    installDeps = false,
    incremental = true,
    json = false,
    progress = false,
    threads = Runtime.getRuntime.availableProcessors(),
    assumeYes = false,
    xprintphases = false,
    xnodeprecated = false,
    xsubeffecting = Set.empty,
    xnewmono = EnableMono2,
    XPerfN = None,
    XPerfFrontend = false,
    XPerfPar = false,
    xchaosMonkey = false,
    xverify = false,
    xdatalogExecution = ExecutionMode.Parallel,
    xcollectionExecution = ExecutionMode.Parallel,
    xassumeSingleThreaded = false,
    inMemory = false
  )

  /**
    * Default test options.
    */
  val DefaultTest: Options = Default.copy(lib = LibLevel.All, progress = false, xnodeprecated = true, xchaosMonkey = true, xverify = true)

  /**
    * Default test options with the standard library.
    */
  val TestWithLibAll: Options = DefaultTest

  /**
    * Default test options with the minimal library.
    */
  val TestWithLibMin: Options = DefaultTest.copy(lib = LibLevel.Min)

  /**
    * Default test options without any library.
    */
  val TestWithLibNix: Options = DefaultTest.copy(lib = LibLevel.Nix)
}

/**
  * General Flix options.
  *
  * @param lib                     selects the level of libraries to include.
  * @param build                   selects development or production mode.
  * @param compilerTop             shows a live TUI of where the compiler spends its time.
  * @param entryPoint              specifies the main entry point.
  * @param githubToken             the token to use for authenticated GitHub requests.
  * @param incremental             enables incremental compilation.
  * @param installDeps             enables automatic installation of dependencies.
  * @param json                    enables JSON output.
  * @param progress                prints progress during compilation.
  * @param threads                 selects the number of threads to use.
  * @param assumeYes               runs non-interactively and assumes yes for every prompt.
  * @param xverify                 verifies compiler invariants after selected phases.
  * @param xdatalogExecution selects parallel or sequential execution mode for Datalog.
  * @param xcollectionExecution selects parallel or sequential evaluation of pure collection operations.
  * @param xassumeSingleThreaded asserts that the program never creates a thread, which allows the
  *                              standard library's concurrent data structures to drop their locks.
  *                              Only sound together with the two options above set to sequential,
  *                              which is why `--Xsequential` is the only way to set it from the CLI.
  * @param inMemory              runs builds in memory without writing class files to disk.
  */
case class Options(lib: LibLevel,
                   build: Build,
                   compilerTop: Boolean,
                   entryPoint: Option[Symbol.DefnSym],
                   githubToken: Option[String],
                   incremental: Boolean,
                   installDeps: Boolean,
                   json: Boolean,
                   progress: Boolean,
                   threads: Int,
                   assumeYes: Boolean,
                   xprintphases: Boolean,
                   xnodeprecated: Boolean,
                   xsubeffecting: Set[Subeffecting],
                   xnewmono: Boolean,
                   XPerfFrontend: Boolean,
                   XPerfN: Option[Int],
                   XPerfPar: Boolean,
                   xchaosMonkey: Boolean,
                   xverify: Boolean,
                   xdatalogExecution: ExecutionMode,
                   xcollectionExecution: ExecutionMode,
                   xassumeSingleThreaded: Boolean,
                   inMemory: Boolean
                  ) {

  /**
    * Returns `true` if the program may be compiled as if it were single-threaded.
    *
    * This governs the parts of the library and the generated runtime whose only purpose is to be
    * safe under concurrency: the locks in `BPlusTree` and `Fixpoint3`, the counter in a
    * `BPlusTree`, the lock in a `Lazy`, the atomic behind `Global.newId`, and the region's
    * bookkeeping of its child threads. It also decides how the two ways of writing concurrency are
    * compiled: `par (...) yield` binds its fragments in order, and `spawn` is rejected.
    *
    * Asserting [[xassumeSingleThreaded]] is not enough on its own. Giving those up is only sound if
    * nothing is left that could run in parallel, so both execution modes must be sequential as
    * well. `--Xsequential` sets all three together; this keeps the compiler API from producing an
    * unsynchronized program under a configuration that would race.
    */
  def isSingleThreaded: Boolean =
    xassumeSingleThreaded &&
      xdatalogExecution == ExecutionMode.Sequential &&
      xcollectionExecution == ExecutionMode.Sequential

}

/**
  * An option to control whether to run in development or production mode.
  */
sealed trait Build {
  /**
    * The name of the directory this mode's output goes in, under the project's build directory.
    */
  def directoryName: String
}

object Build {
  /**
    * Run in development mode.
    */
  case object Development extends Build {
    override val directoryName: String = "development"
  }

  /**
    * Run in production mode.
    *
    * Running the compiler in production mode disables certain features that are allowed during development.
    */
  case object Production extends Build {
    override val directoryName: String = "production"
  }
}

sealed trait LibLevel

object LibLevel {

  /**
    * Do not include any libraries, even those essential for basic functionality.
    */
  case object Nix extends LibLevel

  /**
    * Only include essential libraries.
    */
  case object Min extends LibLevel

  /**
    * Include the full standard library.
    */
  case object All extends LibLevel

}

sealed trait Subeffecting

object Subeffecting {

  /**
    * Enable sub-effecting for module-level definitions.
    */
  case object ModDefs extends Subeffecting

  /**
    * Enable sub-effecting for instance-level defs.
    */
  case object InsDefs extends Subeffecting

  /**
    * Enable sub-effecting for lambda expressions.
    */
  case object Lambdas extends Subeffecting

}
