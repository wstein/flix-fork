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

import org.json4s.native.JsonMethods
import org.json4s.{JString, JValue, jvalue2monadic}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/**
  * The stream a forked test run's own printing goes through.
  *
  * The events themselves are pinned by [[TestTesterSink]], which every rendering shares. What is only
  * true of this rendering is that a test's `println` has to survive being turned into an event and read
  * back by another process -- and both defects below produced output that looked plausible and was
  * wrong, which is the kind a reader blames on their own code.
  */
class TestJsonTestSink extends AnyFunSuite {

  test("a line of output is decoded as UTF-8, not one byte per character") {
    // `PrintStream` encodes before the bytes arrive here, so reading each byte as a character is
    // Latin-1: every non-ASCII character a test prints reaches the client as mojibake.
    val (sink, written) = mkSink()
    val out = new PrintStream(sink.outputStream, true, StandardCharsets.UTF_8)

    out.println("héllo wörld — ok")

    assert(linesOf(written) == List("héllo wörld — ok"), s"unexpected output: ${linesOf(written)}")
  }

  test("two writers share one line") {
    // `outputStream` was a `def`, so every caller was handed a buffer of its own -- and the runner
    // installs one as `System.out` while holding another. Half a line each, and neither is a line.
    val (sink, written) = mkSink()

    val first = sink.outputStream
    val second = sink.outputStream
    first.write('a'.toInt)
    second.write('b'.toInt)
    second.write('\n'.toInt)

    assert(linesOf(written) == List("ab"), s"the line was split across streams: ${linesOf(written)}")
  }

  test("a partial line is reported when the stream is flushed") {
    // A program that ends without a newline still said something, and a client waiting for the rest of
    // a line it will never get shows nothing at all.
    val (sink, written) = mkSink()
    val out = sink.outputStream

    out.write('h'.toInt)
    out.write('i'.toInt)
    assert(linesOf(written).isEmpty, "a partial line was reported before it ended")

    out.flush()
    assert(linesOf(written) == List("hi"), s"unexpected output: ${linesOf(written)}")
  }

  /** A sink writing to a buffer, with the buffer. */
  private def mkSink(): (JsonTestSink, ByteArrayOutputStream) = {
    val written = new ByteArrayOutputStream()
    val sink = new JsonTestSink(new PrintStream(written, true, StandardCharsets.UTF_8))
    (sink, written)
  }

  /** The `line` of every `output` event written so far, in order. */
  private def linesOf(written: ByteArrayOutputStream): List[String] =
    new String(written.toByteArray, StandardCharsets.UTF_8)
      .linesIterator
      .filter(_.nonEmpty)
      .map(JsonMethods.parse(_))
      .filter(json => (json \ "event") == JString("output"))
      .map(json => (json \ "line") match {
        case JString(s) => s
        case other => fail(s"an output event carried no line: $other")
      })
      .toList
}
