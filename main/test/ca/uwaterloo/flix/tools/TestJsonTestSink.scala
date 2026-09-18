/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{Options, Result}
import org.json4s.{JArray, JBool, JInt, JString}
import org.json4s.native.JsonMethods
import org.json4s.jvalue2monadic
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

class TestJsonTestSink extends AnyFunSuite {

  test("test events carry names, locations, outcomes, and elapsed time") {
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true))
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(
      CompilerConstants.VirtualTestFile,
      """mod Json.Fixture {
        |    @Test def passes(): Unit \ IO = println("héllo from test")
        |    @Test @Skip def skipped(): Unit = ()
        |}
        |""".stripMargin,
      sctx,
    )
    val compilationResult = flix.compile() match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(errors.map(_.summary).mkString(", "))
    }
    val (sink, written) = mkSink()

    val loaded = JvmLoader.load(compilationResult)
    val result = try Tester.run(Nil, loaded, sink, Tester.CancellationToken.Never, compilationResult.getCoverageSession)
    finally loaded.coverage.foreach(_.close())
    val events = jsonLines(written)

    assert(result == Result.Ok(()))
    assert((events.head \ "event") == JString("start"))
    assert((events.head \ "protocolVersion") == JInt(1))
    assert((events.last \ "event") == JString("finished"))
    assert((events.last \ "nanos").isInstanceOf[JInt])
    assert(events.map(_ \ "event").toSet == Set(JString("start"), JString("before"), JString("output"), JString("passed"), JString("skipped"), JString("coverage"), JString("finished")))
    val coverage = events.find(json => (json \ "event") == JString("coverage")).get
    assert((coverage \ "coverage" \ "formatVersion") == JInt(1))
    assert(events.filter(json => (json \ "event") == JString("output")).map(_ \ "line") == List(JString("héllo from test")))

    val announced = (events.head \ "tests") match {
      case JArray(tests) => tests
      case other => fail(s"start event has no tests: $other")
    }
    assert(announced.size == 2)
    assert(announced.forall(test => (test \ "name").isInstanceOf[JString]))
    assert(announced.forall(test => (test \ "file").isInstanceOf[JString]))
    assert(announced.forall(test => (test \ "startLine").isInstanceOf[JInt]))
    assert(announced.exists(test => (test \ "skip") == JBool(true)))
  }

  test("output is UTF-8 JSONL") {
    val (sink, written) = mkSink()
    val out = new PrintStream(sink.outputStream.get, true, StandardCharsets.UTF_8)

    out.println("héllo wörld — ok")

    assert(outputLines(written) == List("héllo wörld — ok"))
  }

  test("an output chunk never splits a UTF-8 code point") {
    val (sink, written) = mkSink()
    val out = new PrintStream(sink.outputStream.get, true, StandardCharsets.UTF_8)
    val text = "a" * (8 * 1024 - 1) + "—"

    out.println(text)

    assert(outputLines(written).mkString == text)
  }

  test("all writers share one line buffer") {
    val (sink, written) = mkSink()
    val first = sink.outputStream.get
    val second = sink.outputStream.get

    first.write('a')
    second.write('b')
    second.write('\n')

    assert(outputLines(written) == List("ab"))
  }

  test("flush reports a partial line") {
    val (sink, written) = mkSink()
    sink.outputStream.get.write('h')
    sink.outputStream.get.write('i')
    assert(outputLines(written).isEmpty)

    sink.outputStream.get.flush()

    assert(outputLines(written) == List("hi"))
  }

  private def mkSink(): (JsonTestSink, ByteArrayOutputStream) = {
    val written = new ByteArrayOutputStream()
    val sink = new JsonTestSink(new PrintStream(written, true, StandardCharsets.UTF_8))
    (sink, written)
  }

  private def outputLines(written: ByteArrayOutputStream): List[String] =
    jsonLines(written)
      .filter(json => (json \ "event") == JString("output"))
      .map(json => (json \ "line") match {
        case JString(line) => line
        case other => fail(s"output event has no string line: $other")
      })
      .toList

  private def jsonLines(written: ByteArrayOutputStream): List[org.json4s.JValue] =
    new String(written.toByteArray, StandardCharsets.UTF_8)
      .linesIterator
      .filter(_.nonEmpty)
      .map(JsonMethods.parse(_))
      .toList
}
