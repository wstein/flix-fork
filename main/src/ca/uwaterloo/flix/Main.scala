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

import ca.uwaterloo.flix.api.lsp.{LspServer, VSCodeLspServer, FormatterLsp as LspFormatter}
import ca.uwaterloo.flix.tools.fmt.{Canonical, PrettyPrinter}
import ca.uwaterloo.flix.api.{Bootstrap, BootstrapError, CliContract, Flix, Version}
import org.json4s.native.JsonMethods
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.shared.{Input, SecurityContext}
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.language.phase.Documentor
import ca.uwaterloo.flix.language.phase.unification.zhegalkin.ZhegalkinPerf
import ca.uwaterloo.flix.runtime.Coverage
import ca.uwaterloo.flix.runtime.shell.Shell
import ca.uwaterloo.flix.tools.*
import ca.uwaterloo.flix.tools.pkg.PackageModules
import ca.uwaterloo.flix.util.*

import java.io.{File, IOException, PrintStream}
import java.net.BindException
import java.nio.file.{Files, Paths}

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

        case Command.Check =>
          if (cmdOpts.jsonDiagnostics) {
            if (cmdOpts.files.nonEmpty) {
              exitWithJson(Result.Err(BootstrapError.FileError("The 'check' command does not support file arguments with '--diagnostics-json'.")))
            } else {
              exitWithJson {
                Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                  val flix = new Flix().setFormatter(formatter)
                  flix.setOptions(options)
                  addLibs(flix, cmdOpts.libs).flatMap(_ => bootstrap.check(flix))
                }
              }
            }
          } else if (cmdOpts.files.isEmpty) {
            exitOnResult {
              Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options)
                addLibs(flix, cmdOpts.libs).flatMap(_ => bootstrap.check(flix))
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
            addLibs(flix, cmdOpts.libs).flatMap(_ => bootstrap.build(flix))
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
              bootstrap.buildJar(flix)
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
              bootstrap.buildFatJar(flix)
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
          val format = Metrics.Format.ofString(cmdOpts.metricFormat) match {
            case Some(fmt) => fmt
            case None =>
              println(s"Unknown metric format '${cmdOpts.metricFormat}'. Expected one of: ${Metrics.Format.names}.")
              System.exit(1)
              return
          }
          // Progress goes to stderr whenever the report is meant for a program: "Resolving Flix
          // dependencies..." ahead of a JSON document makes it unparseable, and the point of these
          // formats is that something else reads them.
          val progress = format match {
            case Metrics.Format.Text | Metrics.Format.Markdown => System.out
            case Metrics.Format.Json | Metrics.Format.Csv => System.err
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
              print(Metrics.render(Metrics.compute(root), format, formatter))
              System.exit(if (errors.isEmpty) 0 else 1)
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
          exitOnResult(
            Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
              val flix = new Flix().setFormatter(formatter)
              flix.setOptions(options)
              bootstrap.run(flix, cmdOpts.args.toArray)
            },
            options
          )

        case Command.Test =>
          if (cmdOpts.files.isEmpty) {
            exitOnResult(
              Bootstrap.bootstrap(cwd, options.githubToken).flatMap { bootstrap =>
                val flix = new Flix().setFormatter(formatter)
                flix.setOptions(options.copy(progress = false))
                bootstrap.test(flix)
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
    docFormat: DocFormat = Options.Default.docFormat,
    metricFormat: String = "text",
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
    * Parse command line options.
    *
    * @param args the arguments array.
    */
  def parseCmdOpts(args: Array[String]): Option[CmdOpts] = {
    // Split at "--" separator: arguments before are for flix, arguments after are for the program
    val separatorIndex = args.indexOf("--")
    val (flixArgs, progArgs) = if (separatorIndex >= 0) {
      (args.take(separatorIndex), args.drop(separatorIndex + 1))
    } else {
      (args, Array.empty[String])
    }

    implicit val readLibLevel: scopt.Read[LibLevel] = scopt.Read.reads {
      case "nix" => LibLevel.Nix
      case "min" => LibLevel.Min
      case "all" => LibLevel.All
      case arg => throw new IllegalArgumentException(s"'$arg' is not a valid library level. Valid options are 'all', 'min', and 'nix'.")
    }

    implicit val readDatalogDebug: scopt.Read[DatalogDebug] = scopt.Read.reads {
      case "rules" => DatalogDebug.Rules
      case "facts" => DatalogDebug.Facts
      case "ram" => DatalogDebug.Ram
      case arg => throw new IllegalArgumentException(s"'$arg' is not a valid Datalog debug option. Valid options are comma-separated combinations of 'rules', 'facts', and 'ram'.")
    }

    implicit val readDocFormat: scopt.Read[DocFormat] = scopt.Read.reads {
      case "html" => DocFormat.Html
      case "md" => DocFormat.Markdown
      case "all" => DocFormat.All
      case arg => throw new IllegalArgumentException(s"'$arg' is not a valid documentation format. Valid options are 'html', 'md', and 'all'.")
    }

    implicit val readSubEffectLevel: scopt.Read[Subeffecting] = scopt.Read.reads {
      case "mod-defs" => Subeffecting.ModDefs
      case "ins-defs" => Subeffecting.InsDefs
      case "lambdas" => Subeffecting.Lambdas
      case arg => throw new IllegalArgumentException(s"'$arg' is not a valid subeffecting option. Valid options are comma-separated combinations of 'mod-defs', 'ins-defs', and 'lambdas'.")
    }

    val parser = new scopt.OptionParser[CmdOpts]("flix") {

      // Head
      head("The Flix Programming Language", Version.CurrentVersion.toString)

      // Command
      cmd("init").action((_, c) => c.copy(command = Command.Init)).text("  interactively creates a new project in an optional directory.").children(
        opt[Unit]("refresh").action((_, c) => c.copy(refresh = true)).
          text("rewrites the generated agent guide for this version of Flix. An edited guide is left alone."),
      )

      cmd("check").action((_, c) => c.copy(command = Command.Check)).text("  checks the current project for errors.").children(
        opt[String]("lib").unbounded().action((arg, c) => c.copy(libs = c.libs :+ arg)).
          text("adds a jar to the classpath. Repeatable."),
        opt[Unit]("diagnostics-json").action((_, c) => c.copy(jsonDiagnostics = true)).
          text("writes diagnostics to stdout as JSON, for a build tool to read."),
      )

      cmd("capabilities").action((_, c) => c.copy(command = Command.Capabilities)).text("  reports the tooling contract this compiler speaks.").children(
        opt[Int]("contract-version").action((arg, c) => c.copy(clientContractVersion = Some(arg))).
          text("the contract version the caller speaks. Exits non-zero if it cannot be served."),
      )

      cmd("stubs").action((_, c) => c.copy(command = Command.Stubs)).text("  writes compile-only Java stubs for the @Export-ed defs.").children(
        opt[String]("out").action((arg, c) => c.copy(stubsOut = Some(arg))).
          text("where to write the stubs. Defaults to 'build/stubs'."),
      )

      cmd("build").action((_, c) => c.copy(command = Command.Build)).text("  builds (i.e. compiles) the current project.").children(
        opt[String]("lib").unbounded().action((arg, c) => c.copy(libs = c.libs :+ arg)).
          text("adds a jar to the classpath. Repeatable."),
        opt[Unit]("diagnostics-json").action((_, c) => c.copy(jsonDiagnostics = true)).
          text("writes diagnostics to stdout as JSON, for a build tool to read."),
      )

      cmd("build-jar").action((_, c) => c.copy(command = Command.BuildJar)).text("  builds a jar-file from the current project (full, clean build).")

      cmd("build-fatjar").action((_, c) => c.copy(command = Command.BuildFatJar)).text("  builds a fatjar-file from the current project (full, clean build).")

      cmd("build-pkg").action((_, c) => c.copy(command = Command.BuildPkg)).text("  builds a fpkg-file from the current project.")

      cmd("clean").action((_, c) => c.copy(command = Command.Clean)).text("  recursively removes class files from the build directory.")

      cmd("metric")
        .action((_, c) => c.copy(command = Command.Metric))
        .text("  displays code or compiler metrics for the project.")
        .children(
          opt[String]("metric-format").action((arg, c) => c.copy(metricFormat = arg))
            .text("selects the format that 'metric' emits (text, json, csv, md). Defaults to text."),
        )

      cmd("doc").action((_, c) => c.copy(command = Command.Doc)).text("  generates API documentation.").children(
        opt[DocFormat]("doc-format").action((arg, c) => c.copy(docFormat = arg)).
          text("selects the format that 'doc' emits (html, md, all). Defaults to html."),
      )

      cmd("format").action((_, c) => c.copy(command = Command.Format)).text("  formats Flix source code files.")
        .children(
          opt[Unit]("canonical").action((_, c) => c.copy(canonical = true)).
            text("  imposes one spacing per syntax tree, instead of preserving the source's own.")
        )

      cmd("run").action((_, c) => c.copy(command = Command.Run)).text("  runs main for the current project.")

      cmd("test").action((_, c) => c.copy(command = Command.Test)).text("  runs the tests for the current project.")

      cmd("repl").action((_, c) => c.copy(command = Command.Repl)).text("  starts a repl for the current project, or provided Flix source files.")

      cmd("lsp").text("  starts the Plain-LSP server.")
        .action((_, c) => c.copy(command = Command.PlainLsp))

      cmd("lsp-vscode").text("  starts the VSCode-LSP server and listens on the given port.")
        .children(
          arg[Int]("port").action((port, c) => c.copy(command = Command.VSCodeLsp(port)))
            .required()
            .text("the port number to listen on.")
        )

      cmd("release").text("  releases a new version to GitHub.")
        .action((_, c) => c.copy(command = Command.Release))

      cmd("outdated").text("  shows dependencies which have newer versions available.")
        .action((_, c) => c.copy(command = Command.Outdated))

      cmd("eff-check").text("  checks that dependencies respect the 'effects.lock' file.")
        .action((_, c) => c.copy(command = Command.EffCheck))

      cmd("eff-lock").text("  locks the current effect signatures.")
        .action((_, c) => c.copy(command = Command.EffLock))

      cmd("Xperf").action((_, c) => c.copy(command = Command.CompilerPerf)).children(
        opt[Unit]("frontend")
          .action((_, c) => c.copy(XPerfFrontend = true))
          .text("benchmark only frontend"),
        opt[Unit]("par")
          .action((_, c) => c.copy(XPerfPar = true))
          .text("benchmark only parallel evaluation"),
        opt[Int]("n")
          .action((v, c) => c.copy(XPerfN = Some(v)))
          .text("number of compilations")
      ).hidden()

      cmd("Xmemory").action((_, c) => c.copy(command = Command.CompilerMemory)).hidden()

      cmd("Xzhegalkin").action((_, c) => c.copy(command = Command.Zhegalkin)).children(
        opt[Int]("n")
          .action((v, c) => c.copy(XPerfN = Some(v)))
          .text("number of compilations")
      ).hidden()

      note("")

      opt[Unit]("coverage").action((_, c) => c.copy(coverage = true)).
        text("enables source-level coverage instrumentation for tests.")

      opt[String]("coverage-output").action((p, c) => c.copy(coverageOutput = Some(p))).
        valueName("<path>").
        text("path to write the coverage report (JSON format). Defaults to build/coverage.json.")

      opt[String]("coverage-lcov-output").action((p, c) => c.copy(coverageLcovOutput = Some(p))).
        valueName("<path>").
        text("path to write the LCOV coverage report (.info format). Defaults to build/coverage.info.")

      opt[String]("entrypoint").action((s, c) => c.copy(entryPoint = Some(s))).
        text("specifies the main entry point.")

      opt[String]("github-token").action((s, c) => c.copy(githubToken = Some(s))).
        text("API key to use for GitHub dependency resolution.")

      help("help").text("prints this usage information.")

      opt[Unit]("json").action((_, c) => c.copy(json = true)).
        text("enables json output.")

      opt[Int]("listen").action((s, c) => c.copy(listen = Some(s))).
        valueName("<port>").
        text("starts the socket server and listens on the given port.")

      opt[Unit]("no-install").action((_, c) => c.copy(installDeps = false)).
        text("disables automatic installation of dependencies.")

      opt[Int]("threads").action((n, c) => c.copy(threads = Some(n))).
        text("number of threads to use for compilation.")

      opt[Unit]("top").action((_, c) => c.copy(top = true)).
        text("displays a live view of where the compiler spends its time.")

      opt[Unit]("yes").action((_, c) => c.copy(assumeYes = true)).
        text("automatically answer yes to all prompts.")

      version("version").text("prints the version number.")

      // Experimental options:
      note("")
      note("The following options are experimental:")

      // Xbenchmark-code-size
      opt[Unit]("Xbenchmark-code-size").action((_, c) => c.copy(xbenchmarkCodeSize = true)).
        text("[experimental] benchmarks the size of the generated JVM files.")

      // Xbenchmark-incremental
      opt[Unit]("Xbenchmark-incremental").action((_, c) => c.copy(xbenchmarkIncremental = true)).
        text("[experimental] benchmarks the performance of each compiler phase in incremental mode.")

      // Xbenchmark-phases
      opt[Unit]("Xbenchmark-phases").action((_, c) => c.copy(xbenchmarkPhases = true)).
        text("[experimental] benchmarks the performance of each compiler phase.")

      // Xbenchmark-frontend
      opt[Unit]("Xbenchmark-frontend").action((_, c) => c.copy(xbenchmarkFrontend = true)).
        text("[experimental] benchmarks the performance of the frontend.")

      // Xbenchmark-throughput
      opt[Unit]("Xbenchmark-throughput").action((_, c) => c.copy(xbenchmarkThroughput = true)).
        text("[experimental] benchmarks the performance of the entire compiler.")

      // Xdatalog-debug
      opt[Seq[DatalogDebug]]("Xdatalog-debug").action((choices, c) => c.copy(xdatalogDebug = choices.toSet)).
        text("[experimental] traces the Datalog solver (rules, facts, ram).")

      // Xdebug
      opt[Unit]("Xdebug").action((_, c) => c.copy(xdebug = true)).
        text("[experimental] emits full debug information so a debugger can step and inspect variables.")

      // Xlib
      opt[LibLevel]("Xlib").action((arg, c) => c.copy(xlib = arg)).
        text("[experimental] controls the amount of std. lib. to include (nix, min, all).")

      // Xno-deprecated
      opt[Unit]("Xno-deprecated").action((_, c) => c.copy(xnodeprecated = true)).
        text("[experimental] disables deprecated features.")

      // Xprint-phase
      opt[Unit]("Xprint-phases").action((_, c) => c.copy(xprintphases = true)).
        text("[experimental] prints the ASTs after the each phase.")

      // Xsummary
      opt[Unit]("Xsummary").action((_, c) => c.copy(xsummary = true)).
        text("[experimental] prints a summary of the compiled modules.")

      // Xsubeffecting
      opt[Seq[Subeffecting]]("Xsubeffecting").action((subeffectings, c) => c.copy(xsubeffecting = subeffectings.toSet)).
        text("[experimental] enables sub-effecting in select places")

      // Xnewmono
      opt[Unit]("Xnewmono").action((_, c) => c.copy(xnewmono = true)).
        text("[experimental] uses the constraint-based monomorphization pipeline instead of the demand-driven one.")

      note("")

      // Input files.
      arg[File]("<file>...").action((x, c) => c.copy(files = c.files :+ x))
        .optional()
        .unbounded()
        .text("input Flix source code files, Flix packages, and Java archives.")

    }

    parser.parse(flixArgs, CmdOpts()).map(_.copy(args = progArgs.toList))
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
  private def exitOnResult[T](result: Result[T, BootstrapError], options: Options)(implicit formatter: Formatter): Unit = {
    if (options.coverage) {
      val session = Coverage.getSession
      CoverageReporter.writeJsonReport(session, options.coverageOutput)
      CoverageReporter.writeLcovReport(session, options.coverageLcovOutput)
      println(CoverageReporter.formatSummary(session))
    }
    result match {
      case Result.Ok(_) => System.exit(0)
      case Result.Err(error) =>
        println(error.message(formatter))
        System.exit(1)
    }
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
