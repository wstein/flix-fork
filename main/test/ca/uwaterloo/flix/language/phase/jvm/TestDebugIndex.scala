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
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._

/**
  * Tests the source-to-class index a `--Xdebug` build writes.
  *
  * A generated name encodes the symbol it came from and never the file: `Clo$main$626ZYxrpg1N`,
  * `Tag$Obj$Obj`. So a debugger asked to break on a line has no way to name the classes that
  * implement it, and watches every class the VM prepares instead -- correct, and a class-prepare
  * event per class loaded. This is the mapping that removes the search.
  */
class TestDebugIndex extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """|def one(): Int32 = 1
       |
       |def main(): Unit \ IO =
       |    let add = (x -> y -> x + y);
       |    println(add(one())(2))
       |""".stripMargin

  test("a source names every class carrying its code") {
    // Including the lifted lambdas: `add` becomes two closure classes, and their names say `main`
    // and a hash, so nothing about them identifies the file.
    val index = indexOf(compile(xdebug = true))
    val classes = index.getOrElse(sourceKey(index), Set.empty)

    assert(classes.exists(_.endsWith("Def$main")), s"the definition is missing: $classes")
    assert(classes.exists(_.endsWith("Def$one")), s"a second definition is missing: $classes")
    assert(classes.count(_.contains("Clo$")) >= 2, s"the lifted lambdas are missing: $classes")
  }

  test("a class that belongs to no source is left out") {
    // Runtime support and the shared representations -- `Tag$Obj`, `Tuple$Int32$Int32` -- carry no
    // `.flix` source, and naming them would put every value class under whichever file loaded first.
    val index = indexOf(compile(xdebug = true))
    val named = index.values.flatten.toSet

    assert(!named.exists(_.endsWith("Tagged$")), s"a runtime class is indexed: $named")
    assert(named.forall(_.startsWith("dev.flix.gen.") || named.isEmpty), s"unexpected class: $named")
  }

  test("a class carrying code from two files is listed under both") {
    // The many-to-many half. Inlining puts a foreign file's lines into a class and records it in the
    // class's SMAP, so the class implements lines of both -- and a debugger breaking in either file
    // has to be told about it. `--Xdebug` turns the optimizer off, so this is measured on an
    // optimized build: the index is asked directly rather than read from disk.
    val classes = compile(xdebug = false)
    val index = DebugIndex.of(classes)
    val shared = index.toList.flatMap { case (source, names) => names.map(_ -> source) }
      .groupBy(_._1).filter(_._2.size > 1)

    assert(shared.nonEmpty, "no class was listed under more than one source; inlining should produce one")
  }

  test("without --Xdebug no index is written") {
    val out = compileToDirectory(xdebug = false)
    assert(!Files.exists(out.resolve(DebugIndex.FileName)), "an optimized build wrote a debug index")
  }

  test("with --Xdebug the index is written beside the class directory") {
    // Beside it, because the class directory is reconciled against the class files a build produced
    // and a file that is not one either gets deleted or stops the build.
    val out = compileToDirectory(xdebug = true)
    assert(Files.exists(out.resolve(DebugIndex.FileName)), "no debug index was written")
    assert(Files.isDirectory(out.resolve("class")), "the class directory moved")
  }

  /**
    * The generated classes of [[Program]], read back from disk.
    *
    * From the files rather than from the compiler's own result, because that is what the index is
    * built from: the attributes of the classes that were *written*. A `CompilationResult` carries
    * their names and paths and not their bytes, which is the right shape for everything else it is
    * for.
    */
  private def compile(xdebug: Boolean): Iterable[JvmClass] = {
    val classDir = compileToDirectory(xdebug).resolve("class")
    Files.walk(classDir).toList.asScala
      .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".class"))
      .map { path =>
        val relative = classDir.relativize(path).toString.stripSuffix(".class").split('/').toList
        JvmClass(JvmName(relative.init, relative.last), Files.readAllBytes(path))
      }
      .toList
  }

  private def compileToDirectory(xdebug: Boolean): Path = {
    val out = Files.createTempDirectory("flix-debug-index-test")
    val opts = Options.DefaultTest.copy(xdebug = xdebug, outputJvm = true, outputPath = out)
    val flix = new Flix().setOptions(opts)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
    flix.compile().toResult match {
      case Result.Ok(_) => out
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  private def indexOf(classes: Iterable[JvmClass]): Map[String, Set[String]] = DebugIndex.of(classes)

  /** The key for the test program's own source, whatever the compiler called it. */
  private def sourceKey(index: Map[String, Set[String]]): String =
    index.keys.find(_.contains(CompilerConstants.VirtualTestFile.toString))
      .getOrElse(fail(s"the test source is not in the index: ${index.keys}"))
}
