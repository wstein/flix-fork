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

import java.nio.file.Path

object Options {
  /**
    * Default options.
    */
  val Default: Options = Options(
    lib = LibLevel.All,
    build = Build.Development,
    compilerTop = false,
    coverage = false,
    coverageOutput = Path.of("./build/coverage.json"),
    coverageLcovOutput = Path.of("./build/coverage.info"),
    docFormat = DocFormat.Html,
    entryPoint = None,
    githubToken = None,
    installDeps = false,
    incremental = true,
    json = false,
    outputJvm = false,
    outputPath = Path.of("./build/"),
    progress = false,
    threads = Runtime.getRuntime.availableProcessors(),
    loadClassFiles = true,
    assumeYes = false,
    xprintphases = false,
    xnodeprecated = false,
    xsummary = false,
    xsubeffecting = Set.empty,
    xdatalogDebug = Set.empty,
    xnewmono = false,
    XPerfN = None,
    XPerfFrontend = false,
    XPerfPar = false,
    xchaosMonkey = false,
    xdebug = false
  )

  /**
    * Default test options.
    */
  val DefaultTest: Options = Default.copy(lib = LibLevel.All, progress = false, xnodeprecated = true, xchaosMonkey = true)

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
  * @param lib              selects the level of libraries to include.
  * @param build            selects development or production mode.
  * @param compilerTop      shows a live TUI of where the compiler spends its time.
  * @param coverage         enables source-level coverage instrumentation.
  * @param coverageOutput   path to write the coverage report (JSON format).
  * @param docFormat        selects the format that 'flix doc' emits.
  * @param entryPoint       specifies the main entry point.
  * @param githubToken      the API key to use for GitHub dependency resolution.
  * @param incremental      enables incremental compilation.
  * @param installDeps      enables automatic installation of dependencies.
  * @param json             enable json output.
  * @param outputJvm        Enable JVM bytecode output.
  * @param outputPath       The path to the output folder.
  * @param progress         print progress during compilation.
  * @param threads          selects the number of threads to use.
  * @param loadClassFiles   loads the generated class files into the JVM.
  * @param assumeYes        run non-interactively and assume answer to all prompts is yes.
  * @param xdatalogDebug    selects which parts of the Datalog solver to trace.
  * @param xdebug           emits full debug information so a debugger can step and inspect variables.
  */
case class Options(lib: LibLevel,
                   build: Build,
                   compilerTop: Boolean,
                   coverage: Boolean,
                   coverageOutput: Path,
                   coverageLcovOutput: Path,
                   docFormat: DocFormat,
                   entryPoint: Option[Symbol.DefnSym],
                   githubToken: Option[String],
                   incremental: Boolean,
                   installDeps: Boolean,
                   json: Boolean,
                   progress: Boolean,
                   outputJvm: Boolean,
                   outputPath: Path,
                   threads: Int,
                   loadClassFiles: Boolean,
                   assumeYes: Boolean,
                   xprintphases: Boolean,
                   xnodeprecated: Boolean,
                   xsummary: Boolean,
                   xsubeffecting: Set[Subeffecting],
                   xdatalogDebug: Set[DatalogDebug],
                   xnewmono: Boolean,
                   XPerfFrontend: Boolean,
                   XPerfPar: Boolean,
                   XPerfN: Option[Int],
                   xchaosMonkey: Boolean,
                   xdebug: Boolean
                  )

/**
  * An option to control whether to run in development or production mode.
  */
sealed trait Build

object Build {
  /**
    * Run in development mode.
    */
  case object Development extends Build

  /**
    * Run in production mode.
    *
    * Running the compiler in production mode disables certain features that are allowed during development.
    */
  case object Production extends Build
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

/**
  * An option that selects which parts of the Datalog solver emit a trace.
  *
  * The Datalog subset of Flix is not compiled to code: rules are lowered into values that the
  * `Fixpoint` solver interprets, so a debugger cannot step through them. Tracing the solver is
  * therefore the only way to observe what a set of rules does.
  */
sealed trait DatalogDebug

object DatalogDebug {

  /** Trace the Datalog program, as the solver sees it after lowering. */
  case object Rules extends DatalogDebug

  /** Trace the input facts and the minimal model. */
  case object Facts extends DatalogDebug

  /** Trace the relation algebra machine, including index selection. Intended for solver developers. */
  case object Ram extends DatalogDebug

  /** All of the above. */
  val All: Set[DatalogDebug] = Set(Rules, Facts, Ram)

}

/**
  * An option that selects the format that `flix doc` emits.
  */
sealed trait DocFormat

object DocFormat {

  /** Emit HTML pages, for reading in a browser. */
  case object Html extends DocFormat

  /** Emit Markdown pages, for reading in a checkout or by a language model. */
  case object Markdown extends DocFormat

  /** Emit both. */
  case object All extends DocFormat

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
