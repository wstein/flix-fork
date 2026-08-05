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
import ca.uwaterloo.flix.api.lsp.{FormattingOptions, TextEdit}
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

  /** The text the editor would end up with, given a single whole-document edit. */
  private def formattedText(src: String): String = {
    val (edits, _) = formatViaLsp(src)
    assert(edits.sizeIs == 1, s"expected one whole-document edit, got ${edits.size}")
    edits.head.newText
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

  test("the edit spans the whole document") {
    // The provider returns one edit covering the document. It is worth pinning:
    // an edit whose range fell short would truncate the file rather than reformat
    // it, and the range is computed from the source rather than from the tree.
    val src = "def f(): Int32 =\n    1\n"
    val (edits, _) = formatViaLsp(src)
    val range = edits.head.range
    assert(range.start.line == 1 && range.start.character == 1, s"starts at 1:1, got ${range.start}")
    val lines = src.split("\n", -1)
    assert(range.end.line == lines.length, s"ends on the last line, got ${range.end}")
    assert(range.end.character == lines.last.length + 1, s"ends past the last character, got ${range.end}")
  }
}
