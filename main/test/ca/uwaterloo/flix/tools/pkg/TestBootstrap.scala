package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.api.{Bootstrap, BootstrapError, Version}
import ca.uwaterloo.flix.util.{FileOps, Formatter, Result}
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.{EnumerationHasAsScala, ListHasAsScala}
import scala.util.Using

@DoNotDiscover
class TestBootstrap extends AnyFunSuite {

  private val ProjectPrefix: String = "flix-project-"

  test("init") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
  }

  test("init writes a version stock Flix can read") {
    // `flix.toml` states the compiler version in three numbers and nothing else. This fork's own
    // parser was widened to tolerate a `+fork...` qualifier, which hides the problem locally --
    // upstream Flix has not been, so a manifest generated here would be unreadable there, and a
    // package published from it unusable by anyone on a stock compiler.
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val declared = Files.readAllLines(p.resolve("flix.toml")).asScala
      .map(_.trim)
      .find(_.startsWith("flix"))
      .map(_.dropWhile(_ != '=').drop(1).trim.stripPrefix("\"").stripSuffix("\""))
      .getOrElse(fail("The generated manifest declares no Flix version."))

    assert(declared.matches("""\d+\.\d+\.\d+"""),
      s"init wrote '$declared'; a manifest version must be three numbers and nothing else.")
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
    assert(manifest.license.isEmpty)
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
    val stale = p.resolve("build").resolve("class").resolve("Stale.class")
    Files.write(stale, Array[Byte](0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte))
    assert(Files.exists(stale))

    b.build(PkgTestUtils.mkFlix)

    assert(!Files.exists(stale), "stale class file survived the rebuild")
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

    // Plant a class file that no build would produce.
    val classDir = p.resolve("build").resolve("class")
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

  test("build-jar refuses to delete a non-class file in the class directory") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(PkgTestUtils.mkFlix)

    // A file that is not a class file must stop the build rather than be deleted.
    val precious = p.resolve("build").resolve("class").resolve("precious.txt")
    Files.write(precious, "do not delete me".getBytes)

    val result = b.buildJar(PkgTestUtils.mkFlix)

    assert(result.isInstanceOf[Result.Err[_, _]], "expected build-jar to fail on an unexpected file")
    assert(Files.exists(precious), "build-jar deleted a file that was not a class file")
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
    val buildFiles = FileOps.getFilesIn(buildDir, Int.MaxValue)
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

}
