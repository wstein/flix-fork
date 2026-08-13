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

package ca.uwaterloo.flix

import ca.uwaterloo.flix.api.{Bootstrap, Version}
import ca.uwaterloo.flix.tools.pkg.ManifestParser
import ca.uwaterloo.flix.util.{DatalogDebug, DocFormat, LibLevel, Subeffecting}
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class TestMain extends AnyFunSuite {

  test("init") {
    val args = Array("init")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Init)
  }

  test("init accepts one target directory") {
    val cwd = Path.of("/tmp/flix-init-test")

    assert(Main.initProjectPath(cwd, Seq.empty).contains(cwd))
    assert(Main.initProjectPath(cwd, Seq(new File("example"))).contains(cwd.resolve("example")))
    assert(Main.initProjectPath(cwd, Seq(new File("one"), new File("two"))).isEmpty)
  }

  test("init --refresh") {
    val args = Array("init", "--refresh")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Init)
    assert(opts.refresh)
  }

  test("init without --refresh") {
    // Refreshing overwrites a file. Plain init writes only what is absent, and stays that way.
    val args = Array("init")
    val opts = Main.parseCmdOpts(args).get
    assert(!opts.refresh)
  }

  test("init author uses a complete Git identity") {
    val completeIdentity = Map(
      "user.name" -> "Ada Lovelace",
      "user.email" -> "ada@example.com"
    )

    assert(Main.defaultInitAuthor(completeIdentity.get) == "Ada Lovelace <ada@example.com>")
    assert(Main.defaultInitAuthor(_ => None) == "TODO")
    assert(Main.defaultInitAuthor(key => completeIdentity.get(key).filter(_ => key == "user.name")) == "TODO")
  }

  test("init accepts supported license choices") {
    assert(Bootstrap.InitOptions.Default.license == Bootstrap.InitLicense.Apache2)
    assert(Main.parseInitLicense("apache2").contains(Bootstrap.InitLicense.Apache2))
    assert(Main.parseInitLicense("MIT").contains(Bootstrap.InitLicense.Mit))
    assert(Main.parseInitLicense("bsd3").contains(Bootstrap.InitLicense.Bsd3))
    assert(Main.parseInitLicense("gpl3").contains(Bootstrap.InitLicense.Gpl3))
    assert(Main.parseInitLicense("none").contains(Bootstrap.InitLicense.NoLicense))
    assert(Main.parseInitLicense("ISC").isEmpty)
  }

  test("init writes the selected license and description") {
    val project = Files.createTempDirectory("flix-init-test")
    val options = Bootstrap.InitOptions(
      description = "A package with a useful description.",
      author = "Ada Lovelace <ada@example.com>",
      license = Bootstrap.InitLicense.Mit
    )

    Bootstrap.init(project, options)(System.out).unsafeGet

    val manifest = ManifestParser.parse(project.resolve("flix.toml")).unsafeGet
    assert(manifest.license.contains("MIT"))
    assert(Files.readString(project.resolve("README.md")).contains(options.description))
    assert(Files.readString(project.resolve("LICENSE.md")).contains("# MIT"))
  }

  test("build") {
    val args = Array("build")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Build)
  }

  test("build-jar") {
    val args = Array("build-jar")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.BuildJar)
  }

  test("build-pkg") {
    val args = Array("build-pkg")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.BuildPkg)
  }

  test("release") {
    val args = Array("release")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Release)
  }

  test("outdated") {
    val args = Array("outdated")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Outdated)
  }

  test("doc") {
    val args = Array("doc")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Doc)
  }

  test("doc --doc-format html") {
    val args = Array("doc", "--doc-format", "html")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Doc)
    assert(opts.docFormat == DocFormat.Html)
  }

  test("doc --doc-format md") {
    val args = Array("doc", "--doc-format", "md")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Doc)
    assert(opts.docFormat == DocFormat.Markdown)
  }

  test("doc --doc-format all") {
    val args = Array("doc", "--doc-format", "all")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.docFormat == DocFormat.All)
  }

  test("doc without --doc-format") {
    // HTML has always been what 'doc' emits, and stays so unless asked otherwise.
    val args = Array("doc")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.docFormat == DocFormat.Html)
  }

  test("doc --doc-format with an unknown format") {
    val args = Array("doc", "--doc-format", "xml")
    assert(Main.parseCmdOpts(args).isEmpty)
  }

  test("format") {
    val args = Array("format")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Format)
  }

  test("run") {
    val args = Array("run")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Run)
  }

  test("test") {
    val args = Array("test")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Test)
  }

  test("repl") {
    val args = Array("repl")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Repl)
  }

  test("check") {
    val args = Array("check")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Check)
  }

  test("check with files") {
    val args = Array("check", "foo.flix", "bar.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Check)
    assert(opts.files.length == 2)
  }

  test("test with files") {
    val args = Array("test", "foo.flix", "bar.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Test)
    assert(opts.files.length == 2)
  }

  test("doc with files") {
    val args = Array("doc", "foo.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Doc)
    assert(opts.files.length == 1)
  }

  test("run -- arg1 arg2") {
    val args = Array("run", "--", "arg1", "arg2")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Run)
    assert(opts.args == Seq("arg1", "arg2"))
  }

  test("--json") {
    val args = Array("--json")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.json)
  }

  test("--no-install") {
    val args = Array("--no-install")
    val opts = Main.parseCmdOpts(args).get
    assert(!opts.installDeps)
  }

  test("--listen") {
    val args = Array("--listen", "8080", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.listen.nonEmpty)
  }

  test("--threads") {
    val args = Array("--threads", "42", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.threads.contains(42))
  }

  test("--yes") {
    val args = Array("--yes")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.assumeYes)
  }

  test("--Xbenchmark-code-size") {
    val args = Array("--Xbenchmark-code-size", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xbenchmarkCodeSize)
  }

  test("--Xbenchmark-phases") {
    val args = Array("--Xbenchmark-phases", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xbenchmarkPhases)
  }

  test("--Xbenchmark-frontend") {
    val args = Array("--Xbenchmark-frontend", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xbenchmarkFrontend)
  }

  test("--Xbenchmark-throughput") {
    val args = Array("--Xbenchmark-throughput", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xbenchmarkThroughput)
  }

  test("--Xlib nix") {
    val args = Array("--Xlib", "nix", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xlib == LibLevel.Nix)
  }

  test("--Xlib min") {
    val args = Array("--Xlib", "min", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xlib == LibLevel.Min)
  }

  test("--Xlib all") {
    val args = Array("--Xlib", "all", "p.flix")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xlib == LibLevel.All)
  }

  test("--Xno-deprecated") {
    val args = Array("--Xno-deprecated")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xnodeprecated)
  }

  test("--Xsummary") {
    val args = Array("--Xsummary")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.xsummary)
  }

  test("doc --doc-format is an option of doc, not a global flag") {
    // `--doc-format` shapes what `doc` emits and means nothing to any other command. Declaring it
    // globally would have it parse anywhere and silently do nothing, which is how an option comes
    // to exist without being wired to anything.
    assert(Main.parseCmdOpts(Array("doc", "--doc-format", "md")).isDefined)
    assert(Main.parseCmdOpts(Array("build", "--doc-format", "md")).isEmpty)
    assert(Main.parseCmdOpts(Array("--doc-format", "md")).isEmpty)
    assert(Main.parseCmdOpts(Array("--doc-format", "md", "doc")).isEmpty)
  }

  test("init --refresh is an option of init, not a global flag") {
    // `--refresh` rewrites the generated agent guide and means nothing to any other command.
    // Declared globally it would parse anywhere and silently do nothing.
    assert(Main.parseCmdOpts(Array("init", "--refresh")).isDefined)
    assert(Main.parseCmdOpts(Array("build", "--refresh")).isEmpty)
    assert(Main.parseCmdOpts(Array("--refresh")).isEmpty)
    assert(Main.parseCmdOpts(Array("--refresh", "init")).isEmpty)
  }

  test("an unrecognised doc option is rejected, not ignored") {
    // The `--datalog` diagram option was removed because nothing consumed it. These assertions are
    // what make the rest of this suite mean anything: if the parser accepted unknown flags, a test
    // that an option parses would not distinguish a wired option from a typo.
    assert(Main.parseCmdOpts(Array("doc", "--datalog")).isEmpty)
    assert(Main.parseCmdOpts(Array("doc", "--extended")).isEmpty)
    assert(Main.parseCmdOpts(Array("doc", "--doc-format")).isEmpty)
  }

  //
  // Every remaining command and option, pinned.
  //
  // What follows is a characterisation of the whole command line rather than a sample of it. The
  // tests above grew one at a time beside the options they were written for, so they cover half the
  // commands and half the flags -- enough to catch a broken option, not enough to survive replacing
  // the parser underneath them. A command line is a published interface: an option that silently
  // stops binding is indistinguishable, to the caller, from one that was never there.
  //

  test("capabilities") {
    val opts = Main.parseCmdOpts(Array("capabilities")).get
    assert(opts.command == Main.Command.Capabilities)
    assert(opts.clientContractVersion.isEmpty)
  }

  test("capabilities --contract-version") {
    val opts = Main.parseCmdOpts(Array("capabilities", "--contract-version", "3")).get
    assert(opts.command == Main.Command.Capabilities)
    assert(opts.clientContractVersion.contains(3))
  }

  test("stubs") {
    val opts = Main.parseCmdOpts(Array("stubs")).get
    assert(opts.command == Main.Command.Stubs)
    assert(opts.stubsOut.isEmpty)
  }

  test("stubs --out") {
    val opts = Main.parseCmdOpts(Array("stubs", "--out", "build/java")).get
    assert(opts.command == Main.Command.Stubs)
    assert(opts.stubsOut.contains("build/java"))
  }

  test("build-fatjar") {
    assert(Main.parseCmdOpts(Array("build-fatjar")).get.command == Main.Command.BuildFatJar)
  }

  test("clean") {
    assert(Main.parseCmdOpts(Array("clean")).get.command == Main.Command.Clean)
  }

  test("lsp") {
    assert(Main.parseCmdOpts(Array("lsp")).get.command == Main.Command.PlainLsp)
  }

  test("lsp-vscode carries its port in the command") {
    // The port is not a field of the options: it is an argument of the command, so a VSCode server
    // cannot be started without one.
    assert(Main.parseCmdOpts(Array("lsp-vscode", "8080")).get.command == Main.Command.VSCodeLsp(8080))
    assert(Main.parseCmdOpts(Array("lsp-vscode")).isEmpty)
    assert(Main.parseCmdOpts(Array("lsp-vscode", "http")).isEmpty)
  }

  test("eff-check") {
    assert(Main.parseCmdOpts(Array("eff-check")).get.command == Main.Command.EffCheck)
  }

  test("eff-lock") {
    assert(Main.parseCmdOpts(Array("eff-lock")).get.command == Main.Command.EffLock)
  }

  test("Xperf") {
    val opts = Main.parseCmdOpts(Array("Xperf", "--frontend", "--par", "--n", "50")).get
    assert(opts.command == Main.Command.CompilerPerf)
    assert(opts.XPerfFrontend)
    assert(opts.XPerfPar)
    assert(opts.XPerfN.contains(50))
  }

  test("Xperf without options") {
    val opts = Main.parseCmdOpts(Array("Xperf")).get
    assert(opts.command == Main.Command.CompilerPerf)
    assert(!opts.XPerfFrontend)
    assert(!opts.XPerfPar)
    assert(opts.XPerfN.isEmpty)
  }

  test("Xmemory") {
    assert(Main.parseCmdOpts(Array("Xmemory")).get.command == Main.Command.CompilerMemory)
  }

  test("Xzhegalkin") {
    val opts = Main.parseCmdOpts(Array("Xzhegalkin", "--n", "7")).get
    assert(opts.command == Main.Command.Zhegalkin)
    assert(opts.XPerfN.contains(7))
  }

  test("a hidden command is still a command") {
    // `Xperf`, `Xmemory` and `Xzhegalkin` are `.hidden()`, which keeps them out of the usage text.
    // It does not keep them out of the parser, and CLAUDE.md tells a contributor to run `Xperf`.
    assert(Main.parseCmdOpts(Array("Xperf")).isDefined)
    assert(Main.parseCmdOpts(Array("Xmemory")).isDefined)
    assert(Main.parseCmdOpts(Array("Xzhegalkin")).isDefined)
  }

  test("check --sarif") {
    val opts = Main.parseCmdOpts(Array("check", "--sarif", "flix.sarif")).get
    assert(opts.command == Main.Command.Check)
    assert(opts.sarifPath.contains("flix.sarif"))
  }

  test("check --diagnostics-json") {
    val opts = Main.parseCmdOpts(Array("check", "--diagnostics-json")).get
    assert(opts.command == Main.Command.Check)
    assert(opts.jsonDiagnostics)
  }

  test("build --diagnostics-json") {
    val opts = Main.parseCmdOpts(Array("build", "--diagnostics-json")).get
    assert(opts.command == Main.Command.Build)
    assert(opts.jsonDiagnostics)
  }

  test("--lib is repeatable, and keeps the order given") {
    // A classpath is ordered, so collecting the jars into a set -- or keeping only the last -- would
    // change which class wins without changing any message.
    val opts = Main.parseCmdOpts(Array("build", "--lib", "a.jar", "--lib", "b.jar")).get
    assert(opts.libs == Seq("a.jar", "b.jar"))
    assert(Main.parseCmdOpts(Array("check", "--lib", "a.jar")).get.libs == Seq("a.jar"))
    assert(Main.parseCmdOpts(Array("build")).get.libs.isEmpty)
  }

  test("format --canonical") {
    val opts = Main.parseCmdOpts(Array("format", "--canonical")).get
    assert(opts.command == Main.Command.Format)
    assert(opts.canonical)
    assert(!Main.parseCmdOpts(Array("format")).get.canonical)
  }

  test("metric") {
    val opts = Main.parseCmdOpts(Array("metric")).get
    assert(opts.command == Main.Command.Metric)
    // Absent, not "text": what an unasked-for format resolves to is the command's decision, and
    // the parser recording a default here is what let `--json` be answered twice.
    assert(opts.metricFormat.isEmpty)
  }

  test("metric --format") {
    assert(Main.parseCmdOpts(Array("metric", "--format", "json")).get.metricFormat.contains("json"))
    assert(Main.parseCmdOpts(Array("metric", "--format", "csv")).get.metricFormat.contains("csv"))
    assert(Main.parseCmdOpts(Array("metric", "--format", "md")).get.metricFormat.contains("md"))
    assert(Main.parseCmdOpts(Array("metric", "--format", "sarif")).get.metricFormat.contains("sarif"))
  }

  test("metric --format is checked when it runs, not when it parses") {
    // Unlike `--doc-format`, which is a typed option and fails here, `--format` is a string the
    // command validates itself. The reading is the same either way -- an unknown format is refused
    // and named -- but the two are inconsistent, and a parser that could type both should.
    assert(Main.parseCmdOpts(Array("metric", "--format", "xml")).get.metricFormat.contains("xml"))
  }

  test("`--json` is one option, and means the same on either side of the command") {
    // It used to be two options with one name -- a global setting `json`, and a child of `metric`
    // selecting the report format -- which scopt resolved by position, so each spelling did half of
    // what it reads as. There is now one, it is global, and `metric` reads it as the format to emit
    // when no `--format` was given.
    for (line <- List(Array("metric", "--json"), Array("--json", "metric"))) {
      val opts = Main.parseCmdOpts(line).get
      assert(opts.command == Main.Command.Metric)
      assert(opts.json)
      assert(opts.metricFormat.isEmpty)
    }

    assert(Main.parseCmdOpts(Array("build", "--json")).get.json)
  }

  test("an explicit --format wins over --json, in either order") {
    // Not "the last one wins". `--format` names a format and `--json` asks for something a program
    // can read; the first is the more specific answer to the same question, so order does not come
    // into it. Under scopt these two lines disagreed.
    assert(Main.parseCmdOpts(Array("metric", "--json", "--format", "csv")).get.metricFormat.contains("csv"))
    assert(Main.parseCmdOpts(Array("metric", "--format", "csv", "--json")).get.metricFormat.contains("csv"))
  }

  test("a repeated option takes the last value given") {
    assert(Main.parseCmdOpts(Array("metric", "--format", "csv", "--format", "md")).get.metricFormat.contains("md"))
    assert(Main.parseCmdOpts(Array("--threads", "2", "--threads", "8", "p.flix")).get.threads.contains(8))
  }

  test("metric thresholds") {
    val args = Array(
      "metric",
      "--max-lines", "40",
      "--max-params", "5",
      "--max-nesting", "4",
      "--max-complexity", "15",
      "--max-line-tokens", "25",
      "--max-line-length", "100",
      "--min-doc-coverage", "0.8"
    )
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Metric)
    assert(opts.metricMaxLines.contains(40))
    assert(opts.metricMaxParams.contains(5))
    assert(opts.metricMaxNesting.contains(4))
    assert(opts.metricMaxComplexity.contains(15))
    assert(opts.metricMaxLineTokens.contains(25))
    assert(opts.metricMaxLineLength.contains(100))
    assert(opts.metricMinDocCoverage.contains(0.8))
  }

  test("metric thresholds are absent unless given") {
    // An absent threshold is not a threshold of zero, and not the default either: the command
    // distinguishes "no limit asked for" from "this limit", which is what lets one flag fail a
    // build without the other six also failing it.
    val opts = Main.parseCmdOpts(Array("metric")).get
    assert(opts.metricMaxLines.isEmpty)
    assert(opts.metricMaxParams.isEmpty)
    assert(opts.metricMaxNesting.isEmpty)
    assert(opts.metricMaxComplexity.isEmpty)
    assert(opts.metricMaxLineTokens.isEmpty)
    assert(opts.metricMaxLineLength.isEmpty)
    assert(opts.metricMinDocCoverage.isEmpty)
  }

  test("--coverage and where it writes") {
    val args = Array("test", "--coverage", "--coverage-output", "cov.json", "--coverage-lcov-output", "cov.info")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.coverage)
    assert(opts.coverageOutput.contains("cov.json"))
    assert(opts.coverageLcovOutput.contains("cov.info"))
  }

  test("--entrypoint") {
    assert(Main.parseCmdOpts(Array("run", "--entrypoint", "Main.main")).get.entryPoint.contains("Main.main"))
  }

  test("--github-token") {
    assert(Main.parseCmdOpts(Array("build", "--github-token", "gh_secret")).get.githubToken.contains("gh_secret"))
  }

  test("--top") {
    assert(Main.parseCmdOpts(Array("build", "--top")).get.top)
    assert(!Main.parseCmdOpts(Array("build")).get.top)
  }

  test("--Xbenchmark-incremental") {
    assert(Main.parseCmdOpts(Array("--Xbenchmark-incremental", "p.flix")).get.xbenchmarkIncremental)
  }

  test("--Xdebug") {
    assert(Main.parseCmdOpts(Array("--Xdebug", "p.flix")).get.xdebug)
  }

  test("--Xprint-phases") {
    assert(Main.parseCmdOpts(Array("--Xprint-phases", "p.flix")).get.xprintphases)
  }

  test("--Xnewmono") {
    assert(Main.parseCmdOpts(Array("--Xnewmono", "p.flix")).get.xnewmono)
  }

  test("--Xdatalog-debug takes a comma-separated list") {
    val opts = Main.parseCmdOpts(Array("--Xdatalog-debug", "rules,facts", "p.flix")).get
    assert(opts.xdatalogDebug == Set(DatalogDebug.Rules, DatalogDebug.Facts))
    assert(Main.parseCmdOpts(Array("--Xdatalog-debug", "ram", "p.flix")).get.xdatalogDebug == Set(DatalogDebug.Ram))
    assert(Main.parseCmdOpts(Array("--Xdatalog-debug", "traces", "p.flix")).isEmpty)
    assert(Main.parseCmdOpts(Array("p.flix")).get.xdatalogDebug.isEmpty)
  }

  test("--Xsubeffecting takes a comma-separated list") {
    val opts = Main.parseCmdOpts(Array("--Xsubeffecting", "mod-defs,lambdas", "p.flix")).get
    assert(opts.xsubeffecting == Set(Subeffecting.ModDefs, Subeffecting.Lambdas))
    assert(Main.parseCmdOpts(Array("--Xsubeffecting", "ins-defs", "p.flix")).get.xsubeffecting == Set(Subeffecting.InsDefs))
    assert(Main.parseCmdOpts(Array("--Xsubeffecting", "everywhere", "p.flix")).isEmpty)
    assert(Main.parseCmdOpts(Array("p.flix")).get.xsubeffecting.isEmpty)
  }

  test("no command at all") {
    // `flix p.flix` compiles and runs the named files, so an absent command is a command.
    val opts = Main.parseCmdOpts(Array("p.flix")).get
    assert(opts.command == Main.Command.None)
    assert(opts.files.length == 1)
  }

  test("a misspelled command is read as an input file") {
    // Not a decision -- a consequence. The root takes an unbounded, optional `<file>...`, so a word
    // that names no command is a filename, and `flix metrics` reports that `metrics` cannot be
    // opened rather than that the command does not exist. Pinned because it is exactly the beginner
    // failure this CLI should handle well, and because a parser that knows its own command names
    // can say "did you mean 'metric'?" instead.
    val opts = Main.parseCmdOpts(Array("metrics")).get
    assert(opts.command == Main.Command.None)
    assert(opts.files.map(_.getName) == Seq("metrics"))
  }

  test("a global option is accepted after a command") {
    // Every command takes the global options, and takes them in either position. A migration that
    // scoped them under each command would break `flix build --threads 4` without breaking
    // `flix --threads 4 build`, which is the harder half to notice.
    assert(Main.parseCmdOpts(Array("build", "--threads", "4")).get.threads.contains(4))
    assert(Main.parseCmdOpts(Array("--threads", "4", "build")).get.threads.contains(4))
    assert(Main.parseCmdOpts(Array("metric", "--yes")).get.assumeYes)
  }

  test("`--` forwards the rest to the program, and is split before the command is known") {
    // Only `run` reads `args`: it is what reaches `main`. The split is nonetheless applied to every
    // command, so `flix check -- x` parses and discards `x` rather than refusing it. That is
    // incidental rather than intended, and this pins it so that narrowing it to `run` is a visible
    // decision instead of a silent one.
    assert(Main.parseCmdOpts(Array("run", "--", "arg1", "arg2")).get.args == List("arg1", "arg2"))
    assert(Main.parseCmdOpts(Array("check", "--", "x")).get.args == List("x"))
    assert(Main.parseCmdOpts(Array("run")).get.args.isEmpty)
  }

  test("`--` hides a following flag from the parser") {
    // The words after `--` are the program's, including ones that name a Flix option. Were they
    // parsed here, a program could not be passed `--json` without the compiler taking it.
    val opts = Main.parseCmdOpts(Array("run", "--", "--json")).get
    assert(opts.args == List("--json"))
    assert(!opts.json)
  }

  test("--version prints something containing the version") {
    // `.github/workflows/release-jar.yaml` runs the built jar and refuses to publish it unless
    // `--version` prints text containing the tag. A version option that prints nothing therefore
    // fails nowhere until the release, which is the one run with no cheap way back. Asserted on the
    // spec rather than by invoking it, since printing the version also exits the process.
    val version = Main.rootSpec(new Main.OptsCell).version()
    assert(version.length == 1)
    assert(version.head.contains(Version.CurrentVersion.toString))
  }

  test("every command in the usage text is one the parser accepts") {
    // The two are now the same list -- a command is a subcommand spec, and the help is printed from
    // it -- so this cannot drift the way a hand-written usage string does. It can still be wrong in
    // the direction that matters: a command nobody can run.
    val commands = Main.rootSpec(new Main.OptsCell).subcommands().keySet().asScala
    assert(commands.nonEmpty)
    for (name <- commands) {
      assert(Main.parseCmdOpts(Array(name)).isDefined || name == "lsp-vscode", s"'$name' is listed but does not parse")
    }
  }

  test("input files are collected in the order given") {
    val opts = Main.parseCmdOpts(Array("check", "a.flix", "b.flix", "c.flix")).get
    assert(opts.files.map(_.getName) == Seq("a.flix", "b.flix", "c.flix"))
  }
}
