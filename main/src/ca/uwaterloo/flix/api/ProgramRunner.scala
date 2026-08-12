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
