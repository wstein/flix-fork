/*
 * Copyright 2026 Werner Stein
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
package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.runtime.TestFn
import ca.uwaterloo.flix.tools.Tester
import ca.uwaterloo.flix.util.Result
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods
import org.json4s.{JArray, JBool, JInt, JString, JValue, jvalue2monadic}

import java.nio.file.{Files, Path}

/**
  * Which tests a build produced, and where their compiled shims ended up.
  *
  * ==Why this is not in [[BuildManifest]]==
  *
  * Because it is a description of the *program*, and that manifest is a record of products and inputs.
  * The distinction is not filing: a wrong `BuildManifest` costs a rebuild, while a wrong test table
  * means the tests someone believes ran did not. Keeping them apart makes the second artifact's
  * weaker standing visible, and it is also forced by a fact about the compiler -- `flix build` runs
  * with `loadClassFiles = false` and so knows no test shims at all, and a build server's compile
  * deliberately does the same. Only a run that loaded the classes can write this, which is exactly
  * the run that has the information.
  *
  * ==What makes it trustworthy enough to use==
  *
  * Nothing about it, on its own. It is usable only when the fingerprint and the source digest match
  * the build that is on disk -- the same conditions a skipped build needs -- *and* every method it
  * names still resolves in the class files. That last check is what distinguishes this from a cache:
  * the record is not believed, it is confirmed against the products before a single test runs.
  *
  * @param fingerprint   the [[BuildManifest.fingerprintOf]] hash of the build that wrote this.
  * @param sourcesDigest the [[BuildManifest.digestOfSources]] hash of the sources it read.
  * @param tests         every test the build compiled, whether or not it is skipped.
  */
case class TestManifest(fingerprint: String, sourcesDigest: String, tests: List[TestManifest.RecordedTest])

object TestManifest {

  /**
    * One test: what to call it, where its shim is, and where it is written.
    *
    * @param name       the fully qualified Flix name, which is what a filter matches and a client shows.
    * @param className  the binary name of the generated class carrying the shim.
    * @param methodName the shim's method name.
    * @param skip       whether it is marked `@Skip`.
    * @param file       the source file, as the compiler named it, for a clickable result.
    */
  case class RecordedTest(name: String,
                          className: String,
                          methodName: String,
                          skip: Boolean,
                          file: String,
                          startLine: Int,
                          startCol: Int,
                          endLine: Int,
                          endCol: Int)

  /** The name of the file, relative to a build mode's output directory. */
  val FileName: String = "tests.json"

  /**
    * The format version.
    *
    * A record in any other version is discarded rather than migrated, which costs one compile.
    */
  private val FormatVersion: Int = 1

  /** Returns the path of the record inside the output directory `outputDir`. */
  def fileIn(outputDir: Path): Path = outputDir.resolve(FileName).normalize()

  /** Returns the record describing `tests`, as a build with `fingerprint` over `sourcesDigest` found them. */
  def of(fingerprint: String, sourcesDigest: String, tests: Iterable[TestFn]): TestManifest = {
    val recorded = tests.toList.map { fn =>
      val loc = fn.sym.loc
      RecordedTest(
        fn.sym.toString, fn.className, fn.methodName, fn.skip,
        loc.source.name, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
    }
    TestManifest(fingerprint, sourcesDigest, recorded.sortBy(_.name))
  }

  /** Returns the identity a runner reports `recorded` under. */
  def idOf(recorded: RecordedTest): Tester.TestId =
    Tester.TestId(recorded.name, Some(Tester.TestLocation(
      recorded.file, recorded.startLine, recorded.startCol, recorded.endLine, recorded.endCol)))

  /**
    * Reads the record at `path`.
    *
    * `None` if there is none, or if it cannot be read as one of this format version. That is not an
    * error: it means nothing is known about the tests of the build on disk, which the caller answers by
    * compiling.
    */
  def read(path: Path): Option[TestManifest] = {
    if (!Files.isRegularFile(path)) {
      return None
    }
    val json =
      try JsonMethods.parse(Files.readString(path))
      catch { case _: Exception => return None }

    (json \ "formatVersion") match {
      case JInt(v) if v == BigInt(FormatVersion) => ()
      case _ => return None
    }

    for {
      fingerprint <- stringOf(json \ "fingerprint")
      digest <- stringOf(json \ "sourcesDigest")
      tests <- testsOf(json \ "tests")
    } yield TestManifest(fingerprint, digest, tests)
  }

  /** Writes `manifest` to `path`, creating the parent directory if needed. */
  def write(path: Path, manifest: TestManifest): Result[Unit, Exception] = {
    val tests: List[JValue] = manifest.tests.map { t =>
      ("name" -> t.name) ~
        ("className" -> t.className) ~
        ("methodName" -> t.methodName) ~
        ("skip" -> t.skip) ~
        ("file" -> t.file) ~
        ("startLine" -> t.startLine) ~
        ("startCol" -> t.startCol) ~
        ("endLine" -> t.endLine) ~
        ("endCol" -> t.endCol)
    }
    val json: JValue =
      ("formatVersion" -> FormatVersion) ~
        ("fingerprint" -> manifest.fingerprint) ~
        ("sourcesDigest" -> manifest.sourcesDigest) ~
        ("tests" -> JArray(tests))
    try {
      Files.createDirectories(path.getParent.normalize())
      Files.write(path, JsonMethods.pretty(JsonMethods.render(json)).getBytes)
      Result.Ok(())
    } catch {
      case e: Exception => Result.Err(e)
    }
  }

  private def stringOf(json: JValue): Option[String] = json match {
    case JString(s) => Some(s)
    case _ => None
  }

  private def intOf(json: JValue): Option[Int] = json match {
    case JInt(v) => Some(v.toInt)
    case _ => None
  }

  /** Returns the recorded tests of the JSON array `json`, or `None` if any entry is not one. */
  private def testsOf(json: JValue): Option[List[RecordedTest]] = json match {
    case JArray(values) =>
      val parsed = values.map { v =>
        for {
          name <- stringOf(v \ "name")
          className <- stringOf(v \ "className")
          methodName <- stringOf(v \ "methodName")
          skip <- (v \ "skip") match {
            case JBool(b) => Some(b)
            case _ => None
          }
          file <- stringOf(v \ "file")
          startLine <- intOf(v \ "startLine")
          startCol <- intOf(v \ "startCol")
          endLine <- intOf(v \ "endLine")
          endCol <- intOf(v \ "endCol")
        } yield RecordedTest(name, className, methodName, skip, file, startLine, startCol, endLine, endCol)
      }
      // All or nothing: a half-read table would run some of the tests and report a pass.
      if (parsed.forall(_.isDefined)) Some(parsed.flatten) else None
    case _ => None
  }
}
