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

import ca.uwaterloo.flix.api.ProjectView
import ca.uwaterloo.flix.util.Build

import java.io.{BufferedReader, File, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
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
  * ==The entry class==
  *
  * `Main`, in the root package, with a `public static void main(String[])`. That is what `CodeGen`
  * emits for a program with an entry point, and it is why the classpath alone is enough to start one.
  */
object BspRunner {

  /** The class `CodeGen` emits for a program's entry point. */
  val MainClass: String = "Main"

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
    * @param timeout    how long to wait before giving up and killing the process. A server must not
    *                   be held open forever by a program that does not end.
    */
  def run(view: ProjectView,
          build: Build,
          arguments: List[String],
          onOutput: String => Unit,
          timeout: java.time.Duration): Outcome = {
    val classpath = view.runtimeClasspath(build).map(_.toAbsolutePath.toString).mkString(File.pathSeparator)
    val command = javaBinary :: "-cp" :: classpath :: MainClass :: arguments

    val process = new ProcessBuilder(command*)
      // In the project, so a program that reads a relative path finds what the user would expect.
      .directory(view.projectPath.toFile)
      // Merged, so the program's own interleaving survives.
      .redirectErrorStream(true)
      .start()

    // Nothing is written to the program's input. Closing it means a program that reads stdin sees the
    // end of it rather than blocking until the timeout.
    process.getOutputStream.close()

    try {
      val reader = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
      var line = reader.readLine()
      while (line != null) {
        onOutput(line)
        line = reader.readLine()
      }

      if (process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)) {
        Outcome(process.exitValue(), timedOut = false)
      } else {
        process.destroyForcibly()
        Outcome(process.exitValue(), timedOut = true)
      }
    } catch {
      case _: InterruptedException =>
        process.destroyForcibly()
        Thread.currentThread().interrupt()
        Outcome(exitCode = -1, timedOut = true)
    }
  }

  /** The `java` of the running JVM, which is the one this compiler's output was built for. */
  private def javaBinary: String = {
    val name = if (System.getProperty("os.name", "").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", name).toAbsolutePath.normalize().toString
  }
}
