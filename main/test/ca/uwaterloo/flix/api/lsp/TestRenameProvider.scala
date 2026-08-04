/*
 * Copyright 2026 Flix Authors
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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.lsp.provider.RenameProvider
import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.TypedAst.Root
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

/**
  * Which symbols can be renamed.
  *
  * Rename used to answer for four kinds of symbol -- type parameters, type variables, and local
  * variables in their two forms -- while find references answered for fifteen. A def, trait, enum
  * or effect therefore produced no occurrences, `LspServer.rename` turned that into an empty
  * `WorkspaceEdit`, and the IDE reported "The element can't be renamed". Renaming is rewriting
  * every occurrence, so both features now ask [[FindReferencesProvider]] the same question and
  * cannot drift apart again.
  */
class TestRenameProvider extends AnyFunSuite {

  private val Program: String =
    """|pub trait Comparable[a] {
       |    pub def compare(x: a, y: a): Int32
       |}
       |
       |pub enum Colour {
       |    case Red,
       |    case Blue
       |}
       |
       |pub def maxDemo(a: Int32, b: Int32): Int32 = if (a > b) a else b
       |
       |pub def useIt(): Int32 = maxDemo(1, 2) + maxDemo(3, 4)
       |
       |pub def pick(c: Colour): Int32 = match c {
       |    case Colour.Red => 1
       |    case Colour.Blue => 2
       |}
       |""".stripMargin

  private def compile(program: String): (Root, Flix) = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.Default)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    flix.check() match {
      case (Some(r), _) => (r, flix)
      case (None, errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  /** How many edits renaming at 1-based `line`/`col` produces. */
  private def edits(line: Int, col: Int, program: String = Program): Int = {
    val (root, _) = compile(program)
    RenameProvider.processRename("renamed", CompilerConstants.VirtualTestFile.toString, Position(line, col))(root)
      .map(_.changes.values.map(_.size).sum)
      .getOrElse(0)
  }

  test("a def is renamed at its declaration and at every call") {
    // `maxDemo` is declared on line 10 and called twice on line 12. All three move together, or the
    // ones left behind refer to a name that no longer exists.
    assertResult(3)(edits(line = 10, col = 10))
  }

  test("renaming a def from a call site does the same as from its declaration") {
    // The user reaches for rename wherever the caret happens to be; both must mean the same thing.
    assertResult(edits(line = 10, col = 10))(edits(line = 12, col = 30))
  }

  test("a trait can be renamed") {
    // One edit, because nothing in this program uses `Comparable`; the point is that a trait is
    // now offered at all, where it previously produced nothing.
    assertResult(1)(edits(line = 1, col = 12))
  }

  test("an enum and its cases can be renamed") {
    // The enum is used in `pick`'s parameter type and in two qualified case patterns.
    assert(edits(line = 5, col = 11) >= 2, "enum declaration and its uses")
    assert(edits(line = 6, col = 11) >= 2, "a case and the pattern matching it")
  }

  test("a local variable is still renamed, as it always was") {
    // The four kinds rename supported before must not be lost in gaining the rest. `a` is the
    // first parameter of maxDemo, used twice in its body.
    assertResult(3)(edits(line = 10, col = 18))
  }

  test("a library symbol is refused rather than half-renamed") {
    // `println` is declared inside the compiler jar, which no edit can reach. Rewriting only the
    // call sites would leave them calling a name that does not exist, so nothing is offered.
    val program =
      """|pub def shout(): Unit \ IO = println("hi")
         |""".stripMargin
    assertResult(0)(edits(line = 1, col = 32, program))
  }
}
