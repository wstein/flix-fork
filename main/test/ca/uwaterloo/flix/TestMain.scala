/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix

import ca.uwaterloo.flix.api.BootstrapError
import ca.uwaterloo.flix.util.{Formatter, LibLevel, Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

class TestMain extends AnyFunSuite {

  test("init") {
    val args = Array("init")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Init)
  }

  test("--Xsymbol-hash-length sets the compacted-name hash width") {
    assert(Main.parseCmdOpts(Array("build", "--Xsymbol-hash-length", "8")).get.xsymbolHashLength == 8)
    assert(Main.parseCmdOpts(Array("build")).get.xsymbolHashLength == ca.uwaterloo.flix.language.phase.jvm.JvmNameTable.DefaultWidth)
  }

  test("--Xsymbol-hash-length outside its range is refused") {
    assert(Main.parseCmdOpts(Array("build", "--Xsymbol-hash-length", "0")).isEmpty)
    assert(Main.parseCmdOpts(Array("build", "--Xsymbol-hash-length", "50")).isEmpty)
  }

  test("--Xsymbol-names selects stable or counter names") {
    import ca.uwaterloo.flix.language.phase.jvm.JvmNameTable.Mode
    assert(Main.parseCmdOpts(Array("build")).get.xsymbolNames == Mode.Stable)
    assert(Main.parseCmdOpts(Array("build", "--Xsymbol-names", "counter")).get.xsymbolNames == Mode.Counter)
    assert(Main.parseCmdOpts(Array("build", "--Xsymbol-names", "other")).isEmpty)
    assert(Main.parseCmdOpts(Array("build", "--Xstable-name-length", "8")).isEmpty)
  }

  test("demangle accepts a generated class name") {
    assert(Main.parseCmdOpts(Array("demangle", "Def$map$I5Int32E")).get.command ==
      Main.Command.Demangle("Def$map$I5Int32E"))
    assert(Main.parseCmdOpts(Array("demangle")).isEmpty)
  }

  test("build") {
    val args = Array("build")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Build)
  }

  test("build-classes") {
    val args = Array("build-classes")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.BuildClasses)
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

  test("install") {
    val args = Array("install", "flix/museum-clerk")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Install(List("flix/museum-clerk")))
  }

  test("install.many") {
    val args = Array("install", "flix/museum-clerk", "flix/museum-giftshop@2.0.2")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Install(List("flix/museum-clerk", "flix/museum-giftshop@2.0.2")))
    assert(opts.files.isEmpty)
  }

  test("install.many.yes") {
    val args = Array("install", "flix/museum-clerk", "flix/museum-giftshop", "--yes")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Install(List("flix/museum-clerk", "flix/museum-giftshop")))
    assert(opts.assumeYes)
  }

  test("install.no-package") {
    val args = Array("install")
    assert(Main.parseCmdOpts(args).isEmpty)
  }

  test("remove") {
    val args = Array("remove", "flix/museum-clerk")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Remove(List("flix/museum-clerk")))
  }

  test("remove.many") {
    val args = Array("remove", "flix/museum-clerk", "flix/museum-giftshop")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Remove(List("flix/museum-clerk", "flix/museum-giftshop")))
  }

  test("remove.no-package") {
    val args = Array("remove")
    assert(Main.parseCmdOpts(args).isEmpty)
  }

  test("upgrade") {
    val args = Array("upgrade", "flix/museum-clerk@1.1.0")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Upgrade(List("flix/museum-clerk@1.1.0")))
  }

  test("upgrade.many") {
    val args = Array("upgrade", "flix/museum-clerk@1.1.0", "flix/museum-giftshop")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Upgrade(List("flix/museum-clerk@1.1.0", "flix/museum-giftshop")))
  }

  test("upgrade.no-package") {
    // A command that names no package upgrades every package the project declares.
    val args = Array("upgrade")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Upgrade(Nil))
  }

  test("outdated") {
    val args = Array("outdated")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Outdated)
  }

  test("stat") {
    val args = Array("stat")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Stat)
  }

  test("doc") {
    val args = Array("doc")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Doc)
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

  test("test --filter") {
    val args = Array("test", "--filter", "Main\\.selected", "--filter", "Other\\..*")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Test)
    assert(opts.testFilters == List("Main\\.selected", "Other\\..*"))
  }

  test("test rejects an invalid filter") {
    val args = Array("test", "--filter", "[")
    assert(Main.parseCmdOpts(args).isEmpty)
  }

  test("--filter belongs to test") {
    assert(Main.parseCmdOpts(Array("run", "--filter", "Main\\..*")).isEmpty)
  }

  test("test --events-json") {
    val opts = Main.parseCmdOpts(Array("test", "--events-json")).get
    assert(opts.command == Main.Command.Test)
    assert(opts.testEventsJson)
  }

  test("run and test accept coverage report options") {
    for (command <- List("run", "test")) {
      val opts = Main.parseCmdOpts(Array(command, "--coverage", "--coverage-output", "custom/report.json", "--coverage-lcov-output", "custom/report.info")).get
      assert(opts.coverage)
      assert(opts.coverageOutput == "custom/report.json")
      assert(opts.coverageLcovOutput == "custom/report.info")
    }
  }

  test("coverage belongs to executable commands") {
    assert(Main.parseCmdOpts(Array("build", "--coverage")).isEmpty)
  }

  test("--events-json belongs to test") {
    assert(Main.parseCmdOpts(Array("run", "--events-json")).isEmpty)
  }

  test("repl") {
    val args = Array("repl")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Repl)
    assert(!opts.pauseOnExit)
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

  test("structured check compiles the explicitly named files") {
    val source = Files.createTempFile("flix-check-json", ".flix")
    Files.writeString(source, "def main(): Unit = 42")

    Main.checkFiles(Seq(source.toFile), Options.DefaultTest, Nil)(Formatter.NoFormatter) match {
      case Result.Err(BootstrapError.CompilationErrors(errors, _)) =>
        assert(errors.nonEmpty)
        assert(errors.exists(_.loc.source.name == source.toString))
      case result => fail(s"expected errors from the explicitly named file, got: $result")
    }
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

  test("--pause-on-exit") {
    val args = Array("--pause-on-exit")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.pauseOnExit)
  }

  test("repl --pause-on-exit") {
    val args = Array("repl", "--pause-on-exit")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.command == Main.Command.Repl)
    assert(opts.pauseOnExit)
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

  test("--version") {
    val args = Array("--version")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.version)
    assert(!opts.json)
  }

  test("--version --json") {
    val args = Array("--version", "--json")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.version)
    assert(opts.json)
  }

  test("--json --version") {
    val args = Array("--json", "--version")
    val opts = Main.parseCmdOpts(args).get
    assert(opts.version)
    assert(opts.json)
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

  test("check accepts repeatable --lib paths") {
    val opts = Main.parseCmdOpts(Array("check", "--lib", "one.jar", "--lib", "two.jar")).get
    assert(opts.libs == Seq("one.jar", "two.jar"))
  }

  test("build accepts repeatable --lib paths") {
    val opts = Main.parseCmdOpts(Array("build", "--lib", "generated.jar")).get
    assert(opts.libs == Seq("generated.jar"))
  }

  test("check and build accept structured diagnostics") {
    assert(Main.parseCmdOpts(Array("check", "--diagnostics-json")).get.jsonDiagnostics)
    assert(Main.parseCmdOpts(Array("build", "--diagnostics-json")).get.jsonDiagnostics)
  }

}
