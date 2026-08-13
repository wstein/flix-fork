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
package ca.uwaterloo.flix.api.bsp

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp
import ca.uwaterloo.flix.tools.Tester
import ca.uwaterloo.flix.util.Duration

import java.nio.file.{Files, Path}
import ch.epfl.scala.bsp4j.*


/**
  * Reports a test run as protocol notifications.
  *
  * ==One runner, two renderings==
  *
  * This is a `Tester.TestEventSink` rather than a second test loop, and the difference is not
  * cosmetic. What counts as a failure is written down in exactly one place -- `Tester`'s runner: a
  * `false` result is an assertion failure, a non-false result that wrote to standard error is *also*
  * one, and a skipped test never starts the clock. A second implementation of that would drift, and
  * `flix test` and the editor would come to disagree about whether a test passed. They cannot here,
  * because they are watching the same events.
  *
  * ==Why each test gets a task==
  *
  * A client renders a test tree from `taskStart`/`taskFinish` pairs carrying `test-start` and
  * `test-finish`, one per test, under a parent task for the run. The location on each is what makes
  * a result clickable, and `Symbol.DefnSym` already carries it, so it costs nothing to provide.
  */
class BspTestSink(tasks: BspTasks, target: BuildTargetIdentifier, parent: TaskId) extends Tester.TestEventSink {

  /** The task opened for each test, so its finish can name the same id. */
  private var open: Map[String, TaskId] = Map.empty

  private var passed: Int = 0
  private var failed: Int = 0
  private var skipped: Int = 0

  /** How long the whole run took, once it has ended. */
  private var elapsedTotal: Duration = Duration(0)

  override def start(tests: Vector[Tester.TestCase])(implicit flix: Flix): Unit = ()

  override def accept(event: Tester.TestEvent)(implicit flix: Flix): Unit = event match {
    case Tester.TestEvent.Before(id) =>
      openFor(id, s"Running $id")

    case Tester.TestEvent.Success(id, elapsed) =>
      passed += 1
      finish(id, TestStatus.PASSED, message = None, elapsed)

    case Tester.TestEvent.Failure(id, output, elapsed) =>
      failed += 1
      // The output the console rendering would have printed. Without it a client shows that a test
      // failed and nothing about why, which is the half that matters.
      val message = if (output.isEmpty) None else Some(output.mkString(System.lineSeparator()))
      finish(id, TestStatus.FAILED, message, elapsed)

    case Tester.TestEvent.Skip(id) =>
      skipped += 1
      // A skipped test has no `Before`, so this opens and closes its pair in one step -- a finish with
      // no start would leave a client rendering a row it cannot place in its tree.
      openFor(id, s"Skipping $id")
      finish(id, TestStatus.SKIPPED, message = None, Duration(0))

    case Tester.TestEvent.Finished(elapsed) =>
      // The parent task's own finish is the caller's, since only the caller knows the overall status.
      // Its duration is here, though: this is the only event that carries it.
      elapsedTotal = elapsed
  }

  /**
    * Returns the report that ends the run, for the caller to attach to the parent task.
    *
    * Built with setters rather than the constructor on purpose: it takes five consecutive `Integer`
    * parameters, and getting `cancelled` and `skipped` the wrong way round compiles, runs, and reports
    * a plausible number in the wrong column -- which is what happened.
    */
  def report(): TestReport = {
    val report = new TestReport(target, passed, failed, 0, 0, 0)
    report.setPassed(passed)
    report.setFailed(failed)
    report.setSkipped(skipped)
    report.setIgnored(0)
    report.setCancelled(0)
    report.setTime(elapsedTotal.milliseconds.toLong)
    report
  }

  /** Returns `true` if no test failed. */
  def isSuccess: Boolean = failed == 0

  /** Opens the task for `sym` and returns its id, recording it so the finish can name the same one. */
  private def openFor(id: Tester.TestId, message: String): TaskId = {
    val task = tasks.child(parent)
    open += (id.name -> task)
    val start = new TestStart(id.name)
    locationOf(id).foreach(start.setLocation)
    tasks.start(task, message, Some((TaskStartDataKind.TEST_START, start)))
    task
  }

  /** Ends the task opened for `sym`. */
  private def finish(id: Tester.TestId, status: TestStatus, message: Option[String],
                     elapsed: Duration): Unit = {
    // A test with no open task is one whose `Before` never arrived. That cannot happen for the events
    // the runner emits today, and if a new one is added the pair is still opened here rather than a
    // finish being sent on its own -- which is the defect the skip case above exists to avoid.
    val task = open.getOrElse(id.name, openFor(id, s"Running $id"))
    open -= id.name

    val data = new TestFinish(id.name, status)
    message.foreach(data.setMessage)
    locationOf(id).foreach(data.setLocation)

    // The duration goes in the message: `TestFinish` has no field for it, and a client showing a test
    // tree without timings is missing the thing people look at first.
    tasks.finish(
      task,
      if (status == TestStatus.SKIPPED) s"$id" else s"$id ${elapsed.fmt}",
      if (status == TestStatus.FAILED) StatusCode.ERROR else StatusCode.OK,
      Some((TaskFinishDataKind.TEST_FINISH, data)))
  }

  /**
    * Returns where `id` is defined, which is what makes a result clickable.
    *
    * `None` when the test carries no location, or when the file it names is not there: a client turns a
    * location into a jump, and a location that does not resolve is a broken link rather than a feature.
    * A test loaded from a recorded build is the case where that can happen.
    *
    * The range is converted to zero-based the same way every other position this server reports is --
    * `lsp.Position` is one-indexed and only `toLsp4j` subtracts.
    */
  private def locationOf(id: Tester.TestId): Option[Location] = id.location.flatMap { loc =>
    val path = Path.of(loc.file)
    if (!Files.isRegularFile(path)) {
      None
    } else {
      val converted = lsp.Range(
        lsp.Position(loc.startLine, loc.startCol), lsp.Position(loc.endLine, loc.endCol)).toLsp4j
      Some(new Location(
        BspUri.ofFile(path),
        new Range(
          new Position(converted.getStart.getLine, converted.getStart.getCharacter),
          new Position(converted.getEnd.getLine, converted.getEnd.getCharacter))))
    }
  }
}
