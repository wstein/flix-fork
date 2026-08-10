/*
 * Copyright 2023 Magnus Madsen
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
package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.api.Bootstrap.{EXT_CLASS, EXT_FLIX, EXT_FPKG, EXT_JAR, FLIX_TOML, LICENSE, README}
import ca.uwaterloo.flix.api.effectlock.{EffectLock, EffectUpgrade, UseGraph}
import ca.uwaterloo.flix.api.lsp.FormatterLsp as LspFormatter
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.ast.{Scheme, SourceLocation, Symbol, TypedAst}
import ca.uwaterloo.flix.language.phase.Documentor
import ca.uwaterloo.flix.runtime.CompilationResult
import ca.uwaterloo.flix.runtime.shell.FileWatcher
import ca.uwaterloo.flix.tools.Tester
import ca.uwaterloo.flix.tools.fmt.PrettyPrinter
import ca.uwaterloo.flix.tools.pkg.github.GitHub
import ca.uwaterloo.flix.tools.pkg.{FlixPackageManager, JarPackageManager, Manifest, ManifestParser, MavenPackageManager, PackageModules, ReleaseError}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.collection.ListMap
import ca.uwaterloo.flix.util.{Build, FileOps, Formatter, Result}

import java.io.{IOException, PrintStream}
import java.nio.file.{FileSystems, Files, Path, StandardCopyOption}
import java.util.zip.{ZipInputStream, ZipOutputStream}
import scala.collection.mutable
import scala.io.StdIn.readLine
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.util.{Failure, Success, Using}


object Bootstrap {

  /** Metadata collected by the `flix init` wizard. */
  case class InitOptions(description: String, author: String, license: InitLicense = InitLicense.NoLicense)

  /** A license choice supported by the `flix init` wizard. */
  sealed trait InitLicense {
    def spdxId: Option[String]
  }

  object InitLicense {
    case object Apache2 extends InitLicense {
      val spdxId: Option[String] = Some("Apache-2.0")
    }

    case object Mit extends InitLicense {
      val spdxId: Option[String] = Some("MIT")
    }

    case object Bsd3 extends InitLicense {
      val spdxId: Option[String] = Some("BSD-3-Clause")
    }

    case object Gpl3 extends InitLicense {
      val spdxId: Option[String] = Some("GPL-3.0-only")
    }

    case object NoLicense extends InitLicense {
      val spdxId: Option[String] = None
    }
  }

  object InitOptions {
    val Default: InitOptions = InitOptions(
      description = "TODO",
      author = "TODO",
      license = InitLicense.Apache2
    )
  }

  /**
    * Initializes a new flix project at the given path `p`.
    *
   * Creates the project directory when it does not already exist.
    */
  def init(p: Path)(implicit out: PrintStream): Result[Unit, BootstrapError] =
    init(p, InitOptions.Default)

  /**
    * Initializes a new Flix project at `p` with the supplied package metadata.
    */
  def init(p: Path, options: InitOptions)(implicit out: PrintStream): Result[Unit, BootstrapError] = {
    //
    // Create the project directory, then check that it is usable.
    //
    try {
      FileOps.newDirectoryIfAbsent(p)
    } catch {
      case e: IOException =>
        return Result.Err(BootstrapError.FileError(s"Unable to create directory '$p': ${e.getMessage}"))
    }

    if (!Files.isDirectory(p) || !Files.isReadable(p) || !Files.isWritable(p)) {
      return Result.Err(BootstrapError.FileError(s"The directory: '$p' is not accessible. Aborting."))
    }

    //
    // Compute the name of the package based on the directory name.
    //
    val packageName = getPackageName(p)

    //
    // Compute all the directories and files we intend to create.
    //
    val sourceDirectory = getSourceDirectory(p)
    val testDirectory = getTestDirectory(p)
    val workflowsDirectory = getWorkflowsDirectory(p)

    val manifestFile = getManifestFile(p)
    val gitignoreFile = getGitIgnoreFile(p)
    val editorConfigFile = getEditorConfigFile(p)
    val agentsFile = getAgentsFile(p)
    val claudeMdFile = getClaudeMdFile(p)
    val copilotInstructionsFile = getCopilotInstructionsFile(p)
    val licenseFile = getLicenseFile(p)
    val readmeFile = getReadmeFile(p)
    val mainSourceFile = getMainSourceFile(p)
    val mainTestFile = getMainTestFile(p)
    val buildAndTestWorkflowFile = getBuildAndTestWorkflowFile(p)

    //
    // Create the project directories and files.
    //
    FileOps.newDirectoryIfAbsent(sourceDirectory)
    FileOps.newDirectoryIfAbsent(testDirectory)
    FileOps.newDirectoryIfAbsent(workflowsDirectory)

    FileOps.newFileIfAbsent(manifestFile) {
      val lines = List(
        "[package]",
        s"name        = \"$packageName\"",
        s"description = \"${escapeTomlString(options.description)}\"",
        "version     = \"0.1.0\"",
        s"flix        = \"${Version.CurrentVersion}\""
      ) ++ options.license.spdxId.map(id => s"license     = \"$id\"") ++ List(
        s"authors     = [\"${escapeTomlString(options.author)}\"]"
      )
      lines.mkString("", "\n", "\n")
    }

    FileOps.newFileIfAbsent(gitignoreFile) {
      s"""*.fpkg
         |*.jar
         |.GITHUB_TOKEN
         |$artifactDirectoryRaw
         |$buildDirectoryRaw
         |$libDirectoryRaw
         |$bspDirectoryRaw
         |crash_report_*.txt
         |""".stripMargin
    }

    FileOps.newFileIfAbsent(editorConfigFile) {
      """# EditorConfig for Flix projects — https://editorconfig.org
        |#
        |# A compatibility floor, not a style guide. It keeps an editor that knows nothing
        |# about Flix from fighting the layout of a Flix source file: open a formatted
        |# file, type in it, save it, and it is still formatted.
        |#
        |# Rules that require parsing Flix — spacing, wrapping, alignment — are left to
        |# `flix format`, which is the authority on them.
        |
        |root = true
        |
        |[*]
        |charset = utf-8
        |end_of_line = lf
        |indent_style = space
        |indent_size = 4
        |insert_final_newline = true
        |trim_trailing_whitespace = true
        |
        |[*.flix]
        |# `indent_size` is the load-bearing key here. Per the EditorConfig specification
        |# `tab_width` defaults to `indent_size`, but the implication does not run the
        |# other way: setting only `tab_width` leaves the width of a space indent to each
        |# editor's own default.
        |indent_size = 4
        |tab_width = 4
        |
        |# `max_line_length` is off on purpose. Some editors read it as an instruction to
        |# hard-wrap, which reformats code without understanding its syntax.
        |max_line_length = off
        |
        |# IntelliJ-family IDEs default to a continuation indent of 8, which fights the
        |# four-space indent on every wrapped signature. `ij_` keys are ignored by every
        |# other editor, so this line costs nothing elsewhere.
        |ij_continuation_indent_size = 4
        |
        |[*.{yaml,yml,json}]
        |indent_size = 2
        |
        |[*.md]
        |indent_size = 2
        |
        |# Trailing whitespace is significant in Markdown: two trailing spaces are a hard
        |# line break. Trimming it silently changes the rendered output.
        |trim_trailing_whitespace = false
        |""".stripMargin
    }

    FileOps.newFileIfAbsent(agentsFile) {
      mkAgentGuide()
    }

    FileOps.newFileIfAbsent(claudeMdFile) {
      ClaudeMd
    }

    FileOps.newFileIfAbsent(copilotInstructionsFile) {
      CopilotInstructions
    }

    FileOps.newFileIfAbsent(licenseFile) {
      options.license.spdxId match {
        case Some(id) =>
          s"""# $id
             |
             |This project declares `$id` in `flix.toml`. Add the full license text and the
             |appropriate copyright notice before distributing the project.
             |""".stripMargin
        case None =>
          """No license selected. Add license information here before distributing the project.
            |""".stripMargin
      }
    }

    FileOps.newFileIfAbsent(readmeFile) {
      s"""# $packageName
         |
         |${options.description}
         |
         |""".stripMargin
    }

    FileOps.newFileIfAbsent(mainSourceFile) {
      """// The main entry point.
        |def main(): Unit \ IO =
        |    println("Hello World!")
        |""".stripMargin
    }

    FileOps.newFileIfAbsent(mainTestFile) {
      """@Test
        |def test01(): Unit \ Assert = Assert.assertEq(expected = 2, 1 + 1)
        |""".stripMargin
    }

    FileOps.newFileIfAbsent(buildAndTestWorkflowFile) {
      """name: Build and Test
        |
        |on:
        |  pull_request:
        |  push:
        |    branches: [ main, master ]
        |
        |jobs:
        |  build-and-test:
        |    runs-on: ubuntu-latest
        |    steps:
        |      - name: Check out
        |        uses: actions/checkout@v5
        |
        |      - name: Install JDK 21
        |        uses: actions/setup-java@v4
        |        with:
        |          distribution: 'temurin'
        |          java-version: '21'
        |
        |      - name: Read Flix version from flix.toml
        |        id: flix
        |        run: |
        |          version=$(grep -E '^"?flix"?[[:space:]]*=' flix.toml \
        |            | head -n1 \
        |            | sed -E 's/.*"([^"]+)"[[:space:]]*$/\1/')
        |          echo "version=$version" >> "$GITHUB_OUTPUT"
        |
        |      - name: Download Flix
        |        run: |
        |          curl -fsSL -o flix.jar \
        |            "https://github.com/flix/flix/releases/download/v${{ steps.flix.outputs.version }}/flix.jar"
        |
        |      - name: Check
        |        run: java -jar flix.jar check
        |
        |      - name: Test
        |        run: java -jar flix.jar test
        |""".stripMargin
    }
    Result.Ok(())
  }

  /**
    * Rewrites the generated agent guide at the given path `p` for the running compiler.
    *
    * The guide names the version it was generated for, so it goes stale as soon as the project
    * moves to another Flix release. Only a guide that still carries [[AGENT_GUIDE_MARKER]] is
    * rewritten: deleting that line hands the file to the project, exactly as a Markdown
    * documentation page without its marker is left alone by `doc`.
    */
  def refreshAgentGuide(p: Path)(implicit out: PrintStream): Result[Unit, BootstrapError] = {
    if (!Files.isDirectory(p) || !Files.isReadable(p) || !Files.isWritable(p)) {
      return Result.Err(BootstrapError.FileError(s"The directory: '$p' is not accessible. Aborting."))
    }

    val agentsFile = getAgentsFile(p)

    if (!Files.exists(agentsFile)) {
      FileOps.writeString(agentsFile, mkAgentGuide())
      out.println(s"Created '$AGENTS_MD' for Flix ${Version.CurrentVersion}.")
    } else if (!Files.readString(agentsFile).contains(AGENT_GUIDE_MARKER)) {
      out.println(s"Left '$AGENTS_MD' alone: it no longer carries the generated marker.")
    } else {
      FileOps.writeString(agentsFile, mkAgentGuide())
      out.println(s"Refreshed '$AGENTS_MD' for Flix ${Version.CurrentVersion}.")
    }

    // The guide reaches Claude Code only through the import in CLAUDE.md, and a missing import
    // fails silently: the guide is simply never loaded.
    val claudeMdFile = getClaudeMdFile(p)
    if (Files.exists(claudeMdFile) && !Files.readString(claudeMdFile).contains(s"@$AGENTS_MD")) {
      out.println(s"Note: '$CLAUDE_MD' does not import '$AGENTS_MD'; add a line reading '@$AGENTS_MD' to load the guide.")
    }

    Result.Ok(())
  }

  /**
    * Returns the generated agent guide, stamped with the version of the compiler that wrote it.
    *
    * Four rules bind what may go in here; see the scaffolding section of `.claude/CLAUDE.md`. The
    * one that is easiest to break by accident: every line must be true of the shipped binary, so
    * `flix format` stays out until it does something.
    */
  private def mkAgentGuide(): String = {
    val marker = s"$AGENT_GUIDE_MARKER generated for Flix ${Version.CurrentVersion}. " +
      s"Rewritten by 'flix init --refresh'; delete this line to keep your own edits. -->"
    s"$marker\n$AgentGuideBody"
  }

  /**
    * The body of the generated agent guide.
    *
    * Kept out of [[mkAgentGuide]] because an interpolated string processes escape sequences, and
    * the guide has to show effect syntax as it is written in Flix.
    */
  private val AgentGuideBody: String =
    """
      |# Working on this project
      |
      |A Flix project. The Flix version it targets is pinned in `flix.toml`.
      |
      |## Commands
      |
      |- `flix check` — type-check without generating code; the fast feedback loop
      |- `flix test` — run every `@Test` function under `test/`
      |- `flix run` — run `main`
      |- `flix build` — compile the project; `--clean` rebuilds from scratch
      |- `flix doc` — write API documentation for the standard library and this project to `build/doc/`
      |
      |## Layout
      |
      |- `src/` — sources; `src/Main.flix` holds `main`
      |- `test/` — `@Test` functions
      |- `flix.toml` — package metadata, the Flix version, and dependencies
      |- `build/`, `artifact/`, `lib/` — generated; do not edit and do not commit
      |
      |## Writing Flix
      |
      |Your training data is probably older than this compiler. Read
      |https://doc.flix.dev/for-llms.html before writing Flix: it lists what changed. For the
      |standard library use https://api.flix.dev, or run `flix doc` and read `build/doc/`, which
      |matches this project's compiler exactly.
      |
      |The mistakes that show up most often:
      |
      |- `def main(): Unit \ IO = ...` — arguments come from `Env.getArgs()`, not from parameters
      |- effects are written with `\`, not `&`
      |- effect operations are called like ordinary functions; there is no `do` keyword
      |- handlers are `run { ... } with handler E { ... }`; chain them rather than nesting `run`
      |- annotations are uppercase: `@Test`, `@Lazy`, `@Parallel`, `@MustUse`
      |- Java types need a top-level `import`, and all Java interop carries `IO`
      |
      |Prefer effects and handlers to callbacks or hand-written CPS, and standard library effects
      |to Java interop.
      |""".stripMargin

  /**
    * The generated `CLAUDE.md`.
    *
    * Claude Code reads `CLAUDE.md` and not `AGENTS.md`, so the guide is written once and imported
    * here. This file is never rewritten, because anything a project adds below the import is its
    * own.
    */
  private val ClaudeMd: String =
    """<!-- Imports the generated agent guide. Add Claude-specific instructions below it. -->
      |@AGENTS.md
      |""".stripMargin

  /**
    * The generated GitHub Copilot repository instructions.
    *
    * The project guide remains the single source of truth. This small wrapper makes that guide
    * discoverable from Copilot's repository-wide instruction location without duplicating it.
    */
  private val CopilotInstructions: String =
    """# Flix Project Instructions
      |
      |Read and follow [`AGENTS.md`](../AGENTS.md) for this project's commands, layout, and Flix guidance.
      |""".stripMargin

  /** The class file extension. Does not contain leading '.' */
  private val EXT_CLASS: String = "class"

  /** The flix file extension. Does not contain leading '.' */
  private val EXT_FLIX: String = "flix"

  /** The flix package file extension. Does not contain leading '.' */
  private val EXT_FPKG: String = "fpkg"

  /** The jar file extension. Does not contain leading '.' */
  private val EXT_JAR: String = "jar"

  /** The manifest / flix toml file name. */
  private val FLIX_TOML: String = "flix.toml"

  /** The license file name. */
  private val LICENSE: String = "LICENSE.md"

  /** The readme file name. */
  private val README: String = "README.md"

  /** The agent guide file name. */
  private val AGENTS_MD: String = "AGENTS.md"

  /** The file name Claude Code reads. */
  private val CLAUDE_MD: String = "CLAUDE.md"

  /** The file name GitHub Copilot reads for repository-wide instructions. */
  private val COPILOT_INSTRUCTIONS_MD: String = "copilot-instructions.md"

  /**
    * The opening of the marker comment on a generated agent guide.
    *
    * A block-level HTML comment, so it is stripped before the guide is given to a model and costs
    * no context. Changing this text orphans every guide written by an earlier version: they lose
    * the marker and `--refresh` will decline to touch them.
    */
  private val AGENT_GUIDE_MARKER: String = "<!-- flix-init:"

  /** The build-and-test GitHub Actions workflow file name. */
  private val BUILD_AND_TEST_WORKFLOW: String = "build-and-test.yaml"

  /**
    * The relative path to the GitHub Actions workflows directory as a string.
    *
    * N.B.: Use [[getWorkflowsDirectory]] if possible.
    */
  private val workflowsDirectoryRaw: String = ".github/workflows/"

  /**
    * Returns the path to the artifact directory relative to the given path `p`.
    */
  private def getArtifactDirectory(p: Path): Path = p.resolve(s"./$artifactDirectoryRaw").normalize()

  /**
    * The relative path to the artifact directory as a string.
    *
    * N.B.: Use [[getArtifactDirectory]] if possible.
    */
  private val artifactDirectoryRaw: String = "artifact/"

  /**
    * Returns the path to the library directory relative to the given path `p`.
    */
  def getLibraryDirectory(p: Path): Path = p.resolve(s"./$libDirectoryRaw").normalize()

  /**
    * The relative path to the library directory as a string.
    *
    * N.B.: Use [[getLibraryDirectory]] if possible.
    */
  private val libDirectoryRaw: String = "lib/"

  /**
    * The relative path to the build server's discovery directory as a string.
    *
    * Named here only so that `init` can ignore it. A connection file names a compiler jar on one
    * machine, so it has no business in a repository.
    *
    * @see [[ca.uwaterloo.flix.api.bsp.BspDiscovery]]
    */
  private val bspDirectoryRaw: String = ".bsp/"

  /**
    * Returns the path to the source directory relative to the given path `p`.
    */
  private def getSourceDirectory(p: Path): Path = p.resolve("./src/").normalize()

  /**
    * Returns the path to the test directory relative to the given path `p`.
    */
  private def getTestDirectory(p: Path): Path = p.resolve("./test/").normalize()

  /**
    * Returns the path to the build directory relative to the given path `p`.
    */
  private def getBuildDirectory(p: Path): Path = p.resolve(s"./$buildDirectoryRaw").normalize()

  /**
    * The relative path to the build directory as a string.
    *
    * N.B.: Use [[getBuildDirectory]] if possible.
    */
  private val buildDirectoryRaw: String = "build/"

  /**
    * Returns the output directory of the build mode `build`, relative to the given path `p`.
    *
    * Each mode owns a directory - `build/development/` and `build/production/` - and its class files, its
    * build manifest and its product set all live in that one. They cannot be shared: the mode
    * reaches the typer, so the two modes do not compile the same program, and a single directory
    * would have each build invalidate the other's and reset it.
    *
    * This is what `Options.outputPath` is set to, so it is also where the class files land:
    * `JvmWriter` resolves `class/` against that path, which is what [[getClassDirectory]]
    * returns.
    */
  private def getOutputDirectory(p: Path, build: Build): Path =
    getBuildDirectory(p).resolve(s"./${build.directoryName}/").normalize()

  /**
    * Returns the directory of the output .class-files of the build mode `build`, relative to the
    * given path `p`.
    */
  private def getClassDirectory(p: Path, build: Build): Path =
    getOutputDirectory(p, build).resolve("./class/").normalize()

  /**
    * Returns the path to the build manifest of the build mode `build`, relative to the given
    * path `p`.
    *
    * @see [[BuildManifest]]
    */
  private def getBuildManifestFile(p: Path, build: Build): Path =
    BuildManifest.fileIn(getOutputDirectory(p, build))

  /** Every build mode, so that `clean` can visit the output of each. */
  private val AllBuilds: List[Build] = List(Build.Development, Build.Production)

  /**
    * What a compilation produced, and what the compiler said while producing it.
    *
    * Kept apart from `Result` on purpose. A `Result` says a build either worked or failed, which is
    * what a command needs to decide an exit code -- but a compile that *succeeded* can still carry
    * messages, and a caller publishing diagnostics has to report those. Collapsing the two loses
    * exactly the case a build server exists to handle.
    *
    * @param result   the compilation, if the program type checked.
    * @param root     the typed program, when there is one -- available even on failure, which is
    *                 what lets a caller name the sources a previous report covered.
    * @param messages everything the compiler said. Empty exactly when nothing was wrong.
    * @param error    a failure of the *build* rather than of the code: an output directory that
    *                 could not be emptied, a class directory that could not be reconciled. Separate
    *                 from `messages` because it has no source location and is not the user's fault.
    */
  case class CompileOutcome(result: Option[CompilationResult],
                            root: Option[TypedAst.Root],
                            messages: List[CompilationMessage],
                            error: Option[BootstrapError] = None) {

    /** Returns `true` if the program compiled and the build finished. */
    def isSuccess: Boolean = result.isDefined && error.isEmpty

    /** Collapses to the shape a command wants: the compilation, or what prevented it. */
    def toResult: Result[CompilationResult, BootstrapError] = (result, error) match {
      // A build failure outranks a successful compile: a class directory that was not reconciled is
      // one whose contents nobody can describe, whatever the compiler managed.
      case (_, Some(e)) => Err(e)
      case (Some(compiled), None) => Ok(compiled)
      case (None, None) => Err(BootstrapError.CompilationErrors(messages, root))
    }
  }

  /**
    * Returns the directory `flix stubs` writes Java stubs to by default, relative to `p`.
    *
    * @see [[ca.uwaterloo.flix.tools.ExportStubs]]
    */
  private def getStubsDirectory(p: Path): Path = getBuildDirectory(p).resolve("./stubs/").normalize()

  /**
    * The coverage reports, relative to the build directory.
    *
    * These are the default names only. `--coverage-output` can put a report anywhere, and one
    * written outside the build directory is not `clean`'s business - but one left at its default
    * name is, and before it was recognised here `flix test --coverage` made a project that `flix
    * clean` refused to clean at all.
    *
    * @see [[ca.uwaterloo.flix.util.Options.coverageOutput]]
    */
  private val CoverageReports: List[String] = List("coverage.json", "coverage.info")

  /**
    * Returns the directory of the generated documentation files relative to the given path `p`.
    */
  private def getDocumentationDirectory(p: Path): Path = getBuildDirectory(p).resolve("./doc/").normalize()

  /**
    * Returns the path to the artifact directory relative to the given path `p`.
    */
  private def getResourcesDirectory(p: Path): Path = p.resolve("./resources/").normalize()

  /**
    * Returns the path to the `effects.lock` relative to the given path `p`.
    */
  private def getEffectLockFile(p: Path): Path = p.resolve("effects.lock").normalize()

  /**
    * Returns the path to the LICENSE file relative to the given path `p`.
    */
  private def getLicenseFile(p: Path): Path = p.resolve(s"./$LICENSE").normalize()

  /**
    * Returns the path to the README file relative to the given path `p`.
    */
  private def getReadmeFile(p: Path): Path = p.resolve(s"./$README").normalize()

  /**
    * Returns the path to the GitHub Actions workflows directory relative to the given path `p`.
    */
  private def getWorkflowsDirectory(p: Path): Path = p.resolve(s"./$workflowsDirectoryRaw").normalize()

  /**
    * Returns the path to the build-and-test workflow file relative to the given path `p`.
    */
  private def getBuildAndTestWorkflowFile(p: Path): Path = getWorkflowsDirectory(p).resolve(s"./$BUILD_AND_TEST_WORKFLOW").normalize()

  /**
    * Returns the path to the main source file relative to the given path `p`.
    */
  private def getMainSourceFile(p: Path): Path = getSourceDirectory(p).resolve("./Main.flix").normalize()

  /**
    * Returns the path to the main test file relative to the given path `p`.
    */
  private def getMainTestFile(p: Path): Path = getTestDirectory(p).resolve("./TestMain.flix").normalize()

  /**
    * Returns the path to the Manifest file relative to the given path `p`.
    */
  private def getManifestFile(p: Path): Path = p.resolve(s"./$FLIX_TOML").normalize()

  /** Escapes a value for a TOML basic string. */
  private def escapeTomlString(s: String): String = s.flatMap {
    case '\\' => "\\\\"
    case '"'  => "\\\""
    case '\b' => "\\b"
    case '\t' => "\\t"
    case '\n' => "\\n"
    case '\f' => "\\f"
    case '\r' => "\\r"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c => c.toString
  }

  /**
    * Returns the path to the .gitignore file relative to the given path `p`.
    */
  private def getGitIgnoreFile(p: Path): Path = p.resolve("./.gitignore").normalize()

  /**
    * Returns the path to the .editorconfig file relative to the given path `p`.
    */
  private def getEditorConfigFile(p: Path): Path = p.resolve("./.editorconfig").normalize()

  /**
    * Returns the path to the AGENTS.md file relative to the given path `p`.
    */
  private def getAgentsFile(p: Path): Path = p.resolve(s"./$AGENTS_MD").normalize()

  /**
    * Returns the path to the CLAUDE.md file relative to the given path `p`.
    */
  private def getClaudeMdFile(p: Path): Path = p.resolve(s"./$CLAUDE_MD").normalize()

  /**
    * Returns the path to the GitHub Copilot instructions file relative to the given path `p`.
    */
  private def getCopilotInstructionsFile(p: Path): Path =
    p.resolve(s"./.github/$COPILOT_INSTRUCTIONS_MD").normalize()

  /**
    * Returns the path to the jar file based on the given path `p`.
    */
  private def getJarFile(p: Path): Path = getArtifactDirectory(p).resolve(getPackageName(p) + s".$EXT_JAR").normalize()

  /**
    * Returns the package name based on the given path `p`.
    */
  private def getPackageName(p: Path): String = p.toAbsolutePath.normalize().getFileName.toString

  /**
    * Returns the path to the pkg file based on the given path `p`.
    */
  private def getPkgFile(p: Path): Path = getArtifactDirectory(p).resolve(getPackageName(p) + s".$EXT_FPKG").normalize()

  /**
    * Returns `true` if the given path `p` is a jar-file.
    */
  private def isJarFile(p: Path): Boolean = p.normalize().getFileName.toString.endsWith(s".$EXT_JAR") && FileOps.isZipArchive(p)

  /**
    * Returns `true` if the given path `p` is a fpkg-file.
    */
  private def isPkgFile(p: Path): Boolean = p.normalize().getFileName.toString.endsWith(s".$EXT_FPKG") && FileOps.isZipArchive(p)

  /**
    * Creates a new Bootstrap object and initializes it.
    * If a `flix.toml` file exists, parses that to a Manifest and
    * downloads all required files. Otherwise, checks the /lib directory
    * to see what dependencies are already downloaded. Also finds
    * all .flix source files.
    * Then returns the initialized Bootstrap object or an error.
    */
  def bootstrap(path: Path, apiKey: Option[String])(implicit formatter: Formatter, out: PrintStream): Result[Bootstrap, BootstrapError] = {
    //
    // Determine the mode: If `path/flix.toml` exists then "project" mode else "directory mode".
    //
    val bootstrap = new Bootstrap(path, apiKey)
    val tomlPath = getManifestFile(path)
    if (Files.exists(tomlPath)) {
      out.println(s"Found '${formatter.blue(FLIX_TOML)}'. Checking dependencies...")
      bootstrap.projectMode().map(_ => bootstrap)
    } else {
      out.println(s"""No '${formatter.blue(FLIX_TOML)}'. Will load source files from '${formatter.blue(s"*.$EXT_FLIX")}', '${formatter.blue("src/**")}', and '${formatter.blue("test/**")}'.""")
      bootstrap.directoryMode().map(_ => bootstrap)
    }
  }
}

class Bootstrap(val projectPath: Path, apiKey: Option[String]) {

  // The `flix.toml` manifest if in project mode, otherwise `None`
  private var optManifest: Option[Manifest] = None

  // Timestamps at the point the sources were loaded
  private var timestamps: Map[Path, Long] = Map.empty

  // The Flix instance the timestamps above - and the drained watcher events - describe.
  // Which sources are stale is a question about one instance: a different instance has been
  // given nothing, however recently this one was brought up to date.
  private var lastFlix: Option[Flix] = None

  // Lists of paths to the source files, flix packages and .jar files used
  private var sourcePaths: List[Path] = List.empty
  private var flixPackagePaths: List[Path] = List.empty
  private var mavenPackagePaths: List[Path] = List.empty
  private var jarPackagePaths: List[Path] = List.empty

  private var securityLevels: Map[Path, SecurityContext] = Map.empty

  // The file watcher, if active (used by the REPL shell).
  private var fileWatcher: Option[FileWatcher] = None

  /**
    * Starts a file system watcher that monitors the project directories for changes.
    * When active, `updateStaleSources` will drain watcher events instead of polling timestamps.
    */
  def startWatching(): Unit = {
    val fw = new FileWatcher()
    fw.watchShallow(projectPath)
    // Register these as recursive roots even if they don't exist yet.
    // The watcher will automatically pick them up when they are created.
    fw.watchRecursively(Bootstrap.getSourceDirectory(projectPath))
    fw.watchRecursively(Bootstrap.getTestDirectory(projectPath))
    fw.watchRecursively(Bootstrap.getLibraryDirectory(projectPath))
    fw.start()
    fileWatcher = Some(fw)
  }

  /**
    * Stops the file system watcher, if active.
    */
  def stopWatching(): Unit = {
    fileWatcher.foreach(_.stop())
    fileWatcher = None
  }

  /**
    * Applies any pending file changes to the Flix instance.
    * When the file watcher is active, drains watcher events.
    * Otherwise, falls back to timestamp-based change detection.
    */
  def applyFileChanges(flix: Flix): Unit = {
    Steps.updateStaleSources(flix)
  }

  /**
    * Parses `flix.toml` to a Manifest and downloads all required files.
    * Then makes a list of all flix source files, flix packages
    * and .jar files that this project uses.
    */
  private def projectMode()(implicit formatter: Formatter, out: PrintStream): Result[Unit, BootstrapError] = {
    val tomlPath = Bootstrap.getManifestFile(projectPath)
    for {
      manifest <- Steps.parseManifest(tomlPath)
      deps <- Steps.resolveFlixDependencies(manifest)
      _ <- Steps.installDependencies(deps)
      _ = Steps.addLocalFlixFiles()
    } yield {
      ()
    }
  }

  /**
    * Checks the /lib directory to find existing flix packages and .jar files.
    * Then makes a list of all flix source files, flix packages
    * and .jar files that this project uses.
    */
  private def directoryMode(): Result[Unit, BootstrapError] = {
    Steps.addLocalFlixFiles()
    Steps.addLocalLibs()
    Result.Ok(())
  }

  /**
    * Builds (compiles) the source files for the project.
    *
    * @param clean empties the output directory before compiling and rebuilds from nothing. This
    *              is the only path that deletes before it knows the compile succeeds, and it does
    *              so because that is what was asked for.
    * @see [[compileProject]]
    */
  def build(flix: Flix, build: Build = Build.Development, clean: Boolean = false): Result[CompilationResult, BootstrapError] = {
    val newOptions = flix.options.copy(build = build, outputJvm = true, outputPath = Bootstrap.getOutputDirectory(projectPath, build))
    flix.setOptions(newOptions)
    compileProject(flix, clean)
  }

  /**
    * Builds a jar package for the project.
    *
    * The jar holds exactly the class files this build wrote - not whatever the class
    * directory happens to contain - so it is a function of the current sources alone whether
    * or not the build directory was wiped first.
    *
    * Builds in production mode, so its output is `build/production/` and it neither reads nor
    * invalidates what `flix build` left in `build/development/`.
    *
    * @param clean empties the output directory before compiling and rebuilds from nothing. This
    *              is what a reproducible release wants: it makes the jar a function of the sources
    *              and nothing else, including anything an earlier build left in the directory.
    */
  def buildJar(flix: Flix, clean: Boolean = false): Result[Unit, BootstrapError] = {
    val jarFile = Bootstrap.getJarFile(projectPath)
    for {
      _ <- Steps.configureJarOutput(flix)
      result <- compileProject(flix, clean)
      _ <- Steps.validateJarFile(jarFile)
      _ <- Steps.validateProducts(Build.Production, result.products)
      contents = (zip: ZipOutputStream) => {
        Steps.addProductsToZip(Build.Production, result.products, zip)
        Steps.addResourcesFromDirToZip(Bootstrap.getResourcesDirectory(projectPath), zip)
      }
      _ <- Steps.createJar(jarFile, contents)
    } yield {
      ()
    }
  }

  /**
    * Builds a fatjar package for the project.
    *
    * @param clean empties the output directory before compiling and rebuilds from nothing.
    * @see [[buildJar]]
    */
  def buildFatJar(flix: Flix, clean: Boolean = false): Result[Unit, BootstrapError] = {
    val jarFile = Bootstrap.getJarFile(projectPath)
    val libDir = Bootstrap.getLibraryDirectory(projectPath)
    for {
      _ <- Steps.configureJarOutput(flix)
      result <- compileProject(flix, clean)
      _ <- Steps.validateJarFile(jarFile)
      _ <- Steps.validateProducts(Build.Production, result.products)
      _ <- Steps.validateDirectory(libDir)
      _ <- Steps.validateJarFilesIn(libDir)
      contents = (zip: ZipOutputStream) => {
        Steps.addProductsToZip(Build.Production, result.products, zip)
        Steps.addResourcesFromDirToZip(Bootstrap.getResourcesDirectory(projectPath), zip)
        Steps.addJarsFromDirToZip(libDir, zip, extraJars = mavenPackagePaths ::: jarPackagePaths)
      }
      _ <- Steps.createJar(jarFile, contents)
    } yield {
      ()
    }
  }

  /**
    * Compiles the project and leaves the class directory holding exactly the class files this
    * compilation wrote.
    *
    * The class directory is *reconciled* rather than wiped: the compiler's back end is
    * whole-program, so the set of class files it just wrote is the complete set the current
    * sources require, and every other class file in the directory belongs to an earlier build.
    * Deleting those - and only those - leaves the same directory a wipe-and-recompile would,
    * without discarding the front end's caches or rewriting files that did not change.
    * [[BuildManifest]] records the outcome so that a build which fails, or one interrupted
    * between writing and packaging, still leaves a directory that can be described.
    *
    * A full build - nothing the previous build left is reused - happens for two different
    * reasons, and they are deliberately *not* the same operation:
    *
    *   - `clean` is set. The caller asked to build from nothing, so the output directory is
    *     emptied before the compile starts: class files, the directories that leaves empty, and
    *     the manifest. There is then no moment at which the previous build's output could be
    *     mistaken for this one's, which is the guarantee a reproducible release is after. The cost
    *     is that a `--clean` whose compile then fails leaves nothing behind - the same bargain
    *     `make clean && make` offers, and the caller asked for it.
    *   - The recorded fingerprint of the non-source inputs - compiler version, back-end options,
    *     dependencies - does not match this build's. Nothing an earlier build left was produced
    *     under these settings, so the compiler's in-memory state is discarded; but **nothing on
    *     disk is deleted**, because nobody asked for that. Reconciling after a successful compile
    *     reaches the same directory anyway, and emptying it up front would destroy a working
    *     build's output whenever the compile meant to replace it fails. A failing compile is
    *     ordinary, and so are the inputs that land here: a compiler upgrade, a new `--coverage`
    *     flag, an updated dependency.
    *
    * Everything here is scoped to the output directory of the mode `flix` is configured for, so
    * a development build and a production build keep separate products and separate manifests
    * and neither resets the other.
    *
    * If compilation fails, nothing on disk is touched *except* by an explicit `clean`: the class
    * directory and the manifest still describe the last build that succeeded.
    */
  private def compileProject(flix: Flix, clean: Boolean): Result[CompilationResult, BootstrapError] =
    compileProjectOutcome(flix, clean).toResult

  /**
    * Compiles the project and reports what the compiler said, not only whether it worked.
    *
    * The same path as [[compileProject]] -- reconciliation and the manifest included -- because a
    * second compile path is how a build server's idea of a build drifts from `flix build`'s.
    *
    * On failure the messages are returned and nothing on disk is touched, so a caller can publish
    * diagnostics for a program that does not compile without the class directory being disturbed.
    */
  private[api] def compileProjectOutcome(flix: Flix, clean: Boolean): Bootstrap.CompileOutcome = {
    val build = flix.options.build
    val fingerprint = BuildManifest.fingerprintOf(flix.options, dependencyPaths)
    val recorded = Steps.readBuildManifest(build)
    val staleInputs = !recorded.exists(_.fingerprint == fingerprint)

    if (clean || staleInputs) {
      Steps.discardIncrementalState(flix)
    }

    // Only `--clean` empties the directory, and only before the compile. See the note above.
    val emptied = if (clean) Steps.emptyOutputDirectory(build) else Ok(())
    emptied match {
      case Err(e) => return Bootstrap.CompileOutcome(None, None, Nil, Some(e))
      case Ok(()) => ()
    }

    // Which files the project *has*, before asking which of them changed.
    //
    // `updateStaleSources` answers the second question about the paths it already knows, and a file
    // created since the last build is not among them -- so without this a long-lived session never
    // compiles a new source, and never stops compiling a deleted one. The scan happens once per
    // build and costs a directory walk against a whole-program compile.
    Steps.rescanSources()
    Steps.updateStaleSources(flix)

    val outcome = Steps.compileOutcome(flix)
    outcome.result match {
      case None =>
        // Nothing to reconcile against and no manifest to write, so the previous build's output and
        // its manifest stay as they are, describing the last build that succeeded.
        outcome

      case Some(result) =>
        val recorded = for {
          _ <- Steps.reconcileClassDirectory(build, result.products)
          _ <- Steps.writeBuildManifest(build, fingerprint, result.products)
        } yield ()
        recorded match {
          case Ok(()) => outcome
          // The program compiled but the build did not finish. Reported as a failure, because a
          // class directory that was not reconciled is one whose contents nobody can describe.
          case Err(e) => outcome.copy(error = Some(e))
        }
    }
  }

  /** Returns every dependency this project resolves against: flix packages, maven and url jars. */
  private def dependencyPaths: List[Path] =
    flixPackagePaths ::: mavenPackagePaths ::: jarPackagePaths

  /**
    * Builds a flix package for the project.
    */
  def buildPkg()(implicit formatter: Formatter): Result[Unit, BootstrapError] = {

    // Check that there is a `flix.toml` file.
    if (!Files.exists(Bootstrap.getManifestFile(projectPath))) {
      return Result.Err(BootstrapError.FileError(s"Cannot create a Flix package without a `${formatter.red(FLIX_TOML)}` file."))
    }

    // Create the artifact directory, if it does not exist.
    Files.createDirectories(Bootstrap.getArtifactDirectory(projectPath))

    // The path to the fpkg file.
    val pkgFile = Bootstrap.getPkgFile(projectPath)

    // Check whether it is safe to write to the file.
    if (Files.exists(pkgFile) && !Bootstrap.isPkgFile(pkgFile)) {
      return Result.Err(BootstrapError.FileError(s"The path '${formatter.red(pkgFile.toString)}' exists and is not a $EXT_FPKG-file. Refusing to overwrite."))
    }

    // Copy the `flix.toml` to the artifact directory.
    Files.copy(Bootstrap.getManifestFile(projectPath), Bootstrap.getArtifactDirectory(projectPath).resolve(FLIX_TOML), StandardCopyOption.REPLACE_EXISTING)

    // Construct a new zip file.
    Using(new ZipOutputStream(Files.newOutputStream(pkgFile))) { zip =>
      // Add required resources.
      FileOps.addToZip(zip, FLIX_TOML, Bootstrap.getManifestFile(projectPath))
      FileOps.addToZip(zip, LICENSE, Bootstrap.getLicenseFile(projectPath))
      FileOps.addToZip(zip, README, Bootstrap.getReadmeFile(projectPath))

      // Add all source files.
      // Here we sort entries by relative file name to apply https://reproducible-builds.org/
      val srcFiles = FileOps.getFlixFilesIn(Bootstrap.getSourceDirectory(projectPath), Int.MaxValue)
      for ((sourceFile, fileNameWithSlashes) <- FileOps.sortPlatformIndependently(projectPath, srcFiles)) {
        FileOps.addToZip(zip, fileNameWithSlashes, sourceFile)
      }
    } match {
      case Success(()) => Result.Ok(())
      case Failure(e) => Result.Err(BootstrapError.FileError(e.getMessage))
    }
  }

  /**
    * Returns `Ok(())` if the dependencies are consistent with the `effects.lock` file.
    * Returns `Err(e)` if an error `e` occurred or if the dependencies are inconsistent with the `effect.lock` file.
    */
  def checkEffects(flix: Flix): Result[Unit, BootstrapError] = {
    if (!isProjectMode) {
      return Err(BootstrapError.FileError("No 'flix.toml' found. Refusing to run 'eff-check'"))
    }

    FileOps.exists(Bootstrap.getEffectLockFile(projectPath)) match {
      case Err(e) => return Err(BootstrapError.FileError(s"IO error: ${e.getMessage}"))
      case Ok(false) => return Err(BootstrapError.FileError("No 'effects.lock' file found. Unable to run 'eff-check'."))
      case Ok(true) => ()
    }

    Steps.updateStaleSources(flix)
    for {
      json <- FileOps.readString(Bootstrap.getEffectLockFile(projectPath)).mapErr(e => BootstrapError.FileError(s"IO error: ${e.getMessage}"))
      (lockedDefs, lockedSigs) <- EffectLock.deserialize(json).mapErr(BootstrapError.FileError.apply)
      root <- Steps.check(flix)
      errors <- reportEffectUpgradeErrors(lockedDefs, lockedSigs, root)(flix)
    } yield {
      errors
    }
  }

  /**
    * Helper function for [[checkEffects]] to be used in for comprehension.
    *
    * Returns `Ok(())` if no effect upgrade errors are found.
    * Returns `Err(BootstrapError.EffectUpgradeError(errors))` otherwise.
    */
  private def reportEffectUpgradeErrors(lockedDefs: Map[Symbol.DefnSym, Scheme], lockedSigs: Map[Symbol.SigSym, Scheme], root: TypedAst.Root)(implicit flix: Flix): Result[Unit, BootstrapError] = {
    // Compute the inverted use graph to get `f -> g` if `f` is used in `g`.
    val useGraph = ListMap.from(UseGraph.computeGraph(root).invert.map {
      case (UseGraph.UsedSym.DefnSym(f), UseGraph.UsedSym.DefnSym(g)) => f.toString -> g.loc
      case (UseGraph.UsedSym.DefnSym(f), UseGraph.UsedSym.SigSym(g)) => f.toString -> g.loc
      case (UseGraph.UsedSym.SigSym(f), UseGraph.UsedSym.DefnSym(g)) => f.toString -> g.loc
      case (UseGraph.UsedSym.SigSym(f), UseGraph.UsedSym.SigSym(g)) => f.toString -> g.loc
    })

    // N.B.: We erase the keys of the maps to strings, since maps are invariant in the key
    val erasedLockedDefs = lockedDefs.map { case (sym, scheme) => sym.toString -> scheme }
    val erasedUpgradedDefs = root.defs.map { case (sym, defn) => sym.toString -> defn.spec.declaredScheme }
    val erasedLockedSigs = lockedSigs.map { case (sym, scheme) => sym.toString -> scheme }
    val erasedUpgradedSigs = root.sigs.map { case (sym, sig) => sym.toString -> sig.spec.declaredScheme }
    val defnErrors = collectUpgradeErrors(erasedLockedDefs, erasedUpgradedDefs, useGraph)
    val sigErrors = collectUpgradeErrors(erasedLockedSigs, erasedUpgradedSigs, useGraph)
    val allErrors = defnErrors ::: sigErrors

    if (allErrors.isEmpty) {
      Ok(())
    } else {
      Err(BootstrapError.EffectUpgradeError(allErrors))
    }
  }

  /**
    * Collects a list of tuples `(sym, scheme, uses)` if function represented by `sym` is not an effect safe upgrade.
    */
  private def collectUpgradeErrors(lockedFunctions: Map[String, Scheme], upgradeFunctions: Map[String, Scheme], useGraph: ListMap[String, SourceLocation])(implicit flix: Flix): List[(String, Scheme, List[SourceLocation])] = {
    val errors = mutable.ArrayBuffer.empty[(String, Scheme, List[SourceLocation])]
    for ((sym, lockedScheme) <- lockedFunctions) {
      if (upgradeFunctions.contains(sym)) {
        val upgradedScheme = upgradeFunctions(sym)
        val uses = useGraph.get(sym)
        if (!(uses.isEmpty || EffectUpgrade.isEffSafeUpgrade(lockedScheme, upgradedScheme)(flix))) {
          errors.addOne((sym, upgradedScheme, uses))
        }
      }
    }
    errors.toList
  }

  /**
    * Type checks the program and performs effect locking, overwriting the current 'effects.lock' file if it exists.
    * If the program does not type check, then effect locking is aborted without touching the file system.
    */
  def lockEffects(flix: Flix): Result[Unit, BootstrapError] = {
    if (!isProjectMode) {
      return Err(BootstrapError.FileError("No 'flix.toml' found. Refusing to run 'eff-lock'"))
    }
    Steps.updateStaleSources(flix)
    for {
      root <- Steps.check(flix)
    } yield {
      EffectLock.lock(root) match {
        case Err(e) => return Err(BootstrapError.GeneralError(s"Unexpected serialization error: $e"))
        case Ok(json) =>
          val path = Bootstrap.getEffectLockFile(projectPath)
          // N.B.: Do not use FileOps.writeJSON, since we use custom serialization formats.
          FileOps.writeString(path, json)
      }
    }
  }

  /** Returns `true` if in project mode. This is the case when a `flix.toml` file is present. */
  private def isProjectMode: Boolean = optManifest.isDefined

  /**
    * Returns what this project is configured to be, as of now.
    *
    * A copy, and one method rather than an accessor per field: the lists below are rewritten by a
    * rescan, a watcher event or a reload, so a caller holding several accessors could answer one
    * request from two different projects. It is built here because the layout functions on the
    * companion are private and should stay that way - a second definition of where `src/` lives is
    * how two definitions of a layout begin.
    *
    * Everything in it is known without compiling, which is the point: the questions it answers
    * arrive before the first build and while the project is broken.
    *
    * Paths are absolutised and normalised here rather than trusted, because `projectPath` is
    * whatever the caller constructed this with, and only the command line guarantees an absolute
    * one.
    *
    * @see [[ProjectView]]
    */
  def view: ProjectView = {
    val root = projectPath.toAbsolutePath.normalize()
    ProjectView(
      projectPath = root,
      packageName = optManifest.map(_.name).getOrElse(Bootstrap.getPackageName(root)),
      manifest = optManifest,
      sourcePaths = sourcePaths.map(_.toAbsolutePath.normalize()).distinct.sorted,
      flixPackagePaths = flixPackagePaths.map(_.toAbsolutePath.normalize()).distinct.sorted,
      mavenPackagePaths = mavenPackagePaths.map(_.toAbsolutePath.normalize()).distinct.sorted,
      jarPackagePaths = jarPackagePaths.map(_.toAbsolutePath.normalize()).distinct.sorted,
      sourceDirectory = Bootstrap.getSourceDirectory(root),
      testDirectory = Bootstrap.getTestDirectory(root),
      resourcesDirectory = Bootstrap.getResourcesDirectory(root),
      libraryDirectory = Bootstrap.getLibraryDirectory(root),
      artifactDirectory = Bootstrap.getArtifactDirectory(root),
      jarFile = Bootstrap.getJarFile(root),
      outputDirectories = Bootstrap.AllBuilds.map(b => b -> Bootstrap.getOutputDirectory(root, b)).toMap,
      classDirectories = Bootstrap.AllBuilds.map(b => b -> Bootstrap.getClassDirectory(root, b)).toMap
    )
  }

  /**
    * Deletes all compiled `.class` files under the project's build directory and removes any now-empty
    * directories (including the `build` directory itself). Performs safety checks to ensure:
    *  - the current directory is a Flix project (manifest present),
    *  - no root or home directories are targeted,
    *  - no ancestor of the project directory is targeted,
    *  - every file in the build directory is a valid class file, a build manifest, or documentation.
    *
    * Every build mode's output is visited, since this resets the project rather than one of its
    * modes: a `clean` that emptied `build/development/` and left `build/production/` alone would be a
    * surprise, and one that refused to recognise the mode it was not asked about would make the
    * project uncleanable.
    *
    * Returns `Ok(())` on success or `Err(BootstrapError.FileError(...))` on validation or IO failures.
    */
  def clean(): Result[Unit, BootstrapError] = {
    // Ensure project mode
    if (optManifest.isEmpty) {
      return Err(BootstrapError.FileError("No manifest found ('flix.toml'). Refusing to run 'clean' in a non-project directory."))
    }

    // Ensure `cwd` is not dangerous
    val cwd = Path.of(System.getProperty("user.dir"))
    checkForSystemPath(cwd) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }

    // Ensure `projectPath` is not dangerous
    checkForSystemPath(projectPath) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }

    val buildDir = Bootstrap.getBuildDirectory(projectPath)
    // Every build mode's output, since 'clean' resets the project and not one of its modes.
    val classDirs = Bootstrap.AllBuilds.map(Bootstrap.getClassDirectory(projectPath, _))
    val manifestFiles = Bootstrap.AllBuilds.map(Bootstrap.getBuildManifestFile(projectPath, _))
    val docDir = Bootstrap.getDocumentationDirectory(projectPath)
    val stubsDir = Bootstrap.getStubsDirectory(projectPath)
    val coverageReports = Bootstrap.CoverageReports.map(name => buildDir.resolve(name).normalize())

    // Ensure `buildDir` is not dangerous
    checkForDangerousPath(buildDir) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }

    // Ensure all files in `buildDir` are valid class files.
    val files = FileOps.getFilesIn(buildDir, Int.MaxValue).map(_.normalize())
    for (file <- files) {
      if (manifestFiles.contains(file)) {
        // The record of what the last build produced. It has to go with the products it
        // describes: a manifest that outlives them would let the next build reuse a class
        // directory that is no longer there.
      } else if (classDirs.exists(file.startsWith)) {
        if (!FileOps.checkExt(file, "class")) {
          return Err(BootstrapError.FileError(s"Unexpected file extension in build directory (only '.class' files are allowed): '${projectPath.relativize(file)}'"))
        }

        if (!FileOps.isClassFile(file)) {
          return Err(BootstrapError.FileError(s"Invalid class file in build directory: '${projectPath.relativize(file)}'"))
        }
      } else if (file.startsWith(docDir)) {
        isValidDocumentFile(file) match {
          case Err(e) => return Err(e)
          case Ok(()) => ()
        }
      } else if (coverageReports.contains(file)) {
        // A coverage report left at its default name. Generated, so it goes.
      } else if (file.startsWith(stubsDir)) {
        // `flix stubs` output. Only the Java sources it writes - anything else under there was
        // put there by somebody, and this is the one chance to notice.
        if (!FileOps.checkExt(file, "java")) {
          return Err(BootstrapError.FileError(s"Unexpected file in the stubs directory (only 'java' files are allowed): '${projectPath.relativize(file)}'"))
        }
      } else {
        return Err(BootstrapError.FileError(s"Unexpected directory in build directory: '${projectPath.relativize(file)}'"))
      }

      checkForDangerousPath(file) match {
        case Err(e) => return Err(e)
        case Ok(()) => ()
      }
    }

    // Delete files
    for (file <- files) {
      FileOps.delete(file) match {
        case Err(e) => return Err(BootstrapError.FileError(s"Failed to delete file '$file': $e"))
        case Ok(_) => ()
      }
    }

    // Delete empty directories
    // Visit in reverse order to delete the innermost directories first
    val directories = FileOps.getDirectoriesIn(buildDir, Int.MaxValue).map(_.normalize())
    for (dir <- directories.reverse) {
      checkForDangerousPath(dir) match {
        case Err(e) => return Err(e)
        case Ok(()) => ()
      }

      FileOps.delete(dir) match {
        case Err(e) => return Err(BootstrapError.FileError(s"Failed to delete directory '$dir': $e"))
        case Ok(_) => ()
      }
    }

    Ok(())
  }

  /**
    * Returns `Err` if `path` is one of the following:
    *   - A root directory of the system
    *   - The user's home directory (`"user.home"` system property, using [[System.getProperty]])
    *   - Any ancestor of [[projectPath]]
    *
    * Returns `Ok(())` otherwise.
    */
  private def checkForDangerousPath(path: Path): Result[Unit, BootstrapError] = {
    checkForSystemPath(path) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }
    checkForAncestor(path) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }
    Ok(())
  }

  /** Returns `Err` if `path` is either a root directory or the user's home directory.
    *
    * @see [[checkForRootDir]]
    * @see [[checkForHomeDir]]
    */
  private def checkForSystemPath(path: Path): Result[Unit, BootstrapError] = {
    checkForRootDir(path) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }
    checkForHomeDir(path) match {
      case Err(e) => return Err(e)
      case Ok(()) => ()
    }
    Ok(())
  }

  /** Returns `Err` if `path` is the user's home directory. */
  private def checkForHomeDir(path: Path): Result[Unit, BootstrapError] = {
    val home = Path.of(System.getProperty("user.home"))
    if (home.normalize() == path.normalize()) {
      return Err(BootstrapError.FileError("Refusing to delete file in home directory."))
    }
    Ok(())
  }

  /** Returns `Err` if `path` is a root directory. */
  private def checkForRootDir(path: Path): Result[Unit, BootstrapError] = {
    val roots = FileSystems.getDefault.getRootDirectories.asScala.toList.map(_.normalize())
    if (roots.contains(path.normalize())) {
      return Err(BootstrapError.FileError("Refusing to delete file in root directory."))
    }
    Ok(())
  }

  /** Returns `Err` if `path` is an ancestor of `projectPath`. */
  private def checkForAncestor(path: Path): Result[Unit, BootstrapError] = {
    if (projectPath.normalize().startsWith(path.normalize())) {
      return Err(BootstrapError.FileError(s"Refusing to delete file in ancestor of project directory: '${path.normalize()}"))
    }
    Ok(())
  }

  /** Returns `Err` if `path` is not a file that could be produced by a documentation backend. */
  private def isValidDocumentFile(path: Path): Result[Unit, BootstrapError] = {
    val knownFiles = List("favicon.png", "index.js", "styles.css")
    if (knownFiles.contains(path.getFileName.toString)) {
      return Ok(())
    }
    if (FileOps.checkExt(path, "html") || FileOps.checkExt(path, "md")) {
      return Ok(())
    }
    val iconsDir = Bootstrap.getDocumentationDirectory(projectPath).resolve("./icons/").normalize()
    if (path.startsWith(iconsDir) && FileOps.checkExt(path, "svg")) {
      return Ok(())
    }

    Err(BootstrapError.FileError(s"Unexpected file '${projectPath.relativize(path)}'. Refusing to run 'clean'."))
  }

  /**
    * Type checks the source files for the project.
    */
  def check(flix: Flix): Result[Unit, BootstrapError] = {
    Steps.updateStaleSources(flix)
    Steps.check(flix).map(_ => ())
  }

  /**
    * Generates API documentation.
    */
  def doc(flix: Flix): Result[Unit, BootstrapError] = {
    Steps.updateStaleSources(flix)
    Steps.check(flix).map(Documentor.run(_, getPackageModules, flix.options.docFormat)(flix))
  }

  /**
    * Formats all source files in the project.
    */
  def format(flix: Flix, separators: PrettyPrinter.Separators): Result[Unit, BootstrapError] = {
    Steps.updateStaleSources(flix)
    // Formatting does not require the project to compile: declarations that failed
    // to parse are reproduced verbatim and the rest are formatted, so a project
    // with one broken file still gets formatted everywhere else. Reporting the
    // errors belongs to `flix check`, not here.
    val _ = flix.check()
    val syntaxTree = flix.getParsedAst
    LspFormatter.formatFiles(syntaxTree, sourcePaths, separators)(flix)
    Result.Ok(())
  }

  /**
    * Runs the main function in flix package for the project.
    */
  def run(flix: Flix, args: Array[String]): Result[Unit, BootstrapError] = {
    for {
      compilationResult <- build(flix)
    } yield {
      compilationResult.getMain match {
        case None => ()
        case Some(main) => main(args)
      }
    }
  }

  /**
    * Runs all tests in the flix package for the project.
    */
  def test(flix: Flix): Result[Unit, BootstrapError] = {
    for {
      compilationResult <- build(flix)
      res <- Tester.run(Nil, compilationResult)(flix).mapErr(_ => BootstrapError.GeneralError("Tester Error"))
    } yield {
      res
    }
  }

  /**
    * Package the current project and release it on GitHub.
    */
  def release(flix: Flix)(implicit out: PrintStream): Result[Unit, BootstrapError] = {
    implicit val formatter: Formatter = flix.getFormatter

    // Ensure that we have a manifest
    val manifest = optManifest match {
      case Some(m) => m
      case None => return Result.Err(BootstrapError.ReleaseError(ReleaseError.MissingManifest))
    }

    // Check if `github` option is present
    val githubRepo = manifest.repository match {
      case Some(r) => r
      case None => return Result.Err(BootstrapError.ReleaseError(ReleaseError.MissingRepository))
    }

    // Check if `--github-token` option is present
    val githubToken = flix.options.githubToken match {
      case Some(k) => k
      case None => return Result.Err(BootstrapError.ReleaseError(ReleaseError.MissingApiKey))
    }

    if (!flix.options.assumeYes) {
      // Ask for confirmation
      out.print(s"Release ${formatter.blue(s"github:$githubRepo")} ${formatter.yellow(s"v${manifest.version}")}? [y/N]: ")
      val response = readLine()
      response.toLowerCase match {
        case "y" => // Continue
        case "yes" => // Continue
        case _ => return Result.Err(BootstrapError.ReleaseError(ReleaseError.Cancelled))
      }
    }

    // Build artifacts
    out.println("Building project...")
    buildPkg() match {
      case Ok(_) => // Continue
      case Err(e) => return Result.Err(e)
    }

    // Publish to GitHub
    out.println("Publishing a new release...")
    val artifacts = List(Bootstrap.getPkgFile(projectPath), Bootstrap.getManifestFile(projectPath))
    val publishResult = GitHub.publishRelease(githubRepo, manifest.version, artifacts, githubToken)
    publishResult match {
      case Ok(()) => // Continue
      case Err(e) => return Result.Err(BootstrapError.ReleaseError(e))
    }

    out.println(formatter.green(
      s"""
         |Successfully released v${manifest.version}
         |${formatter.underline(s"https://github.com/${githubRepo.owner}/${githubRepo.repo}/releases/tag/v${manifest.version}")}
         |""".stripMargin
    ))

    Result.Ok(())
  }

  /**
    * Show dependencies which have newer versions available.
    *
    * @return `true` if any outdated dependencies were found, `false` if everything is up to date.
    */
  def outdated(flix: Flix)(implicit out: PrintStream): Result[Boolean, BootstrapError] = {
    implicit val formatter: Formatter = flix.getFormatter

    val flixDeps = optManifest.map(FlixPackageManager.findFlixDependencies).getOrElse(Nil)

    val rows = flixDeps.flatMap { dep =>
      val updates = FlixPackageManager.findAvailableUpdates(dep, flix.options.githubToken) match {
        case Ok(u) => u
        case Err(e) => return Result.Err(BootstrapError.FlixPackageError(e))
      }

      if (updates.isEmpty)
        None
      else
        Some(List(
          s"${dep.username}/${dep.projectName}",
          dep.version.toString,
          updates.major.map(v => v.toString).getOrElse(""),
          updates.minor.map(v => v.toString).getOrElse(""),
          updates.patch.map(v => v.toString).getOrElse(""),
        ))
    }

    if (rows.isEmpty) {
      out.println(formatter.green(
        """
          |All dependencies are up to date
          |""".stripMargin
      ))
      Result.Ok(false)
    } else {
      out.println("")
      out.println(formatter.table(
        List("package", "current", "major", "minor", "patch"),
        List(formatter.blue, formatter.cyan, formatter.yellow, formatter.yellow, formatter.yellow),
        rows
      ))
      out.println("")
      Result.Ok(true)
    }
  }

  /**
    * Returns the modules of the package if manifest is present.
    * Returns [[PackageModules.All]] if manifest is not present.
    */
  private def getPackageModules: PackageModules = {
    optManifest match {
      case None => PackageModules.All
      case Some(manifest) => manifest.modules
    }
  }

  private object Steps {

    /**
      * Adds the class files `products` - relative to the project's class directory - to `zip`.
      *
      * The jar is built from the product set the compiler reported rather than from a walk of
      * the class directory. The two agree after [[reconcileClassDirectory]], and packaging the
      * set is what makes them agree *by construction*: a class file that appears in the
      * directory between reconciling and packaging cannot reach the jar, and neither can one
      * this build did not write.
      */
    def addProductsToZip(build: Build, products: Set[Path], zip: ZipOutputStream): Unit = {
      val classDir = Bootstrap.getClassDirectory(projectPath, build)
      // Here we sort entries by relative file name to apply https://reproducible-builds.org/
      val entries = products.toList.map(BuildManifest.nameOf).sorted
      for (name <- entries) {
        FileOps.addToZip(zip, name, classDir.resolve(name))
      }
    }

    /**
      * Returns `Ok(())` if every product of the build mode `build` is on disk.
      *
      * Checked *before* the jar is opened, because opening it truncates the last good one and
      * `FileOps.addToZip` skips a path that does not exist rather than failing. Without this, a
      * product that went missing between writing and packaging - another build reconciling the
      * same directory, something outside the compiler deleting it - yields a jar quietly missing
      * a class, an exit code of zero, and no previous jar to fall back on.
      */
    def validateProducts(build: Build, products: Set[Path]): Result[Unit, BootstrapError] = {
      val classDir = Bootstrap.getClassDirectory(projectPath, build)
      val missing = products.toList.map(BuildManifest.nameOf).sorted.filterNot(name => Files.isRegularFile(classDir.resolve(name)))
      if (missing.nonEmpty) {
        return Err(BootstrapError.FileError(
          s"${missing.length} class file(s) the compiler reported are missing from '${projectPath.relativize(classDir)}', so the jar would be incomplete: ${missing.take(5).mkString(", ")}"))
      }
      Ok(())
    }

    /**
      * Adds all jars in `dir` to `zip`.
      * Ignores non-jar files and does nothing if `dir` does not exist.
      *
      * Most of each dependency jar is copied verbatim — class files, ordinary resources
      * (native libraries, capability files, `.properties` files, ...), and library-specific
      * `META-INF/` resources such as JLine's `META-INF/jline/providers/` registry — with two
      * exceptions:
      *   - `META-INF/services/` service-provider files are *merged* across jars (rather than
      *     letting one jar's copy overwrite another's) so that `java.util.ServiceLoader` still
      *     finds every provider.
      *   - A small set of entries is dropped because copying them would be unsafe or useless:
      *       - `META-INF/MANIFEST.MF` would collide with (and clobber) the fat jar's own manifest.
      *       - Signature files (`.SF`, `.RSA`, `.DSA`, `.EC`, `SIG-` files) would no longer match
      *         the repacked contents, making the JVM reject the jar with a `SecurityException`.
      *       - `META-INF/INDEX.LIST` would reference jars that no longer exist.
      *       - `META-INF/versions/` multi-release classes would be inert (the fat jar manifest
      *         does not declare `Multi-Release: true`) and risk shadowing the base classes.
      *       - `module-info.class` cannot be merged: only one may live at the jar root.
      *
      * Duplicate entry paths across jars are de-duplicated (first jar wins) so that the build
      * does not abort with a `ZipException: duplicate entry`.
      *
      * `extraJars` is scanned *before* `dir` (so it wins any entry-name collision) and is meant
      * for dependency jars that were resolved live (their paths are already known -- see
      * [[installMavenDependencies]]/[[installJarDependencies]]) but never physically copied into
      * `dir`: a package manager only caches what it actually downloaded over the network, and a
      * dependency resolved from a local (e.g. `file:`-scheme) repository has nothing to download
      * in the first place, so relying on `dir`'s contents alone silently omits it from the fat
      * jar -- producing one that compiles fine (Flix's own compiler doesn't need the Java
      * classes to exist, only their names) but throws `NoClassDefFoundError` the moment it runs.
      */
    def addJarsFromDirToZip(dir: Path, zip: ZipOutputStream, extraJars: List[Path] = Nil): Unit = {
      val servicesPrefix = "META-INF/services/"
      val metaInfPrefix = "META-INF/"
      // If `dir` doesn't exist, we suppose there is simply no on-disk dependency cache and
      // trigger no error -- `extraJars` (if any) is still processed below.
      val jarDependencies = extraJars ::: (if (Files.exists(dir)) FileOps.getFilesWithExtIn(dir, EXT_JAR, Int.MaxValue) else Nil)

      // Tracks entry names already written to `zip` so that an entry present in more than one
      // dependency jar is written only once (first jar wins) instead of throwing.
      val seen = mutable.Set.empty[String]

      // Accumulates merged `META-INF/services/*` files: service name -> ordered, de-duplicated provider lines.
      val services = mutable.LinkedHashMap.empty[String, List[String]]

      // Returns `true` for entries that must not be copied into the fat jar (see method doc).
      def isUnsafeEntry(name: String): Boolean = {
        // Signature files sit directly under META-INF/ (a single path segment after it).
        val rest = if (name.startsWith(metaInfPrefix)) name.substring(metaInfPrefix.length) else name
        val isSignatureFile = name.startsWith(metaInfPrefix) && !rest.contains("/") &&
          (rest.startsWith("SIG-") || rest.endsWith(".SF") || rest.endsWith(".RSA") ||
            rest.endsWith(".DSA") || rest.endsWith(".EC"))
        name.equals(s"module-info.$EXT_CLASS") ||
          name.equals("META-INF/MANIFEST.MF") ||
          name.equals("META-INF/INDEX.LIST") ||
          name.startsWith("META-INF/versions/") ||
          isSignatureFile
      }

      // Add jar dependencies.
      jarDependencies.foreach(dep => {
        // Extract the runtime contents of the dependency into the fat jar.
        Using(new ZipInputStream(Files.newInputStream(dep))) {
          zipIn =>
            var entry = zipIn.getNextEntry
            while (entry != null) {
              val name = entry.getName
              if (entry.isDirectory) {
                // Directory entries carry no content; the zip records them implicitly.
              } else if (name.startsWith(servicesPrefix)) {
                // Merge service-provider files rather than overwriting, so every provider survives.
                val lines = new String(zipIn.readAllBytes()).linesIterator
                  .map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toList
                services(name) = (services.getOrElse(name, List.empty) ++ lines).distinct
              } else if (isUnsafeEntry(name)) {
                // Drop entries that are unsafe or useless in a fat jar (see method doc).
              } else if (seen.add(name)) {
                // Copy everything else — classes, resources, and library-specific META-INF
                // entries such as META-INF/jline/ — skipping paths taken by an earlier jar.
                FileOps.addToZip(zip, name, zipIn.readAllBytes())
              }
              entry = zipIn.getNextEntry
            }
        }
      })

      // Write the merged service-provider files.
      for ((name, providers) <- services) {
        FileOps.addToZip(zip, name, providers.mkString("\n").getBytes)
      }
    }

    /**
      * Re-reads which `.flix` files the project has.
      *
      * Separate from [[updateStaleSources]] because they answer different questions. That one asks
      * which of the *known* sources changed; this one asks which sources there are. A file created
      * since the last scan is in neither list until this runs, and a file deleted since is still in
      * both.
      *
      * Only the sources. Dependencies change when the manifest does, which is a reload rather than a
      * build, and rescanning `lib/` here would make every build pay for a directory walk it cannot
      * act on.
      */
    def rescanSources(): Unit = {
      addLocalFlixFiles()
      ()
    }

    /**
      * Returns and caches all `.flix` files from `src/` and `test/`.
      */
    def addLocalFlixFiles(): List[Path] = {
      val filesHere = FileOps.getFlixFilesIn(projectPath, 1)
      val filesSrc = FileOps.getFlixFilesIn(Bootstrap.getSourceDirectory(projectPath), Int.MaxValue)
      val filesTest = FileOps.getFlixFilesIn(Bootstrap.getTestDirectory(projectPath), Int.MaxValue)
      val result = filesHere ::: filesSrc ::: filesTest
      sourcePaths = result
      result
    }

    /**
      * Returns and caches all `.fpkg` files from `lib/`.
      * The cached result is stored in [[flixPackagePaths]].
      */
    private def addLocalFlixLibs(): List[Path] = {
      val flixFilesLib = FileOps.getFilesWithExtIn(Bootstrap.getLibraryDirectory(projectPath), EXT_FPKG, Int.MaxValue)
      flixPackagePaths = flixFilesLib
      flixFilesLib
    }

    /**
      * Returns and caches all `.jar` files from `lib/external/`.
      * The cached result is stored in [[jarPackagePaths]].
      */
    private def addLocalJars(): List[Path] = {
      val jarFilesLib = FileOps.getFilesWithExtIn(Bootstrap.getLibraryDirectory(projectPath).resolve(JarPackageManager.DirName), EXT_JAR, Int.MaxValue)
      jarPackagePaths = jarFilesLib
      jarFilesLib
    }

    /**
      * Returns a list of 3 lists of paths.
      * The lists contain the following paths in the following order:
      *   1. All `.jar` files from `lib/cache/`.
      *   1. All `.jar` files from `lib/external/`.
      *   1. All `.fpkg` files from `lib/`.
      *
      * All results are cached in [[mavenPackagePaths]], [[jarPackagePaths]], and [[flixPackagePaths]], respectively.
      */
    def addLocalLibs(): List[List[Path]] = {
      addLocalMavenJars() :: addLocalJars() :: addLocalFlixLibs() :: Nil
    }

    /**
      * Returns and caches all `.jar` files from `lib/cache/`.
      * The cached result is stored in [[mavenPackagePaths]].
      */
    private def addLocalMavenJars(): List[Path] = {
      val mavenFilesLib = FileOps.getFilesWithExtIn(Bootstrap.getLibraryDirectory(projectPath).resolve(MavenPackageManager.DirName), EXT_JAR, Int.MaxValue)
      mavenPackagePaths = mavenFilesLib
      mavenFilesLib
    }

    /**
      * Adds a `META-INF/MANIFEST.MF` file to `zip`.
      */
    private def addManifestToZip(zip: ZipOutputStream): Unit = {
      val manifest =
        """Manifest-Version: 1.0
          |Main-Class: Main
          |""".stripMargin

      FileOps.addToZip(zip, "META-INF/MANIFEST.MF", manifest.getBytes)
    }

    /**
      * Adds all files in `dir` to `zip`.
      */
    def addResourcesFromDirToZip(dir: Path, zip: ZipOutputStream): Unit = {
      // Add all resources, again sorting by relative file name
      val resources = FileOps.getFilesIn(dir, Int.MaxValue)
      for ((resource, fileNameWithSlashes) <- FileOps.sortPlatformIndependently(dir, resources)) {
        FileOps.addToZip(zip, fileNameWithSlashes, resource)
      }
    }

    /**
      * Type checks the source files for the project.
      */
    def check(flix: Flix): Result[TypedAst.Root, BootstrapError] = {
      val (optRoot, errors) = flix.check()
      if (errors.isEmpty) {
        Ok(optRoot.get)
      } else {
        Err(BootstrapError.CompilationErrors(errors, optRoot))
      }
    }

    /**
      * Runs the compile function on the `flix` object.
      * It is up to the caller to set the appropriate options on `flix`.
      * It is often the case that `outputJvm` and `loadClassFiles` must be toggled on or off.
      */
    def compile(flix: Flix): Result[CompilationResult, BootstrapError] =
      compileOutcome(flix).toResult

    /**
      * Compiles, and reports what the compiler said as well as whether it succeeded.
      *
      * `compile` collapses that into a `Result`, which is the right shape for a command that either
      * builds or prints errors. It is the wrong shape for a caller that has to publish diagnostics:
      * a compile can succeed *and* have messages, and there is nowhere in a `Result` to put them.
      * The typed root goes with them, because it is what names the sources a previous compile
      * reported on -- which is what a caller needs to clear a marker that no longer applies.
      */
    def compileOutcome(flix: Flix): Bootstrap.CompileOutcome = {
      val (optRoot, errors) = flix.check()
      if (errors.isEmpty) {
        Bootstrap.CompileOutcome(Some(flix.codeGen(optRoot.get)), optRoot, errors)
      } else {
        Bootstrap.CompileOutcome(None, optRoot, errors)
      }
    }

    /**
      * Configures `flix` to emit class files to the production output directory (on the file
      * system) in production mode.
      *
      * @see [[Bootstrap.getOutputDirectory]]
      * @see [[Build.Production]]
      */
    def configureJarOutput(flix: Flix): Result[Unit, BootstrapError] = {
      val outputDir = Bootstrap.getOutputDirectory(projectPath, Build.Production)
      for {
        _ <- validateDirectory(outputDir)
      } yield {
        val newOptions = flix.options.copy(build = Build.Production, outputJvm = true, outputPath = outputDir)
        flix.setOptions(newOptions)
        ()
      }
    }

    /**
      * Writes `contents` to the jar file located at `jar`.
      *
      * This function also adds a manifest to the jar file.
      *
      * Creates the jar file if it does not exist, and truncates it if it already exists.
      *
      * @see [[Steps.addManifestToZip]]
      */
    def createJar(jar: Path, contents: ZipOutputStream => Unit): Result[Unit, BootstrapError.FileError] = {
      Files.createDirectories(jar.getParent.normalize())
      val contentsWithManifest = (zip: ZipOutputStream) => {
        Steps.addManifestToZip(zip)
        contents(zip)
      }
      Result.fromTry(Using(new ZipOutputStream(Files.newOutputStream(jar)))(contentsWithManifest))
        .mapErr(e => BootstrapError.FileError(e.getMessage))
    }

    /**
      * Returns true if the timestamp of the given source file has changed since the last reload.
      */
    private def hasChanged(file: Path) = {
      !timestamps.contains(file) || (timestamps(file) != file.toFile.lastModified())
    }

    /**
      * Downloads and installs all `.fpkg` and `.jar` (maven and urls) dependencies defined by `dependencyManifests`
      * into the `lib/`, `lib/cache`, and `lib/external` directories, respectively.
      * Requires network access.
      * Returns a list of 3 lists of paths containing (in the following order):
      *   1. Paths to `.fpkg` dependencies in `lib/`.
      *   1. Paths to `.jar` dependencies in `lib/cache` (maven).
      *   1. Paths to `.jar` dependencies in `lib/external` (urls).
      */
    def installDependencies(resolution: FlixPackageManager.SecureResolution)(implicit formatter: Formatter, out: PrintStream): Result[List[List[Path]], BootstrapError] = {
      for {
        flixPaths <- installFlixDependencies(resolution)
        mavenPaths <- installMavenDependencies(resolution.manifests)
        jarPaths <- installJarDependencies(resolution.manifests)
      } yield {
        out.println("Dependency resolution completed.")
        List(flixPaths, mavenPaths, jarPaths)
      }
    }

    /**
      * Downloads and installs all `.fpkg` dependencies defined by `dependencyManifests` into the `lib/` directory.
      * Requires network access.
      * Returns the paths to the installed dependencies.
      */
    private def installFlixDependencies(resolution: FlixPackageManager.SecureResolution)(implicit formatter: Formatter, out: PrintStream): Result[List[Path], BootstrapError] = {
      FlixPackageManager.installAll(resolution, projectPath, apiKey) match {
        case Ok(result: List[(Path, SecurityContext)]) =>
          securityLevels = result.toMap
          flixPackagePaths = result.map { case (path, _) => path }
          Ok(flixPackagePaths)
        case Err(e) =>
          Err(BootstrapError.FlixPackageError(e))
      }
    }

    /**
      * Downloads and installs all `.jar` dependencies defined by `dependencyManifests` into the `lib/external/` directory.
      * Requires network access.
      * Returns the paths to the installed dependencies.
      */
    private def installJarDependencies(dependencyManifests: List[Manifest])(implicit out: PrintStream): Result[List[Path], BootstrapError] = {
      JarPackageManager.installAll(dependencyManifests, projectPath) match {
        case Ok(paths) =>
          jarPackagePaths = paths
          Ok(paths)
        case Err(e) =>
          Err(BootstrapError.JarPackageError(e))
      }
    }

    /**
      * Downloads and installs all `.jar` dependencies defined by `dependencyManifests` into the `lib/cache/` directory.
      * Requires network access.
      * Returns the paths to the installed dependencies.
      */
    private def installMavenDependencies(dependencyManifests: List[Manifest])(implicit formatter: Formatter, out: PrintStream): Result[List[Path], BootstrapError] = {
      MavenPackageManager.installAll(dependencyManifests, projectPath) match {
        case Ok(paths) =>
          mavenPackagePaths = paths
          Ok(paths)
        case Err(e) =>
          Err(BootstrapError.MavenPackageError(e))
      }
    }

    /**
      * Parses and returns the manifest at `tomlPath`.
      */
    def parseManifest(tomlPath: Path): Result[Manifest, BootstrapError] = {
      ManifestParser.parse(tomlPath) match {
        case Ok(manifest) =>
          optManifest = Some(manifest)
          Ok(manifest)
        case Err(e) =>
          Err(BootstrapError.ManifestParseError(e))
      }
    }

    /**
      * Returns flix manifests of all dependencies of `manifest`. This includes transitive dependencies.
      * Requires network access.
      */
    def resolveFlixDependencies(manifest: Manifest)(implicit formatter: Formatter, out: PrintStream): Result[FlixPackageManager.SecureResolution, BootstrapError] = {
      FlixPackageManager.findTransitiveDependencies(manifest, projectPath, apiKey).map(FlixPackageManager.resolveSecurityLevels) match {
        case Err(e) => Err(BootstrapError.FlixPackageError(e))
        case Ok(securityMap) =>
          val securityResolutionErrors = FlixPackageManager.checkSecurity(securityMap)
          if (securityResolutionErrors.isEmpty) {
            Ok(securityMap)
          } else {
            Err(BootstrapError.GeneralError(securityResolutionErrors.map(_.message(formatter)).mkString(System.lineSeparator())))
          }
      }
    }

    /**
      * Checks to see if any source files or packages have been changed.
      * If they have, they are added to flix. Then updates the timestamps
      * map to reflect the current source files and packages.
      *
      * When a file watcher is active (REPL mode), drains watcher events instead of polling timestamps.
      */
    def updateStaleSources(flix: Flix): Unit = {
      // Both records of what is already loaded - the timestamps, and the watcher events already
      // drained - are records about one Flix instance. Handing a *different* instance only what
      // changed since would leave it compiling an empty program, so it is given everything.
      val sameInstance = lastFlix.exists(_ eq flix)
      lastFlix = Some(flix)

      fileWatcher match {
        case Some(fw) =>
          val events = fw.drain()
          if (sameInstance) applyWatcherEvents(events, flix) else rescanAndUpdate(flix)

        case None =>
          // Without a watcher, staleness can only be guessed from modification times - and a guess
          // is not enough to license reusing a cached AST. `Source` equality is by path and not by
          // content (`Source.equals`), and `ChangeSet.partition` hands back the *cached* result for
          // any input not marked changed, so a file whose mtime did not move is compiled as it was
          // and the edit silently never reaches the output. Modification times are millisecond
          // resolution at best and whole seconds on some filesystems, so two writes inside one tick
          // are ordinary rather than exotic. Every source is therefore handed over again, which
          // marks it changed; the watcher path above is the one that may be selective, because
          // there an edit is an event rather than an inference.
          //
          // Re-offering is *not* the same as forgetting. `timestamps` doubles as the record of which
          // sources have been loaded, and that record is what identifies a source that has since been
          // deleted. Clearing it first leaves the deleted file in the compiler's inputs, where the
          // reader then fails on a file that is not there.
          updateStaleSourcesByTimestamp(flix, reofferAll = true)
      }
    }

    /**
      * Applies file watcher events to the Flix instance and updates the cached path lists.
      * On overflow, falls back to a full re-scan.
      */
    private def applyWatcherEvents(events: List[FileWatcher.WatchEvent], flix: Flix): Unit = {
      import FileWatcher.WatchEvent.*

      if (events.exists(_ == Overflow)) {
        // Overflow occurred: fall back to a full re-scan.
        rescanAndUpdate(flix)
        return
      }

      for (event <- events) event match {
        case Created(path) =>
          if (FileOps.checkExt(path, EXT_FLIX)) {
            sourcePaths = path :: sourcePaths
            flix.addFile(path)(SecurityContext.Unrestricted)
          } else if (FileOps.checkExt(path, EXT_FPKG)) {
            flixPackagePaths = path :: flixPackagePaths
            flix.addPkg(path)(securityLevels.getOrElse(path, SecurityContext.Plain))
          } else if (FileOps.checkExt(path, EXT_JAR)) {
            val libDir = Bootstrap.getLibraryDirectory(projectPath)
            val mavenDir = libDir.resolve(MavenPackageManager.DirName)
            val jarDir = libDir.resolve(JarPackageManager.DirName)
            if (path.startsWith(mavenDir)) {
              mavenPackagePaths = path :: mavenPackagePaths
            } else if (path.startsWith(jarDir)) {
              jarPackagePaths = path :: jarPackagePaths
            }
            flix.addJar(path)
          }

        case Modified(path) =>
          if (FileOps.checkExt(path, EXT_FLIX)) {
            flix.addFile(path)(SecurityContext.Unrestricted)
          } else if (FileOps.checkExt(path, EXT_FPKG)) {
            flix.addPkg(path)(securityLevels.getOrElse(path, SecurityContext.Plain))
          } else if (FileOps.checkExt(path, EXT_JAR)) {
            flix.addJar(path)
          }

        case Deleted(path) =>
          if (path.toString.endsWith(s".$EXT_FLIX")) {
            sourcePaths = sourcePaths.filterNot(_ == path)
            flix.remFile(path)(SecurityContext.Unrestricted)
          } else if (path.toString.endsWith(s".$EXT_FPKG")) {
            flixPackagePaths = flixPackagePaths.filterNot(_ == path)
            flix.remFile(path)(SecurityContext.Unrestricted)
          } else if (path.toString.endsWith(s".$EXT_JAR")) {
            mavenPackagePaths = mavenPackagePaths.filterNot(_ == path)
            jarPackagePaths = jarPackagePaths.filterNot(_ == path)
          } else {
            // No recognized file extension — likely a directory deletion.
            // Remove all tracked files that were children of this path.
            val deletedFlix = sourcePaths.filter(_.startsWith(path))
            val deletedFpkg = flixPackagePaths.filter(_.startsWith(path))
            sourcePaths = sourcePaths.filterNot(_.startsWith(path))
            flixPackagePaths = flixPackagePaths.filterNot(_.startsWith(path))
            mavenPackagePaths = mavenPackagePaths.filterNot(_.startsWith(path))
            jarPackagePaths = jarPackagePaths.filterNot(_.startsWith(path))
            for (p <- deletedFlix) flix.remFile(p)(SecurityContext.Unrestricted)
            for (p <- deletedFpkg) flix.remFile(p)(SecurityContext.Unrestricted)
          }

        case Overflow => // already handled above
      }
    }

    /**
      * Falls back to a full directory re-scan and updates the Flix instance with any changes.
      * Used when the watcher reports an overflow event.
      */
    private def rescanAndUpdate(flix: Flix): Unit = {
      val previousSources = (sourcePaths ::: flixPackagePaths ::: mavenPackagePaths ::: jarPackagePaths).toSet

      // Re-scan directories to discover current files.
      addLocalFlixFiles()
      addLocalLibs()

      val currentSources = (sourcePaths ::: flixPackagePaths ::: mavenPackagePaths ::: jarPackagePaths).toSet

      // Add new or re-add all current sources.
      for (path <- currentSources) {
        if (FileOps.checkExt(path, EXT_FLIX)) {
          flix.addFile(path)(SecurityContext.Unrestricted)
        } else if (FileOps.checkExt(path, EXT_FPKG)) {
          flix.addPkg(path)(securityLevels.getOrElse(path, SecurityContext.Plain))
        } else if (FileOps.checkExt(path, EXT_JAR)) {
          flix.addJar(path)
        }
      }

      // Remove deleted sources.
      for (path <- previousSources -- currentSources) {
        flix.remFile(path)(SecurityContext.Unrestricted)
      }
    }

    /**
      * Timestamp-based stale source detection (used when no file watcher is active).
      *
      * @param reofferAll hand every source to the compiler again rather than only the ones whose
      *                   modification time moved. What the caller wants when it cannot trust an
      *                   mtime to prove a file is unchanged - which, without a watcher, it cannot.
      *                   Note that this is separate from *forgetting* what was loaded: the record of
      *                   loaded sources is what identifies one that has since been deleted.
      */
    private def updateStaleSourcesByTimestamp(flix: Flix, reofferAll: Boolean = false): Unit = {
      val previousSources = timestamps.keySet

      // A path that no longer exists reads as stale - it has no timestamp to match - but it is
      // gone rather than changed, and every `add` below rejects a file that is not there. It is
      // removed further down instead. The path stays in the cached lists so that a file which
      // comes back is picked up again.
      def isStale(path: Path): Boolean = Files.exists(path) && (reofferAll || hasChanged(path))

      for (path <- sourcePaths if isStale(path)) {
        flix.addFile(path)(SecurityContext.Unrestricted)
      }

      for (path <- flixPackagePaths if isStale(path)) {
        flix.addPkg(path)(securityLevels.getOrElse(path, SecurityContext.Plain))
      }

      for (path <- mavenPackagePaths if isStale(path)) {
        flix.addJar(path)
      }

      for (path <- jarPackagePaths if isStale(path)) {
        flix.addJar(path)
      }

      val currentSources = (sourcePaths ::: flixPackagePaths ::: mavenPackagePaths ::: jarPackagePaths).filter(p => Files.exists(p))

      // Only a Flix source can be removed by path: `remFile` rejects anything else, and a
      // dependency that disappeared is dropped from the classpath by the resolution step, not
      // here. The extension is read off the name and not off the file, which is gone.
      val deletedSources = (previousSources -- currentSources).filter(_.toString.endsWith(s".$EXT_FLIX"))
      for (path <- deletedSources) {
        flix.remFile(path)(SecurityContext.Unrestricted)
      }

      timestamps = currentSources.map(f => f -> f.toFile.lastModified).toMap
    }

    /**
      * Returns `OK(())` if `dir` exists and is a readable directory.
      * If `dir` does not exist, it returns `Ok(())` too.
      */
    def validateDirectory(dir: Path): Result[Unit, BootstrapError] = {
      if (Files.exists(dir)) {
        if (!Files.isDirectory(dir)) {
          return Err(BootstrapError.FileError(s"The path '${dir.toString}' is not a directory."))
        }
        if (!Files.isReadable(dir)) {
          return Err(BootstrapError.FileError(s"The path '${dir.toString}' is not readable."))
        }
      }
      Ok(())
    }

    /**
      * Removes every class file from the class directory that is not in `products`, and prunes
      * the directories left empty.
      *
      * A build only overwrites the class files it generates. Class files left behind by an
      * earlier build - because the def they belonged to was deleted from the source, or renamed,
      * or because a specialization is no longer reachable - would otherwise survive and be
      * packaged into the jar.
      *
      * Removing exactly the complement of `products` is sound because `Flix.codeGen` is
      * whole-program: `products` is every class file the current sources require, so a class
      * file outside it is one no longer required. This is the same end state a wipe followed by
      * a full recompile reaches, and unlike a wipe it does not have to be paid for by discarding
      * the compiler's caches and rewriting every file.
      *
      * Refuses to delete anything that is not a class file, so that a mis-configured output path
      * cannot cause data loss.
      */
    def reconcileClassDirectory(build: Build, products: Set[Path]): Result[Unit, BootstrapError] = {
      // A build that wrote nothing makes every class file on disk look stale. Reconciling
      // against it would empty the directory and then package an empty jar, so it is refused:
      // any program at all produces class files, and JVM output was configured above.
      if (products.isEmpty) {
        return Err(BootstrapError.FileError("The compiler wrote no class files. Refusing to reconcile the class directory."))
      }

      val classDir = Bootstrap.getClassDirectory(projectPath, build)
      for {
        _ <- removeClassFiles(build, keep = products.map(p => classDir.resolve(p).normalize()))
        _ <- pruneEmptyDirectories(classDir)
      } yield {
        ()
      }
    }

    /**
      * Removes every class file in the class directory of the build mode `build` except those in
      * `keep`.
      *
      * Validates every file in the directory before deleting any of it, so that an unexpected
      * file stops the build with the directory untouched rather than half-emptied.
      *
      * The name is not enough: a file called `Notes.class` that is not bytecode is somebody's
      * file, and this now runs on every ordinary build where before only `clean` deleted here.
      * So the contents are checked too, the way `clean` checks them - and an empty file is
      * tolerated for the same reason `JvmWriter` tolerates one, since that is what an interrupted
      * write leaves behind and refusing it would wedge every later build.
      */
    private def removeClassFiles(build: Build, keep: Set[Path]): Result[Unit, BootstrapError] = {
      val classDir = Bootstrap.getClassDirectory(projectPath, build)
      if (!Files.exists(classDir)) {
        return Ok(())
      }

      checkForDangerousPath(classDir) match {
        case Err(e) => return Err(e)
        case Ok(()) => ()
      }

      val files = FileOps.getFilesIn(classDir, Int.MaxValue).map(_.normalize())

      for (file <- files) {
        if (!FileOps.checkExt(file, EXT_CLASS)) {
          return Err(BootstrapError.FileError(s"Unexpected file extension in class directory (only '$EXT_CLASS' files are allowed): '${projectPath.relativize(file)}'"))
        }
        if (!(FileOps.isEmpty(file) || FileOps.isClassFile(file))) {
          return Err(BootstrapError.FileError(s"Refusing to delete a file that is not a class file: '${projectPath.relativize(file)}'"))
        }
        checkForDangerousPath(file) match {
          case Err(e) => return Err(e)
          case Ok(()) => ()
        }
      }

      for (file <- files if !keep.contains(file)) {
        FileOps.delete(file) match {
          case Err(e) => return Err(BootstrapError.FileError(s"Failed to delete file '$file': $e"))
          case Ok(_) => ()
        }
      }

      Ok(())
    }

    /**
      * Removes the directories under `dir` that are now empty, innermost first. `dir` itself is
      * kept: the next build writes into it.
      *
      * A package that loses its last class file leaves a directory behind, and a tree of empty
      * directories is what a build that only ever deleted files looks like after a rename.
      */
    private def pruneEmptyDirectories(dir: Path): Result[Unit, BootstrapError] = {
      if (!Files.exists(dir)) {
        return Ok(())
      }

      val directories = FileOps.getDirectoriesIn(dir, Int.MaxValue).map(_.normalize()).filterNot(_ == dir.normalize())
      for (d <- directories.reverse) {
        checkForDangerousPath(d) match {
          case Err(e) => return Err(e)
          case Ok(()) => ()
        }
        // Only empty directories are removed, and `reverse` puts the innermost first, so a
        // directory emptied by this loop is itself removed in the same pass.
        val isEmpty = Using(Files.list(d))(!_.findAny().isPresent)
        isEmpty match {
          case Success(true) =>
            FileOps.delete(d) match {
              case Err(e) => return Err(BootstrapError.FileError(s"Failed to delete directory '$d': $e"))
              case Ok(_) => ()
            }
          case Success(false) => ()
          case Failure(e) => return Err(BootstrapError.FileError(s"Failed to inspect directory '$d': ${e.getMessage}"))
        }
      }

      Ok(())
    }

    /**
      * Discards the state an incremental build would have reused: the compiler's cached ASTs.
      *
      * Nothing on disk. A full build does not need to delete the previous build's class files
      * first - [[reconcileClassDirectory]] reaches the same directory after a successful compile -
      * and deleting them up front would destroy a working build's output whenever the compile
      * meant to replace it fails. The same applies to the manifest: leaving the old one in place
      * costs one more full build if this one fails, where deleting it early costs the products it
      * describes.
      *
      * The record of which sources have been loaded is deliberately *kept*. It looks like state an
      * incremental build reuses, and it is not: it is the only account of what the compiler was
      * given, so it is what identifies a source that has since been deleted. Clearing it left the
      * deleted file in the compiler's inputs and the reader failed on a file that was not there --
      * on every build after a failed one, since a failed build writes no manifest and so every
      * later build arrives here. Re-offering every source, which
      * [[updateStaleSourcesByTimestamp]] does without a watcher, already achieves what clearing was
      * for.
      */
    def discardIncrementalState(flix: Flix): Unit = {
      flix.clearCaches()
    }

    /**
      * Empties the output directory of the build mode `build`: every class file, the directories
      * that leaves empty, and the build manifest.
      *
      * This is the one thing `--clean` does that an ordinary build does not, and it runs *before*
      * the compile on purpose. The request is to build from nothing, so there must be no moment at
      * which the previous build's output could be taken for this one's - which is what makes a
      * `--clean` artifact a function of the sources and nothing else. It follows that a `--clean`
      * whose compile then fails leaves nothing behind; that is the bargain the caller asked for,
      * and it is why a full build forced by a *changed fingerprint* deliberately does not come
      * here.
      *
      * The manifest goes first. A manifest that outlived the products it describes is one the next
      * build would trust; a missing one only costs one more full build.
      */
    def emptyOutputDirectory(build: Build): Result[Unit, BootstrapError] = {
      for {
        _ <- deleteBuildManifest(build)
        _ <- removeClassFiles(build, keep = Set.empty)
        _ <- pruneEmptyDirectories(Bootstrap.getClassDirectory(projectPath, build))
      } yield {
        ()
      }
    }

    /**
      * Returns the recorded manifest of the previous build in the mode `build`, if there is one
      * this compiler can read.
      */
    def readBuildManifest(build: Build): Option[BuildManifest] =
      BuildManifest.read(Bootstrap.getBuildManifestFile(projectPath, build))

    /**
      * Records what this build produced, from which inputs.
      *
      * Written after the class directory has been reconciled, so that a manifest exists only
      * once it describes the directory.
      */
    def writeBuildManifest(build: Build, fingerprint: String, products: Set[Path]): Result[Unit, BootstrapError] = {
      // Only sources that exist. A deleted path stays in `sourcePaths` on purpose - so that a file
      // which comes back is noticed - but recording it here would describe the build as having read
      // a file that is not there.
      val manifest = BuildManifest(
        fingerprint,
        products.toList.map(BuildManifest.nameOf).sorted,
        sourcePaths.filter(Files.isRegularFile(_)).map(p => BuildManifest.relativeName(projectPath, p)).sorted
      )
      BuildManifest.write(Bootstrap.getBuildManifestFile(projectPath, build), manifest)
        .mapErr(e => BootstrapError.FileError(s"Failed to write the build manifest: ${e.getMessage}"))
    }

    /** Deletes the build manifest of the build mode `build`, if there is one. */
    private def deleteBuildManifest(build: Build): Result[Unit, BootstrapError] = {
      val path = Bootstrap.getBuildManifestFile(projectPath, build)
      if (!Files.exists(path)) {
        return Ok(())
      }
      checkForDangerousPath(path) match {
        case Err(e) => return Err(e)
        case Ok(()) => ()
      }
      FileOps.delete(path).mapErr(e => BootstrapError.FileError(s"Failed to delete the build manifest: ${e.getMessage}"))
    }

    /**
      * Returns `Ok(())` if `jarFile` exists and is a readable jar file (a zip archive).
      * If `jarFile` does not exist, it also returns `Ok(())`.
      *
      * @see [[Bootstrap.isJarFile]]
      */
    def validateJarFile(jarFile: Path): Result[Unit, BootstrapError] = {
      if (Files.exists(jarFile) && !Bootstrap.isJarFile(jarFile)) {
        return Err(BootstrapError.FileError(s"The path '${jarFile.toString}' exists and is not a jar-file."))
      }
      Ok(())
    }

    /**
      * Returns `Ok(())` if all files ending with `.jar` in `dir` are valid jar files.
      *
      * @see [[Steps.validateJarFile]]
      */
    def validateJarFilesIn(dir: Path): Result[Unit, BootstrapError] = {
      Result.traverse(FileOps.getFilesWithExtIn(dir, EXT_JAR, Int.MaxValue))(Steps.validateJarFile).map(_ => ())
    }

  }
}
