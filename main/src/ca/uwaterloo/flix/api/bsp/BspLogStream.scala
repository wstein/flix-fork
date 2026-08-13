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

import ch.epfl.scala.bsp4j.{BuildClient, LogMessageParams, MessageType}

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
  * Where everything that used to be printed on standard output goes instead.
  *
  * ==Why this exists==
  *
  * Standard output is the protocol channel. A single stray line of text on it is not a cosmetic
  * problem: it lands between two JSON-RPC frames and the connection is finished. The compiler
  * prints in more places than one can audit once and forget -- the crash handler writes a report,
  * the progress bar draws, the test runner tees a program's own output, and `Bootstrap` narrates
  * dependency resolution -- and any of them may be reached from a build.
  *
  * So `System.out` is replaced by this and the real descriptor is handed to the launcher. Every line
  * written becomes a `build/logMessage`, which is strictly better than discarding it: a crash report
  * that vanishes is worse than one that arrives in the client's log.
  *
  * ==Why it buffers==
  *
  * The interesting failures happen while the project is being loaded, which is before the client is
  * connected. Lines written then are held and flushed on [[connect]]. The buffer is bounded and
  * drops its oldest lines, because a runaway printer must not become a memory leak in a
  * long-running server.
  */
class BspLogStream(maxBufferedLines: Int = 512) extends OutputStream {

  /** The client, once there is one. Read from any thread that writes a log line. */
  @volatile private var client: Option[BuildClient] = None

  /** Whether the rest of the current line is being discarded, having already been reported truncated. */
  private var dropping: Boolean = false

  /**
    * The longest line this will hold before reporting it truncated.
    *
    * Generous enough for a stack frame or a long path, and small enough that a program printing without
    * newlines cannot exhaust the server's memory -- which is the only thing that made this a bound
    * rather than a preference.
    */
  private val MaxLineLength: Int = 32 * 1024

  /** The current line, up to the newline that will send it. */
  private val line: ByteArrayOutputStream = new ByteArrayOutputStream()

  /** Lines written before a client existed, oldest first. */
  private val pending: mutable.Queue[String] = mutable.Queue.empty

  /**
    * Guards against a log line whose delivery logs.
    *
    * If notifying the client writes to `System.out` -- which is this stream -- the second write must
    * not try to notify again. Per-thread, because the reentrant call is on the same thread.
    */
  private val sending: ThreadLocal[Boolean] = ThreadLocal.withInitial(() => false)

  /**
    * Attaches `c` and flushes what was written before it arrived.
    */
  def connect(c: BuildClient): Unit = {
    val backlog = synchronized {
      client = Some(c)
      val held = pending.toList
      pending.clear()
      held
    }
    backlog.foreach(send(c, _))
  }

  override def write(b: Int): Unit = synchronized {
    if (b == '\n') {
      if (dropping) {
        // The marker was already sent for this line; the newline just ends it.
        dropping = false
        line.reset()
      } else {
        emit()
      }
    } else if (dropping) {
      () // The rest of an over-long line, discarded so that one line cannot become a flood of them.
    } else if (b != '\r') {
      line.write(b)
      if (line.size() >= MaxLineLength) {
        // Bounded here and not only in the pre-connect buffer, which is the gap this closes: a write
        // with no newline in it -- a progress bar, a stack trace rendered without breaks, a program
        // printing megabytes -- grew this without limit however long the connection had been up.
        emitTruncated()
      }
    }
  }

  override def write(bytes: Array[Byte], off: Int, len: Int): Unit = synchronized {
    var i = off
    while (i < off + len) {
      write(bytes(i).toInt & 0xff)
      i += 1
    }
  }

  /** Sends whatever is buffered, so a prompt written without a newline is not held forever. */
  override def flush(): Unit = synchronized {
    if (line.size() > 0) emit()
  }

  /**
    * Sends what has accumulated with a marker, and starts discarding the rest of the line.
    *
    * One marker per over-long line rather than one per chunk: a program that writes a megabyte without
    * a newline has said one thing badly, and turning it into a hundred log notifications would replace
    * a memory problem with a traffic problem.
    */
  private def emitTruncated(): Unit = {
    val text = new String(line.toByteArray, StandardCharsets.UTF_8)
    line.reset()
    dropping = true
    client match {
      case Some(c) => send(c, s"$text …[line truncated at $MaxLineLength bytes]")
      case None =>
        pending.enqueue(s"$text …[line truncated at $MaxLineLength bytes]")
        while (pending.sizeIs > maxBufferedLines) {
          pending.dequeue()
        }
    }
  }

  /** Sends the current line, or holds it if no client has connected yet. */
  private def emit(): Unit = {
    val text = new String(line.toByteArray, StandardCharsets.UTF_8)
    line.reset()
    if (text.isBlank) {
      return
    }
    client match {
      case Some(c) => send(c, text)
      case None =>
        pending.enqueue(text)
        while (pending.sizeIs > maxBufferedLines) {
          pending.dequeue()
        }
    }
  }

  /** Delivers one line, and never lets a failure to deliver become a new line to deliver. */
  private def send(c: BuildClient, text: String): Unit = {
    if (sending.get()) {
      return
    }
    sending.set(true)
    try {
      c.onBuildLogMessage(new LogMessageParams(MessageType.LOG, text))
    } catch {
      // The connection is gone or the client threw. There is nowhere left to report this that is
      // not the channel that just failed, so it is dropped on purpose.
      case _: Exception => ()
    } finally {
      sending.set(false)
    }
  }
}
