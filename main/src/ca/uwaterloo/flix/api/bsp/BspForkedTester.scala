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

import ca.uwaterloo.flix.api.{Flix, ProgramRunner, ProjectView}
import ca.uwaterloo.flix.tools.Tester
import ca.uwaterloo.flix.util.Duration
import org.json4s.native.JsonMethods
import org.json4s.{JArray, JBool, JInt, JString, JValue, jvalue2monadic}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
  * Runs a project's tests in a JVM of its own, and reports them as if they had run here.
  *
  * ==Why forked==
  *
  * Because a test is a compiled function this process reflects and calls, so running one in the server
  * means running arbitrary user code inside the thing an editor depends on. Three failures follow from
  * that and only from that: a test that calls `System.exit` takes the server with it, a test that loops
  * forever occupies it, and a test that leaks a thread leaks it into a process that lives for hours. A
  * forked JVM makes all three the fork's problem, and killing it is a normal operation rather than an
  * impossible one -- `Thread.stop` was removed from the JVM, `Process.destroy` was not.
  *
  * ==Why it is the compiler that is forked==
  *
  * Because no new program has to be generated for this. The class files are already on disk and
  * `TestManifest` already records where each test's shim went, so `flix test --reuse-build` in the
  * project directory reaches exactly the tests the server would have run and compiles nothing. What it
  * needs is a way to report events to a reader instead of a terminal, which is `--events-json`.
  *
  * ==Why the events come back as `Tester.TestEvent`==
  *
  * So that there is still one rendering path. The lines are parsed back into the events `Tester` emits
  * and handed to the same [[BspTestSink]] an in-process run used, which is what stops a forked run and
  * `flix test` from disagreeing about a result. A bespoke path from JSON to notifications would be a
  * second opinion about what a test outcome is.
  */
object BspForkedTester {

  /** What a forked run produced. */
  case class Outcome(passed: Boolean, timedOut: Boolean, started: Boolean)

  /**
    * Runs the tests of `view`, reporting each event to `sink` as it arrives.
    *
    * @param filters   regular expressions selecting which tests to run; all of them when empty.
    * @param sink      where the events go, exactly as an in-process run would send them.
    * @param onStart   called with the process as soon as it exists, so a caller can stop it.
    * @param timeout   how long to wait before killing the run.
    * @param environment variables to set on top of the ones this process inherited.
    */
  def run(view: ProjectView,
          filters: List[String],
          sink: Tester.TestEventSink,
          onStart: Process => Unit,
          timeout: java.time.Duration,
          environment: Map[String, String])(implicit flix: Flix): Outcome = {
    val builder = new ProcessBuilder(command(view, filters)*)
      // The project, because that is where `flix test` finds `flix.toml`.
      .directory(view.projectPath.toFile)
    environment.foreach { case (name, value) => builder.environment().put(name, value) }
    // Kept separate: the events are on standard output and must not have anything else mixed into them.
    // The runner quarantines its own standard output for the same reason, so a test's printing arrives as
    // an event rather than as a stray line.
    builder.redirectErrorStream(false)
    // And separate is not the same as unread. A pipe nobody drains fills at about 64 KB and blocks the
    // fork in `write` until the timeout kills it -- one GC log, one JIT warning, one stack trace from a
    // user thread is enough. Inherited rather than drained by a thread of our own, because this server's
    // standard *error* carries no protocol (docs/BSP.md §13: the frames are on stdout), so the fork's
    // complaints land where a person reading the server log will see them.
    builder.redirectError(ProcessBuilder.Redirect.INHERIT)

    val process = builder.start()
    process.getOutputStream.close()
    onStart(process)

    // Written by the reader thread and read by this one after `join`, which returns on a timeout as
    // well as on termination -- so both threads can be at it at once, and a plain `var` would let this
    // one read a value the other has already replaced.
    val started = new AtomicBoolean(false)
    val reader = new Thread(() => {
      val lines = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
      try {
        var line = lines.readLine()
        while (line != null) {
          if (accept(line, sink)) {
            started.set(true)
          }
          line = lines.readLine()
        }
      } catch {
        // The pipe closed under us, which is what killing the run does.
        case _: java.io.IOException => ()
      }
    }, "flix-bsp-test-events")
    reader.setDaemon(true)
    reader.start()

    // Supervised on its own, never through the reader: a runner that stopped writing would otherwise hold
    // the clock open forever, which is the defect the run path had.
    try {
      if (process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)) {
        reader.join(DrainGrace.toMillis)
        Outcome(passed = process.exitValue() == 0, timedOut = false, started = started.get())
      } else {
        ProgramRunner.terminateTree(process, DrainGrace)
        reader.join(DrainGrace.toMillis)
        Outcome(passed = false, timedOut = true, started = started.get())
      }
    } catch {
      case _: InterruptedException =>
        ProgramRunner.terminateTree(process, DrainGrace)
        Thread.currentThread().interrupt()
        Outcome(passed = false, timedOut = true, started = started.get())
    }
  }

  /** Returns the command that runs this compiler's own test runner over `view`. */
  private def command(view: ProjectView, filters: List[String]): List[String] = {
    // This process's classpath, because the runner *is* this compiler: a jar when a release runs it, a
    // directory list when the tests do, and correct in both cases without anything being recorded.
    val classpath = System.getProperty("java.class.path")
    val filterArgs = filters.flatMap(f => List("--filter", f))
    // `--reuse-build`, because this server has just compiled and is the authority on the build. Without
    // it the fork would ask the same question under its own options, compute a different fingerprint, and
    // write a manifest the server then reads as stale -- two processes each invalidating the other's
    // build, forever, at two full compiles per test run.
    javaBinary :: "-cp" :: classpath :: "ca.uwaterloo.flix.Main" :: "test" ::
      "--events-json" :: "--reuse-build" :: filterArgs
  }

  /** The `java` of this JVM, which is the one that can load these class files. */
  private def javaBinary: String = {
    val name = if (System.getProperty("os.name", "").toLowerCase.contains("win")) "java.exe" else "java"
    Path.of(System.getProperty("java.home"), "bin", name).toAbsolutePath.normalize().toString
  }

  /**
    * Reports `line` to `sink`, and returns whether it was a test event.
    *
    * A line that is not an event -- a JVM warning, something printed before the quarantine was installed
    * -- is reported as *output*. Dropping it loses the only clue about why a run behaved oddly, and the
    * first version of this reported it as a failed test called `<runner>`, which invented a test that does
    * not exist and put it in the client's test tree.
    */
  private def accept(line: String, sink: Tester.TestEventSink)(implicit flix: Flix): Boolean = {
    val json =
      try JsonMethods.parse(line)
      catch {
        case _: Exception =>
          sink.output(line)
          return false
      }

    (json \ "event") match {
      case JString("start") =>
        sink.start(testsOf(json \ "tests"))
        true
      case JString("before") =>
        sink.accept(Tester.TestEvent.Before(idOf(json)))
        true
      case JString("passed") =>
        sink.accept(Tester.TestEvent.Success(idOf(json), durationOf(json)))
        true
      case JString("failed") =>
        sink.accept(Tester.TestEvent.Failure(idOf(json), stringsOf(json \ "output"), durationOf(json)))
        true
      case JString("skipped") =>
        sink.accept(Tester.TestEvent.Skip(idOf(json)))
        true
      case JString("finished") =>
        sink.accept(Tester.TestEvent.Finished(durationOf(json)))
        true
      case JString("output") =>
        // A program's own writing. It is not about one test -- the runner reports tests separately, and
        // this arrives while whichever test is running writes it.
        (json \ "line") match {
          case JString(text) => sink.output(text)
          case _ => ()
        }
        false
      case _ =>
        // Well-formed JSON that is not one of ours. Reported rather than dropped, for the same reason.
        sink.output(line)
        false
    }
  }

  /** The tests a `start` event announced. */
  private def testsOf(json: JValue): Vector[Tester.TestCase] = json match {
    case JArray(values) =>
      values.map { entry =>
        val skip = (entry \ "skip") match {
          case JBool(b) => b
          case _ => false
        }
        // Not runnable here, and saying so rather than pretending: these describe tests that ran in
        // another process. A sink is given them to announce what a run will cover, never to call them.
        Tester.TestCase(idOf(entry), skip, () => throw new UnsupportedOperationException(
          "a test reported by a forked runner cannot be called in this process"))
      }.toVector
    case _ => Vector.empty
  }

  /** The identity an event names. */
  private def idOf(json: JValue): Tester.TestId = {
    val name = (json \ "name") match {
      case JString(s) => s
      case _ => "<unknown>"
    }
    val location = for {
      file <- (json \ "file") match {
        case JString(s) => Some(s)
        case _ => None
      }
      startLine <- intOf(json \ "startLine")
      startCol <- intOf(json \ "startCol")
      endLine <- intOf(json \ "endLine")
      endCol <- intOf(json \ "endCol")
    } yield Tester.TestLocation(file, startLine, startCol, endLine, endCol)
    Tester.TestId(name, location)
  }

  private def durationOf(json: JValue): Duration = (json \ "nanos") match {
    case JInt(n) => Duration(n.toLong)
    case _ => Duration(0)
  }

  private def stringsOf(json: JValue): List[String] = json match {
    case JArray(values) => values.collect { case JString(s) => s }
    case _ => Nil
  }

  private def intOf(json: JValue): Option[Int] = json match {
    case JInt(n) => Some(n.toInt)
    case _ => None
  }

  /** How long to wait for the event reader after the runner has gone. */
  private val DrainGrace: java.time.Duration = java.time.Duration.ofSeconds(5)
}
