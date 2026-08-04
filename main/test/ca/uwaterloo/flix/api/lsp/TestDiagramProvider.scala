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

import ca.uwaterloo.flix.api.lsp.provider.{DiagramProvider, HoverProvider}
import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.TypedAst.Root
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

/**
  * Looking up the structural diagram of a trait or module.
  *
  * Both servers answer this -- the VS Code protocol as `getDiagram`, standard LSP as the
  * `flix.showDiagram` command -- through this one lookup, so that a client asking one cannot get a
  * different answer from the other.
  */
class TestDiagramProvider extends AnyFunSuite {

  private val Program: String =
    """|pub trait Comparable[a] {
       |  pub def compare(x: a, y: a): Int32
       |}
       |
       |pub trait Equatable[a] with Comparable[a] {
       |  pub def isEq(x: a, y: a): Bool
       |}
       |
       |pub def standalone(x: Int32): Int32 = x
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

  test("a trait with a supertrait has a diagram") {
    val (root, flix) = compile(Program)
    DiagramProvider.getDiagram("Equatable", new DiagramProvider.Cache)(root, flix) match {
      case DiagramProvider.Result.Svg(svg) =>
        assert(svg.contains("<svg"), "expected SVG markup")
        assert(svg.contains("Equatable"), "expected the trait's own name in the diagram")
      case other => fail(s"expected a diagram, got $other")
    }
  }

  test("a name that matches nothing is Unknown, not an empty diagram") {
    // The distinction is the whole point: a client must be able to tell "nothing to draw" from
    // "you asked about something that does not exist", and both used to be a bare null.
    val (root, flix) = compile(Program)
    val result = DiagramProvider.getDiagram("NoSuchTrait", new DiagramProvider.Cache)(root, flix)
    assert(result.isInstanceOf[DiagramProvider.Result.Unknown], s"expected Unknown, got $result")
    assert(DiagramProvider.messageFor(result).contains("NoSuchTrait"))
  }

  test("an empty root answers Unknown rather than compiling a diagram for nothing") {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.Default)
    val result = DiagramProvider.getDiagram("Equatable", new DiagramProvider.Cache)(TypedAst.empty, flix)
    assert(result.isInstanceOf[DiagramProvider.Result.Unknown], s"expected Unknown, got $result")
  }

  test("a hover offers the View Diagram link only to a client that can follow it") {
    // The link is a `command:` URI, which only VS Code resolves. Every other client hands the
    // unknown scheme to the operating system, which reports that it cannot open it -- so the link
    // is offered on the VS Code path and withheld everywhere else, which is what these two calls
    // are: the same hover, asked for by the two protocols.
    val program =
      """|pub def target(x: Int32): Int32 = x
         |def main(): Unit \ IO = println(target(1))
         |""".stripMargin
    val (root, flix) = compile(program)
    val onTheCall = Position(2, 35) // inside `target` in the call on line 2

    val uri = CompilerConstants.VirtualTestFile.toString
    val forVSCode = HoverProvider.processHover(uri, onTheCall, commandLinks = true)(root, flix)
      .getOrElse(fail("no hover at the call site; the position no longer points at `target`"))
    val forEveryoneElse = HoverProvider.processHover(uri, onTheCall, commandLinks = false)(root, flix)
      .getOrElse(fail("no hover at the call site; the position no longer points at `target`"))

    assert(forVSCode.contents.value.contains("command:flix.showDiagram"), "VS Code gets the link")
    assert(
      !forEveryoneElse.contents.value.contains("command:"),
      s"a client that cannot follow it must not be offered it, got: ${forEveryoneElse.contents.value}",
    )
    // Withholding the link must not cost the documentation around it.
    assert(forEveryoneElse.contents.value.contains("target"), "the signature is still shown")
  }

  test("the cache serves a second request and still tracks a new root") {
    // Generating every diagram walks the whole module tree, so it must not repeat per hover -- and
    // must not survive a recompilation either. The cache keys on the root it was built from, so no
    // caller has to remember to invalidate it; this pins that it actually re-reads a new one.
    val cache = new DiagramProvider.Cache
    val (first, firstFlix) = compile(Program)

    val a = DiagramProvider.getDiagram("Equatable", cache)(first, firstFlix)
    val b = DiagramProvider.getDiagram("Equatable", cache)(first, firstFlix)
    assert(a == b, "the same root must give the same answer")

    // A different compilation, in which Equatable does not exist.
    val (second, secondFlix) = compile("pub trait Unrelated[a] { pub def f(x: a): a }")
    val c = DiagramProvider.getDiagram("Equatable", cache)(second, secondFlix)
    assert(
      c.isInstanceOf[DiagramProvider.Result.Unknown],
      s"a cache holding the previous root would still answer Svg here, got $c",
    )
  }
}
