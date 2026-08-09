package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.api.{Bootstrap, BootstrapError, BuildManifest, Flix, Version}
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

  test("build-jar deletes the class files the sources no longer produce") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    writeSources(p, extra = true)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val jarPath = p.resolve("artifact").resolve(p.getFileName.toString + ".jar")

    b.buildJar(mkDeterministicFlix).unsafeGet
    val before = classFilesOf(p)

    // Remove the module main was calling. Its class files are products of a build that no longer
    // describes the project, and a build that only overwrites what it generates would leave them
    // behind - on the classpath, and in the jar.
    writeSources(p, extra = false)
    b.buildJar(mkDeterministicFlix).unsafeGet
    val after = classFilesOf(p)

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
    val incrementalClasses = classFilesOf(p)
    val incrementalEntries = entryNamesOf(jarPath)

    b.buildJar(mkDeterministicFlix, clean = true).unsafeGet

    assert(
      classFilesOf(p) == incrementalClasses,
      s"the class directory differs from a clean build's: ${diffOf(incrementalClasses, classFilesOf(p))}")
    assert(
      entryNamesOf(jarPath) == incrementalEntries,
      s"the jar differs from a clean build's: ${diffOf(incrementalEntries, entryNamesOf(jarPath))}")
  }

  test("build-jar records what it produced in the build manifest") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildJar(PkgTestUtils.mkFlix).unsafeGet

    val manifest = BuildManifest.read(p.resolve("build").resolve(BuildManifest.FileName)) match {
      case Some(m) => m
      case None => fail("the build wrote no readable build manifest")
    }

    // The manifest is the account of the class directory that the next build reconciles against.
    // One that disagrees with the directory would have it delete products that are in use, or
    // keep ones that are not.
    assert(manifest.products.toSet == classFilesOf(p), "the manifest disagrees with the class directory")
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
    val manifestFile = p.resolve("build").resolve(BuildManifest.FileName)
    FileOps.writeString(manifestFile, """{"formatVersion": 999, "fingerprint": "?"}""")
    assert(BuildManifest.read(manifestFile).isEmpty, "a manifest of an unknown format was read anyway")

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
    // The build manifest is the one file in the build directory that is not a class file, and
    // 'clean' has to remove it with the products it describes.
    val manifestFile = buildDir.resolve(BuildManifest.FileName).normalize()
    assert(Files.exists(manifestFile), "the build wrote no build manifest")
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

  /** Returns the class files in the class directory of the project at `p`, relative to it. */
  private def classFilesOf(p: Path): Set[String] = {
    val classDir = p.resolve("build").resolve("class").normalize()
    FileOps.getFilesWithExtIn(classDir, "class", Int.MaxValue)
      .map(f => classDir.relativize(f.normalize()).toString.replace('\\', '/'))
      .toSet
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
