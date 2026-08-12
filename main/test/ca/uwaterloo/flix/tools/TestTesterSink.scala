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
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/**
  * What a test run reports, at the level every rendering of it shares.
  *
  * `flix test` and an editor must not be able to disagree about whether a test passed, and the only
  * structural guarantee of that is that there is one runner and the renderings are sinks over its
  * events. This suite pins the events themselves -- one per test, with the outcome attached -- because
  * that is the contract [[Tester.TestEventSink]] offers and the thing a second implementation of the
  * loop would quietly break.
  */
class TestTesterSink extends AnyFunSuite {

  /** One of each outcome. `@Skip` is the case that has no `Before`, which a sink has to tolerate. */
  private val MixedTests: String =
    """mod Sink.Fixture {
      |    use Assert.assertEq
      |
      |    @Test
      |    def testPasses(): Unit \ Assert = assertEq(expected = 2, 1 + 1)
      |
      |    @Test
      |    def testFails(): Unit \ Assert = assertEq(expected = 3, 1 + 1)
      |
      |    @Test
      |    @Skip
      |    def testSkipped(): Unit \ Assert = assertEq(expected = 1, 1)
      |}
      |""".stripMargin

  test("every test is reported, and a skipped one is reported as skipped") {
    val (_, events) = run(Nil)

    val outcomes = events.collect {
      case Tester.TestEvent.Success(id, _) => simpleName(id) -> "pass"
      case Tester.TestEvent.Failure(id, _, _) => simpleName(id) -> "fail"
      case Tester.TestEvent.Skip(id) => simpleName(id) -> "skip"
    }.toMap

    assert(outcomes == Map("testPasses" -> "pass", "testFails" -> "fail", "testSkipped" -> "skip"),
      s"unexpected outcomes: $outcomes")
  }

  test("a skipped test is announced without being started") {
    val (_, events) = run(Nil)

    // The runner returns before `Before` for a skipped test, so a sink that opened its task pair on
    // `Before` would emit a finish with no start -- which leaves a client rendering a tree it cannot
    // place. Stated here because it is the runner's behaviour, not the sink's choice.
    val started = events.collect { case Tester.TestEvent.Before(id) => simpleName(id) }
    assert(!started.contains("testSkipped"), s"a skipped test was started: $started")
    assert(started.toSet == Set("testPasses", "testFails"), s"unexpected: $started")
  }

  test("a failure carries the output that explains it") {
    val (_, events) = run(Nil)

    val output = events.collectFirst { case Tester.TestEvent.Failure(_, output, _) => output }
      .getOrElse(fail("no failure was reported"))
    assert(output.nonEmpty, "the failure carried no output")
    assert(output.exists(_.contains("Assertion")), s"unexpected output: $output")
  }

  test("the run ends exactly once, after everything else") {
    val (_, events) = run(Nil)

    assert(events.count(_.isInstanceOf[Tester.TestEvent.Finished]) == 1,
      s"unexpected number of terminal events: $events")
    assert(events.last.isInstanceOf[Tester.TestEvent.Finished],
      s"the run did not end with its terminal event: ${events.last}")
  }

  test("the runner decides success, not the sink") {
    val (withFailing, _) = run(Nil)
    assert(withFailing == Result.Err(1), "a run containing a failure reported success")

    val (onlyPassing, events) = run(List(".*testPasses"))
    assert(onlyPassing == Result.Ok(()), "a run of only passing tests reported failure")
    // The filter selected, rather than the sink ignoring: a skipped test would otherwise still arrive.
    assert(events.collect { case Tester.TestEvent.Before(id) => simpleName(id) } == List("testPasses"))
  }

  test("the sink is told what the run will cover before it starts") {
    val (_, _, announced) = runCollecting(Nil)

    // A client draws a progress bar from this, so it has to include the skipped tests: they are part
    // of what the run reports on even though none of them runs.
    assert(announced.map(t => simpleName(t.id)).toSet == Set("testPasses", "testFails", "testSkipped"),
      s"unexpected: ${announced.map(t => simpleName(t.id))}")
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  /** The last segment of a test's qualified name, which is what these assertions are about. */
  private def simpleName(id: Tester.TestId): String = id.name.split('.').last

  private def run(filters: List[String]): (Result[Unit, Int], List[Tester.TestEvent]) = {
    val (result, events, _) = runCollecting(filters)
    (result, events)
  }

  /** Compiles the fixture and runs its tests, collecting everything the sink was told. */
  private def runCollecting(filters: List[String]): (Result[Unit, Int], List[Tester.TestEvent], Vector[Tester.TestCase]) = {
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(java.nio.file.Path.of("Fixture.flix"), MixedTests)

    val compiled = flix.compile().toResult match {
      case Result.Ok(r) => r
      case Result.Err(errors) => fail(s"the fixture did not compile: ${errors.map(_.summary).mkString(", ")}")
    }

    val sink = new RecordingSink
    val result = Tester.run(filters.map(_.r), compiled, sink)
    (result, sink.events.toList, sink.announced)
  }

  private class RecordingSink extends Tester.TestEventSink {
    val events: mutable.Buffer[Tester.TestEvent] = mutable.Buffer.empty
    var announced: Vector[Tester.TestCase] = Vector.empty

    override def start(tests: Vector[Tester.TestCase])(implicit flix: Flix): Unit = announced = tests

    override def accept(event: Tester.TestEvent)(implicit flix: Flix): Unit = events += event
  }
}
