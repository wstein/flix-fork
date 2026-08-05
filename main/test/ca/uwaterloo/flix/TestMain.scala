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

import ca.uwaterloo.flix.api.Bootstrap
import ca.uwaterloo.flix.tools.pkg.ManifestParser
import ca.uwaterloo.flix.util.{DocFormat, LibLevel}
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.nio.file.{Files, Path}

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
}
