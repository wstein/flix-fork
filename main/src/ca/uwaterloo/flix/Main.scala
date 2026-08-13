/*
 * Copyright 2019 Magnus Madsen
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

package ca.uwaterloo.flix

import ca.uwaterloo.flix.api.bsp.{BspDiscovery, BspServer}
import ca.uwaterloo.flix.api.lsp.{LspServer, VSCodeLspServer, FormatterLsp as LspFormatter}
import ca.uwaterloo.flix.tools.fmt.{Canonical, PrettyPrinter}
import ca.uwaterloo.flix.api.{Bootstrap, BootstrapError, CliContract, Flix, Version}
import org.json4s.native.JsonMethods
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.shared.{Input, SecurityContext}
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, TypedAst}
import ca.uwaterloo.flix.language.phase.Documentor
import ca.uwaterloo.flix.language.phase.unification.zhegalkin.ZhegalkinPerf
import ca.uwaterloo.flix.runtime.Coverage
import ca.uwaterloo.flix.runtime.shell.Shell
import ca.uwaterloo.flix.tools.*
import ca.uwaterloo.flix.tools.pkg.PackageModules
import ca.uwaterloo.flix.util.*

import picocli.CommandLine
import picocli.CommandLine.Model.{CommandSpec, OptionSpec, PositionalParamSpec}

import java.io.{File, IOException, PrintStream}
import java.net.BindException
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

object Main {

  def main(argv: Array[String]): Unit = {

    // retrieve the current working directory.
    val cwd = Paths.get(".").toAbsolutePath.normalize()

    // parse command line options.
    val cmdOpts: CmdOpts = parseCmdOpts(argv).getOrElse {
      Console.err.println("Unable to parse command line arguments. Will now exit.")
      System.exit(1)
      null
    }

    // get GitHub token
    val githubToken =
      cmdOpts.githubToken
        .orElse(FileOps.readLine(cwd.resolve("./.GITHUB_TOKEN").normalize()))
        .orElse(sys.env.get("GITHUB_TOKEN"))

    // compute the main entry point
    val entryPoint = cmdOpts.entryPoint match {
      case None => Options.Default.entryPoint
      case Some(s) => Some(Symbol.mkDefnSym(s))
    }

    // construct flix options.
    var options = Options(
      lib = cmdOpts.xlib,
      build = Build.Development,
      compilerTop = cmdOpts.top,
      coverage = cmdOpts.coverage,
      coverageOutput = cmdOpts.coverageOutput.map(Paths.get(_)).getOrElse(Options.Default.coverageOutput),
      coverageLcovOutput = cmdOpts.coverageLcovOutput.map(Paths.get(_)).getOrElse(Options.Default.coverageLcovOutput),
      docFormat = cmdOpts.docFormat,
      entryPoint = entryPoint,
      githubToken = githubToken,
      incremental = Options.Default.incremental,
      json = cmdOpts.json,
      progress = true,
      installDeps = cmdOpts.installDeps,
      outputJvm = false,
      outputPath = Options.Default.outputPath,
      threads = cmdOpts.threads.getOrElse(Options.Default.threads),
      loadClassFiles = Options.Default.loadClassFiles,
      assumeYes = cmdOpts.assumeYes,
      xprintphases = cmdOpts.xprintphases,
      xnodeprecated = cmdOpts.xnodeprecated,
      xsummary = cmdOpts.xsummary,
      xsubeffecting = cmdOpts.xsubeffecting,
      xdatalogDebug = cmdOpts.xdatalogDebug,
      xnewmono = cmdOpts.xnewmono,
      XPerfFrontend = cmdOpts.XPerfFrontend,
      XPerfPar = cmdOpts.XPerfPar,
      XPerfN = cmdOpts.XPerfN,
      xchaosMonkey = Options.Default.xchaosMonkey,
      xdebug = cmdOpts.xdebug
    )

    // Don't use progress bar if benchmarking.
    if (cmdOpts.xbenchmarkCodeSize || cmdOpts.xbenchmarkIncremental || cmdOpts.xbenchmarkPhases || cmdOpts.xbenchmarkFrontend || cmdOpts.xbenchmarkThroughput) {
      options = options.copy(progress = false)
    }

    // Don't use progress bar / --top TUI if not attached to a console.
    if (System.console() == null) {
      options = options.copy(progress = false, compilerTop = false)
    }

    // Don't use progress bar if --top is set: the live TUI repaints the screen
    // every 100ms and the spinner would just fight with it.
    if (cmdOpts.top) {
      options = options.copy(progress = false)
    }

    // check if command was passed.
    try {
      implicit val formatter: Formatter = Formatter.getDefault
      implicit val out: PrintStream = System.err

      cmdOpts.command match {
        case Command.None =>
          // check if the --listen flag was passed.
          if (cmdOpts.listen.nonEmpty) {
            SocketServer.listen(cmdOpts.listen.get)
            System.exit(0)
          }

          // check if the --Xbenchmark-code-size flag was passed.
          if (cmdOpts.xbenchmarkCodeSize) {
            BenchmarkCompilerOld.benchmarkCodeSize(options)
            System.exit(0)
          }

          // check if the --Xbenchmark-incremental flag was passed.
          if (cmdOpts.xbenchmarkIncremental) {
            BenchmarkCompilerOld.benchmarkIncremental(options)
            System.exit(0)
          }

          // check if the --Xbenchmark-phases flag was passed.
          if (cmdOpts.xbenchmarkPhases) {
            BenchmarkCompilerOld.benchmarkPhases(options)
            System.exit(0)
          }

          // check if the --Xbenchmark-frontend flag was passed.
          if (cmdOpts.xbenchmarkFrontend) {
            BenchmarkCompilerOld.benchmarkThroughput(options, frontend = true)
            System.exit(0)
          }

          // check if the --Xbenchmark-throughput flag was passed.
          if (cmdOpts.xbenchmarkThroughput) {
            BenchmarkCompilerOld.benchmarkThroughput(options, frontend = false)
            System.exit(0)
          }

          // check if we should start a REPL
          if (cmdOpts.files.isEmpty) {
            Bootstrap.bootstrap(cwd, options.githubToken) match {
              case Result.Ok(bootstrap) =>
                val shell = new Shell(bootstrap, options)
                shell.loop()
                System.exit(0)
              case Result.Err(error) =>
                println(error.message(formatter))
                System.exit(1)
            }
          }

          // configure Flix and add the paths.
          val flix = new Flix()
          flix.setOptions(options)
          implicit val sctx: SecurityContext = SecurityContext.Unrestricted
          for (file <- cmdOpts.files) {
            val ext = file.getName.split('.').last
            ext match {
              case "flix" => flix.addFile(file.toPath)
              case "fpkg" => flix.addPkg(file.toPath)
              case "jar" => flix.addJar(file.toPath)
              case _ =>
                Console.println(s"Unrecognized file extension: '$ext'.")
                System.exit(1)
            }
          }

          flix.setFormatter(formatter)

          // evaluate main.
          flix.check() match {
            case (Some(root), Nil) =>
              flix.codeGen(root).getMain match {
                case None => // nop
                case Some(m) =>
                  // Invoke main with the supplied arguments.
                  m(cmdOpts.args.toArray)
              }
              System.exit(0)
            case (optRoot, errors) =>
              println(CompilationMessage.formatAll(errors)(formatter, optRoot))
              System.exit(1)
          }

        case Command.Init =>
          val projectPath = initProjectPath(cwd, cmdOpts.files).getOrElse {
            println("The 'init' command accepts at most one directory argument.")
            System.exit(1)
            cwd
          }
          exitOnResult {
            val initOptions = if (Files.exists(projectPath.resolve("flix.toml"))) {
              Bootstrap.InitOptions.Default
            } else {
              promptForInitOptions()
            }
            Bootstrap.init(projectPath, initOptions).flatMap { _ =>
              // Everything init writes is written only if absent, so refreshing is a separate step
              // rather than a mode: it is the one thing that overwrites a file.
              if (cmdOpts.refresh) Bootstrap.refreshAgentGuide(projectPath) else Result.Ok(())
            }
          }

        case Command.Capabilities =>
          // The same document `flix/initializeBuild` will return over a connection. Offering it
          // one-shot is what lets a build tool negotiate *before* deciding whether a daemon is
          // available -- and keeps the one-shot path a first-class fallback rather than a
          // degraded mode that skips the handshake.
          val (compatible, document) = CliContract.describe(cmdOpts.clientContractVersion)
          Console.out.println(JsonMethods.pretty(JsonMethods.render(document)))
          System.exit(if (compatible) 0 else 1)

        case Command.Stubs =>
          // Pass 0 of joint compilation. It exists to run *before* anything is compiled, on a
          // program that cannot yet compile: a Flix module calling a Java class that does not
          // exist because that class calls back into this module. So it must not bootstrap the
          // project or resolve dependencies -- it reads sources and nothing else.
          val destination = Paths.get(cmdOpts.stubsOut.getOrElse("build/stubs"))
          val sources =
            if (cmdOpts.files.nonEmpty) cmdOpts.files.toList.map(_.toPath)
            else FileOps.getFilesWithExtIn(cwd.resolve("src"), "flix", Int.MaxValue)

          implicit val sctx: SecurityContext = SecurityContext.Unrestricted
          implicit val flix: Flix = new Flix().setFormatter(formatter).setOptions(options)
          val (facades, unsupported) = ExportStubs.run(sources.map(Input.RealFile(_, sctx)))

          if (unsupported.nonEmpty) {
            // Refusing is the conservative outcome, not the convenient one: a wrong stub compiles,
            // and the caller meets the mistake as a linkage error at run time.
            Console.err.println("Cannot describe these exported defs in Java:")
            for (u <- unsupported) Console.err.println(s"  ${u.loc.format}: ${u.name} -- ${u.reason}")
            Console.err.println("Import the Java types they name, or give them a type that can cross the boundary.")
            System.exit(1)
          }

          ExportStubs.write(facades, destination)
          println(s"Wrote ${facades.length} stub(s) to $destination")
          System.exit(0)

        case Command.Check if cmdOpts.sarifPath.isDefined =>
          // The SARIF is written beside the ordinary output rather than instead of it: the file is
          // for the pull request, and whoever ran the command still wants to read the errors.
          val flix =
            if (cmdOpts.files.isEmpty) {
              Bootstrap.bootstrap(cwd, options.githubToken)(formatter, System.out) match {
                case Result.Ok(bootstrap) =>
                  val f = new Flix().setFormatter(formatter)
                  f.setOptions(options)
                  bootstrap.check(f)
                  f
                case Result.Err(e) =>
                  println(e.message(formatter))
                  System.exit(1)
                  return
              }
            } else mkFlixWithFiles(cmdOpts.files, options)
          val (optRoot, errors) = flix.check()
          // A project that does not compile still has smells worth reporting, and a project that
          // does has diagnostics worth none.
          val smells = optRoot
            .map(r => Metrics.violations(Metrics.compute(r, Some(cwd)), Metrics.SmellThresholds))
            .getOrElse(Nil)
          // Resolved against the working directory, so that a bare file name has a parent to
          // create and lands where it was asked for rather than wherever the process started.
          FileOps.writeString(cwd.resolve(cmdOpts.sarifPath.get),
            DiagnosticSarif.format(errors, smells, Some(cwd)))
          if (errors.isEmpty) System.exit(0) else exitWithErrors(flix, errors, optRoot)

        case Command.Check =>
          if (cmdOpts.jsonDiagnostics) {
            if (cmdOpts.files.nonEmpty) {
              exitWithJson(Result.Err(BootstrapError.FileError("The 'check' command does not support file arguments with '--diagnostics-json'.")))
            } else {
              exitWithJson {
                Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                  val flix = new Flix().setFormatter(formatter)
                  flix.setOptions(options)
                  addLibs(flix, cmdOpts.libs).flatMap(_ => runCheck(bootstrap, flix, cmdOpts, quiet = true))
                }
              }
            }
          } else if (cmdOpts.files.isEmpty) {
            exitOnResult {
              Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options)
                addLibs(flix, cmdOpts.libs).flatMap(_ => runCheck(bootstrap, flix, cmdOpts, quiet = false))
              }
            }
          } else {
            val flix = mkFlixWithFiles(cmdOpts.files, options)
            addLibs(flix, cmdOpts.libs) match {
              case Result.Ok(_) =>
                val (optRoot, errors) = flix.check()
                if (errors.isEmpty) System.exit(0)
                else exitWithErrors(flix, errors, optRoot)
              case Result.Err(error) => exitOnResult(Result.Err(error))
            }
          }

        case Command.Build =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'build' command does not support file arguments.")
            System.exit(1)
          }
          val runBuild = () => Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
            val flix = new Flix().setFormatter(formatter)
            flix.setOptions(options.copy(loadClassFiles = false))
            // The `--lib` jars are added before the fingerprint is computed, and they are part of it, so
            // a build given one is up to date or not on the same terms as any other.
            addLibs(flix, cmdOpts.libs).flatMap { _ =>
              bootstrap.buildIfNeeded(flix, clean = cmdOpts.clean).map { compiled =>
                // Said out loud, because a build that prints nothing and a build that did nothing look
                // the same. Not on the JSON path, which carries diagnostics and nothing else.
                if (!compiled && !cmdOpts.jsonDiagnostics) {
                  println("Nothing to do: the build output is up to date.")
                }
              }
            }
          }
          if (cmdOpts.jsonDiagnostics) exitWithJson(runBuild()) else exitOnResult(runBuild())

        case Command.BuildJar =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'build-jar' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              val flix = new Flix().setFormatter(formatter)
              flix.setOptions(options.copy(loadClassFiles = false))
              bootstrap.buildJar(flix, clean = cmdOpts.clean)
            }
          }

        case Command.BuildFatJar =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'build-fatjar' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              val flix = new Flix().setFormatter(formatter)
              flix.setOptions(options.copy(loadClassFiles = false))
              bootstrap.buildFatJar(flix, clean = cmdOpts.clean)
            }
          }

        case Command.BuildPkg =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'build-pkg' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              bootstrap.buildPkg()
            }
          }

        case Command.Clean =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'clean' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              bootstrap.clean()
            }
          }

        case Command.Doc =>
          if (cmdOpts.files.isEmpty) {
            exitOnResult {
              Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options)
                bootstrap.doc(flix)
              }
            }
          } else {
            val flix = mkFlixWithFiles(cmdOpts.files, options)
            val (optRoot, errors) = flix.check()
            if (errors.isEmpty) {
              Documentor.run(optRoot.get, PackageModules.All, options.docFormat)(flix)
              System.exit(0)
            } else exitWithErrors(flix, errors, optRoot)
          }

        case Command.Metric =>
          val requested = metricFormatOf(cmdOpts)
          val format = Metrics.Format.ofString(requested) match {
            case Some(fmt) => fmt
            case None =>
              println(s"Unknown metric format '$requested'. Expected one of: ${Metrics.Format.names}.")
              System.exit(1)
              return
          }
          // Progress goes to stderr whenever the report is meant for a program: "Resolving Flix
          // dependencies..." ahead of a JSON document makes it unparseable, and the point of these
          // formats is that something else reads them.
          // Only the text report is written for a terminal. Everything else is redirected into a
          // file or a pipe often enough that progress on stdout ends up inside the document --
          // which is how a Markdown report came to begin with an escape sequence.
          val progress = format match {
            case Metrics.Format.Text => System.out
            case Metrics.Format.Json | Metrics.Format.Csv | Metrics.Format.Markdown | Metrics.Format.Sarif => System.err
          }
          // And nothing but the text report is coloured, since an escape sequence in a document is
          // not a colour, it is a character someone has to strip.
          val reportFormatter = format match {
            case Metrics.Format.Text => formatter
            case _ => Formatter.NoFormatter
          }
          val flix =
            if (cmdOpts.files.isEmpty) {
              Bootstrap.bootstrap(cwd, options.githubToken)(formatter, progress) match {
                case Result.Ok(bootstrap) =>
                  val f = new Flix().setFormatter(formatter)
                  f.setOptions(options)
                  bootstrap.check(f)
                  f
                case Result.Err(e) =>
                  println(e.message(formatter))
                  System.exit(1)
                  return
              }
            } else mkFlixWithFiles(cmdOpts.files, options)
          val (optRoot, errors) = flix.check()
          optRoot match {
            case Some(root) =>
              // Reported even when the project does not type check, because the numbers are still
              // true of the code as written, and a beginner asking for them is often mid-repair.
              val report = Metrics.compute(root, Some(cwd))
              // Smells are always reported: a report that has to be asked for what it already
              // knows is a report someone will forget to ask.
              val asked = Metrics.Thresholds(
                cmdOpts.metricMaxLines, cmdOpts.metricMaxParams, cmdOpts.metricMaxNesting,
                cmdOpts.metricMaxComplexity, cmdOpts.metricMaxLineTokens, cmdOpts.metricMaxLineLength,
                cmdOpts.metricMinDocCoverage)
              val reporting = Metrics.Thresholds(
                asked.maxLines.orElse(Metrics.SmellThresholds.maxLines),
                asked.maxParameters.orElse(Metrics.SmellThresholds.maxParameters),
                asked.maxNesting.orElse(Metrics.SmellThresholds.maxNesting),
                asked.maxComplexity.orElse(Metrics.SmellThresholds.maxComplexity),
                asked.maxLineTokens.orElse(Metrics.SmellThresholds.maxLineTokens),
                asked.maxLineLength.orElse(Metrics.SmellThresholds.maxLineLength),
                asked.minDocCoverage.orElse(Metrics.SmellThresholds.minDocCoverage))
              val smells = Metrics.violations(report, reporting)
              print(Metrics.render(report, format, reportFormatter, smells))

              // Failing is still asked for. A default limit is a suggestion, and a suggestion that
              // breaks a build is not a suggestion -- so only a limit someone set can fail one.
              val exceeded = if (asked.isEmpty) Nil else Metrics.violations(report, asked)
              if (exceeded.nonEmpty) System.err.print(Metrics.formatViolations(exceeded, formatter))
              System.exit(if (errors.nonEmpty || exceeded.nonEmpty) 1 else 0)
            case None => exitWithErrors(flix, errors, optRoot)
          }

        case Command.Format =>
          // The canonical policy chooses spacing from the tokens alone; the default
          // reproduces the spacing the source had.
          val separators =
            if (cmdOpts.canonical) Canonical else PrettyPrinter.Separators.Verbatim
          // With no file arguments the whole project is formatted through Bootstrap;
          // otherwise only the named files are. The two are alternatives: without the
          // `else`, the project branch falls through into the file branch with an empty
          // file list, and is stopped only by `exitOnResult` never returning.
          if (cmdOpts.files.isEmpty) {
            exitOnResult {
              Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options)
                bootstrap.format(flix, separators)
              }
            }
          } else {
            val flix = mkFlixWithFiles(cmdOpts.files, options)
            // Formatting deliberately does not require the program to compile. A
            // developer mid-edit has a broken program most of the time, and a
            // formatter available only on correct code is unavailable exactly when
            // it is being used. The parser produces a tree for a malformed file,
            // the declarations that failed to parse are reproduced verbatim, and
            // the rest are formatted. Reporting the errors is `flix check`'s job,
            // so this neither prints them nor fails on them.
            val _ = flix.check()
            val syntaxTree = flix.getParsedAst
            LspFormatter.formatFiles(syntaxTree, cmdOpts.files.map(_.toPath).toList, separators)(flix)
            System.exit(0)
          }


        case Command.Run =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'run' command does not support file arguments.")
            System.exit(1)
          }
          val ran = Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
            val flix = new Flix().setFormatter(formatter)
            flix.setOptions(options)
            bootstrap.run(flix, cmdOpts.args.toArray, reuse = !cmdOpts.clean)
          }
          // The program's own exit code, not the compiler's success. A program that failed and a
          // compiler that could not build it are different outcomes, and a script has to tell them
          // apart: 1 for the second, whatever the program said for the first.
          exitWithCode(ran, options)

        case Command.Test =>
          if (cmdOpts.files.isEmpty) {
            exitOnResult(
              Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options.copy(progress = false))
                bootstrap.test(flix, reuse = !cmdOpts.clean)
              },
              options
            )
          } else {
            val flix = mkFlixWithFiles(cmdOpts.files, options.copy(progress = false))
            flix.compile() match {
              case Validation.Success(compilationResult) =>
                Tester.run(Nil, compilationResult)(flix) match {
                  case Result.Ok(_) =>
                    if (options.coverage) {
                      val session = Coverage.getSession
                      CoverageReporter.writeJsonReport(session, options.coverageOutput)
                      CoverageReporter.writeLcovReport(session, options.coverageLcovOutput)
                      println(CoverageReporter.formatSummary(session))
                    }
                    System.exit(0)
                  case Result.Err(_) =>
                    if (options.coverage) {
                      val session = Coverage.getSession
                      CoverageReporter.writeJsonReport(session, options.coverageOutput)
                      CoverageReporter.writeLcovReport(session, options.coverageLcovOutput)
                      println(CoverageReporter.formatSummary(session))
                    }
                    System.exit(1)
                }
              case Validation.Failure(errors) => exitWithErrors(flix, errors.toList, None)
            }
          }

        case Command.Repl =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'repl' command does not support file arguments.")
            System.exit(1)
          }
          Bootstrap.bootstrap(cwd, options.githubToken) match {
            case Result.Ok(bootstrap) =>
              val shell = new Shell(bootstrap, options)
              shell.loop()
              System.exit(0)
            case Result.Err(error) =>
              println(error.message(formatter))
              System.exit(1)
          }

        case Command.Bsp =>
          // No check that `cmdOpts.files` is empty: the command declares no positional, so the
          // parser refuses a file before this runs. It read as a guard under a flat parser that
          // accepted files for every command.
          //
          // `cwd` and not a flag: a client starts the server with the workspace as its working
          // directory, which is exactly the project it means.
          //
          // The status is the server's, not a constant: the specification asks for 0 after an orderly
          // shutdown and 1 for a `build/exit` without one, and a client reads it to tell a server that
          // went away cleanly from one that was told to stop and had not been shut down.
          System.exit(BspServer.run(options, cwd))

        case Command.BspInstall =>
          BspDiscovery.install(cwd, cmdOpts.bspJar.map(Paths.get(_)), cmdOpts.force) match {
            case Result.Ok(file) =>
              println(s"Wrote $file")
              if (BspDiscovery.isFromCheckout(cmdOpts.bspJar.map(Paths.get(_)))) {
                println("Note: it names this checkout's classpath, so it works on this machine only.")
              }
              System.exit(0)
            case Result.Err(message) =>
              println(message)
              System.exit(1)
          }

        case Command.PlainLsp =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'lsp' command does not support file arguments.")
            System.exit(1)
          }
          LspServer.run(options)
          System.exit(0)

        case Command.VSCodeLsp(port) =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'lsp-vscode' command does not support file arguments.")
            System.exit(1)
          }
          val o = options.copy(progress = false)
          try {
            val languageServer = new VSCodeLspServer(port, o)
            languageServer.run()
          } catch {
            case ex: BindException =>
              throw new RuntimeException(ex)
          }
          System.exit(0)

        case Command.Release =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'release' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              val flix = new Flix().setFormatter(formatter)
              flix.setOptions(options.copy(progress = false))
              bootstrap.release(flix)(System.err)
            }
          }

        case Command.Outdated =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'outdated' command does not support file arguments.")
            System.exit(1)
          }
          Bootstrap.bootstrap(cwd, options.githubToken).flatMap {
            bootstrap =>
              val flix = new Flix().setFormatter(formatter)
              flix.setOptions(options.copy(progress = false))
              bootstrap.outdated(flix)(System.err)
          } match {
            case Result.Ok(false) =>
              // Up to date
              System.exit(0)
            case Result.Ok(true) =>
              // Contains outdated dependencies
              System.exit(1)
            case Result.Err(error) =>
              println(error.message(formatter))
              System.exit(1)
          }

        case Command.EffCheck =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'eff-check' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              val flix = new Flix().setFormatter(formatter)
              flix.setOptions(options.copy(progress = false))
              bootstrap.checkEffects(flix)
            }
          }

        case Command.EffLock =>
          if (cmdOpts.files.nonEmpty) {
            println("The 'eff-lock' command does not support file arguments.")
            System.exit(1)
          }
          exitOnResult {
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap {
              bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options.copy(progress = false))
                bootstrap.lockEffects(flix)
            }
          }

        case Command.CompilerPerf =>
          CompilerPerf.run(options)

        case Command.CompilerMemory =>
          CompilerMemory.run(options)

        case Command.Zhegalkin =>
          ZhegalkinPerf.run(options.XPerfN)

      }
    } catch {
      case ex: RuntimeException =>
        ex.printStackTrace()
        System.exit(1)
    }
  }

  /**
    * A case class representing the parsed command line options.
    */
  case class CmdOpts(
    command: Command = Command.None,
    stubsOut: Option[String] = None,
    libs: Seq[String] = Seq.empty,
    jsonDiagnostics: Boolean = false,
    clientContractVersion: Option[Int] = None,
    args: List[String] = Nil,
    coverage: Boolean = false,
    coverageOutput: Option[String] = None,
    coverageLcovOutput: Option[String] = None,
    canonical: Boolean = false,
    clean: Boolean = false,
    bspJar: Option[String] = None,
    force: Boolean = false,
    docFormat: DocFormat = Options.Default.docFormat,
    // Absent rather than "text": what an unasked-for format resolves to is the command's decision,
    // and a default recorded here is one the command can no longer tell from an answer.
    metricFormat: Option[String] = None,
    sarifPath: Option[String] = None,
    metricMaxLines: Option[Int] = None,
    metricMaxParams: Option[Int] = None,
    metricMaxNesting: Option[Int] = None,
    metricMaxComplexity: Option[Int] = None,
    metricMinDocCoverage: Option[Double] = None,
    metricMaxLineTokens: Option[Int] = None,
    metricMaxLineLength: Option[Int] = None,
    entryPoint: Option[String] = None,
    installDeps: Boolean = true,
    githubToken: Option[String] = None,
    json: Boolean = false,
    listen: Option[Int] = None,
    refresh: Boolean = false,
    threads: Option[Int] = None,
    top: Boolean = false,
    assumeYes: Boolean = false,
    xbenchmarkCodeSize: Boolean = false,
    xbenchmarkIncremental: Boolean = false,
    xbenchmarkPhases: Boolean = false,
    xbenchmarkFrontend: Boolean = false,
    xbenchmarkThroughput: Boolean = false,
    xnodeprecated: Boolean = false,
    xdebug: Boolean = false,
    // Read inside `parseCmdOpts`, which prints and leaves: it is a question about the command line
    // rather than a setting for the run, and no command reads it back.
    xhelp: Boolean = false,
    xlib: LibLevel = LibLevel.All,
    xprintphases: Boolean = false,
    xsummary: Boolean = false,
    xsubeffecting: Set[Subeffecting] = Set.empty,
    xdatalogDebug: Set[DatalogDebug] = Set.empty,
    xnewmono: Boolean = false,
    XPerfN: Option[Int] = None,
    XPerfFrontend: Boolean = false,
    XPerfPar: Boolean = false,
    files: Seq[File] = Seq()
  )

  /**
    * A case class representing possible commands.
    */
  sealed trait Command

  object Command {

    case object None extends Command

    case object Init extends Command

    case object Check extends Command

    case object Build extends Command

    case object BuildJar extends Command

    case object BuildFatJar extends Command

    case object BuildPkg extends Command

    case object Clean extends Command

    case object Doc extends Command

    case object Metric extends Command

    case object Format extends Command

    case object Stubs extends Command

    case object Capabilities extends Command

    case object Run extends Command

    case object Test extends Command

    case object Repl extends Command

    case object PlainLsp extends Command

    case object Bsp extends Command

    case object BspInstall extends Command

    case class VSCodeLsp(port: Int) extends Command

    case object Release extends Command

    case object Outdated extends Command

    case object EffCheck extends Command

    case object EffLock extends Command

    case object CompilerPerf extends Command

    case object CompilerMemory extends Command

    case object Zhegalkin extends Command

  }

  /**
    * The report format `metric` was asked for, as the word naming it.
    *
    * `--format` when it was given, and text otherwise. The global `--json` is not a second spelling
    * of `--format json`: this command emits five formats, and a word that names one of them is a
    * shorthand only for as long as nobody reaches for another.
    *
    * Separate from the command that reads it so that the answer is checkable without running a
    * build: a shorthand withdrawn has to stay withdrawn, and nothing about it is visible in the
    * parsed options.
    */
  private[flix] def metricFormatOf(cmdOpts: CmdOpts): String =
    cmdOpts.metricFormat.getOrElse("text")

  /**
    * Reads `args` as a command line, or reports why it is not one.
    *
    * Returns `None` when a word cannot be read, having said which and why; the caller decides that
    * this is fatal. `--help` and `--version` answer the line rather than describing it, so they
    * print and leave rather than returning options nobody asked for.
    */
  def parseCmdOpts(args: Array[String]): Option[CmdOpts] = {
    // Everything after "--" belongs to the program being run, not to the compiler, so it is taken
    // out before the parser sees it. Only `run` reads it back -- it is what reaches `main`.
    val separatorIndex = args.indexOf("--")
    val (flixArgs, progArgs) = if (separatorIndex >= 0) {
      (args.take(separatorIndex), args.drop(separatorIndex + 1))
    } else {
      (args, Array.empty[String])
    }

    val cell = new OptsCell
    val commandLine = new CommandLine(rootSpec(cell))
    commandLine.registerConverter(classOf[LibLevel], converter(readLibLevel))
    commandLine.registerConverter(classOf[DocFormat], converter(readDocFormat))
    commandLine.registerConverter(classOf[DatalogDebug], converter(readDatalogDebug))
    commandLine.registerConverter(classOf[Subeffecting], converter(readSubeffecting))
    // `--format csv --format md` is a caller changing their mind -- a line assembled by a script
    // that appends a flag -- not a mistake to report. The last word wins.
    commandLine.setOverwrittenOptionsAllowed(true)

    val parsed =
      try commandLine.parseArgs(flixArgs *)
      catch {
        case e: CommandLine.ParameterException =>
          Console.err.println(e.getMessage)
          Console.err.println("Try --help for more information.")
          return None
      }

    // Help and version answer the command line rather than running it, and say so by leaving.
    // scopt did this from inside the parser; doing it here keeps the exit in one place.
    //
    // `--Xhelp` is answered first, because a line carrying both asks for the longer of the two
    // answers: it is `--help` again with nothing left out.
    if (cell.value.xhelp) {
      Console.out.print(usageText(HelpScope.Full, commandNameOf(parsed)))
      System.exit(0)
    }
    if (CommandLine.printHelpIfRequested(parsed)) {
      System.exit(0)
    }

    Some(cell.value.copy(command = commandOf(parsed), args = progArgs.toList))
  }

  /**
    * The usage text of `command`, or of `flix` itself, written at `scope`.
    *
    * Built from a spec of its own rather than from the one that parsed the line: what a usage text
    * leaves out is decided when the spec is built, and the spec that parsed the line has to be the
    * one that hides nothing from the parser.
    */
  private[flix] def usageText(scope: HelpScope, command: Option[String], ansi: CommandLine.Help.Ansi = CommandLine.Help.Ansi.AUTO): String = {
    val commandLine = new CommandLine(rootSpec(new OptsCell, scope))
    val target = command.flatMap(name => Option(commandLine.getSubcommands.get(name))).getOrElse(commandLine)
    target.getUsageMessage(ansi)
  }

  /** The name of the command the line names, as picocli spells it, or `None` for bare `flix`. */
  private def commandNameOf(parsed: CommandLine.ParseResult): Option[String] =
    if (parsed.hasSubcommand) Some(parsed.subcommand().commandSpec().name()) else None

  /**
    * Returns the command the parsed line names, or `Command.None`.
    *
    * A command is read from the parse tree rather than set by an action, because it is the one
    * thing about a line that is not a field somebody assigned: it is which branch of the parser was
    * taken.
    */
  private def commandOf(parsed: CommandLine.ParseResult): Command = {
    if (!parsed.hasSubcommand) return Command.None
    val sub = parsed.subcommand()
    sub.commandSpec().name() match {
      case "init" => Command.Init
      case "check" => Command.Check
      case "capabilities" => Command.Capabilities
      case "stubs" => Command.Stubs
      case "build" => Command.Build
      case "build-jar" => Command.BuildJar
      case "build-fatjar" => Command.BuildFatJar
      case "build-pkg" => Command.BuildPkg
      case "clean" => Command.Clean
      case "metric" => Command.Metric
      case "doc" => Command.Doc
      case "format" => Command.Format
      case "run" => Command.Run
      case "test" => Command.Test
      case "repl" => Command.Repl
      case "bsp" => Command.Bsp
      case "bsp-install" => Command.BspInstall
      case "lsp" => Command.PlainLsp
      // The only command carrying a value in its identity: there is no VSCode server without a port
      // to serve it on, so the port is an argument of the command and not a field beside it.
      case "lsp-vscode" => Command.VSCodeLsp(sub.matchedPositionalValue[Integer](0, Integer.valueOf(0)).intValue())
      case "release" => Command.Release
      case "outdated" => Command.Outdated
      case "eff-check" => Command.EffCheck
      case "eff-lock" => Command.EffLock
      case "Xperf" => Command.CompilerPerf
      case "Xmemory" => Command.CompilerMemory
      case "Xzhegalkin" => Command.Zhegalkin
      case other => throw new InternalCompilerException(s"Unknown command '$other'.", SourceLocation.Unknown)
    }
  }

  /**
    * The options accumulated so far, written by the setter of each option as it is matched.
    *
    * The order matters and is the reason this is a cell rather than a fold: a repeated option is
    * the last word winning and a repeatable one keeps the order it was given in, so each word has
    * to be applied where it appears. picocli calls a setter once per occurrence, in that order.
    */
  private[flix] final class OptsCell {
    var value: CmdOpts = CmdOpts()
  }

  /** Builds the setter that applies one matched option to the options so far. */
  private def assign[A](cell: OptsCell)(f: (CmdOpts, A) => CmdOpts): CommandLine.Model.ISetter =
    new CommandLine.Model.ISetter {
      override def set[T](value: T): T = {
        cell.value = f(cell.value, value.asInstanceOf[A])
        null.asInstanceOf[T]
      }
    }

  /** Builds a converter from a partial reading of a word, which reports the words it does accept. */
  private def converter[A](read: String => A): CommandLine.ITypeConverter[A] =
    (value: String) => read(value)

  private def readLibLevel(arg: String): LibLevel = arg match {
    case "nix" => LibLevel.Nix
    case "min" => LibLevel.Min
    case "all" => LibLevel.All
    case _ => throw new CommandLine.TypeConversionException(s"'$arg' is not a valid library level. Valid options are 'all', 'min', and 'nix'.")
  }

  private def readDocFormat(arg: String): DocFormat = arg match {
    case "html" => DocFormat.Html
    case "md" => DocFormat.Markdown
    case "all" => DocFormat.All
    case _ => throw new CommandLine.TypeConversionException(s"'$arg' is not a valid documentation format. Valid options are 'html', 'md', and 'all'.")
  }

  private def readDatalogDebug(arg: String): DatalogDebug = arg match {
    case "rules" => DatalogDebug.Rules
    case "facts" => DatalogDebug.Facts
    case "ram" => DatalogDebug.Ram
    case _ => throw new CommandLine.TypeConversionException(s"'$arg' is not a valid Datalog debug option. Valid options are comma-separated combinations of 'rules', 'facts', and 'ram'.")
  }

  private def readSubeffecting(arg: String): Subeffecting = arg match {
    case "mod-defs" => Subeffecting.ModDefs
    case "ins-defs" => Subeffecting.InsDefs
    case "lambdas" => Subeffecting.Lambdas
    case _ => throw new CommandLine.TypeConversionException(s"'$arg' is not a valid subeffecting option. Valid options are comma-separated combinations of 'mod-defs', 'ins-defs', and 'lambdas'.")
  }

  /** A flag: present or absent, and worth nothing when absent. */
  private def flag(cell: OptsCell, name: String, description: String)(f: CmdOpts => CmdOpts): OptionSpec =
    OptionSpec.builder(name)
      .description(description)
      .`type`(classOf[Boolean])
      .hasInitialValue(false)
      .setter(assign[java.lang.Boolean](cell)((c, on) => if (on) f(c) else c))
      .build()

  /** An option taking one value of type `A`. */
  private def value[A](cell: OptsCell, name: String, label: String, cls: Class[?], description: String)(f: (CmdOpts, A) => CmdOpts): OptionSpec =
    OptionSpec.builder(name)
      .paramLabel(label)
      .description(description)
      .`type`(cls)
      .hasInitialValue(false)
      .setter(assign[A](cell)(f))
      .build()

  /**
    * An option taking several values, either by repetition or by commas.
    *
    * The setter is called once per value, in the order the values appear, and is handed only that
    * value -- not the collection so far. So `f` is given what arrived and appends it: a `f` that
    * assigns instead keeps the last jar of a classpath and drops the rest, which is a program that
    * fails to find a class rather than a command line that reports an error.
    */
  private def values[A](cell: OptsCell, name: String, label: String, element: Class[?], split: Option[String], description: String)(f: (CmdOpts, Seq[A]) => CmdOpts): OptionSpec = {
    val builder = OptionSpec.builder(name)
      .paramLabel(label)
      .description(description)
      .`type`(classOf[java.util.List[?]])
      .auxiliaryTypes(element)
      .hasInitialValue(false)
      .setter(assign[java.util.List[A]](cell)((c, xs) => f(c, xs.asScala.toSeq)))
    split.foreach(builder.splitRegex)
    builder.build()
  }

  /**
    * The files named on the line, which every command that reads source accepts.
    *
    * A spec belongs to one command, so each command that takes files needs its own; they all write
    * the same field.
    */
  private def files(cell: OptsCell): PositionalParamSpec =
    PositionalParamSpec.builder()
      .index("0..*")
      .paramLabel("<file>")
      .description("input Flix source code files, Flix packages, and Java archives.")
      .`type`(classOf[java.util.List[?]])
      .auxiliaryTypes(classOf[File])
      .hasInitialValue(false)
      .setter(assign[java.util.List[File]](cell)((c, fs) => c.copy(files = c.files ++ fs.asScala)))
      .build()

  /** Builds a command that takes files and nothing else. */
  private def command(cell: OptsCell, name: String, description: String, takesFiles: Boolean = true): CommandSpec = {
    val spec = CommandSpec.create().name(name)
    // The globals are inherited by every command, so spelling them all into the synopsis buries the
    // one line a reader came for -- what this command takes.
    spec.usageMessage().description(description).abbreviateSynopsis(true)
    if (takesFiles) spec.addPositional(files(cell))
    spec
  }

  /**
    * How much of the command line a usage text describes.
    *
    * Only the usage text: both scopes parse the same language, since an option a reader was not
    * shown is still an option they were once told about, and refusing it would break the line they
    * saved. Hiding is what a help text leaves out, never what the parser accepts.
    */
  private[flix] sealed trait HelpScope

  private[flix] object HelpScope {

    /** What the command takes, without the experimental options and commands. */
    case object Standard extends HelpScope

    /** Everything, experimental included. What `--Xhelp` prints. */
    case object Full extends HelpScope
  }

  /**
    * The synopsis, as lines that fit a terminal, listing every command a reader may type.
    *
    * Wrapped here rather than by picocli, which sees the whole bracketed group as one word and so
    * breaks it wherever the width runs out -- `metr|ic`, `eff-|check`. A command name split across
    * two lines is not a command name.
    *
    * The continuation indent aligns under the first name, which is `"Usage: "` plus `"flix ["`, so
    * every line has the same room for names.
    */
  private def synopsisLines(names: Seq[String]): Array[String] = {
    val head = "flix ["
    val tail = "] [options] <file>..."
    val indent = " " * ("Usage: ".length + head.length)
    val budget = 80 - indent.length

    val lines = mutable.ArrayBuffer.empty[String]
    var current = new StringBuilder
    for ((name, i) <- names.zipWithIndex) {
      // The tail is carried by the last name rather than appended afterwards, so the bracket that
      // closes the list is never orphaned on a line of its own.
      val last = i == names.length - 1
      val token = if (last) name + tail else name
      val word = if (current.isEmpty) token else s"|$token"
      // A broken line keeps the separator that follows it, or the names either side of the break
      // read as a list and a name standing apart from it. Room for that separator is reserved by
      // packing every continued line to one character less than the width.
      val limit = if (last) budget else budget - 1
      if (current.nonEmpty && current.length + word.length > limit) {
        lines += current.append("|").toString()
        current = new StringBuilder(token)
      } else {
        current ++= word
      }
    }
    lines += current.toString()

    (head + lines.head) +: lines.tail.map(indent + _).toArray
  }

  /**
    * True of an option or command whose name marks it experimental.
    *
    * The name is the marker rather than a flag beside it, because the name is the part a reader
    * sees: `--Xdebug` and `Xperf` say what they are wherever they are quoted, including in a bug
    * report that quotes nothing else. `TestMain` holds the description's `[experimental]` prefix to
    * the same rule, so an option cannot be experimental in its name and stable in its description.
    */
  private def isExperimental(name: String): Boolean = name.stripPrefix("--").startsWith("X")

  /**
    * The whole command line, as a tree of commands, described to the depth `scope` asks for.
    *
    * Every command takes the global options as well as its own, so `flix build --threads 4` and
    * `flix --threads 4 build` are the same line -- which is the thing a single flat parser cannot
    * do. Which of them a usage text *lists* is [[addGlobalOptions]]'s decision, and no parser's.
    */
  private[flix] def rootSpec(cell: OptsCell, scope: HelpScope = HelpScope.Standard): CommandSpec = {
    val root = CommandSpec.create().name("flix")
    root.usageMessage()
      .header("The Flix Programming Language", Version.CurrentVersion.toString)
      .abbreviateSynopsis(true)
      .synopsisSubcommandLabel("[COMMAND]")
    // A list is only worth reading if everything on it is meant for the reader, so the experimental
    // options are off it -- and a reader who is looking for one has to be told where it went, or
    // hiding it is indistinguishable from removing it.
    if (scope == HelpScope.Standard) {
      root.usageMessage().footer("Experimental options and commands are omitted. Run 'flix --Xhelp' to list them.")
    }
    // What `--version` prints. The release workflow runs the built jar and refuses to publish it
    // unless this contains the tag being released, so a version option that prints nothing does not
    // fail here -- it fails in CI, on the one run that matters.
    root.version(s"The Flix Programming Language ${Version.CurrentVersion.toString}")
    root.addPositional(files(cell))

    val init = command(cell, "init", "interactively creates a new project in an optional directory.")
    init.addOption(flag(cell, "--refresh",
      "rewrites the generated agent guide for this version of Flix. An edited guide is left alone.")(_.copy(refresh = true)))

    val check = command(cell, "check", "checks the current project for errors.")
    check.addOption(value[String](cell, "--sarif", "<file>", classOf[String],
      "also writes the diagnostics, and what 'metric' would report, to <file> as SARIF 2.1.0.")((c, p) => c.copy(sarifPath = Some(p))))
    check.addOption(libOption(cell))
    check.addOption(diagnosticsJson(cell))
    check.addOption(cleanOption(cell, "checks even when a successful build already answers for these sources."))

    val capabilities = command(cell, "capabilities", "reports the tooling contract this compiler speaks.", takesFiles = false)
    capabilities.addOption(value[Integer](cell, "--contract-version", "<n>", classOf[Integer],
      "the contract version the caller speaks. Exits non-zero if it cannot be served.")((c, v) => c.copy(clientContractVersion = Some(v.intValue()))))

    val stubs = command(cell, "stubs", "writes compile-only Java stubs for the @Export-ed defs.")
    stubs.addOption(value[String](cell, "--out", "<dir>", classOf[String],
      "where to write the stubs. Defaults to 'build/stubs'.")((c, p) => c.copy(stubsOut = Some(p))))

    val build = command(cell, "build", "builds (i.e. compiles) the current project.")
    build.addOption(libOption(cell))
    build.addOption(diagnosticsJson(cell))
    build.addOption(cleanOption(cell, "empties the output directory first and rebuilds from nothing."))

    val buildJar = command(cell, "build-jar", "builds a jar-file from the current project.")
    buildJar.addOption(cleanOption(cell, "empties the output directory first and rebuilds from nothing. For a reproducible release."))

    val buildFatJar = command(cell, "build-fatjar", "builds a fatjar-file from the current project.")
    buildFatJar.addOption(cleanOption(cell, "empties the output directory first and rebuilds from nothing. For a reproducible release."))

    val run = command(cell, "run", "runs main for the current project.")
    run.addOption(cleanOption(cell, "empties the output directory and rebuilds before running."))

    val test = command(cell, "test", "runs the tests for the current project.")
    test.addOption(cleanOption(cell, "empties the output directory and rebuilds before testing."))

    // Two commands rather than `bsp --install`: a flag on `bsp` would also be accepted while
    // serving, and writing a JSON document onto the protocol stream is the failure this whole
    // endpoint is arranged to avoid.
    val bsp = command(cell, "bsp", "starts the Build Server Protocol server on stdio.", takesFiles = false)

    val bspInstall = command(cell, "bsp-install", "writes '.bsp/flix.json' so an editor can find the BSP server.", takesFiles = false)
    bspInstall.addOption(value[String](cell, "--jar", "<path>", classOf[String],
      "the compiler jar to name in the connection file. Defaults to the running one.")((c, p) => c.copy(bspJar = Some(p))))
    bspInstall.addOption(flag(cell, "--force",
      "replaces a connection file this server did not write.")(_.copy(force = true)))

    val metric = command(cell, "metric", "displays code or compiler metrics for the project.")
    metric.addOption(value[String](cell, "--format", "<format>", classOf[String],
      "selects the format that 'metric' emits (text, json, csv, md, sarif). Defaults to text.")((c, f) => c.copy(metricFormat = Some(f))))
    metric.addOption(value[Integer](cell, "--max-lines", "<n>", classOf[Integer],
      "fails if any function is longer than this many lines.")((c, n) => c.copy(metricMaxLines = Some(n.intValue()))))
    metric.addOption(value[Integer](cell, "--max-params", "<n>", classOf[Integer],
      "fails if any parameter list, including a local definition's, is wider than this.")((c, n) => c.copy(metricMaxParams = Some(n.intValue()))))
    metric.addOption(value[Integer](cell, "--max-nesting", "<n>", classOf[Integer],
      "fails if any function nests branches deeper than this.")((c, n) => c.copy(metricMaxNesting = Some(n.intValue()))))
    metric.addOption(value[Integer](cell, "--max-complexity", "<n>", classOf[Integer],
      "fails if any function has a cognitive complexity above this.")((c, n) => c.copy(metricMaxComplexity = Some(n.intValue()))))
    metric.addOption(value[Integer](cell, "--max-line-tokens", "<n>", classOf[Integer],
      "fails if any line holds more tokens than this.")((c, n) => c.copy(metricMaxLineTokens = Some(n.intValue()))))
    metric.addOption(value[Integer](cell, "--max-line-length", "<n>", classOf[Integer],
      "fails if any line is longer than this many characters.")((c, n) => c.copy(metricMaxLineLength = Some(n.intValue()))))
    metric.addOption(value[java.lang.Double](cell, "--min-doc-coverage", "<fraction>", classOf[java.lang.Double],
      "fails if less than this fraction of the public API is documented, e.g. 0.8.")((c, d) => c.copy(metricMinDocCoverage = Some(d.doubleValue()))))

    val doc = command(cell, "doc", "generates API documentation.")
    doc.addOption(value[DocFormat](cell, "--doc-format", "<format>", classOf[DocFormat],
      "selects the format that 'doc' emits (html, md, all). Defaults to html.")((c, f) => c.copy(docFormat = f)))

    val format = command(cell, "format", "formats Flix source code files.")
    format.addOption(flag(cell, "--canonical",
      "imposes one spacing per syntax tree, instead of preserving the source's own.")(_.copy(canonical = true)))

    val lspVscode = command(cell, "lsp-vscode", "starts the VSCode-LSP server and listens on the given port.", takesFiles = false)
    lspVscode.addPositional(PositionalParamSpec.builder()
      .index("0")
      .paramLabel("<port>")
      .description("the port number to listen on.")
      .`type`(classOf[Integer])
      .required(true)
      .build())

    val xperf = command(cell, "Xperf", "benchmarks the compiler.", takesFiles = false)
    xperf.addOption(flag(cell, "--frontend", "benchmarks the frontend only.")(_.copy(XPerfFrontend = true)))
    xperf.addOption(flag(cell, "--par", "benchmarks parallel evaluation only.")(_.copy(XPerfPar = true)))
    xperf.addOption(perfN(cell))

    val xmemory = command(cell, "Xmemory", "benchmarks compiler memory use.", takesFiles = false)

    val xzhegalkin = command(cell, "Xzhegalkin", "benchmarks Zhegalkin normal forms.", takesFiles = false)
    xzhegalkin.addOption(perfN(cell))

    root.addSubcommand("init", init)
    root.addSubcommand("check", check)
    root.addSubcommand("capabilities", capabilities)
    root.addSubcommand("stubs", stubs)
    root.addSubcommand("build", build)
    root.addSubcommand("build-jar", buildJar)
    root.addSubcommand("build-fatjar", buildFatJar)
    root.addSubcommand("build-pkg", command(cell, "build-pkg", "builds a fpkg-file from the current project."))
    root.addSubcommand("clean", command(cell, "clean", "recursively removes class files from the build directory."))
    root.addSubcommand("metric", metric)
    root.addSubcommand("doc", doc)
    root.addSubcommand("format", format)
    root.addSubcommand("run", run)
    root.addSubcommand("test", test)
    root.addSubcommand("repl", command(cell, "repl", "starts a repl for the current project, or provided Flix source files."))
    root.addSubcommand("bsp", bsp)
    root.addSubcommand("bsp-install", bspInstall)
    root.addSubcommand("lsp", command(cell, "lsp", "starts the Plain-LSP server.", takesFiles = false))
    root.addSubcommand("lsp-vscode", lspVscode)
    root.addSubcommand("release", command(cell, "release", "releases a new version to GitHub.", takesFiles = false))
    root.addSubcommand("outdated", command(cell, "outdated", "shows dependencies which have newer versions available.", takesFiles = false))
    root.addSubcommand("eff-check", command(cell, "eff-check", "checks that dependencies respect the 'effects.lock' file.", takesFiles = false))
    root.addSubcommand("eff-lock", command(cell, "eff-lock", "locks the current effect signatures.", takesFiles = false))
    root.addSubcommand("Xperf", xperf)
    root.addSubcommand("Xmemory", xmemory)
    root.addSubcommand("Xzhegalkin", xzhegalkin)

    // A command is hidden by the same rule as an option, and by reading the same name, so `--Xhelp`
    // is one answer about what else is there rather than two half-answers -- and a command added
    // later is covered by having been named `X...`, which is where the claim was made anyway.
    if (scope == HelpScope.Standard) {
      for ((name, sub) <- root.subcommands().asScala if isExperimental(name)) {
        sub.getCommandSpec.usageMessage().hidden(true)
      }
    }

    // The commands, spelled out. picocli's `[COMMAND]` says that a command exists without saying
    // which, so the first line a reader sees named nothing they could type. Generated from the
    // visible subcommands rather than written out, so it cannot drift from what the parser takes --
    // which is exactly what the hand-written synopsis it replaces had done.
    val visible = root.subcommands().asScala.collect {
      case (name, sub) if !sub.getCommandSpec.usageMessage().hidden() => name
    }
    root.usageMessage().customSynopsis(synopsisLines(visible.toSeq) *)

    addGlobalOptions(cell, root, scope)
    root
  }

  /**
    * Attaches the options every command takes to `root` and to each of its commands.
    *
    * Declared once and copied rather than inherited (`ScopeType.INHERIT`), because an inherited
    * option carries one visibility everywhere it lands and these need two: `flix --help` is where a
    * reader looks for what applies to every command, and `flix build --help` is where they look for
    * what `build` takes -- a page that answered the first question was answering it once per
    * command and the second one never.
    *
    * `-h` is the exception, listed wherever it is answered: the option you reach for when lost is
    * no use hidden. Copying keeps the guard that inheriting gave, since a command that declared one
    * of these names itself would now be declaring it twice, which picocli refuses.
    */
  private def addGlobalOptions(cell: OptsCell, root: CommandSpec, scope: HelpScope): Unit = {
    globalOptions(cell, scope).foreach(root.addOption)
    for (sub <- root.subcommands().values().asScala) {
      val spec = sub.getCommandSpec
      globalOptions(cell, scope).map(onCommand(_, scope)).foreach(spec.addOption)
      // Where the shared options went. Without this the reader is left to conclude that `build`
      // does not take `--threads`, which is the one thing the shorter page must not say.
      if (scope == HelpScope.Standard) {
        spec.usageMessage().footer("Options common to every command are listed by 'flix --help', experimental ones by 'flix --Xhelp'.")
      }
    }
  }

  /** The form a global option takes on a command: answered there, listed only where it is an answer. */
  private def onCommand(spec: OptionSpec, scope: HelpScope): OptionSpec =
    spec.toBuilder.hidden(scope == HelpScope.Standard && !spec.usageHelp()).build()

  /** Repeated on `build` and `check`, where a classpath is assembled. */
  private def libOption(cell: OptsCell): OptionSpec =
    values[String](cell, "--lib", "<jar>", classOf[String], None,
      "adds a jar to the classpath. Repeatable.")((c, xs) => c.copy(libs = c.libs ++ xs))

  /**
    * Repeated on the six commands that can answer from a recorded build.
    *
    * Declared per command rather than globally, and that is the point of it: a command that cannot
    * skip work has no use for a flag that says do it anyway, and a flag nothing reads is worse than
    * one the parser rejects -- it looks like an escape hatch and is not.
    */
  private def cleanOption(cell: OptsCell, description: String): OptionSpec =
    flag(cell, "--clean", description)(_.copy(clean = true))

  private def diagnosticsJson(cell: OptsCell): OptionSpec =
    flag(cell, "--diagnostics-json",
      "writes diagnostics to stdout as JSON, for a build tool to read.")(_.copy(jsonDiagnostics = true))

  private def perfN(cell: OptsCell): OptionSpec =
    value[Integer](cell, "--n", "<n>", classOf[Integer], "the number of compilations to run.")((c, n) => c.copy(XPerfN = Some(n.intValue())))

  /**
    * The options every command takes, wherever they appear on the line, as they are listed on
    * `flix` itself. [[addGlobalOptions]] copies them onto each command.
    *
    * Position stops mattering because every command declares all of them, which is also what
    * forbids a command from declaring one of these names again -- the whole reason `metric` no
    * longer has a `--json` of its own.
    *
    * At `Standard` scope the experimental ones are built hidden. Hiding is a decision about the
    * usage text alone: it removes an option from every page and from none of the parsers.
    */
  private def globalOptions(cell: OptsCell, scope: HelpScope): List[OptionSpec] = {
    def global(spec: OptionSpec): OptionSpec = {
      val hidden = scope == HelpScope.Standard && spec.names().exists(isExperimental)
      spec.toBuilder.hidden(hidden).build()
    }

    List(
      global(flag(cell, "--coverage", "enables source-level coverage instrumentation for tests.")(_.copy(coverage = true))),
      global(value[String](cell, "--coverage-output", "<path>", classOf[String],
        "path to write the coverage report (JSON format). Defaults to build/coverage.json.")((c, p) => c.copy(coverageOutput = Some(p)))),
      global(value[String](cell, "--coverage-lcov-output", "<path>", classOf[String],
        "path to write the LCOV coverage report (.info format). Defaults to build/coverage.info.")((c, p) => c.copy(coverageLcovOutput = Some(p)))),
      global(value[String](cell, "--entrypoint", "<name>", classOf[String],
        "specifies the main entry point.")((c, s) => c.copy(entryPoint = Some(s)))),
      global(value[String](cell, "--github-token", "<token>", classOf[String],
        "API key to use for GitHub dependency resolution.")((c, s) => c.copy(githubToken = Some(s)))),
      // `-h` first, so it is the name the usage text leads with. It is the one option a reader
      // reaches for before having read anything, which is why it is also the only short name here:
      // a letter is worth its ambiguity for the option you type when you are lost, and for no other.
      global(OptionSpec.builder("-h", "--help").usageHelp(true).description("prints this usage information.").build()),
      // One `--json`, read by the commands whose output is either a report or a document and
      // nothing else. `metric` is not one of them: it emits five formats, so it is asked with
      // `--format`, and naming one of the five twice is what made this option two options.
      global(flag(cell, "--json", "emits machine-readable output from the benchmarking commands and options.")(_.copy(json = true))),
      global(value[Integer](cell, "--listen", "<port>", classOf[Integer],
        "starts the socket server and listens on the given port.")((c, p) => c.copy(listen = Some(p.intValue())))),
      global(flag(cell, "--no-install", "disables automatic installation of dependencies.")(_.copy(installDeps = false))),
      global(value[Integer](cell, "--threads", "<n>", classOf[Integer],
        "number of threads to use for compilation.")((c, n) => c.copy(threads = Some(n.intValue())))),
      global(flag(cell, "--top", "displays a live view of where the compiler spends its time.")(_.copy(top = true))),
      global(flag(cell, "--yes", "automatically answer yes to all prompts.")(_.copy(assumeYes = true))),
      global(OptionSpec.builder("--version").versionHelp(true).description("prints the version number.").build()),

      global(flag(cell, "--Xbenchmark-code-size", "[experimental] benchmarks the size of the generated JVM files.")(_.copy(xbenchmarkCodeSize = true))),
      global(flag(cell, "--Xbenchmark-incremental", "[experimental] benchmarks the performance of each compiler phase in incremental mode.")(_.copy(xbenchmarkIncremental = true))),
      global(flag(cell, "--Xbenchmark-phases", "[experimental] benchmarks the performance of each compiler phase.")(_.copy(xbenchmarkPhases = true))),
      global(flag(cell, "--Xbenchmark-frontend", "[experimental] benchmarks the performance of the frontend.")(_.copy(xbenchmarkFrontend = true))),
      global(flag(cell, "--Xbenchmark-throughput", "[experimental] benchmarks the performance of the entire compiler.")(_.copy(xbenchmarkThroughput = true))),
      global(values[DatalogDebug](cell, "--Xdatalog-debug", "<choices>", classOf[DatalogDebug], Some(","),
        "[experimental] traces the Datalog solver (rules, facts, ram).")((c, xs) => c.copy(xdatalogDebug = c.xdatalogDebug ++ xs))),
      global(flag(cell, "--Xdebug", "[experimental] emits full debug information so a debugger can step and inspect variables.")(_.copy(xdebug = true))),
      global(flag(cell, "--Xhelp", "[experimental] prints this usage information, with the experimental options and commands.")(_.copy(xhelp = true))),
      global(value[LibLevel](cell, "--Xlib", "<level>", classOf[LibLevel],
        "[experimental] controls the amount of std. lib. to include (nix, min, all).")((c, l) => c.copy(xlib = l))),
      global(flag(cell, "--Xno-deprecated", "[experimental] disables deprecated features.")(_.copy(xnodeprecated = true))),
      global(flag(cell, "--Xprint-phases", "[experimental] prints the ASTs after each phase.")(_.copy(xprintphases = true))),
      global(flag(cell, "--Xsummary", "[experimental] prints a summary of the compiled modules.")(_.copy(xsummary = true))),
      global(values[Subeffecting](cell, "--Xsubeffecting", "<choices>", classOf[Subeffecting], Some(","),
        "[experimental] enables sub-effecting in select places.")((c, xs) => c.copy(xsubeffecting = c.xsubeffecting ++ xs))),
      global(flag(cell, "--Xnewmono", "[experimental] uses the constraint-based monomorphization pipeline instead of the demand-driven one.")(_.copy(xnewmono = true)))
    )
  }

  /** Collects the metadata that cannot be inferred from the project directory. */
  private def promptForInitOptions(): Bootstrap.InitOptions = {
    val defaults = Bootstrap.InitOptions(
      description = Bootstrap.InitOptions.Default.description,
      author = defaultInitAuthor(readGitConfig)
    )

    if (System.console() == null) {
      return defaults
    }

    Bootstrap.InitOptions(
      description = promptWithDefault("Project description", defaults.description),
      author = promptWithDefault("Author", defaults.author),
      license = promptForLicense(Bootstrap.InitOptions.Default.license)
    )
  }

  /** Prompts for one of the supported SPDX license identifiers. */
  private def promptForLicense(default: Bootstrap.InitLicense): Bootstrap.InitLicense = {
    val console = System.console()
    while (true) {
      val answer = Option(console.readLine("License [apache2, mit, bsd3, gpl3, none] [apache2]: ")).map(_.trim)
      val license = answer.filter(_.nonEmpty) match {
        case Some(input) => parseInitLicense(input)
        case None => Some(default)
      }
      license match {
        case Some(license) => return license
        case None => console.printf("Choose none, apache2, mit, bsd3, or gpl3.%n")
      }
    }
    Bootstrap.InitLicense.NoLicense
  }

  /** Parses case-insensitive wizard license choices and their SPDX identifiers. */
  private[flix] def parseInitLicense(input: String): Option[Bootstrap.InitLicense] = input.toLowerCase match {
    case "" | "none" => Some(Bootstrap.InitLicense.NoLicense)
    case "apache-2.0" | "apache2" | "apache" => Some(Bootstrap.InitLicense.Apache2)
    case "mit" => Some(Bootstrap.InitLicense.Mit)
    case "bsd-3-clause" | "bsd3" => Some(Bootstrap.InitLicense.Bsd3)
    case "gpl-3.0-only" | "gpl3" => Some(Bootstrap.InitLicense.Gpl3)
    case _ => None
  }

  /** Returns the current directory or the single directory supplied to `flix init`. */
  private[flix] def initProjectPath(cwd: java.nio.file.Path, files: Seq[File]): Option[java.nio.file.Path] = files match {
    case Seq() => Some(cwd)
    case Seq(file) => Some(cwd.resolve(file.toPath).normalize())
    case _ => None
  }

  /** Uses a complete Git identity when available, otherwise preserves the explicit TODO. */
  private[flix] def defaultInitAuthor(readConfig: String => Option[String]): String = {
    val identity = for {
      name <- readConfig("user.name")
      email <- readConfig("user.email")
    } yield s"$name <$email>"
    identity.getOrElse(Bootstrap.InitOptions.Default.author)
  }

  /** Reads one value from Git configuration without making Git a requirement for `flix init`. */
  private def readGitConfig(key: String): Option[String] = {
    try {
      val process = new ProcessBuilder("git", "config", "--get", key).redirectErrorStream(true).start()
      val output = try scala.io.Source.fromInputStream(process.getInputStream).mkString finally process.getInputStream.close()
      if (process.waitFor() == 0) Option(output.trim).filter(_.nonEmpty) else None
    } catch {
      case _: IOException => None
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        None
    }
  }

  /** Prompts on an interactive terminal and uses `default` for blank or EOF input. */
  private def promptWithDefault(label: String, default: String): String = {
    val answer = System.console().readLine(s"$label [$default]: ")
    Option(answer).map(_.trim).filter(_.nonEmpty).getOrElse(default)
  }

  /**
    * Creates a fresh Flix instance configured with the given options and source files.
    */
  /**
    * Adds each `--lib` jar to `flix`, or exits naming the one that could not be used.
    *
    * A project's own dependencies are declared in `flix.toml` and land under `lib/cache` and
    * `lib/external`, which the package managers own. That leaves no way to compile against a jar
    * the *build* just produced -- which is the ordinary case once Java and Flix are built together,
    * since the Java classes exist only as build output. This is that seam: the caller names the
    * classpath instead of the compiler inferring it from a directory it manages.
    *
    * Failures are reported rather than thrown. `addJar` raises `IllegalArgumentException` for a
    * path that is missing, unreadable, or not a zip, and a stack trace is a poor way to say that a
    * build tool passed a path that does not exist yet.
    */
  /**
    * Writes `result` as a build-protocol document on stdout and exits.
    *
    * Nothing else may be written there: progress and prompts already go to stderr, and a single
    * stray `println` turns a parseable document into a parse error for the caller. Exit status is
    * still the primary signal -- a build tool should not have to parse anything to learn that a
    * build failed.
    */
  private def exitWithJson[T](result: Result[T, BootstrapError]): Unit = {
    val errors = result match {
      case Result.Ok(_) => Nil
      case Result.Err(error) => List(error)
    }
    Console.out.println(JsonMethods.pretty(JsonMethods.render(CliContract.result(errors, None))))
    System.exit(if (errors.isEmpty) 0 else 1)
  }

  /**
    * Type checks the project, reusing a recorded build when one answers for the current sources.
    *
    * `--clean` is decided here because only the command line knows it: it is a request to do the work
    * regardless. The `--lib` jars need no decision -- they are added to the `Flix` instance before this
    * runs and are part of the fingerprint, so a check given one is answerable from a record or not on
    * the same terms as any other.
    *
    * @param quiet suppresses the note, for `--diagnostics-json`, whose output is a document.
    */
  private def runCheck(bootstrap: Bootstrap, flix: Flix, cmdOpts: CmdOpts, quiet: Boolean): Result[Unit, BootstrapError] = {
    bootstrap.checkIfNeeded(flix, reuse = !cmdOpts.clean).map { checked =>
      if (!checked && !quiet) {
        println("Nothing to do: the sources have not changed since the last successful build.")
      }
    }
  }

  private def addLibs(flix: Flix, libs: Seq[String]): Result[Unit, BootstrapError] = {
    Result.traverse(libs) { lib =>
      try {
        flix.addJar(Paths.get(lib))
        Result.Ok(())
      } catch {
        case e: IllegalArgumentException =>
          Result.Err(BootstrapError.FileError(s"Cannot use '--lib $lib': ${e.getMessage}"))
      }
    }.map(_ => ())
  }

  private def mkFlixWithFiles(files: Seq[File], options: Options)(implicit formatter: Formatter): Flix = {
    val flix = new Flix().setFormatter(formatter)
    flix.setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    for (file <- files) {
      if (file.getName.endsWith(".flix")) {
        flix.addFile(file.toPath)
      } else {
        Console.println(s"Unrecognized file: '${file.getName}'. Only .flix files are supported.")
        System.exit(1)
      }
    }
    flix
  }

  /**
    * Prints compilation errors and exits with code 1.
    */
  private def exitWithErrors(flix: Flix, errors: List[CompilationMessage], root: Option[TypedAst.Root]): Unit = {
    println(CompilationMessage.formatAll(errors)(flix.getFormatter, root))
    System.exit(1)
  }

  /**
    * Exits with code 0 on success, or prints the error and exits with code 1 on failure.
    */
  /**
    * Exits with the code a successful command reports, or 1 if it failed.
    *
    * For `run`, whose success carries the program's own exit code. A program that failed and a compiler
    * that could not build one are different outcomes and a script has to tell them apart.
    */
  private def exitWithCode(result: Result[Int, BootstrapError], options: Options)(implicit formatter: Formatter): Unit = {
    if (options.coverage) {
      writeCoverage(options)
    }
    result match {
      case Result.Ok(code) => System.exit(code)
      case Result.Err(error) =>
        println(error.message(formatter))
        System.exit(1)
    }
  }

  private def exitOnResult[T](result: Result[T, BootstrapError], options: Options)(implicit formatter: Formatter): Unit = {
    if (options.coverage) {
      writeCoverage(options)
    }
    result match {
      case Result.Ok(_) => System.exit(0)
      case Result.Err(error) =>
        println(error.message(formatter))
        System.exit(1)
    }
  }

  /** Writes the coverage reports of this process's session. */
  private def writeCoverage(options: Options): Unit = {
    val session = Coverage.getSession
    CoverageReporter.writeJsonReport(session, options.coverageOutput)
    CoverageReporter.writeLcovReport(session, options.coverageLcovOutput)
    println(CoverageReporter.formatSummary(session))
  }

  /**
    * Exits with code 0 on success, or prints the error and exits with code 1 on failure.
    */
  private def exitOnResult[T](result: Result[T, BootstrapError])(implicit formatter: Formatter): Unit = {
    result match {
      case Result.Ok(_) => System.exit(0)
      case Result.Err(error) =>
        println(error.message(formatter))
        System.exit(1)
    }
  }

}
