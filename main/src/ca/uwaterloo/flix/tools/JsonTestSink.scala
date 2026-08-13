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
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods
import org.json4s.{JArray, JObject, JValue}

import java.io.PrintStream

/**
  * Reports a test run as one JSON object per line, for another process to read.
  *
  * ==Why this exists==
  *
  * So that tests can run somewhere other than the process that wants to know about them. `flix test`
  * renders to a terminal and `flix bsp` renders to protocol notifications, and both watch `Tester`
  * directly because they are in the same JVM as the tests. A test that calls `System.exit` therefore
  * takes the server with it, and one that loops forever occupies it. Running the tests in a JVM of their
  * own fixes both, and this is how the events get back out.
  *
  * ==One rendering, not a second runner==
  *
  * This is a [[Tester.TestEventSink]] like the others. What counts as a failure is still decided in one
  * place, so a forked run and an in-process run cannot come to disagree -- which is the property the
  * whole sink design exists to protect, and the one a bespoke "test protocol" would quietly give up.
  *
  * ==The stream belongs to the events==
  *
  * A test's own `println` would otherwise land between two JSON objects and end the conversation, which
  * is the same hazard `flix bsp` has on its own standard output. The runner takes the real descriptor
  * for the events and points `System.out` at [[JsonTestSink.outputStream]], so a program's writes arrive
  * as `output` events instead of corrupting the stream they share.
  *
  * Every event that names a test carries its location as well as its name, so a reader needs to keep no
  * state to make a result clickable.
  */
class JsonTestSink(out: PrintStream) extends Tester.TestEventSink {

  override def start(tests: Vector[Tester.TestCase])(implicit flix: Flix): Unit = {
    val entries: List[JValue] = tests.toList.map { test =>
      idFields(test.id) ~ ("skip" -> test.skip)
    }
    emit(("event" -> "start") ~ ("tests" -> JArray(entries)))
  }

  override def accept(event: Tester.TestEvent)(implicit flix: Flix): Unit = event match {
    case Tester.TestEvent.Before(id) =>
      emit(("event" -> "before") ~ idFields(id))

    case Tester.TestEvent.Success(id, d) =>
      emit(("event" -> "passed") ~ idFields(id) ~ ("nanos" -> d.d))

    case Tester.TestEvent.Failure(id, output, d) =>
      emit(("event" -> "failed") ~ idFields(id) ~ ("nanos" -> d.d) ~ ("output" -> output))

    case Tester.TestEvent.Skip(id) =>
      emit(("event" -> "skipped") ~ idFields(id))

    case Tester.TestEvent.Finished(d) =>
      emit(("event" -> "finished") ~ ("nanos" -> d.d))
  }

  /** Reports a line the program wrote itself, which is not an event about a test. */
  def output(line: String): Unit = emit(("event" -> "output") ~ ("line" -> line))

  /**
    * Returns a stream that turns everything written to it into `output` events.
    *
    * The runner installs this as `System.out` before a test can write to it. Line-buffered, because an
    * event is a line and a partial write is not one yet.
    */
  def outputStream: java.io.OutputStream = new java.io.OutputStream {
    private val line = new StringBuilder

    override def write(b: Int): Unit = synchronized {
      if (b == '\n') {
        output(line.toString)
        line.setLength(0)
      } else if (b != '\r') {
        line.append((b & 0xff).toChar)
        // A program writing without newlines must not be able to grow this without bound.
        if (line.length >= MaxLine) {
          output(line.toString)
          line.setLength(0)
        }
      }
    }

    override def flush(): Unit = synchronized {
      if (line.nonEmpty) {
        output(line.toString)
        line.setLength(0)
      }
    }
  }

  /** The name and location of `id`, which every event carrying a test repeats. */
  private def idFields(id: Tester.TestId): JObject = id.location match {
    case None =>
      "name" -> id.name
    case Some(loc) =>
      ("name" -> id.name) ~ ("file" -> loc.file) ~ ("startLine" -> loc.startLine) ~
        ("startCol" -> loc.startCol) ~ ("endLine" -> loc.endLine) ~ ("endCol" -> loc.endCol)
  }

  /** Writes one object on one line, flushed, because a reader is waiting for it. */
  private def emit(json: JObject): Unit = synchronized {
    out.println(JsonMethods.compact(JsonMethods.render(json)))
    out.flush()
  }

  /** The longest run of output without a newline reported as one event. */
  private val MaxLine: Int = 8 * 1024
}
