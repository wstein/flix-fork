package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.util.Result
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

/**
  * What a manifest has to survive being written and read back.
  *
  * The launch spec is here rather than in a runner test because it is the half a *client* reads,
  * and a client is not in this repository: a field that round-trips wrong is discovered by an IDE
  * failing to start a program, with nothing on this side reporting anything.
  */
class TestBuildManifest extends AnyFunSuite {

  private def spec(mainClass: Option[String] = Some("Main")) =
    LaunchSpec("/opt/jdk/bin/java", mainClass, List("/p/build/development/class", "/p/lib/external/x.jar"))

  private def manifest(launch: LaunchSpec = spec()) =
    BuildManifest("fp", "ffp", List("Main.class"), List("src/Main.flix"), "sd", launch.mainClass.isDefined, launch)

  private def roundTrip(m: BuildManifest): Option[BuildManifest] = {
    val dir = Files.createTempDirectory("flix-manifest")
    val file = BuildManifest.fileIn(dir)
    try {
      assert(BuildManifest.write(file, m).isInstanceOf[Result.Ok[?, ?]])
      BuildManifest.read(file)
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }

  /** The written JSON, for the assertions that are about the file rather than about the value. */
  private def written(m: BuildManifest): org.json4s.JValue = {
    val dir = Files.createTempDirectory("flix-manifest")
    val file = BuildManifest.fileIn(dir)
    try {
      val _ = BuildManifest.write(file, m)
      JsonMethods.parse(Files.readString(file))
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }

  test("launch.roundTrip") {
    assert(roundTrip(manifest()).contains(manifest()))
  }

  test("launch.roundTrip.noMain") {
    // A library has no entry point and still records where its classes are: a client running tests
    // asks the same question, and an absent `mainClass` is the only part of the answer that changes.
    val m = manifest(spec(mainClass = None))
    val read = roundTrip(m)
    assert(read.contains(m))
    assert(read.exists(_.launch.runtimeClasspath.nonEmpty))
  }

  test("launch.noMain.omitsTheKey") {
    // Written as an absent key rather than as null, so that a reader testing for the key and a
    // reader testing for a string agree about a build with no entry point.
    assert((written(manifest(spec(mainClass = None))) \ "launch" \ "mainClass") == org.json4s.JNothing)
  }

  test("launch.classpathOrderSurvives") {
    // A classpath is ordered: the class directory has to precede the jars, or a dependency that
    // ships a class of the same name shadows the program's own.
    val ordered = LaunchSpec("/opt/jdk/bin/java", Some("Main"), List("/a", "/b", "/c"))
    assert(roundTrip(manifest(ordered)).map(_.launch.runtimeClasspath).contains(List("/a", "/b", "/c")))
  }

  test("debugBuildId.includesSourcesAndNonSourceInputs") {
    val m = manifest()

    assert(m.debugBuildId == "fp:sd")
    assert(m.copy(fingerprint = "other").debugBuildId != m.debugBuildId)
    assert(m.copy(sourcesDigest = "other").debugBuildId != m.debugBuildId)
  }

  test("read.rejectsAManifestWithNoLaunch") {
    // Removed from the JSON *structurally*, so the manifest that reaches `read` is well formed and
    // merely missing the field. Deleting the text of the key would leave the file unparseable, and
    // this would then pass for a reason that has nothing to do with the launch spec.
    assert(readingWithout("launch").isEmpty)
  }

  test("read.rejectsAnOlderFormat") {
    // The format version is what stops a v3 manifest -- which has no launch spec at all -- being
    // read as a v4 one that merely lost the field.
    assert(readingWith("formatVersion", org.json4s.JInt(3)).isEmpty)
  }

  /** Reads a manifest written with `field` removed from its JSON object. */
  private def readingWithout(field: String): Option[BuildManifest] =
    readingWith(field, org.json4s.JNothing)

  /** Reads a manifest written with `field` replaced by `value` (`JNothing` removes it). */
  private def readingWith(field: String, value: org.json4s.JValue): Option[BuildManifest] = {
    val edited = written(manifest()).transformField { case (name, _) if name == field => (field, value) }
    val dir = Files.createTempDirectory("flix-manifest")
    val file = BuildManifest.fileIn(dir)
    try {
      Files.writeString(file, JsonMethods.pretty(JsonMethods.render(edited)))
      BuildManifest.read(file)
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }
}
