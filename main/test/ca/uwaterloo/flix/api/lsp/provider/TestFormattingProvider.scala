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
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.{FormatterLsp, FormattingOptions, TextEdit}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Paths

/**
  * Tests for `textDocument/formatting` as an editor actually reaches it.
  *
  * The formatter's other suites drive [[ca.uwaterloo.flix.tools.fmt.PrettyPrinter]]
  * directly, which cannot see whether the LSP is wired to anything. It was not:
  * the provider formatted with the default policy, so it returned the document it
  * was given and format-on-save did nothing in any editor. Every property test
  * passed throughout, because none of them went through this door.
  */
class TestFormattingProvider extends AnyFunSuite {

  /** The client's options, which the provider is expected to ignore. */
  private val AnyOptions: FormattingOptions = FormattingOptions(tabSize = 2, insertSpaces = true)

  private val Uri: String = "TestFormattingProvider.flix"

  /** Compiles `src` as the only source and returns the edits the LSP would send. */
  private def formatViaLsp(src: String): (List[TextEdit], Flix) = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    implicit val flix: Flix = new Flix().setOptions(Options.Default)
    flix.addVirtualPath(Paths.get(Uri), src)
    flix.check()
    (FormattingProvider.formatDocument(Uri, AnyOptions), flix)
  }

  /**
    * The text the editor would end up with after applying the edits.
    *
    * The edits are minimal, so the result has to be computed by applying them to
    * the buffer rather than read off a single edit's replacement text.
    */
  private def formattedText(src: String): String = {
    val (edits, _) = formatViaLsp(src)
    FormatterLsp.applyTextEditsToString(src, edits)
  }

  test("formatting an unformatted document actually changes it") {
    // The regression this file exists for. Before the provider was wired to the
    // canonical policy this returned the input verbatim.
    val src = "def add(x:Int32,y:Int32):Int32=x+y\n"
    assert(formattedText(src) == "def add(x: Int32, y: Int32): Int32 = x + y\n")
  }

  test("formatting lays out a document vertically, not just horizontally") {
    val src = "def f(o: Option[Int32]): Int32 = match o { case Some(x) => x\n  case None => 0 }\n"
    val expected =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) => x
        |    case None    => 0
        |}
        |""".stripMargin
    assert(formattedText(src) == expected)
  }

  test("formatting an already canonical document is a no-op") {
    val src = "def add(x: Int32, y: Int32): Int32 = x + y\n"
    assert(formattedText(src) == src)
  }

  test("the client's tab size is ignored") {
    // `AnyOptions` asks for two-space tabs. The indentation unit is fixed at four
    // by docs/STYLE.md, and honouring the client here would make the formatter
    // configurable through the back door.
    val src =
      """mod M {
        |pub def f(): Int32 = 1
        |}
        |""".stripMargin
    val expected =
      """mod M {
        |    pub def f(): Int32 = 1
        |}
        |""".stripMargin
    assert(formattedText(src) == expected)
  }

  test("a document that does not parse is still formatted around the break") {
    // An editor asks to format while the user is mid-edit, which is exactly when
    // the document is broken. The healthy declaration is laid out and the broken
    // one is left alone.
    val src =
      """def healthy(x:Int32):Int32=x
        |
        |def broken(: =
        |""".stripMargin
    val out = formattedText(src)
    assert(out.contains("def healthy(x: Int32): Int32 = x"), s"healthy is formatted:\n$out")
    assert(out.contains("def broken(: ="), s"broken is untouched:\n$out")
  }

  test("an unknown uri yields no edits rather than failing") {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    implicit val flix: Flix = new Flix().setOptions(Options.Default)
    flix.addVirtualPath(Paths.get(Uri), "def f(): Int32 = 1\n")
    flix.check()
    assert(FormattingProvider.formatDocument("NoSuchFile.flix", AnyOptions).isEmpty)
  }

  test("the edit covers only what changed, not the whole document") {
    // An editor applies an edit literally, so a full-buffer replacement would
    // collapse undo, move the caret and reset folding for a one-line change.
    val src =
      """def a(): Int32 = 1
        |def b():Int32=2
        |def c(): Int32 = 3
        |""".stripMargin
    val (edits, _) = formatViaLsp(src)
    assert(edits.sizeIs == 1, s"expected one edit, got ${edits.size}")
    val range = edits.head.range
    assert(range.start.line == 2, s"starts on the changed line, got ${range.start}")
    assert(range.end.line == 2, s"ends on the changed line, got ${range.end}")
  }

  test("formatting an already canonical document produces no edits at all") {
    val src = "def add(x: Int32, y: Int32): Int32 = x + y\n"
    val (edits, _) = formatViaLsp(src)
    assert(edits.isEmpty, s"expected no edits, got $edits")
  }
}
