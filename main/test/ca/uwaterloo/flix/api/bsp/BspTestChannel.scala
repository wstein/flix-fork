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

import java.io.{InputStream, OutputStream}
import java.nio.channels.{Channels, Pipe}

/**
  * The two byte streams a BSP suite connects a client and a server over.
  *
  * ==Why not `PipedInputStream`==
  *
  * Because it is not a pipe between *streams*, it is a pipe between *threads*. It records the last
  * thread that wrote to it and throws `IOException: Write end dead` from `read` once that thread has
  * terminated -- even with bytes still buffered and the writing end still open.
  *
  * A build server writes on whatever thread produced the event, and some of those threads are
  * deliberately short-lived: `Tester` runs each test run on a fresh reporter thread, which emits the
  * per-test notifications and then exits. Over a piped pair that exit kills the client's reader, so the
  * response to the request in progress is never read and the request times out. The server is fine, the
  * transport is not -- which is the worst kind of test failure, because it accuses working code.
  *
  * A real server is given the process's own stdin and stdout, which have no such affinity. `Pipe` is
  * the in-memory equivalent: an OS pipe, blocking, readable and writable from any thread. Use it, and
  * do not reintroduce piped streams here.
  *
  * The buffer is the operating system's, so there is no size to choose -- a writer blocks until the
  * reader drains, which is what a socket does too.
  */
object BspTestChannel {

  /**
    * One connection: what the client writes, the server reads, and vice versa.
    *
    * `close` shuts every channel down. Do it before shutting down the executor, or a listener thread
    * blocked in a read is interrupted and lsp4j logs the interruption as a stack trace through
    * `java.util.logging`, which nothing in this project's test configuration silences.
    */
  case class Channel(clientOut: OutputStream,
                     serverIn: InputStream,
                     serverOut: OutputStream,
                     clientIn: InputStream) extends AutoCloseable {

    override def close(): Unit =
      List[AutoCloseable](clientOut, serverOut, serverIn, clientIn).foreach { c =>
        try c.close()
        catch { case _: Exception => () }
      }
  }

  /** Opens a fresh connection. */
  def open(): Channel = {
    val toServer = Pipe.open()
    val toClient = Pipe.open()
    Channel(
      clientOut = Channels.newOutputStream(toServer.sink()),
      serverIn = Channels.newInputStream(toServer.source()),
      serverOut = Channels.newOutputStream(toClient.sink()),
      clientIn = Channels.newInputStream(toClient.source()))
  }
}
