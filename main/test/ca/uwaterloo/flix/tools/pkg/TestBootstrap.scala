package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.api.{Bootstrap, BootstrapError, BuildManifest, Flix, TestManifest, Version}
import ca.uwaterloo.flix.util.{Build, FileOps, Formatter, LibLevel, Options, Result}
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.EnumerationHasAsScala
import scala.util.Using

@DoNotDiscover
class TestBootstrap extends AnyFunSuite {

  private val ProjectPrefix: String = "flix-project-"

  test("init") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
  }

  test("init creates a missing project directory") {
    val p = Files.createTempDirectory(ProjectPrefix).resolve("new-project")
    Bootstrap.init(p)(System.out).unsafeGet

    assert(Files.isDirectory(p), "init did not create the project directory")
    assert(Files.exists(p.resolve("flix.toml")), "init did not create the manifest")
  }

  test("init writes the supplied package metadata") {
    val p = Files.createTempDirectory(ProjectPrefix)
    val options = Bootstrap.InitOptions(
      description = "An \"experimental\" package\\with a newline\n",
      author = "Ada Lovelace <ada@example.com>"
    )

    Bootstrap.init(p, options)(System.out).unsafeGet

    val manifest = ManifestParser.parse(p.resolve("flix.toml")).unsafeGet
    assert(manifest.description == options.description)
    assert(manifest.authors == List(options.author))
    assert(Files.readString(p.resolve("README.md")).contains(options.description))
  }

  test("init uses explicit TODO metadata by default") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val manifest = ManifestParser.parse(p.resolve("flix.toml")).unsafeGet
    assert(manifest.description == Bootstrap.InitOptions.Default.description)
    assert(manifest.authors == List(Bootstrap.InitOptions.Default.author))

    // The description and the author default to an explicit `TODO` because no answer can be
    // inferred, but the license defaults to one that is *chosen* - a new project gets Apache-2.0
    // rather than no license at all. Asserted against the default rather than restating it, so
    // that changing the default is one edit.
    assert(manifest.license == Bootstrap.InitOptions.Default.license.spdxId)
    assert(manifest.license.nonEmpty, "the default license is a license, not the absence of one")
  }

  test("init writes selected license metadata") {
    val p = Files.createTempDirectory(ProjectPrefix)
    val options = Bootstrap.InitOptions(
      description = "A licensed project",
      author = "Ada Lovelace <ada@example.com>",
      license = Bootstrap.InitLicense.Mit
    )

    Bootstrap.init(p, options)(System.out).unsafeGet

    val manifest = ManifestParser.parse(p.resolve("flix.toml")).unsafeGet
    assert(manifest.license.contains("MIT"))
    assert(Files.readString(p.resolve("LICENSE.md")).contains("# MIT"))
  }

  test("init writes an .editorconfig that agrees with Flix layout") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val editorConfig = p.resolve(".editorconfig")
    assert(Files.exists(editorConfig), "init did not write an .editorconfig")

    val content = Files.readString(editorConfig)

    // `indent_size` is load-bearing: `tab_width` alone leaves the width of a space indent
    // to each editor's own default, which is the divergence this file exists to prevent.
    assert(content.contains("indent_size = 4"), s"missing indent size:\n$content")
    assert(content.contains("indent_style = space"), s"missing indent style:\n$content")
    assert(content.contains("[*.flix]"), s"missing section for Flix sources:\n$content")

    // A hard wrap is a reformat applied by an editor that cannot see the syntax tree.
    assert(content.contains("max_line_length = off"), s"line length is not disabled:\n$content")

    // Two trailing spaces are a hard line break in Markdown.
    assert(
      content.contains("[*.md]") && content.contains("trim_trailing_whitespace = false"),
      s"Markdown is not exempt from whitespace trimming:\n$content")
  }

  test("init writes agent instruction files") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val guide = p.resolve("AGENTS.md")
    val claudeMd = p.resolve("CLAUDE.md")
    val copilotInstructions = p.resolve(".github/copilot-instructions.md")
    assert(Files.exists(guide), "init did not write an AGENTS.md")
    assert(Files.exists(claudeMd), "init did not write a CLAUDE.md")
    assert(Files.exists(copilotInstructions), "init did not write Copilot instructions")

    // Claude Code reads CLAUDE.md and not AGENTS.md. Without the import the guide is never
    // loaded, and nothing reports it: the feature just quietly does nothing.
    assert(Files.readString(claudeMd).contains("@AGENTS.md"), "CLAUDE.md does not import AGENTS.md")
    assert(Files.readString(copilotInstructions).contains("AGENTS.md"), "Copilot instructions do not point to AGENTS.md")

    // The guide states which compiler wrote it, so a project that has moved on can tell.
    val content = Files.readString(guide)
    assert(content.startsWith("<!-- flix-init:"), s"the guide carries no marker:\n$content")
    assert(
      content.contains(Version.CurrentVersion.toString),
      s"the guide is not stamped with ${Version.CurrentVersion}:\n$content")
  }

  test("the agent guide claims nothing the binary does not do") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    // `flix format` parses and runs, but FormatterLsp produces no edits, so a guide that told an
    // agent to run it would be describing a command that silently does nothing.
    val content = Files.readString(p.resolve("AGENTS.md"))
    assert(!content.contains("flix format"), s"the guide advertises the formatter stub:\n$content")
  }

  test("init does not overwrite existing agent instruction files") {
    val p = Files.createTempDirectory(ProjectPrefix)
    val guide = p.resolve("AGENTS.md")
    val claudeMd = p.resolve("CLAUDE.md")
    val copilotInstructions = p.resolve(".github/copilot-instructions.md")
    Files.createDirectories(copilotInstructions.getParent)
    FileOps.writeString(guide, "my own notes\n")
    FileOps.writeString(claudeMd, "my own instructions\n")
    FileOps.writeString(copilotInstructions, "my own Copilot instructions\n")

    Bootstrap.init(p)(System.out).unsafeGet

    assert(Files.readString(guide) == "my own notes\n", "init clobbered a project's own AGENTS.md")
    assert(Files.readString(claudeMd) == "my own instructions\n", "init clobbered a project's own CLAUDE.md")
    assert(Files.readString(copilotInstructions) == "my own Copilot instructions\n", "init clobbered a project's own Copilot instructions")
  }

  test("refresh rewrites a generated guide for the running compiler") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val guide = p.resolve("AGENTS.md")
    FileOps.writeString(guide, "<!-- flix-init: generated for Flix 0.0.1. -->\nstale guidance\n")

    Bootstrap.refreshAgentGuide(p)(System.out).unsafeGet

    val content = Files.readString(guide)
    assert(!content.contains("stale guidance"), s"refresh kept the stale guide:\n$content")
    assert(
      content.contains(Version.CurrentVersion.toString),
      s"refresh did not stamp ${Version.CurrentVersion}:\n$content")
  }

  test("refresh leaves a guide that dropped the marker alone") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    // Deleting the marker line is how a project takes ownership of the file, the same contract
    // the Markdown documentor uses for pages it did not write.
    val guide = p.resolve("AGENTS.md")
    val owned = "# Our own guide\n\nWritten by us.\n"
    FileOps.writeString(guide, owned)

    Bootstrap.refreshAgentGuide(p)(System.out).unsafeGet

    assert(Files.readString(guide) == owned, "refresh overwrote a guide the project had taken over")
  }

  test("refresh writes the guide when it is missing") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    Files.delete(p.resolve("AGENTS.md"))

    Bootstrap.refreshAgentGuide(p)(System.out).unsafeGet

    val content = Files.readString(p.resolve("AGENTS.md"))
    assert(content.startsWith("<!-- flix-init:"), s"the recreated guide carries no marker:\n$content")
  }

  test("refresh reports a CLAUDE.md that does not import the guide") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    FileOps.writeString(p.resolve("CLAUDE.md"), "# Ours\n")

    val out = new ByteArrayOutputStream()
    Bootstrap.refreshAgentGuide(p)(new PrintStream(out)).unsafeGet

    val printed = out.toString
    assert(printed.contains("@AGENTS.md"), s"refresh did not report the missing import:\n$printed")
  }

  test("init does not overwrite an existing .editorconfig") {
    val p = Files.createTempDirectory(ProjectPrefix)
    val editorConfig = p.resolve(".editorconfig")
    val existing = "root = true\n"
    FileOps.writeString(editorConfig, existing)

    Bootstrap.init(p)(System.out).unsafeGet

    assert(Files.readString(editorConfig) == existing, "init clobbered a project's own .editorconfig")
  }

  test("check") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.check(PkgTestUtils.mkFlix)
  }

  test("build") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix)
  }

  test("build removes stale class files from an earlier build") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix)

    // Generated class names can change between compiler versions. A regular rebuild must not
    // leave the old name on the classpath.
    val stale = classDirOf(p, Build.Development).resolve("Stale.class")
    Files.write(stale, Array[Byte](0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte))
    assert(Files.exists(stale))

    b.build(PkgTestUtils.mkFlix)

    assert(!Files.exists(stale), "stale class file survived the rebuild")
  }

  test("a second build has nothing to do") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "the first build did not compile anything")
    val stamps = classStampsOf(p, Build.Development)
    assert(stamps.nonEmpty, "the first build wrote no class files")

    // The point of the whole exercise. A whole-program compile is seconds and most of it is a back end
    // that runs whatever changed, so repeating it to produce the bytes that are already on disk is the
    // most common thing a build is asked to do.
    assert(!b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "a build with nothing changed compiled anyway")
    assert(classStampsOf(p, Build.Development) == stamps, "a build with nothing to do rewrote the output")
  }

  test("a source whose content changed is built again") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    Files.writeString(p.resolve("src").resolve("Main.flix"),
      """def main(): Unit \ IO = println("changed")
        |""".stripMargin)

    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "an edited source was not compiled")
  }

  test("a source that was touched but not changed still has nothing to do") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // A content hash rather than a modification time, and this is the case that tells them apart. It is
    // not exotic: a checkout, a formatter that reformats to the same text, an editor that saves an
    // unchanged buffer all land here, and each would otherwise pay for a full compile.
    val main = p.resolve("src").resolve("Main.flix")
    Files.setLastModifiedTime(main, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000))

    assert(!b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "a touched but unchanged source forced a build")
  }

  test("a created or deleted source is built again") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // The digest covers the *names* as well as the contents, so a file appearing changes it even when
    // nothing that already existed did.
    val added = p.resolve("src").resolve("Added.flix")
    Files.writeString(added, "mod Added { pub def value(): Int32 = 42 }\n")
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "a created source was not compiled")
    assert(!b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    Files.delete(added)
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "a deleted source was not compiled")
  }

  test("a missing product is built again") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // Sources unchanged, output not. Anything else -- a partial copy, a stray delete, a jar packaged
    // from a directory someone cleaned by hand -- would be reported as up to date over an output that
    // cannot run.
    val victim = FileOps.getFilesWithExtIn(classDirOf(p, Build.Development), "class", Int.MaxValue).head
    Files.delete(victim)

    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "a missing class file was not rebuilt")
    assert(Files.exists(victim), "the rebuild did not restore it")
  }

  test("an unexpected product is built again") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // A class directory holding something no build wrote is one nobody can describe, which is the state
    // the manifest exists to prevent. Rebuilding is how it becomes describable again -- and the rebuild
    // reconciles the stray file away.
    val stray = classDirOf(p, Build.Development).resolve("Stray.class")
    Files.write(stray, Array[Byte](0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte))

    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet, "a stray class file did not force a build")
    assert(!Files.exists(stray), "the rebuild left the stray file behind")
  }

  test("a changed non-source input is built again") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // The fingerprint's job, unchanged by any of this: an option that reaches the back end makes the
    // recorded products the wrong ones even though every source is identical.
    val instrumented = mkDeterministicFlix
    instrumented.setOptions(instrumented.options.copy(coverage = true))
    assert(b.buildIfNeeded(instrumented).unsafeGet, "a changed back-end option did not force a build")
  }

  test("--clean builds even when there is nothing to do") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // `--clean` is a request to build from nothing, so being up to date is not an answer to it.
    assert(b.buildIfNeeded(mkDeterministicFlix, clean = true).unsafeGet, "--clean did not build")
    assert(classFilesOf(p, Build.Development).nonEmpty)
  }

  test("a check after a successful build has nothing to do") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // A build *is* a check followed by code generation, and it stops if the check reports anything --
    // so a manifest whose digest still matches proves these sources type check. No new record, and the
    // same guard a skipped build uses.
    assert(!b.checkIfNeeded(mkDeterministicFlix).unsafeGet, "a check after a successful build ran anyway")
  }

  test("a check after an edit runs for real") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    Files.writeString(p.resolve("src").resolve("Main.flix"),
      """def main(): Unit \ IO = println("edited")
        |""".stripMargin)

    assert(b.checkIfNeeded(mkDeterministicFlix).unsafeGet, "an edited source was not checked")
  }

  test("a check reports an error that a recorded build cannot answer for") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // The failure mode worth naming: a stale record must never turn a broken program into a clean
    // report. The digest is what prevents it -- the sources are no longer the ones that were built.
    Files.writeString(p.resolve("src").resolve("Main.flix"), "def main(): Unit = undefinedFunction()\n")

    assert(b.checkIfNeeded(mkDeterministicFlix).isInstanceOf[Result.Err[?, ?]], "a broken project checked clean")
  }

  test("a check whose build output was deleted runs for real") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // Conservative in the direction that costs time rather than correctness: a check does not care
    // about class files, but it consults one predicate rather than two, and that predicate does.
    b.cleanOutput(mkDeterministicFlix, Build.Development).unsafeGet

    assert(b.checkIfNeeded(mkDeterministicFlix).unsafeGet, "a check reused a build whose output is gone")
  }

  test("a check asked not to reuse runs for real") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.buildIfNeeded(mkDeterministicFlix).unsafeGet)

    // What `flix check --clean` asks for. Without a way to say it, the only escape from a wrong answer
    // would be deleting the build directory by hand.
    assert(b.checkIfNeeded(mkDeterministicFlix, reuse = false).unsafeGet, "--clean did not force a check")
  }

  test("run reports the program's own exit code") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    Files.writeString(p.resolve("src").resolve("Main.flix"),
      """use Sys.Exit
        |
        |def main(): Unit \ { Exit, IO } =
        |    println("PROGRAM-RAN");
        |    Exit.exit(3)
        |""".stripMargin)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    // The program runs in a JVM of its own now, so what it says on the way out is reportable. In this
    // process it was not: a `System.exit` would have taken the compiler with it and there would have
    // been nothing left to report.
    assert(b.run(mkDeterministicFlix, Array.empty).unsafeGet == 3, "the program's exit code was lost")
  }

  test("run does not rebuild when the output is current") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    assert(b.run(mkDeterministicFlix, Array.empty).unsafeGet == 0)
    val stamps = classStampsOf(p, Build.Development)
    assert(stamps.nonEmpty, "the first run wrote no class files")

    // The defect this closes: `run` compiled unconditionally, so the most repeated command in a project
    // paid for a whole-program build every time, even with nothing to build.
    assert(b.run(mkDeterministicFlix, Array.empty).unsafeGet == 0)
    assert(classStampsOf(p, Build.Development) == stamps, "a run with nothing to build recompiled")
  }

  test("run compiles what changed before running it") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.run(mkDeterministicFlix, Array.empty).unsafeGet == 0)

    // A stale program must never be the one that runs, which is the failure a fast path can introduce.
    // The new source exits 3, so the exit code is the evidence that it was the one started.
    Files.writeString(p.resolve("src").resolve("Main.flix"),
      """use Sys.Exit
        |
        |def main(): Unit \ { Exit, IO } =
        |    println("EDITED");
        |    Exit.exit(3)
        |""".stripMargin)

    assert(b.run(mkDeterministicFlix, Array.empty).unsafeGet == 3, "an edited program was not recompiled")
  }

  test("run of a program that does not compile does not run anything") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    Files.writeString(p.resolve("src").resolve("Main.flix"), "def main(): Unit = undefinedFunction()\n")
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    assert(b.run(mkDeterministicFlix, Array.empty).isInstanceOf[Result.Err[?, ?]], "a broken program ran")
  }

  test("a second test run does not compile") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]], "the first test run failed")

    val stamps = classStampsOf(p, Build.Development)
    assert(stamps.nonEmpty, "the first test run wrote no class files")
    assert(Files.isRegularFile(outputDirOf(p, Build.Development).resolve(TestManifest.FileName)),
      "the first test run recorded no tests")

    // A test is a compiled function this process reflects and calls, so the run used to need a
    // compilation and therefore compiled every time. It now reaches the same shims through the class
    // files the last run left.
    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]])
    assert(classStampsOf(p, Build.Development) == stamps, "a second test run recompiled")
  }

  test("an edited test is compiled and its new outcome reported") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]])

    // The failure a recorded table can introduce: running yesterday's tests and reporting them as
    // today's. The digest is what prevents it, and a now-failing test is the evidence.
    Files.writeString(p.resolve("test").resolve("TestMain.flix"),
      """use Assert.assertEq
        |
        |@Test
        |def testFails(): Unit \ Assert = assertEq(expected = 3, 1 + 1)
        |""".stripMargin)

    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Err[?, ?]], "an edited test was not compiled")
  }

  test("a recorded test that is not in the class files is not trusted") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]])

    // The record is confirmed against the products rather than believed. Point it at a class that is
    // not there and the run must compile instead of quietly testing whatever it could resolve.
    val recordFile = outputDirOf(p, Build.Development).resolve(TestManifest.FileName)
    val record = TestManifest.read(recordFile).getOrElse(fail("no record was written"))
    val doctored = record.copy(tests = record.tests.map(_.copy(className = "dev.flix.gen.Def$notThere")))
    TestManifest.write(recordFile, doctored).unsafeGet

    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]], "the run did not recover by compiling")
    // And the record is rewritten by the run that compiled, so the next one is fast again.
    assert(TestManifest.read(recordFile).exists(_.tests.forall(_.className != "dev.flix.gen.Def$notThere")))
  }

  test("an empty recorded table is refused while the project has test sources") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]])

    // "No tests" and "the tests were not recorded" look identical from here, and only one of them
    // should report a green run over a project that has tests in it.
    val recordFile = outputDirOf(p, Build.Development).resolve(TestManifest.FileName)
    val record = TestManifest.read(recordFile).getOrElse(fail("no record was written"))
    TestManifest.write(recordFile, record.copy(tests = Nil)).unsafeGet

    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]])
    assert(TestManifest.read(recordFile).exists(_.tests.nonEmpty), "the emptied record was not replaced")
  }

  test("a test run asked not to reuse compiles") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(b.test(mkDeterministicFlix).isInstanceOf[Result.Ok[?, ?]])
    val stamps = classStampsOf(p, Build.Development)

    // `flix test --clean`, for a person who does not believe the record.
    assert(b.test(mkDeterministicFlix, reuse = false).isInstanceOf[Result.Ok[?, ?]])
    assert(classStampsOf(p, Build.Development) != stamps, "--clean did not rebuild")
  }

  test("build-jar") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix
    b.build(flix)
    b.buildJar(flix)

    val packageName = p.getFileName.toString
    val jarPath = p.resolve("artifact").resolve(packageName + ".jar")
    assert(Files.exists(jarPath))
    assert(jarPath.getFileName.toString.startsWith(ProjectPrefix))
  }

  test("build-jar removes stale class files from an earlier build") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(PkgTestUtils.mkFlix)

    // Plant a class file that no build would produce, in the mode `build-jar` writes.
    val classDir = classDirOf(p, Build.Production)
    val stale = classDir.resolve("Stale.class")
    Files.write(stale, Array[Byte](0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte))
    assert(Files.exists(stale))

    b.buildJar(PkgTestUtils.mkFlix)

    // It must be gone from the class directory and absent from the jar.
    assert(!Files.exists(stale), "stale class file survived the rebuild")

    val packageName = p.getFileName.toString
    val jarPath = p.resolve("artifact").resolve(packageName + ".jar")
    val entries = entryNamesOf(jarPath)
    assert(!entries.contains("Stale.class"), s"stale class file was packaged into the jar: $entries")
  }

  test("build-jar produces a complete jar when run twice on the same project") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    val packageName = p.getFileName.toString
    val jarPath = p.resolve("artifact").resolve(packageName + ".jar")

    b.buildJar(PkgTestUtils.mkFlix)
    val first = entryNamesOf(jarPath)

    // The second build must not rely on state left behind by the first: sources are
    // tracked per Bootstrap, but the class files belong to the Flix instance.
    b.buildJar(PkgTestUtils.mkFlix)
    val second = entryNamesOf(jarPath)

    assert(first.sizeIs > 1, s"first jar is suspiciously empty: $first")
    assert(
      first.size == second.size,
      s"second build produced ${second.size} entries but the first produced ${first.size}")
  }

  test("build-jar deletes the class files the sources no longer produce") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val jarPath = p.resolve("artifact").resolve(p.getFileName.toString + ".jar")

    b.buildJar(mkDeterministicFlix).unsafeGet
    val before = classFilesOf(p, Build.Production)

    // Remove the module main was calling. Its class files are products of a build that no longer
    // describes the project, and a build that only overwrites what it generates would leave them
    // behind - on the classpath, and in the jar.
    writeSources(p, extra = false)
    b.buildJar(mkDeterministicFlix).unsafeGet
    val after = classFilesOf(p, Build.Production)

    // Note that `after` is not a subset of `before`: which specializations the monomorphizer
    // reaches is a property of the whole program, so a smaller program can also require a class
    // the larger one did not. What must hold is that the removed module's own class is gone.
    val disappeared = before -- after
    assert(
      disappeared.exists(_.contains("countdown")),
      s"the removed def's class file survived the rebuild: ${disappeared.toList.sorted}")
    assert(
      entryNamesOf(jarPath).intersect(disappeared).isEmpty,
      s"a class file of the earlier build was packaged: ${entryNamesOf(jarPath).intersect(disappeared)}")

    // And the jar holds the products of this build, no more: a jar that carried a class the class
    // directory no longer has would mean packaging read something other than the product set.
    assert(
      entryNamesOf(jarPath).filter(_.endsWith(".class")) == after,
      s"the jar and the class directory disagree: ${diffOf(after, entryNamesOf(jarPath).filter(_.endsWith(".class")))}")
  }

  test("an incremental build leaves what a clean build leaves") {
    // This is the property that makes not wiping the class directory safe. Everything else about
    // incremental packaging - the manifest, the fingerprint, the reconciliation - exists to keep
    // it true, and no other assertion here would notice a stale product that both builds keep.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val jarPath = p.resolve("artifact").resolve(p.getFileName.toString + ".jar")

    b.buildJar(mkDeterministicFlix).unsafeGet

    writeSources(p, extra = false)
    b.buildJar(mkDeterministicFlix).unsafeGet
    val incrementalClasses = classFilesOf(p, Build.Production)
    val incrementalEntries = entryNamesOf(jarPath)

    b.buildJar(mkDeterministicFlix, clean = true).unsafeGet

    assert(
      classFilesOf(p, Build.Production) == incrementalClasses,
      s"the class directory differs from a clean build's: ${diffOf(incrementalClasses, classFilesOf(p, Build.Production))}")
    assert(
      entryNamesOf(jarPath) == incrementalEntries,
      s"the jar differs from a clean build's: ${diffOf(incrementalEntries, entryNamesOf(jarPath))}")
  }

  test("a development build and a production build do not disturb each other") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    b.build(mkDeterministicFlix).unsafeGet
    b.buildJar(mkDeterministicFlix).unsafeGet

    // Each mode owns an output directory. Sharing one made every `build` reset what the last
    // `build-jar` left and the other way round, because the mode reaches the typer and the two
    // do not produce the same class files.
    val debugClasses = classFilesOf(p, Build.Development)
    val releaseClasses = classFilesOf(p, Build.Production)
    assert(debugClasses.nonEmpty, "the development build left no class files")
    assert(releaseClasses.nonEmpty, "the production build left no class files")
    assert(Files.exists(manifestFileOf(p, Build.Development)), "the development build left no manifest")
    assert(Files.exists(manifestFileOf(p, Build.Production)), "the production build left no manifest")

    // And going back to the first mode finds its own products still recorded, so it reuses them
    // rather than starting over.
    val recorded = BuildManifest.read(manifestFileOf(p, Build.Development)).map(_.fingerprint)
    b.build(mkDeterministicFlix).unsafeGet
    assert(
      BuildManifest.read(manifestFileOf(p, Build.Development)).map(_.fingerprint) == recorded,
      "the second development build did not agree with the first about its inputs")
    assert(
      classFilesOf(p, Build.Development) == debugClasses,
      s"the second development build changed its own output: ${diffOf(debugClasses, classFilesOf(p, Build.Development))}")
    assert(
      classFilesOf(p, Build.Production) == releaseClasses,
      s"a development build disturbed the production output: ${diffOf(releaseClasses, classFilesOf(p, Build.Production))}")
  }

  test("repeated builds do not grow the class directory") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    b.buildJar(mkDeterministicFlix).unsafeGet
    val withExtra = classFilesOf(p, Build.Production).size
    val withExtraBytes = classBytesOf(p, Build.Production)
    assert(withExtra > 0, "the first build produced no class files")

    // Counted rather than compared name by name, and that is not laziness: a handful of
    // specializations carry a hash in their class name that is not stable across `Flix`
    // instances - two builds of one program can produce `Order$Def$compare$MKYprbdN3bd` and
    // `Order$Def$compare$gqWQF9BpNTE` - so the *set* legitimately differs where the size does
    // not. Growth is what this test is about, and the count sees it exactly.
    b.buildJar(mkDeterministicFlix).unsafeGet
    assert(
      classFilesOf(p, Build.Production).size == withExtra,
      s"rebuilding unchanged sources changed the file count from $withExtra to ${classFilesOf(p, Build.Production).size}")
    assert(classBytesOf(p, Build.Production) == withExtraBytes, "rebuilding unchanged sources changed its size")

    // Twice on one Flix instance, which is the path that reuses the front end's caches.
    val flix = mkDeterministicFlix
    b.buildJar(flix).unsafeGet
    b.buildJar(flix).unsafeGet
    assert(
      classFilesOf(p, Build.Production).size == withExtra,
      s"rebuilding on one instance changed the file count from $withExtra to ${classFilesOf(p, Build.Production).size}")

    writeSources(p, extra = false)
    b.buildJar(mkDeterministicFlix).unsafeGet
    val withoutExtra = classFilesOf(p, Build.Production).size
    assert(withoutExtra < withExtra, "removing the module freed no class file, so this asserts nothing")
    assert(
      !classFilesOf(p, Build.Production).exists(_.contains("countdown")),
      "the removed def's class file is still there")

    // Toggling the module back and forth must land back on the count each state produced the
    // first time. A directory that only ever gained files would by now hold the union of the two,
    // which is the growth this whole mechanism exists to prevent.
    writeSources(p, extra = true)
    b.buildJar(mkDeterministicFlix).unsafeGet
    assert(
      classFilesOf(p, Build.Production).size == withExtra,
      s"the class directory grew across edits: $withExtra became ${classFilesOf(p, Build.Production).size}")

    writeSources(p, extra = false)
    b.buildJar(mkDeterministicFlix).unsafeGet
    assert(
      classFilesOf(p, Build.Production).size == withoutExtra,
      s"the class directory grew across edits: $withoutExtra became ${classFilesOf(p, Build.Production).size}")
  }

  test("build-jar does not wipe what an earlier build produced") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    b.build(mkDeterministicFlix).unsafeGet
    val development = classStampsOf(p, Build.Development)
    assert(development.nonEmpty, "the development build produced no class files")

    b.buildJar(mkDeterministicFlix).unsafeGet

    // Not deleted, and not even rewritten: the timestamps are what tells "left alone" apart from
    // "wiped and produced again", since both end with the same names on disk.
    assert(
      classStampsOf(p, Build.Development) == development,
      "build-jar disturbed the development class files")
    assert(
      Files.exists(manifestFileOf(p, Build.Development)),
      "build-jar deleted the development build manifest")

    // A second build-jar loses nothing from its own output either. Note that the timestamps there
    // *do* all change: `JvmWriter` writes every class of the program on every `codeGen`, so within
    // one mode a reconciled build and a wiped one are indistinguishable on disk. What reconciling
    // buys is in the build, not in the file times - no wipe, and the compiler's caches survive.
    val production = classFilesOf(p, Build.Production)
    b.buildJar(mkDeterministicFlix).unsafeGet
    assert(
      classFilesOf(p, Build.Production) == production,
      s"the second build-jar changed which class files exist: ${diffOf(production, classFilesOf(p, Build.Production))}")

    // And --clean is still available for the case that wants the wipe.
    b.buildJar(mkDeterministicFlix, clean = true).unsafeGet
    assert(classFilesOf(p, Build.Production) == production, "a clean build produced a different set")
    assert(
      classStampsOf(p, Build.Development) == development,
      "a clean production build disturbed the development class files")
  }

  test("--clean empties the output directory, and an implicit full build does not") {
    // Two different reasons for a full build, deliberately not the same operation. `--clean` was
    // asked for, so it wipes first and a failed compile leaves nothing - that is the bargain, and
    // it is what makes a released artifact a function of the sources alone. A full build forced by
    // a changed fingerprint was *not* asked for, so it must not destroy a working build.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    b.buildJar(mkDeterministicFlix).unsafeGet
    val good = classFilesOf(p, Build.Production)
    assert(good.nonEmpty, "the first build produced no class files")

    // Break the source so that the wipe is the only thing that reaches the disk.
    FileOps.writeString(p.resolve("src").resolve("Main.flix").normalize(), "def main(): Unit = this is not Flix\n")

    val cleanResult = b.buildJar(mkDeterministicFlix, clean = true)
    assert(cleanResult.isInstanceOf[Result.Err[_, _]], "expected the build to fail on a broken source")
    assert(
      classFilesOf(p, Build.Production).isEmpty,
      s"--clean did not empty the output directory: ${classFilesOf(p, Build.Production).toList.sorted.take(5)}")
    assert(!Files.exists(manifestFileOf(p, Build.Production)), "--clean left the build manifest behind")

    // Restore, rebuild, and now force a full build the *implicit* way. The same failing compile
    // must leave the good output alone.
    writeSources(p, extra = true)
    b.buildJar(mkDeterministicFlix).unsafeGet
    val restored = classStampsOf(p, Build.Production)
    val manifest = Files.readString(manifestFileOf(p, Build.Production))

    FileOps.writeString(p.resolve("src").resolve("Main.flix").normalize(), "def main(): Unit = this is not Flix\n")
    FileOps.writeString(manifestFileOf(p, Build.Production), manifest.replace("\"fingerprint\" : \"", "\"fingerprint\" : \"x"))

    val implicitResult = b.buildJar(mkDeterministicFlix)
    assert(implicitResult.isInstanceOf[Result.Err[_, _]], "expected the build to fail on a broken source")
    assert(
      classStampsOf(p, Build.Production) == restored,
      "a full build nobody asked for destroyed the last good output")
  }

  test("a build that fails to compile leaves the last good build on disk") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val jarPath = p.resolve("artifact").resolve(p.getFileName.toString + ".jar")

    b.buildJar(mkDeterministicFlix).unsafeGet
    val good = classStampsOf(p, Build.Production)
    val manifest = Files.readString(manifestFileOf(p, Build.Production))
    val jar = entryNamesOf(jarPath)

    // Break a source, and force the full-build path with it: a changed fingerprint is the
    // ordinary reason for one - a compiler upgrade, a new flag, an updated dependency - and
    // wiping the class directory before knowing whether the compile succeeds would destroy a
    // working build whenever the compile that was meant to replace it fails.
    FileOps.writeString(p.resolve("src").resolve("Main.flix").normalize(), "def main(): Unit = this is not Flix\n")
    FileOps.writeString(manifestFileOf(p, Build.Production), manifest.replace("\"fingerprint\" : \"", "\"fingerprint\" : \"x"))

    val result = b.buildJar(mkDeterministicFlix)
    assert(result.isInstanceOf[Result.Err[_, _]], "expected the build to fail on a broken source")

    assert(classStampsOf(p, Build.Production) == good, "the failed build disturbed the last good class files")
    assert(entryNamesOf(jarPath) == jar, "the failed build damaged the last good jar")
  }

  test("build-fatjar reconciles and packages like build-jar") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val jarPath = p.resolve("artifact").resolve(p.getFileName.toString + ".jar")

    b.buildFatJar(mkDeterministicFlix).unsafeGet
    val before = classFilesOf(p, Build.Production)
    assert(before.nonEmpty, "the fat jar build produced no class files")

    // It shares the production output directory, the manifest and the reconciliation with
    // `build-jar`, so it has to drop a product the sources no longer require just the same.
    writeSources(p, extra = false)
    b.buildFatJar(mkDeterministicFlix).unsafeGet
    val after = classFilesOf(p, Build.Production)

    assert(
      !after.exists(_.contains("countdown")),
      s"the removed def's class file survived a fat jar rebuild: ${(before -- after).toList.sorted}")
    assert(
      entryNamesOf(jarPath).intersect(before -- after).isEmpty,
      "a class file of the earlier build was packaged into the fat jar")
    assert(after.subsetOf(entryNamesOf(jarPath)), "the fat jar is missing class files this build produced")
  }

  test("build-jar records what it produced in the build manifest") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(PkgTestUtils.mkFlix).unsafeGet

    val manifest = BuildManifest.read(manifestFileOf(p, Build.Production)) match {
      case Some(m) => m
      case None => fail("the build wrote no readable build manifest")
    }

    // The manifest is the account of the class directory that the next build reconciles against.
    // One that disagrees with the directory would have it delete products that are in use, or
    // keep ones that are not.
    assert(manifest.products.toSet == classFilesOf(p, Build.Production), "the manifest disagrees with the class directory")
    assert(manifest.sources.contains("src/Main.flix"), s"the manifest omits the sources: ${manifest.sources}")
  }

  test("a build manifest of another compiler or another format is not trusted") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val jarPath = p.resolve("artifact").resolve(p.getFileName.toString + ".jar")
    b.buildJar(mkDeterministicFlix).unsafeGet
    val entries = entryNamesOf(jarPath)

    // A manifest this compiler cannot read leaves the state of the class directory unknown, and
    // the build has to fall back to a full one rather than reconcile against a set it guessed.
    val manifestFile = manifestFileOf(p, Build.Production)
    FileOps.writeString(manifestFile, """{"formatVersion": 999, "fingerprint": "?"}""")
    assert(BuildManifest.read(manifestFile).isEmpty, "a manifest of an unknown format was read anyway")

    // Nor may an unreadable one throw: the caller answers `None` with a full build, and an
    // exception escaping here would instead abort a build over a file the build itself owns.
    for (bad <- List("", "{", "not json at all", """{"formatVersion": 1}""",
      """{"formatVersion": 1, "fingerprint": "a", "products": [1, 2], "sources": []}""")) {
      FileOps.writeString(manifestFile, bad)
      assert(BuildManifest.read(manifestFile).isEmpty, s"a malformed manifest was read anyway: '$bad'")
    }

    b.buildJar(mkDeterministicFlix).unsafeGet
    assert(
      entryNamesOf(jarPath) == entries,
      s"the fallback build did not produce the same jar: ${diffOf(entries, entryNamesOf(jarPath))}")
    assert(BuildManifest.read(manifestFile).isDefined, "the fallback build left no readable manifest")
  }

  test("the build fingerprint separates the inputs that change what is emitted") {
    val options = Options.Default
    val fingerprint = BuildManifest.fingerprintOf(options, Nil)

    // A build may only reuse what an earlier build left if every non-source input agrees. These
    // reach the back end, so a product set produced under one of them describes nothing about
    // what the other produces.
    assert(fingerprint != BuildManifest.fingerprintOf(options.copy(coverage = true), Nil))
    assert(fingerprint != BuildManifest.fingerprintOf(options.copy(build = Build.Production), Nil))
    assert(fingerprint != BuildManifest.fingerprintOf(options.copy(xnewmono = true), Nil))
    assert(fingerprint != BuildManifest.fingerprintOf(options.copy(lib = LibLevel.Min), Nil))
    assert(fingerprint != BuildManifest.fingerprintOf(options.copy(xdebug = true), Nil))

    // A dependency is part of the fingerprint, and one that changed underneath the project is a
    // different input even though its path is the same.
    val jar = Files.createTempFile("flix-dep-", ".jar")
    val withDep = BuildManifest.fingerprintOf(options, List(jar))
    assert(withDep != fingerprint)
    Files.write(jar, "changed".getBytes)
    assert(BuildManifest.fingerprintOf(options, List(jar)) != withDep)

    // Thread count is deliberately not part of it: it perturbs generated symbol names without
    // changing the program, and a rename is handled by reconciling the product set.
    assert(fingerprint == BuildManifest.fingerprintOf(options.copy(threads = 1), Nil))
  }

  test("build-jar refuses to delete a non-class file in the class directory") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(PkgTestUtils.mkFlix)

    // A file that is not a class file must stop the build rather than be deleted.
    val precious = classDirOf(p, Build.Production).resolve("precious.txt")
    Files.write(precious, "do not delete me".getBytes)

    val result = b.buildJar(PkgTestUtils.mkFlix)

    assert(result.isInstanceOf[Result.Err[_, _]], "expected build-jar to fail on an unexpected file")
    assert(Files.exists(precious), "build-jar deleted a file that was not a class file")
  }

  test("build-jar refuses to delete a file that is named like a class file but is not one") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(PkgTestUtils.mkFlix).unsafeGet

    // Reconciling runs on every ordinary build, where before only `clean` deleted in here - and
    // `clean` checks the bytes, not just the name. A file called `Notes.class` that is not
    // bytecode is somebody's file.
    val notes = classDirOf(p, Build.Production).resolve("Notes.class")
    FileOps.writeString(notes, "these are my notes, not bytecode")

    val result = b.buildJar(PkgTestUtils.mkFlix)

    assert(result.isInstanceOf[Result.Err[_, _]], "expected the build to refuse a non-bytecode .class file")
    assert(Files.exists(notes), "the build deleted a file that only looked like a class file")

    // An empty one is tolerated, because that is what an interrupted write leaves and refusing it
    // would wedge every later build.
    FileOps.delete(notes).unsafeGet
    val interrupted = classDirOf(p, Build.Production).resolve("Interrupted.class")
    Files.write(interrupted, Array.emptyByteArray)
    b.buildJar(PkgTestUtils.mkFlix).unsafeGet
    assert(!Files.exists(interrupted), "a half-written class file was not swept")
  }

  test("clean removes the coverage report and the generated stubs") {
    // 'clean' refuses to delete anything it does not recognise, and it aborts rather than skip. So
    // every file the tool itself writes under 'build/' has to be recognised, or an ordinary
    // '--coverage' run leaves a project that cannot be cleaned at all.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix).unsafeGet

    val buildDir = p.resolve("build").normalize()
    FileOps.writeString(buildDir.resolve("coverage.json").normalize(), """{"probes": []}""")
    FileOps.writeString(buildDir.resolve("coverage.info").normalize(), "TN:\nend_of_record\n")
    FileOps.writeString(buildDir.resolve("stubs").resolve("Acme").resolve("Api.java").normalize(), "public class Api {}")

    b.clean() match {
      case Result.Ok(_) => ()
      case Result.Err(e) => fail(s"expected clean to accept the tool's own output, but got: $e")
    }
    assert(!Files.exists(buildDir), s"clean left files behind: ${FileOps.getFilesIn(buildDir, Int.MaxValue)}")
  }

  test("build-jar generates ZIP entries with fixed time") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix
    b.build(flix)
    b.buildJar(flix)

    val packageName = p.getFileName.toString
    val jarPath = p.resolve("artifact").resolve(packageName + ".jar")
    val format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    for (e <- new ZipFile(jarPath.toFile).entries().asScala) {
      val time = new Date(e.getTime)
      val formatted = format.format(time)
      assert(formatted == "2014-06-27 00:00:00")
    }
  }

  test("build-jar always generates package that is byte-for-byte exactly the same modulo concurrency") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val packageName = p.getFileName.toString
    val jarPath = p.resolve("artifact").resolve(packageName + ".jar")

    val flix1 = PkgTestUtils.mkFlix
    // Use 1 thread for deterministic symbols
    flix1.setOptions(flix1.options.copy(threads = 1))

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(flix1)
    val hash1 = calcHash(jarPath)

    // Use new flix instance to reset symbol generation
    val flix2 = PkgTestUtils.mkFlix
    // Use 1 thread for deterministic symbols
    flix2.setOptions(flix2.options.copy(threads = 1))
    b.buildJar(flix2)
    val hash2 = calcHash(jarPath)

    assert(
      hash1 == hash2,
      s"Two file hashes are not same: $hash1 and $hash2")
  }

  test("build-pkg") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildPkg()(Formatter.getDefault)

    val packageName = p.getFileName.toString
    val packagePath = p.resolve("artifact").resolve(packageName + ".fpkg")
    assert(Files.exists(packagePath))
    assert(packagePath.getFileName.toString.startsWith(ProjectPrefix))
  }

  test("build-pkg generates ZIP entries with fixed time") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildPkg()(Formatter.getDefault)

    val packageName = p.getFileName.toString
    val packagePath = p.resolve("artifact").resolve(packageName + ".fpkg")
    val format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    for (e <- new ZipFile(packagePath.toFile).entries().asScala) {
      val time = new Date(e.getTime)
      val formatted = format.format(time)
      assert(formatted == "2014-06-27 00:00:00")
    }
  }

  test("build-pkg always generates package that is byte-for-byte exactly the same") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val packageName = p.getFileName.toString
    val packagePath = p.resolve("artifact").resolve(packageName + ".fpkg")

    val flix = PkgTestUtils.mkFlix

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(flix)

    b.buildPkg()(Formatter.getDefault)

    val hash1 = calcHash(packagePath)

    b.buildPkg()(Formatter.getDefault)

    val hash2 = calcHash(packagePath)

    assert(
      hash1 == hash2,
      s"Two file hashes are not same: $hash1 and $hash2")
  }

  test("run") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.run(PkgTestUtils.mkFlix, Array("arg0", "arg1"))
  }

  test("test") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.test(PkgTestUtils.mkFlix)
  }

  test("clean-command-should-remove-class-files-and-directories-if-compiled-previously") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix)
    val buildDir = p.resolve("./build/").normalize()
    // The build manifest is the one file in the build directory that is not a class file, and
    // 'clean' has to remove it with the products it describes. `build` is development mode, so
    // it is the debug output that exists here.
    val manifestFile = manifestFileOf(p, Build.Development)
    assert(Files.exists(manifestFile), "the build wrote no build manifest")
    assert(
      !Files.exists(outputDirOf(p, Build.Production)),
      "a development build wrote into the production output directory")
    val buildFiles = FileOps.getFilesIn(buildDir, Int.MaxValue).filterNot(_.normalize() == manifestFile)
    if (buildFiles.isEmpty || buildFiles.exists(!FileOps.checkExt(_, "class"))) {
      fail(
        s"""build output is not as expected:
           |${buildFiles.mkString(System.lineSeparator())}
           |""".stripMargin)
    }
    b.clean()
    val newBuildFiles = FileOps.getFilesIn(buildDir, Int.MaxValue)
    if (newBuildFiles.nonEmpty || Files.exists(buildDir)) {
      fail(
        s"""at least one file was not cleaned from build dir:
           |${newBuildFiles.mkString(System.lineSeparator())}
           |""".stripMargin)
    }
  }

  test("clean-should-remove-generated-documentation") {
    // 'clean' refuses to delete anything it does not recognise, so every format that 'doc'
    // can emit has to be recognised, or generating docs makes the project uncleanable.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix)
    val docDir = p.resolve("./build/doc/").normalize()
    Files.createDirectories(docDir)
    FileOps.writeString(docDir.resolve("List.md").normalize(), "# List")
    FileOps.writeString(docDir.resolve("List.html").normalize(), "<h1>List</h1>")
    b.clean() match {
      case Result.Ok(_) => succeed
      case Result.Err(e) => fail(s"expected clean to accept generated documentation, but got: $e")
    }
  }

  test("clean-should-error-on-unexpected-file") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix)
    val buildDir = p.resolve("./build/").normalize()
    FileOps.writeString(buildDir.resolve("./other.txt").normalize(), "hello")
    b.clean() match {
      case Result.Ok(_) => fail("expected clean to abort")
      case Result.Err(_) => succeed
    }
  }

  test("clean-should-succeed-on-non-existent-build-dir") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val buildDir = p.resolve("./build/").normalize()
    if (Files.exists(buildDir)) {
      fail("did not expected build directory to exist")
    }
    b.clean() match {
      case Result.Ok(_) => succeed
      case Result.Err(_) => fail("expected success")
    }
  }

  test("clean-should-do-nothing-in-directory-mode") {
    val p = Files.createTempDirectory(ProjectPrefix)
    FileOps.writeString(p.resolve("./Main.flix").normalize(),
      """
        |def main(): Unit = ()
        |""".stripMargin)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val buildDir = p.resolve("./build/").normalize()
    if (Files.exists(buildDir)) {
      fail("did not expected build directory to exist")
    }
    b.clean() match {
      case Result.Ok(_) => fail("expected failure in directory mode")
      case Result.Err(_) => succeed
    }
  }

  test("eff-lock should write effect lock file") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet // Unsafe get to crash in case of error

    // Override manifest
    val toml = PkgTestUtils.mkTomlWithDeps(
      """
        |"github:jaschdoc/flix-test-pkg-trust-transitive-java" = { version = "0.1.1", security = "unrestricted" }
        |"github:flix/test-pkg-trust-java" = { version = "0.1.0", security = "unrestricted" }
        |""".stripMargin
    )
    FileOps.writeString(p.resolve("flix.toml").normalize(), toml)

    // Override main file
    val main =
      """
        |pub def main(): Unit \ IO =
        |    TestPkgTrustTransitive.entry()
        |""".stripMargin
    FileOps.writeString(p.resolve("src/Main.flix").normalize(), main)

    // Assert effects.lock does not exist
    val effectLockFile = p.resolve("effects.lock").normalize()
    if (Files.exists(effectLockFile)) {
      fail("Unexpected 'effects.lock' file. File is not supposed to exist")
    }

    val bootstrap = Bootstrap.bootstrap(p, PkgTestUtils.gitHubToken)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix
    bootstrap.lockEffects(flix).unsafeGet

    // Assert that effects.lock exists now
    if (Files.exists(effectLockFile)) {
      succeed
    } else {
      fail("File 'effects.lock' does not exist")
    }
  }

  test("eff-check on same version as before is ok") {
    // Version 0.1.0 of the dependency has signature `Int32 -> Int32`.
    // There is no upgrade done, but we assert that
    // performing eff-check after eff-lock succeeds.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet // Unsafe get to crash in case of error

    // Override manifest
    val toml = PkgTestUtils.mkTomlWithDeps(
      """
        |"github:jaschdoc/flix-test-pkg-eff-upgrade" = "0.1.0"
        |""".stripMargin
    )
    FileOps.writeString(p.resolve("flix.toml").normalize(), toml)

    // Override main file
    val main =
      """
        |pub def main(): Unit \ IO =
        |    println(Upgr.entrypoint(42))
        |""".stripMargin
    FileOps.writeString(p.resolve("src/Main.flix").normalize(), main)

    val bootstrap = Bootstrap.bootstrap(p, PkgTestUtils.gitHubToken)(Formatter.getDefault, System.out).unsafeGet
    bootstrap.lockEffects(PkgTestUtils.mkFlix).unsafeGet

    assert(bootstrap.checkEffects(PkgTestUtils.mkFlix) == Result.Ok(()))
  }

  test("eff-check on effect unsafe upgrade reports error") {
    // Version 0.1.0 of the dependency has signature `Int32 -> Int32`.
    // Version 0.1.1 of the dependency has signature `Int32 -> Int32 \ IO`.
    // We upgrade from `Int32 -> Int32` to `Int32 -> Int32 \ IO`
    // and assert that it does NOT succeed.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet // Unsafe get to crash in case of error

    val pkgAuthor = "jaschdoc"
    val pkgName = "flix-test-pkg-eff-upgrade"
    val vOld = "0.1.0"
    val vNew = "0.1.1"

    // Override manifest
    val toml = PkgTestUtils.mkTomlWithDeps(
      s"""
         |"github:$pkgAuthor/$pkgName" = "$vOld"
         |""".stripMargin
    )
    FileOps.writeString(p.resolve("flix.toml").normalize(), toml)

    // Override main file
    val main =
      """
        |pub def main(): Unit \ IO =
        |    println(Upgr.entrypoint(42))
        |""".stripMargin
    FileOps.writeString(p.resolve("src/Main.flix").normalize(), main)

    val bootstrap = Bootstrap.bootstrap(p, PkgTestUtils.gitHubToken)(Formatter.getDefault, System.out).unsafeGet
    bootstrap.lockEffects(PkgTestUtils.mkFlix).unsafeGet

    // Perform upgrade by overriding manifest
    val tomlUpgr = PkgTestUtils.mkTomlWithDeps(
      s"""
         |"github:$pkgAuthor/$pkgName" = "$vNew"
         |""".stripMargin
    )
    FileOps.writeString(p.resolve("flix.toml").normalize(), tomlUpgr)
    // Delete old files
    FileOps.delete(p.resolve(s"lib/github/$pkgAuthor/$pkgName/$vOld/$pkgName-$vOld.toml")).unsafeGet
    FileOps.delete(p.resolve(s"lib/github/$pkgAuthor/$pkgName/$vOld/$pkgName-$vOld.fpkg")).unsafeGet

    val bootstrapUpgr = Bootstrap.bootstrap(p, PkgTestUtils.gitHubToken)(Formatter.getDefault, System.out).unsafeGet

    bootstrapUpgr.checkEffects(PkgTestUtils.mkFlix) match {
      case Result.Err(BootstrapError.EffectUpgradeError(_)) => succeed
      case Result.Err(e) => fail(e.message(Formatter.getDefault))
      case Result.Ok(()) => fail("expected effect upgrade error")
    }
  }

  test("eff-check on effect downgrade is ok") {
    // Version 0.1.0 of the dependency has signature `Int32 -> Int32`.
    // Version 0.1.1 of the dependency has signature `Int32 -> Int32 \ IO`.
    // We downgrade from `Int32 -> Int32 \ IO` to `Int32 -> Int32`
    // and assert that it succeeds.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet // Unsafe get to crash in case of error

    val pkgAuthor = "jaschdoc"
    val pkgName = "flix-test-pkg-eff-upgrade"
    val vSafe = "0.1.0"
    val vUnsafe = "0.1.1"

    // Override manifest
    val toml = PkgTestUtils.mkTomlWithDeps(
      s"""
         |"github:$pkgAuthor/$pkgName" = "$vUnsafe"
         |""".stripMargin
    )
    FileOps.writeString(p.resolve("flix.toml").normalize(), toml)

    // Override main file
    val main =
      """
        |pub def main(): Unit \ IO =
        |    println(Upgr.entrypoint(42))
        |""".stripMargin
    FileOps.writeString(p.resolve("src/Main.flix").normalize(), main)

    val bootstrap = Bootstrap.bootstrap(p, PkgTestUtils.gitHubToken)(Formatter.getDefault, System.out).unsafeGet
    bootstrap.lockEffects(PkgTestUtils.mkFlix).unsafeGet

    // Perform upgrade by overriding manifest
    val tomlUpgr = PkgTestUtils.mkTomlWithDeps(
      s"""
         |"github:$pkgAuthor/$pkgName" = "$vSafe"
         |""".stripMargin
    )
    FileOps.writeString(p.resolve("flix.toml").normalize(), tomlUpgr)
    // Delete old files
    FileOps.delete(p.resolve(s"lib/github/$pkgAuthor/$pkgName/$vUnsafe/$pkgName-$vUnsafe.toml")).unsafeGet
    FileOps.delete(p.resolve(s"lib/github/$pkgAuthor/$pkgName/$vUnsafe/$pkgName-$vUnsafe.fpkg")).unsafeGet

    val bootstrapUpgr = Bootstrap.bootstrap(p, PkgTestUtils.gitHubToken)(Formatter.getDefault, System.out).unsafeGet

    assert(bootstrapUpgr.checkEffects(PkgTestUtils.mkFlix) == Result.Ok(()))
  }

  private def calcHash(p: Path): String = {
    val sha = MessageDigest.getInstance("SHA-256")
    Using(new DigestInputStream(Files.newInputStream(p), sha)) { input =>
      input.readNBytes(8192)
      sha.digest.map("%02x".format(_)).mkString
    }.get
  }

  /** Returns the names of all entries in the zip archive at `p`. */
  private def entryNamesOf(p: Path): Set[String] =
    Using(new ZipFile(p.toFile))(_.entries().asScala.map(_.getName).toSet).get

  /**
    * Returns a Flix instance that generates the same class names for the same program.
    *
    * A few generated names carry a symbol counter, and the order symbols are allocated in
    * depends on how the work was scheduled across threads. Two builds of one program otherwise
    * differ in the *names* of a handful of closure classes, which is indistinguishable from a
    * stale product when comparing class directories.
    */
  private def mkDeterministicFlix: Flix = {
    val flix = PkgTestUtils.mkFlix
    flix.setOptions(flix.options.copy(threads = 1))
  }

  /** Describes how two sets of file names differ, for an assertion message. */
  private def diffOf(expected: Set[String], actual: Set[String]): String =
    s"only in the first: ${(expected -- actual).toList.sorted}, only in the second: ${(actual -- expected).toList.sorted}"

  /**
    * Returns the output directory of the build mode `build` in the project at `p`.
    *
    * Each mode owns one - `build/development/` and `build/production/` - so that a development build and
    * a production build do not invalidate each other.
    */
  private def outputDirOf(p: Path, build: Build): Path =
    p.resolve("build").resolve(build.directoryName).normalize()

  /** Returns the class directory of the build mode `build` in the project at `p`. */
  private def classDirOf(p: Path, build: Build): Path = outputDirOf(p, build).resolve("class").normalize()

  /** Returns the build manifest of the build mode `build` in the project at `p`. */
  private def manifestFileOf(p: Path, build: Build): Path =
    outputDirOf(p, build).resolve(BuildManifest.FileName).normalize()

  /** Returns the class files of the build mode `build` in the project at `p`, relative to its class directory. */
  private def classFilesOf(p: Path, build: Build): Set[String] = {
    val classDir = classDirOf(p, build)
    FileOps.getFilesWithExtIn(classDir, "class", Int.MaxValue)
      .map(f => classDir.relativize(f.normalize()).toString.replace('\\', '/'))
      .toSet
  }

  /** Returns the total size of the class files of the build mode `build` in the project at `p`. */
  private def classBytesOf(p: Path, build: Build): Long =
    FileOps.getFilesWithExtIn(classDirOf(p, build), "class", Int.MaxValue).map(Files.size).sum

  /**
    * Returns the class files of the build mode `build` in the project at `p` with the time each
    * was last written.
    *
    * A rewritten file is what tells a wipe-and-recompile apart from a build that left the file
    * alone: both end with the same name on disk, and only the timestamp says which happened.
    */
  private def classStampsOf(p: Path, build: Build): Map[String, Long] = {
    val classDir = classDirOf(p, build)
    FileOps.getFilesWithExtIn(classDir, "class", Int.MaxValue).map { f =>
      classDir.relativize(f.normalize()).toString.replace('\\', '/') -> f.toFile.lastModified()
    }.toMap
  }

  /**
    * Writes a two-file project whose `main` calls into a second module, or - with `extra` unset -
    * one whose `main` does not, so that the second module's products are no longer required.
    *
    * Both files are always written, and never created or deleted: `Bootstrap` scans for sources
    * once, when it is created, so a file added afterwards is invisible to it until the next scan.
    * Call this before `Bootstrap.bootstrap`.
    *
    * The extra def recurses so that it reaches the back end as a def of its own. A def the
    * optimizer can fold into its one call site produces no class file, and then there is no
    * product for the second build to lose.
    */
  private def writeSources(p: Path, extra: Boolean): Unit = {
    val extraSource =
      if (extra)
        """mod Extra {
          |    pub def countdown(x: Int32): String =
          |        if (x <= 0) "done" else countdown(x - 1)
          |}
          |""".stripMargin
      else
        """mod Extra {
          |}
          |""".stripMargin
    val main =
      if (extra)
        """def main(): Unit \ IO =
          |    println(Extra.countdown(3))
          |""".stripMargin
      else
        """def main(): Unit \ IO =
          |    println("done")
          |""".stripMargin

    FileOps.writeString(p.resolve("src").resolve("Extra.flix").normalize(), extraSource)
    FileOps.writeString(p.resolve("src").resolve("Main.flix").normalize(), main)
  }

}
