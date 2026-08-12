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
package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.util.Build

import java.io.File
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/**
  * How a compiled Flix program is started in a JVM of its own.
  *
  * ==Why this is one definition and not two==
  *
  * The command is short and every part of it is a decision that was made once and must not be made
  * differently a second time: which `java`, which classpath, which class. `flix run` and
  * `buildTarget/run` want different *input and output* handling -- a terminal in one case, log
  * notifications in the other -- and identical *launching*. Splitting them along that line keeps the
  * one part where a mistake is invisible until a program fails to start in a single place.
  *
  * ==The entry class==
  *
  * `Main`, in the root package, with a `public static void main(String[])`. That is what `CodeGen`
  * emits for a program with an entry point, and it is a fixed name rather than something recorded, so
  * the class directory alone is enough to start a program. The generated method hands `argv` to
  * `Global.setArgs` before calling into the program, which is what makes `Env.getArgs()` work in a
  * forked run.
  *
  * @see [[ProjectView.runtimeClasspath]] for what is on the classpath and, more importantly, what is
  *      deliberately not: `flix.jar`, whose mock `dev.flix.runtime.Global` would shadow the program's
  *      own generated copy and throw before `main`.
  */
object ProgramRunner {

  /** The class `CodeGen` emits for a program's entry point. */
  val MainClass: String = "Main"

  /**
    * Stops `process` and everything it started, and waits for them to be gone.
    *
    * ==Why the tree and not the process==
    *
    * `Process.destroyForcibly` kills one process. A program that started a child -- a helper, a shell, a
    * database it spins up for a run -- leaves those children alive when its own JVM dies, holding the
    * pipes the run was reading and writing output after the task that owned them reported finished. From
    * the outside that looks like a server that lies about having stopped something.
    *
    * The descendants are snapshotted *before* anything is killed. Once the root is gone its children are
    * reparented and `descendants()` no longer reaches them, so a snapshot taken afterwards finds nothing
    * and the leak is invisible.
    *
    * Reaped with a bounded wait, because a process that refuses to die must not become a server that
    * refuses to answer. What cannot be reaped in the grace period is left, having been signalled.
    */
  def terminateTree(process: Process, grace: java.time.Duration): Unit = {
    val descendants =
      try process.toHandle.descendants().toList.asScala.toList
      catch {
        // A process that has already exited has no handle to walk, which is not a failure here.
        case _: Exception => Nil
      }

    // Children first, then the root: killing the root first is what orphans them.
    descendants.foreach(handle => try handle.destroyForcibly() catch { case _: Exception => () })
    process.destroyForcibly()

    val deadline = System.nanoTime() + grace.toNanos
    try {
      process.waitFor(grace.toMillis, TimeUnit.MILLISECONDS)
      descendants.foreach { handle =>
        val left = deadline - System.nanoTime()
        if (left > 0 && handle.isAlive) {
          try handle.onExit().get(left, TimeUnit.NANOSECONDS)
          catch { case _: Exception => () }
        }
      }
    } catch {
      case _: InterruptedException => Thread.currentThread().interrupt()
    }
  }

  /** Returns the command that starts the program of `view`, built in `build`, with `arguments`. */
  def command(view: ProjectView, build: Build, arguments: List[String]): List[String] = {
    val classpath = view.runtimeClasspath(build).map(_.toAbsolutePath.toString).mkString(File.pathSeparator)
    javaBinary :: "-cp" :: classpath :: MainClass :: arguments
  }

  /**
    * The `java` of the running JVM, which is the one this compiler's output was built for.
    *
    * Not `java` from the path: a program compiled by a compiler running on one JDK and started on an
    * older one fails with a class-version error that says nothing about why.
    */
  private def javaBinary: String = {
    val name = if (System.getProperty("os.name", "").toLowerCase.contains("win")) "java.exe" else "java"
    Paths.get(System.getProperty("java.home"), "bin", name).toAbsolutePath.normalize().toString
  }
}
