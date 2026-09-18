package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.api.{Bootstrap, BootstrapError, BuildManifest, Version}
import ca.uwaterloo.flix.util.{Build, FileOps, Formatter, Result}
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.EnumerationHasAsScala

@DoNotDiscover
class TestBootstrap extends AnyFunSuite {

  private val ProjectPrefix: String = "flix-project-"

  test("init") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
  }

  test("init creates a valid manifest from a directory with spaces") {
    val p = Files.createTempDirectory("flix project-")
    Bootstrap.init(p)(System.out).unsafeGet
    Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
  }

  test("directory mode uses a portable artifact basename") {
    val p = Files.createTempDirectory("flix project-")
    FileOps.writeString(p.resolve("Main.flix"), "def main(): Unit = ()")

    val bootstrap = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    bootstrap.buildJar(PkgTestUtils.mkFlix(bootstrap)).unsafeGet

    val name = PackageName.normalize(p.getFileName.toString)
    assert(Files.exists(p.resolve("artifact").resolve(s"$name.jar")))
  }

  test("check") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.check(PkgTestUtils.mkFlix(b))
  }

  test("build writes classes and manifest to build/development by default") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix(b)).unsafeGet

    val devDir = Bootstrap.getDevelopmentDirectory(p)
    val devClassDir = Bootstrap.getDevelopmentClassDirectory(p)
    val manifestFile = Bootstrap.getBuildManifestFile(p, Build.Development)

    assert(Files.exists(devDir))
    assert(Files.exists(devClassDir))
    assert(Files.exists(devClassDir.resolve("Main.class")))
    assert(Files.exists(manifestFile))

    val manifest = BuildManifest.read(manifestFile).get
    assert(manifest.hasMain)
    assert(manifest.launch.mainClass.contains("Main"))
    assert(manifest.launch.runtimeClasspath.head == devClassDir.toAbsolutePath.normalize().toString)
  }

  test("development manifest launches the emitted program in a fresh JVM") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix(b)).unsafeGet

    val manifest = BuildManifest.read(Bootstrap.getBuildManifestFile(p, Build.Development)).getOrElse(fail("Missing build manifest."))
    val main = manifest.launch.mainClass.getOrElse(fail("Expected a main class."))
    val process = new ProcessBuilder(manifest.launch.java, "-cp", manifest.launch.runtimeClasspath.mkString(java.io.File.pathSeparator), main)
      .directory(p.toFile)
      .redirectErrorStream(true)
      .start()
    assert(process.waitFor() == 0, s"External JVM failed: ${new String(process.getInputStream.readAllBytes())}")
  }

  test("build with inMemory option writes nothing to disk") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix(b)
    flix.setOptions(flix.options.copy(inMemory = true))
    b.build(flix).unsafeGet

    val buildDir = p.resolve("./build/").normalize()
    assert(!Files.exists(buildDir))
  }

  test("buildIfNeeded compiles the first time and skips an unchanged rebuild") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val first = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(first.buildIfNeeded(PkgTestUtils.mkFlix(first)).unsafeGet, "the first build has nothing recorded yet")

    val second = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(!second.buildIfNeeded(PkgTestUtils.mkFlix(second)).unsafeGet, "the sources and options did not change")
  }

  test("buildIfNeeded recompiles after a source changes") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val first = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    first.buildIfNeeded(PkgTestUtils.mkFlix(first)).unsafeGet

    FileOps.writeString(p.resolve("src/Main.flix"), "def main(): Unit = println(\"changed\")")

    val second = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(second.buildIfNeeded(PkgTestUtils.mkFlix(second)).unsafeGet, "a changed source must not be reported as current")
  }

  test("buildIfNeeded recompiles when a recorded product is missing") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val first = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    first.buildIfNeeded(PkgTestUtils.mkFlix(first)).unsafeGet

    Files.delete(Bootstrap.getDevelopmentClassDirectory(p).resolve("Main.class"))

    val second = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(second.buildIfNeeded(PkgTestUtils.mkFlix(second)).unsafeGet, "a missing product must not be reported as current")
  }

  test("buildIfNeeded recompiles when a stray class file appears") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet

    val first = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    first.buildIfNeeded(PkgTestUtils.mkFlix(first)).unsafeGet

    Files.write(Bootstrap.getDevelopmentClassDirectory(p).resolve("Stray.class"), Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte))

    val second = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    assert(second.buildIfNeeded(PkgTestUtils.mkFlix(second)).unsafeGet, "a stray class file must not be reported as current")
  }

  test("debug build publishes source-to-class index and a normal rebuild removes it") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val debug = PkgTestUtils.mkFlix(b)
    debug.setOptions(debug.options.copy(xdebug = true))
    val index = Bootstrap.getDevelopmentDirectory(p).resolve("debug-index.json")
    val scopes = Bootstrap.getDevelopmentDirectory(p).resolve("debug-scopes.json")
    val calls = Bootstrap.getDevelopmentDirectory(p).resolve("debug-calls.json")

    b.build(debug).unsafeGet
    assert(Files.exists(index))
    assert(Files.exists(scopes))
    assert(Files.exists(calls))
    assert(Files.readString(index).contains("\"formatVersion\":1"))
    assert(Files.readString(scopes).contains("\"formatVersion\":2"))
    assert(Files.readString(calls).contains("\"formatVersion\":2"))
    assert(Files.readString(calls).contains("\"sources\""))

    b.build(PkgTestUtils.mkFlix(b)).unsafeGet
    assert(!Files.exists(index))
    assert(!Files.exists(scopes))
    assert(!Files.exists(calls))
  }

  test("build reconciles obsolete class files in build/development") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val devClassDir = Bootstrap.getDevelopmentClassDirectory(p)
    Files.createDirectories(devClassDir)
    val staleClass = devClassDir.resolve("Stale.class")
    Files.write(staleClass, Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte))

    b.build(PkgTestUtils.mkFlix(b)).unsafeGet

    assert(Files.exists(devClassDir.resolve("Main.class")))
    assert(!Files.exists(staleClass))
  }

  test("build removes classes from a previous source after that source is removed") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val main = p.resolve("src/Main.flix")
    FileOps.writeString(main,
      """def obsolete(n: Int32): Int32 = if (n == 0) 0 else obsolete(n - 1)
        |def main(): Unit \ IO = println(obsolete(1))
        |""".stripMargin)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val classDir = Bootstrap.getDevelopmentClassDirectory(p)

    b.build(PkgTestUtils.mkFlix(b)).unsafeGet
    val obsolete = classDir.resolve("Def$obsolete.class")
    assert(Files.exists(obsolete), s"Expected the first build to emit $obsolete")

    FileOps.writeString(main, "def main(): Unit \\ IO = println(1)\n")
    b.build(PkgTestUtils.mkFlix(b)).unsafeGet

    assert(!Files.exists(obsolete), "Expected the removed definition's prior class file to be reconciled away.")
    assert(Files.exists(classDir.resolve("Main.class")))
  }

  test("build preserves non-class files in the class output directory") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val note = Bootstrap.getDevelopmentClassDirectory(p).resolve("README.txt")
    Files.createDirectories(note.getParent)
    Files.writeString(note, "user note")

    b.build(PkgTestUtils.mkFlix(b)).unsafeGet

    assert(Files.exists(note))
  }

  test("clean removes build/development directory and manifest") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.build(PkgTestUtils.mkFlix(b)).unsafeGet

    val buildDir = p.resolve("./build/").normalize()
    assert(Files.exists(buildDir))

    b.clean().unsafeGet
    assert(!Files.exists(buildDir))
  }

  test("build-classes") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildClasses(PkgTestUtils.mkFlix(b))

    val classDir = p.resolve("./build/class/").normalize()
    val classFiles = FileOps.getFilesIn(classDir, Int.MaxValue)
    assert(classFiles.nonEmpty)
    assert(classFiles.forall(FileOps.isClassFile))
    assert(Files.exists(classDir.resolve("Main.class")))
  }

  test("build-classes reconciles obsolete class files in build/class") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val classDir = Bootstrap.getClassDirectory(p)
    Files.createDirectories(classDir)
    val staleClass = classDir.resolve("Stale.class")
    Files.write(staleClass, Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte))

    b.buildClasses(PkgTestUtils.mkFlix(b)).unsafeGet

    assert(Files.exists(classDir.resolve("Main.class")))
    assert(!Files.exists(staleClass))
  }

  test("build-jar") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix(b)
    b.build(flix)
    b.buildJar(flix)

    val packageName = p.getFileName.toString
    val jarPath = p.resolve("artifact").resolve(packageName + ".jar")
    assert(Files.exists(jarPath))
    assert(jarPath.getFileName.toString.startsWith(ProjectPrefix))
  }

  test("build-jar generates ZIP entries with fixed time") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix(b)
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

    val b1 = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix1 = PkgTestUtils.mkFlix(b1)
    // Use 1 thread for deterministic symbols
    flix1.setOptions(flix1.options.copy(threads = 1))
    b1.buildJar(flix1)
    val hash1 = calcHash(jarPath)

    val b2 = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix2 = PkgTestUtils.mkFlix(b2)
    // Use 1 thread for deterministic symbols
    flix2.setOptions(flix2.options.copy(threads = 1))
    b2.buildJar(flix2)
    val hash2 = calcHash(jarPath)

    assert(
      hash1 == hash2,
      s"Two file hashes are not same: $hash1 and $hash2")
  }

  test("build-pkg") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildPkg(PkgTestUtils.mkFlix(b))(Formatter.getDefault)

    val packageName = p.getFileName.toString
    val packagePath = p.resolve("artifact").resolve(packageName + ".fpkg")
    assert(Files.exists(packagePath))
    assert(packagePath.getFileName.toString.startsWith(ProjectPrefix))
  }

  test("build-pkg generates ZIP entries with fixed time") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildPkg(PkgTestUtils.mkFlix(b))(Formatter.getDefault)

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

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix(b)
    b.build(flix)

    b.buildPkg(flix)(Formatter.getDefault)

    val hash1 = calcHash(packagePath)

    b.buildPkg(flix)(Formatter.getDefault)

    val hash2 = calcHash(packagePath)

    assert(
      hash1 == hash2,
      s"Two file hashes are not same: $hash1 and $hash2")
  }

  test("artifacts use the manifest package name instead of the project directory name") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val directoryName = p.getFileName.toString
    val packageName = "stable-package-name"
    FileOps.writeString(p.resolve("flix.toml"), Files.readString(p.resolve("flix.toml")).replace(directoryName, packageName))

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix(b)
    b.buildJar(flix).unsafeGet
    b.buildPkg(flix)(Formatter.getDefault).unsafeGet

    val artifactDirectory = p.resolve("artifact")
    assert(Files.exists(artifactDirectory.resolve(s"$packageName.jar")))
    assert(Files.exists(artifactDirectory.resolve(s"$packageName.fpkg")))
    assert(!Files.exists(artifactDirectory.resolve(s"$directoryName.jar")))
    assert(!Files.exists(artifactDirectory.resolve(s"$directoryName.fpkg")))
  }

  test("artifacts retain their manifest name when the project directory is renamed") {
    val original = Files.createTempDirectory(ProjectPrefix)
    val packageName = "stable-package-name"
    Bootstrap.init(original)(System.out).unsafeGet
    val directoryName = original.getFileName.toString
    FileOps.writeString(original.resolve("flix.toml"), Files.readString(original.resolve("flix.toml")).replace(directoryName, packageName))

    val beforeRename = Bootstrap.bootstrap(original, None)(Formatter.getDefault, System.out).unsafeGet
    val flix = PkgTestUtils.mkFlix(beforeRename)
    beforeRename.buildJar(flix).unsafeGet
    beforeRename.buildPkg(flix)(Formatter.getDefault).unsafeGet
    val packageHash = calcHash(original.resolve("artifact").resolve(s"$packageName.fpkg"))

    val renamed = original.resolveSibling(s"${ProjectPrefix}renamed-${System.nanoTime()}")
    Files.move(original, renamed)

    val afterRename = Bootstrap.bootstrap(renamed, None)(Formatter.getDefault, System.out).unsafeGet
    afterRename.buildPkg(PkgTestUtils.mkFlix(afterRename))(Formatter.getDefault).unsafeGet
    Files.createDirectories(renamed.resolve("lib"))
    afterRename.buildFatJar(PkgTestUtils.mkFlix(afterRename)).unsafeGet

    val artifactDirectory = renamed.resolve("artifact")
    val packageFile = artifactDirectory.resolve(s"$packageName.fpkg")
    assert(calcHash(packageFile) == packageHash)
    assert(Files.exists(artifactDirectory.resolve(s"$packageName.jar")))
    assert(!Files.exists(artifactDirectory.resolve(s"${renamed.getFileName}.fpkg")))
    assert(!Files.exists(artifactDirectory.resolve(s"${renamed.getFileName}.jar")))
    assert(afterRename.releaseArtifacts == List(packageFile, renamed.resolve("flix.toml")))
  }

  test("build-pkg refuses a project that does not check") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    // A public module in a file whose path does not match its name.
    Files.writeString(p.resolve("src").resolve("Bar.flix"), "pub mod Foo { pub def f(): Int32 = 1 }")

    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    val result = b.buildPkg(PkgTestUtils.mkFlix(b))(Formatter.getDefault)
    assert(result.toOption.isEmpty)

    val packageName = p.getFileName.toString
    val packagePath = p.resolve("artifact").resolve(packageName + ".fpkg")
    assert(!Files.exists(packagePath))
  }

  test("run") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.run(PkgTestUtils.mkFlix(b), Array("arg0", "arg1"))
  }

  test("test") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.test(PkgTestUtils.mkFlix(b))
  }

  test("test filters fully-qualified symbols") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    FileOps.writeString(
      p.resolve("src/Main.flix"),
      """mod Suite {
        |    use Assert.fail
        |
        |    @Test
        |    def selected(): Unit = ()
        |
        |    @Test
        |    def excluded(): Unit \ Assert = fail("excluded")
        |}
        |""".stripMargin,
    )
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet

    val selected = b.test(PkgTestUtils.mkFlix(b), List("Suite\\.selected".r))
    assert(selected.toOption.nonEmpty, selected.toString)
    val excluded = b.test(PkgTestUtils.mkFlix(b), List("Suite\\.excluded".r))
    assert(excluded.toOption.isEmpty, excluded.toString)
    val unmatched = b.test(PkgTestUtils.mkFlix(b), List("Missing\\..*".r))
    assert(unmatched.toOption.nonEmpty, unmatched.toString)
  }

  test("clean-command-should-remove-class-files-and-directories-if-compiled-previously") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildClasses(PkgTestUtils.mkFlix(b))
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

  test("clean-should-error-on-unexpected-file") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out).unsafeGet
    val b = Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out).unsafeGet
    b.buildClasses(PkgTestUtils.mkFlix(b))
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
    val flix = PkgTestUtils.mkFlix(bootstrap)
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
    bootstrap.lockEffects(PkgTestUtils.mkFlix(bootstrap)).unsafeGet

    assert(bootstrap.checkEffects(PkgTestUtils.mkFlix(bootstrap)) == Result.Ok(()))
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
    bootstrap.lockEffects(PkgTestUtils.mkFlix(bootstrap)).unsafeGet

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

    bootstrapUpgr.checkEffects(PkgTestUtils.mkFlix(bootstrapUpgr)) match {
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
    bootstrap.lockEffects(PkgTestUtils.mkFlix(bootstrap)).unsafeGet

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

    assert(bootstrapUpgr.checkEffects(PkgTestUtils.mkFlix(bootstrapUpgr)) == Result.Ok(()))
  }

  test("flix-version.current") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    // N.B.: `init` writes the current version of Flix to `flix.toml`.
    Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out) match {
      case Result.Ok(_) => // Expected.
      case Result.Err(e) => fail(s"Expected success, but got: ${e.message(Formatter.NoFormatter)}")
    }
  }

  test("flix-version.older") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    FileOps.writeString(p.resolve("flix.toml").normalize(), mkTomlWithFlixVersion("0.1.0"))
    Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out) match {
      case Result.Ok(_) => // Expected: an older required version is fine.
      case Result.Err(e) => fail(s"Expected success, but got: ${e.message(Formatter.NoFormatter)}")
    }
  }

  test("flix-version.newer") {
    val p = Files.createTempDirectory(ProjectPrefix)
    Bootstrap.init(p)(System.out)
    FileOps.writeString(p.resolve("flix.toml").normalize(), mkTomlWithFlixVersion("999.0.0"))
    Bootstrap.bootstrap(p, None)(Formatter.getDefault, System.out) match {
      case Result.Ok(_) => fail("Expected BootstrapError.FlixVersionTooOld, but bootstrap succeeded.")
      case Result.Err(e: BootstrapError.FlixVersionTooOld) =>
        assert(e.required == SemVer(999, 0, 0))
        assert(e.current == SemVer.ofVersion(Version.CurrentVersion))
      case Result.Err(e) => fail(s"Expected BootstrapError.FlixVersionTooOld, but got: ${e.message(Formatter.NoFormatter)}")
    }
  }

  test("flix-version.examples") {
    val current = SemVer.ofVersion(Version.CurrentVersion)
    val manifests = FileOps.getFilesIn(Path.of("examples"), Int.MaxValue).filter(_.getFileName.toString == "flix.toml")
    assert(manifests.nonEmpty, "Expected to find at least one 'flix.toml' under 'examples'.")
    for (manifest <- manifests) {
      val required = ManifestParser.parse(manifest).unsafeGet.flix
      assert(required <= current, s"'$manifest' requires Flix $required, but the current version is $current.")
    }
  }

  /**
    * Returns a `flix.toml` without dependencies that requires the given version `v` of Flix.
    */
  private def mkTomlWithFlixVersion(v: String): String = {
    s"""
       |[package]
       |name = "test"
       |description = "test"
       |version = "0.1.0"
       |flix = "$v"
       |authors = ["flix"]
       |""".stripMargin
  }

  private def calcHash(p: Path): String = {
    val sha = MessageDigest.getInstance("SHA-256")
    sha.digest(Files.readAllBytes(p)).map("%02x".format(_)).mkString
  }

}
