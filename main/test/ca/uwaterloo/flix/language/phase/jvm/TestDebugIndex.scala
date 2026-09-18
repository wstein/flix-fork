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
    assert(classes.exists(_.contains("Clo$")), s"the lifted lambda is missing: $classes")
  }

  test("a class that belongs to no source is left out") {
    // Runtime support and the shared representations -- `Tag$Obj`, `Tuple$Int32$Int32` -- carry no
    // `.flix` source, and naming them would put every value class under whichever file loaded first.
    val index = indexOf(compile(xdebug = true))
    val named = index.values.flatten.toSet

    assert(!named.exists(_.endsWith("Tagged$")), s"a runtime class is indexed: $named")
  }

  test("the index is deterministic for unchanged emitted classes") {
    assert(indexOf(compile(xdebug = true)) == indexOf(compile(xdebug = true)))
  }

  /** The emitted classes whose attributes are the index's sole source of truth. */
  private def compile(xdebug: Boolean): Iterable[JvmClass] = {
    val opts = Options.DefaultTest.copy(xdebug = xdebug)
    val flix = new Flix().setOptions(opts)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
    flix.compile() match {
      case Result.Ok(result) => result.getClasses.values
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  private def indexOf(classes: Iterable[JvmClass]): Map[String, Set[String]] = DebugIndex.of(classes)

  /** The key for the test program's own source, whatever the compiler called it. */
  private def sourceKey(index: Map[String, Set[String]]): String =
    index.keys.find(_.contains(CompilerConstants.VirtualTestFile.toString))
      .getOrElse(fail(s"the test source is not in the index: ${index.keys}"))
}
