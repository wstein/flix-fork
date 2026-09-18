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
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

class TestTesterSink extends AnyFunSuite {

  private val MixedTests: String =
    """mod Sink.Fixture {
      |    use Assert.assertEq
      |
      |    @Test def testPasses(): Unit \ Assert = assertEq(expected = 2, 1 + 1)
      |    @Test def testFails(): Unit \ Assert = assertEq(expected = 3, 1 + 1)
      |    @Test @Skip def testSkipped(): Unit = ()
      |}
      |""".stripMargin

  test("sink receives every outcome and one final event") {
    val (result, sink) = run(Nil)
    val outcomes = sink.events.collect {
      case Tester.TestEvent.Success(sym, _) => sym.toString -> "pass"
      case Tester.TestEvent.Failure(sym, _, _) => sym.toString -> "fail"
      case Tester.TestEvent.Skip(sym) => sym.toString -> "skip"
    }.toMap

    assert(result == Result.Err(1))
    assert(outcomes == Map(
      "Sink.Fixture.testPasses" -> "pass",
      "Sink.Fixture.testFails" -> "fail",
      "Sink.Fixture.testSkipped" -> "skip",
    ))
    assert(sink.events.count(_.isInstanceOf[Tester.TestEvent.Finished]) == 1)
    assert(sink.events.last.isInstanceOf[Tester.TestEvent.Finished])
  }

  test("sink sees the filtered test set before events") {
    val (result, sink) = run(List("Sink\\.Fixture\\.testPasses".r))

    assert(result == Result.Ok(()))
    assert(sink.announced.map(_.sym.toString) == Vector("Sink.Fixture.testPasses"))
    assert(sink.events.collect { case Tester.TestEvent.Before(sym) => sym.toString } == List("Sink.Fixture.testPasses"))
  }

  private def run(filters: List[scala.util.matching.Regex]): (Result[Unit, Int], RecordingSink) = {
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(CompilerConstants.VirtualTestFile, MixedTests, sctx)
    val compilationResult = flix.compile() match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(errors.map(_.summary).mkString(", "))
    }
    val sink = new RecordingSink
    val result = Tester.run(filters, JvmLoader.load(compilationResult), sink)
    (result, sink)
  }

  private class RecordingSink extends Tester.TestEventSink {
    val events: mutable.Buffer[Tester.TestEvent] = mutable.Buffer.empty
    var announced: Vector[Tester.TestCase] = Vector.empty

    override def start(tests: Vector[Tester.TestCase])(implicit flix: Flix): Unit = announced = tests
    override def accept(event: Tester.TestEvent)(implicit flix: Flix): Unit = events += event
  }
}
