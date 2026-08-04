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
package ca.uwaterloo.flix.tools.fmt

import ca.uwaterloo.flix.language.ast.{SyntaxTree, TokenKind}

/**
  * Tests for [[TokenStream]].
  *
  * Two things are established here. First, that the syntax tree really is
  * full-fidelity: every token the lexer produced is reachable from the tree, in
  * order, with its text intact. That is what makes any printer built on the tree
  * able to reproduce a program, and it is asserted against the whole corpus
  * rather than argued from the parser's source.
  *
  * Second, that comments are among those tokens and that their anchors say where
  * they sit. This is the only check in the formatter suite that can see a comment
  * move.
  */
class TestTokenStream extends TestFormatterCommon {

  /** Parses `src` as a standalone program and returns its token stream. */
  private def parse(src: String): Parsed =
    reparseAt(exampleFlix, "TestTokenStream.flix", src, restoreTo = None)

  test("printableTokens: an escaped name keeps its dollar sign") {
    // The lexer excludes the `$` from the name token on purpose, so `$run` has a
    // token whose own text is `run`. Printing that text would rename the
    // definition to a keyword, so the `$` is attributed to the token here.
    val src =
      """def $run(): Int32 = 42
        |""".stripMargin
    val printable = TokenStream.printableTokens(parse(src).tree)
    assert(printable.map(_.token.text).contains("run"), "the token itself excludes the $")
    assert(printable.map(_.text).contains("$run"), "the printable text must restore it")
  }

  test("printableTokens: an escaped name in member position keeps its dollar sign") {
    val src =
      """def f(x: BigInt, y: BigInt): BigInt = x.$and(y)
        |""".stripMargin
    assert(TokenStream.printableTokens(parse(src).tree).map(_.text).contains("$and"))
  }

  test("printableTokens: ordinary tokens are printed as themselves") {
    val src =
      """def f(): Int32 = 42
        |""".stripMargin
    val printable = TokenStream.printableTokens(parse(src).tree)
    assert(printable.forall(p => p.text == p.token.text))
  }

  test("sourceText: a whole file is reproduced exactly") {
    val src =
      """// leading
        |def f(): Int32 = 42 // trailing
        |""".stripMargin
    // Leading and trailing whitespace lie outside the span of any token, so the
    // slice is compared against the source with those trimmed off.
    assert(TokenStream.sourceText(parse(src).tree) == src.strip())
  }

  test("tokens: line comments are retained in the tree") {
    val src =
      """// leading
        |def f(): Int32 = 42 // trailing
        |""".stripMargin
    val texts = TokenStream.tokens(parse(src).tree)
      .filter(_.kind.isComment)
      .map(_.text.trim)
    assert(texts == Vector("// leading", "// trailing"))
  }

  test("tokens: block and doc comments are retained in the tree") {
    val src =
      """/* block */
        |///
        |/// Doc.
        |///
        |def f(): Int32 = 42
        |""".stripMargin
    val kinds = TokenStream.tokens(parse(src).tree).filter(_.kind.isComment).map(_.kind)
    assert(kinds.contains(TokenKind.CommentBlock))
    assert(kinds.contains(TokenKind.CommentDoc))
  }

  test("codeTokens: comments are excluded") {
    val src =
      """// a comment
        |def f(): Int32 = 42
        |""".stripMargin
    assert(TokenStream.codeTokens(parse(src).tree).forall(!_.kind.isComment))
  }

  test("commentAnchors: a leading comment anchors before the first code token") {
    val src =
      """// leading
        |def f(): Int32 = 42
        |""".stripMargin
    val anchors = TokenStream.commentAnchors(parse(src).tree)
    assert(anchors.length == 1)
    assert(anchors.head.ordinal == 0)
    assert(anchors.head.before.isEmpty)
    assert(anchors.head.after.contains(TokenKind.KeywordDef))
  }

  test("commentAnchors: a trailing comment anchors after the last code token") {
    val src =
      """def f(): Int32 = 42 // trailing
        |""".stripMargin
    val parsed = parse(src)
    val anchors = TokenStream.commentAnchors(parsed.tree)
    assert(anchors.length == 1)
    assert(anchors.head.ordinal == TokenStream.codeTokens(parsed.tree).length)
    assert(anchors.head.after.isEmpty)
  }

  test("commentAnchors: an interior comment names the tokens it sits between") {
    val src =
      """def f(): Int32 =
        |    let x = 1;
        |    // about the second
        |    let y = 2;
        |    x + y
        |""".stripMargin
    val anchors = TokenStream.commentAnchors(parse(src).tree)
    assert(anchors.length == 1)
    assert(anchors.head.before.contains(TokenKind.Semi))
    assert(anchors.head.after.contains(TokenKind.KeywordLet))
  }

  test("commentAnchors: comments in the same gap share an ordinal and keep their order") {
    val src =
      """def f(): Int32 =
        |    let x = 1;
        |    // first
        |    // second
        |    x
        |""".stripMargin
    val anchors = TokenStream.commentAnchors(parse(src).tree)
    assert(anchors.map(_.text.trim) == Vector("// first", "// second"))
    assert(anchors.map(_.ordinal).distinct.length == 1)
  }

  test("commentAnchors: reformatting whitespace does not move a comment") {
    // The two programs differ only in layout, so their comments must anchor
    // identically. This is the shape of the check that guards the printer.
    val loose =
      """def f(): Int32 =
        |    let x = 1;
        |    // about y
        |    let y = 2;
        |    x + y
        |""".stripMargin
    val tight =
      """def f(): Int32 = { let x = 1;
        |// about y
        |let y = 2; x + y }
        |""".stripMargin
    val a = TokenStream.commentAnchors(parse(loose).tree)
    val b = TokenStream.commentAnchors(parse(tight).tree)
    assert(a.map(x => (x.text, x.before, x.after)) == b.map(x => (x.text, x.before, x.after)))
  }

  test("tokens: the tree is full-fidelity across the examples corpus") {
    checkFullFidelity(ExampleSamples)
  }

  test("tokens: the tree is full-fidelity across the standard library") {
    checkFullFidelity(StdlibSamples)
  }

  /**
    * Asserts that printing every token of the tree in order reproduces every
    * non-whitespace character of the source, in order and without invention.
    *
    * This is the property the whole formatter rests on. A formatter may choose
    * where whitespace goes and nothing else, so if the printable tokens account
    * for all the remaining characters, a printer driven by this tree can reproduce
    * any program — and if they do not, some character has no way of being printed.
    *
    * Comments are the case that matters most: they are what a parser is most
    * likely to discard, and losing one silently corrupts generated documentation.
    */
  private def checkFullFidelity(samples: List[Sample]): Unit = {
    for (sample <- samples) {
      // Reparsing runs a full compile, so both properties are asserted from one
      // parse rather than walking the corpus twice.
      val tree = sample.original.tree
      val printed = TokenStream.printableTokens(tree).map(_.text).mkString
      assert(dense(printed) == dense(sample.content),
        s"Tokens do not account for every character of ${sample.path}")
      assert(TokenStream.sourceText(tree) == sample.content.strip(),
        s"Source is not reproduced for ${sample.path}")
    }
  }

  /** `s` with all whitespace removed. */
  private def dense(s: String): String = s.filterNot(_.isWhitespace)
}
