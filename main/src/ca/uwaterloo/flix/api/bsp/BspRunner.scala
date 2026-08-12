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

import ca.uwaterloo.flix.api.{ProgramRunner, ProjectView}
import ca.uwaterloo.flix.util.Build

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
  * Runs a compiled Flix program in a JVM of its own.
  *
  * ==Why forked==
  *
  * `Bootstrap.run` runs `main` in the compiler's own process by reflection, which is right for a
  * command that exits afterwards and wrong for a server that does not. A `System.exit` in user code
  * would take the connection with it; an infinite loop would wedge every later request; a program
  * that installs a shutdown hook or spawns threads would leave them behind. A forked process makes
  * each of those the program's own problem, and gives the exit status a client asked for.
  *
  * The command itself -- which `java`, which classpath, which class -- comes from
  * [[ProgramRunner]], which `flix run` uses as well. What differs between them is the input and
  * output handling below, not the launching.
  */
object BspRunner {

  /** What a run produced. */
  case class Outcome(exitCode: Int, timedOut: Boolean) {
    /** Returns `true` if the program ran to completion and reported success. */
    def isSuccess: Boolean = !timedOut && exitCode == 0
  }

  /**
    * Runs the program of `view` and reports what happened.
    *
    * Output is streamed to `onOutput` line by line rather than collected, so a client sees a
    * long-running program's progress instead of everything at the end. Both streams are merged: a
    * program's own interleaving of them is the order a user expects to read.
    *
    * @param arguments  passed to the program, after the class name.
    * @param onOutput   called for each line the program writes.
    * @param onStart    called with the process as soon as it exists, so a caller can stop it.
    * @param timeout    how long to wait before giving up and killing the process. A server must not
    *                   be held open forever by a program that does not end.
    */
  def run(view: ProjectView,
          build: Build,
          arguments: List[String],
          onOutput: String => Unit,
          timeout: java.time.Duration,
          onStart: Process => Unit = _ => ()): Outcome = {
    val process = new ProcessBuilder(ProgramRunner.command(view, build, arguments)*)
      // In the project, so a program that reads a relative path finds what the user would expect.
      .directory(view.projectPath.toFile)
      // Merged, so the program's own interleaving survives.
      .redirectErrorStream(true)
      .start()

    // Nothing is written to the program's input. Closing it means a program that reads stdin sees the
    // end of it rather than blocking until the timeout.
    process.getOutputStream.close()

    // Handed over before anything is waited on, so that a client which gives up while the program is
    // starting can still reach it. A cancellation that arrives a moment too late would otherwise leave
    // the program running for the length of the timeout.
    onStart(process)

    // The output is drained on a thread of its own, and that is the whole point of this shape rather
    // than a detail of it. Reading to end-of-stream on *this* thread and only then waiting with a
    // timeout is a timeout that cannot fire: a program that loops without printing, or prints a line it
    // never terminates, holds the reader forever and the clock is never consulted. The process's
    // lifetime has to be supervised independently of its output.
    val reader = new Thread(() => pump(process, onOutput), "flix-bsp-run-output")
    reader.setDaemon(true)
    reader.start()

    try {
      if (process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)) {
        // Ended on its own. Give the reader the moment it needs to finish the bytes already written --
        // bounded, because a child of the program could hold the pipe open after the program itself is
        // gone, and a run that has finished must not wait on a grandchild.
        reader.join(DrainGrace.toMillis)
        Outcome(process.exitValue(), timedOut = false)
      } else {
        // Kill, reap, then join: destroying closes the pipe, which is what ends the reader, and waiting
        // for the exit before joining means the reader is not still being fed while we wait for it.
        process.destroyForcibly()
        process.waitFor()
        reader.join(DrainGrace.toMillis)
        Outcome(process.exitValue(), timedOut = true)
      }
    } catch {
      case _: InterruptedException =>
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        Outcome(exitCode = -1, timedOut = true)
    }
  }

  /**
    * Reports each line the program writes, until its output ends.
    *
    * A partial last line is reported too. A program killed mid-line, or one whose final write has no
    * newline, has still said something, and dropping it loses exactly the output a user is looking for
    * when a run had to be stopped.
    */
  private def pump(process: Process, onOutput: String => Unit): Unit = {
    val reader = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val partial = new StringBuilder
    try {
      var c = reader.read()
      while (c != -1) {
        if (c == '\n') {
          onOutput(partial.toString.stripSuffix("\r"))
          partial.setLength(0)
        } else {
          partial.append(c.toChar)
          // A program that writes megabytes without a newline must not be able to grow this without
          // bound; it is reported in pieces instead.
          if (partial.length >= MaxLineLength) {
            onOutput(partial.toString)
            partial.setLength(0)
          }
        }
        c = reader.read()
      }
    } catch {
      // The pipe was closed under us, which is what killing the process does. Whatever was buffered is
      // still worth reporting.
      case _: java.io.IOException => ()
    } finally {
      if (partial.nonEmpty) {
        onOutput(partial.toString)
      }
    }
  }

  /**
    * How long to wait for the output reader after the process has gone.
    *
    * Short: the bytes are already in the pipe, and the only reason this is not zero is that the reader
    * needs to be scheduled to hand them over.
    */
  private val DrainGrace: java.time.Duration = java.time.Duration.ofSeconds(5)

  /** The longest run of output without a newline that is reported as one line. */
  private val MaxLineLength: Int = 8 * 1024

}
