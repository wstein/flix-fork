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

import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods
import org.json4s.{JArray, JObject, JValue}

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** Reports a test run as one JSON object per line. */
class JsonTestSink(out: PrintStream) extends Tester.TestEventSink {

  override def start(tests: Vector[Tester.TestCase])(implicit flix: ca.uwaterloo.flix.api.Flix): Unit = {
    val entries: List[JValue] = tests.toList.map(test => idFields(test.sym) ~ ("skip" -> test.skip))
    emit(("event" -> "start") ~ ("tests" -> JArray(entries)))
  }

  override def accept(event: Tester.TestEvent)(implicit flix: ca.uwaterloo.flix.api.Flix): Unit = event match {
    case Tester.TestEvent.Before(sym) => emit(("event" -> "before") ~ idFields(sym))
    case Tester.TestEvent.Success(sym, elapsed) =>
      emit(("event" -> "passed") ~ idFields(sym) ~ ("nanos" -> elapsed.d))
    case Tester.TestEvent.Failure(sym, output, elapsed) =>
      emit(("event" -> "failed") ~ idFields(sym) ~ ("nanos" -> elapsed.d) ~ ("output" -> output))
    case Tester.TestEvent.Skip(sym) => emit(("event" -> "skipped") ~ idFields(sym))
    case Tester.TestEvent.Finished(elapsed) => emit(("event" -> "finished") ~ ("nanos" -> elapsed.d))
  }

  override val outputStream: Option[OutputStream] = Some(new OutputStream {
    private val line = new ByteArrayOutputStream()

    override def write(b: Int): Unit = synchronized {
      if (b == '\n') emitLine()
      else if (b != '\r') {
        line.write(b)
        // A PrintStream writes UTF-8 bytes. Do not decode a chunk until its final code point is
        // complete: splitting one between chunks would replace both halves with U+FFFD.
        if (line.size() >= MaxLine && endsAtUtf8Boundary(line.toByteArray)) emitLine()
      }
    }

    override def flush(): Unit = synchronized {
      if (line.size() > 0) emitLine()
    }

    private def emitLine(): Unit = {
      emit(("event" -> "output") ~ ("line" -> new String(line.toByteArray, StandardCharsets.UTF_8)))
      line.reset()
    }
  })

  private def idFields(sym: ca.uwaterloo.flix.language.ast.Symbol.DefnSym): JObject = {
    val loc = sym.loc
    if (!loc.isReal) {
      "name" -> sym.toString
    } else {
      ("name" -> sym.toString) ~
        ("file" -> loc.source.name) ~
        ("startLine" -> loc.startLine) ~
        ("startCol" -> loc.startCol) ~
        ("endLine" -> loc.endLine) ~
        ("endCol" -> loc.endCol)
    }
  }

  private def emit(json: JObject): Unit = synchronized {
    out.println(JsonMethods.compact(JsonMethods.render(json)))
    out.flush()
  }

  private def endsAtUtf8Boundary(bytes: Array[Byte]): Boolean = {
    if (bytes.isEmpty) return true
    var lead = bytes.length - 1
    while (lead >= 0 && (bytes(lead) & 0xc0) == 0x80) lead = lead - 1
    if (lead < 0) return false

    val first = bytes(lead) & 0xff
    val expected =
      if ((first & 0x80) == 0) 1
      else if ((first & 0xe0) == 0xc0) 2
      else if ((first & 0xf0) == 0xe0) 3
      else if ((first & 0xf8) == 0xf0) 4
      else 1 // malformed input: let the decoder replace it instead of growing forever
    bytes.length - lead >= expected
  }

  private val MaxLine: Int = 8 * 1024
}
