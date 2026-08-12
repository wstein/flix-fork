/*
 * Copyright 2022 Magnus Madsen
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
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.runtime.{CompilationResult, Coverage, TestFn}
import ca.uwaterloo.flix.util.{Duration, Result}
import org.jline.terminal.{Terminal, TerminalBuilder}

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream, PrintWriter, StringWriter}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.{Level, Logger}
import scala.util.matching.Regex

/**
  * Evaluates all tests in a Flix program.
  */
object Tester {

  /**
    * Runs all tests, printing the results to the terminal.
    */
  def run(filters: List[Regex], compilationResult: CompilationResult)(implicit flix: Flix): Result[Unit, Int] =
    run(filters, compilationResult, consoleSink)

  /**
    * Returns the rendering `flix test` prints.
    *
    * A fresh one per run: it counts what it has seen, and a shared instance would carry one run's totals
    * into the next.
    */
  def consoleSink: TestEventSink = new ConsoleSink

  /**
    * Runs all tests, reporting each event to `sink`.
    *
    * ==Why a sink rather than a second runner==
    *
    * The rule for what counts as a failure is not written down anywhere except in [[TestRunner]]: a
    * `false` result is an assertion failure, a non-false result that wrote to standard error is *also*
    * a failure, and a skipped test never starts the clock. A caller that wanted results in another
    * shape could reimplement that loop, and then `flix test` and whatever else was reporting would
    * eventually disagree about whether a test passed. There is one runner, and callers choose only how
    * its events are rendered.
    *
    * The console rendering is one such sink and not a privileged one -- which matters more than it
    * sounds, because it builds a *system* terminal and writes to the real file descriptor. In a
    * process where that descriptor carries a protocol, printing there is not a cosmetic problem.
    */
  def run(filters: List[Regex], compilationResult: CompilationResult, sink: TestEventSink)(implicit flix: Flix): Result[Unit, Int] =
    run(getTestCases(filters, compilationResult), sink)

  /**
    * Runs `tests`, reporting each event to `sink`.
    *
    * Takes the cases rather than a compilation, because they do not always come from one: a build that
    * is still current can be tested from the class files it wrote, and the runner is the same either
    * way. Filtering has already happened -- whoever produced the cases decided which ones they are.
    */
  def run(tests: Vector[TestCase], sink: TestEventSink, isCancelled: () => Boolean = () => false)(implicit flix: Flix): Result[Unit, Int] = {
    //
    // Reset coverage before running tests.
    //
    Coverage.reset()

    // Start the TestRunner and TestReporter.
    val queue = new ConcurrentLinkedQueue[TestEvent]()
    val reporter = new TestReporter(queue, tests, sink)
    val runner = new TestRunner(queue, tests, isCancelled)
    reporter.start()
    runner.start()

    // Wait for everything to complete.
    reporter.join()
    runner.join()

    if (reporter.isSuccess()) {
      Result.Ok(())
    } else {
      // Set exit code of program to 1.
      Result.Err(1)
    }
  }

  /**
    * Where the results of a test run are rendered.
    *
    * Implementations receive every event in the order the runner emitted it, and the run is over when
    * [[TestEvent.Finished]] arrives. Whether the run *succeeded* is not the sink's business -- the
    * reporter decides that from the same events, so two renderings cannot disagree about it.
    */
  trait TestEventSink {
    /** Called once before any event, with every test the run will report on. */
    def start(tests: Vector[TestCase])(implicit flix: Flix): Unit

    /** Called for each event, on the reporter's thread. */
    def accept(event: TestEvent)(implicit flix: Flix): Unit
  }

  /**
    * A class that reports the results of test events as they come in.
    */
  private class TestReporter(queue: ConcurrentLinkedQueue[TestEvent], tests: Vector[TestCase], sink: TestEventSink)(implicit flix: Flix) extends Thread {

    private val success = new java.util.concurrent.atomic.AtomicBoolean(true)

    def isSuccess(): Boolean = {
      success.get()
    }

    /**
      * Drains the queue, deciding success and forwarding each event to the sink.
      *
      * Success is decided here rather than in the sink so that every rendering of a run agrees about
      * it: a failure is a `TestEvent.Failure`, and nothing a sink does can change that.
      */
    override def run(): Unit = {
      sink.start(tests)
      var finished = false
      while (!finished) {
        queue.poll() match {
          case null => () // the runner has not started yet, retry
          case event =>
            event match {
              case TestEvent.Failure(_, _, _) => success.set(false)
              case TestEvent.Finished(_) => finished = true
              case _ => ()
            }
            sink.accept(event)
        }
      }
    }
  }

  /**
    * The rendering `flix test` has always printed.
    *
    * Moved out of the reporter unchanged. It builds a *system* terminal, which is why it is a sink a
    * caller selects rather than something every run does: a server whose real file descriptor carries
    * a protocol must not have this attached.
    */
  private class ConsoleSink extends TestEventSink {

    private var terminal: Terminal = _
    private var writer: java.io.PrintWriter = _
    private var passed = 0
    private var skipped = 0
    private var failed: List[(TestId, List[String])] = Nil

    /** Sets up the terminal and prints the headline. */
    def start(tests: Vector[TestCase])(implicit flix: Flix): Unit = {
      // Silence JLine warnings about terminal type.
      Logger.getLogger("org.jline").setLevel(Level.OFF)

      terminal = TerminalBuilder.builder().system(true).build()
      writer = terminal.writer()

      writer.println(s"Running ${tests.length} tests...")
      writer.println()
      writer.flush()
    }

    /** Prints one event, exactly as `flix test` always has. */
    def accept(event: TestEvent)(implicit flix: Flix): Unit = {
      val formatter = flix.getFormatter
      import formatter.*

      event match {
        case TestEvent.Before(id) =>
          // Note: Print \r to reset the caret.
          writer.print(s"  ${bgYellow(" TEST ")} $id\r")
          terminal.flush()

        case TestEvent.Success(id, elapsed) =>
          passed = passed + 1
          writer.println(s"  ${bgGreen(" PASS ")} $id ${elapsed.fmt}")
          terminal.flush()

        case TestEvent.Failure(id, output, _) =>
          failed = (id, output) :: failed
          val line = output.headOption.map(s => s"(${red(s)})").getOrElse("")
          writer.println(s"  ${bgRed(" FAIL ")} $id $line")
          terminal.flush()

        case TestEvent.Skip(id) =>
          skipped = skipped + 1
          writer.println(s"  ${bgYellow(" SKIP ")} $id (${yellow("SKIPPED")})")
          terminal.flush()

        case TestEvent.Finished(elapsed) =>
          // Print the std out / std err of every failed test.
          if (failed.nonEmpty) {
            writer.println()
            writer.println("-" * 80)
            writer.println()
            for ((id, output) <- failed; if output.nonEmpty) {
              writer.println(s"  ${bgRed(" FAIL ")} $id")
              id.location.foreach(loc => writer.println(s"         ${loc.file}:${loc.startLine}"))
              for (line <- output) {
                writer.println(s"    $line")
              }
              writer.println()
            }
            writer.println("-" * 80)
          }

          // Print the summary.
          writer.println()
          writer.println(
            s"Passed: ${green(passed.toString)}, " +
              s"Failed: ${red(failed.length.toString)}. " +
              s"Skipped: ${yellow(skipped.toString)}. " +
              s"Elapsed: ${elapsed.fmt}."
          )
          terminal.flush()
      }
    }

  }

  /**
    * A class that runs all the given tests emitting test events.
    */
  private class TestRunner(queue: ConcurrentLinkedQueue[TestEvent], tests: Vector[TestCase],
                           isCancelled: () => Boolean)(implicit flix: Flix) extends Thread {
    /**
      * Runs all the given tests, stopping if the caller has given up.
      *
      * Checked between tests and not inside one, which is the honest guarantee: a test is a compiled
      * function called by reflection, and a JVM cannot safely stop a method in the middle -- `Thread.stop`
      * was removed because it left locks and objects in states nothing could reason about. So the test
      * in flight finishes and no further one starts.
      *
      * The terminal event is emitted either way. A reporter waits for it, and a run that stopped without
      * one would leave it waiting forever.
      */
    override def run(): Unit = {
      val start = System.nanoTime()
      for (testCase <- tests) {
        if (!isCancelled()) {
          runTest(testCase)
        }
      }
      val elapsed = System.nanoTime() - start
      queue.add(TestEvent.Finished(Duration(elapsed)))
    }

    /**
      * Runs the given `test` emitting test events.
      */
    private def runTest(test: TestCase): Unit = test match {
      case TestCase(id, skip, run) =>
        // Check if the test case should be ignored.
        if (skip) {
          queue.add(TestEvent.Skip(id))
          return
        }

        // We are about to run the test case.
        queue.add(TestEvent.Before(id))

        // Redirect std out and std err.
        val redirect = new ConsoleRedirection
        redirect.redirect()

        // Start the clock.
        val start = System.nanoTime()

        try {
          // Run the test case.
          val result = run()

          // Compute elapsed time.
          val elapsed = System.nanoTime() - start

          // Restore std out and std err.
          redirect.restore()

          result match {
            case java.lang.Boolean.FALSE =>
              // Case 1: Assertion Error.
              queue.add(TestEvent.Failure(id, "Assertion Error" :: redirect.stdOut ++ redirect.stdErr, Duration(elapsed)))

            case _ =>
              if (redirect.stdErr.isEmpty) {
                // Case 2: Non-False result and no stderr output.
                queue.add(TestEvent.Success(id, Duration(elapsed)))
              } else {
                // Case 3: Non-False result, but with stderr output.
                queue.add(TestEvent.Failure(id, "Std Err Output" :: redirect.stdOut ++ redirect.stdErr, Duration(elapsed)))
              }

          }
        } catch {
          case ex: Throwable =>
            // Restore std out and std err.
            redirect.restore()

            // Compute elapsed time.
            val elapsed = System.nanoTime() - start
            queue.add(TestEvent.Failure(id, redirect.stdOut ++ redirect.stdErr ++ fmtStackTrace(ex), Duration(elapsed)))
        }
    }
  }

  /**
    * A class which outputs to two different output streams
    *
    * Largely taken from org.apache.commons.io.output.TeeOutputStream
    */
  class TeeOutputStream(out: OutputStream, branch: OutputStream) extends PrintStream(out) {

    override def write(b: Array[Byte]): Unit = synchronized {
      super.write(b)
      branch.write(b)
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      super.write(b, off, len)
      branch.write(b, off, len)
    }

    override def write(b: Int): Unit = synchronized {
      super.write(b)
      branch.write(b)
    }

    override def flush(): Unit = synchronized {
      super.flush()
      branch.flush()
    }

    override def close(): Unit = synchronized {
      try {
        super.close()
      } finally {
        branch.close()
      }
    }
  }

  /**
    * A class used to redirect the standard out and standard error streams.
    */
  class ConsoleRedirection {
    private val bytesOut = new ByteArrayOutputStream()
    private val bytesErr = new ByteArrayOutputStream()
    private val streamOut = new PrintStream(bytesOut)
    private val streamErr = new PrintStream(bytesErr)

    private var oldStreamOut: PrintStream = _
    private var oldStreamErr: PrintStream = _

    /**
      * Returns the string emitted to the std out during redirection.
      */
    def stdOut: List[String] = bytesOut.toString().linesIterator.toList

    /**
      * Returns the string emitted to the std err during redirection.
      */
    def stdErr: List[String] = bytesErr.toString().linesIterator.toList

    /**
      * Redirect std out and std err.
      */
    def redirect(): Unit = {
      // Store the old streams.
      oldStreamOut = System.out
      oldStreamErr = System.err

      // Set the new streams.
      System.setOut(new TeeOutputStream(streamOut, oldStreamOut))
      System.setErr(new TeeOutputStream(streamErr, oldStreamErr))
    }

    /**
      * Restore the std in and std err to their original streams.
      */
    def restore(): Unit = {
      // Flush the new streams.
      System.out.flush()
      System.err.flush()

      // Restore standard out and standard error.
      System.setOut(oldStreamOut)
      System.setErr(oldStreamErr)
    }
  }

  /**
    * Returns all test cases from the given compilation `result` which satisfy at least one filter.
    */
  def getTestCases(filters: List[Regex], compilationResult: CompilationResult): Vector[TestCase] = {
    /**
      * Returns `true` if at least one filter matches the given symbol _OR_ if there are no filters.
      */
    def isMatch(test: TestCase): Boolean = {
      val name = test.id.name
      filters.isEmpty || filters.exists(regex => regex.matches(name))
    }

    val allTests = compilationResult.getTests.map {
      case (sym, TestFn(_, skip, run, _, _)) => TestCase(TestId.of(sym), skip, run)
    }

    allTests.filter(isMatch).toVector.sorted
  }

  /**
    * Returns the stack trace of the given exception `ex` as a list of strings.
    */
  private def fmtStackTrace(ex: Throwable): Vector[String] = {
    val sw = new StringWriter()
    val pw = new PrintWriter(sw)
    ex.printStackTrace(pw)
    sw.toString.linesIterator.toVector
  }

  /**
    * Where a test is written, as much of it as a runner needs.
    *
    * A file and a range rather than a `SourceLocation`, because a test does not always arrive from a
    * compilation: a build that is already current can be tested from what it left behind, and there is
    * then no typed AST to take a symbol from. Fabricating one -- a `Symbol.DefnSym` around an empty
    * `Source` -- would put a hollow compiler object into a data structure that looks like the real
    * thing, which is worse than saying plainly what is known.
    *
    * @param file the source file, as the compiler named it.
    */
  case class TestLocation(file: String, startLine: Int, startCol: Int, endLine: Int, endCol: Int)

  /**
    * Which test this is: what to call it, and where to find it.
    *
    * @param name     the fully qualified name, which is what a filter matches and what is displayed.
    * @param location where it is written, if that is known.
    */
  case class TestId(name: String, location: Option[TestLocation]) {
    override def toString: String = name
  }

  object TestId {
    /** Returns the identity of the test defined by `sym`. */
    def of(sym: Symbol.DefnSym): TestId = {
      val loc = sym.loc
      TestId(sym.toString, Some(TestLocation(
        loc.source.name, loc.startLine, loc.startCol, loc.endLine, loc.endCol)))
    }
  }

  /**
    * Represents a single test case.
    *
    * @param id   which test this is.
    * @param skip true if the test case should be skipped.
    * @param run  the code to run.
    */
  case class TestCase(id: TestId, skip: Boolean, run: () => AnyRef) extends Ordered[TestCase] {
    override def compare(that: TestCase): Int = this.id.name.compareTo(that.id.name)
  }

  /**
    * A common super-type for test events.
    */
  sealed trait TestEvent

  object TestEvent {

    /**
      * A test event emitted immediately before a test case is executed.
      */
    case class Before(id: TestId) extends TestEvent

    /**
      * A test event emitted to indicate that a test succeeded.
      */
    case class Success(id: TestId, d: Duration) extends TestEvent

    /**
      * A test event emitted to indicate that a test failed.
      */
    case class Failure(id: TestId, output: List[String], d: Duration) extends TestEvent

    /**
      * A test event emitted to indicate that a test was ignored.
      */
    case class Skip(id: TestId) extends TestEvent

    /**
      * A test event emitted to indicates that testing has completed.
      */
    case class Finished(d: Duration) extends TestEvent
  }

}
