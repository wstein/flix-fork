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

/**
  * Tests for the [[Canonical]] separator policy — `flix format --canonical`.
  *
  * The unit tests state each spacing rule on a program small enough to read. The
  * corpus tests state the three properties that must hold whatever the rules are:
  * the output contains the same characters, formatting twice changes nothing more
  * than formatting once, and no comment has moved.
  */
class TestCanonical extends TestFormatterCommon {

  /** Parses `src` as a standalone program and formats it canonically. */
  private def canonical(src: String): String =
    PrettyPrinter.format(
      reparseAt(exampleFlix, "TestCanonical.flix", src, restoreTo = None).tree,
      Canonical
    )

  test("canonical: punctuation and operators take their canonical spacing") {
    val src = "def add(x:Int32,y:Int32):Int32=x+y\n"
    assert(canonical(src) == "def add(x: Int32, y: Int32): Int32 = x + y\n")
  }

  test("canonical: a space before a colon is removed") {
    val src = "def f(name : String) : String = name\n"
    assert(canonical(src) == "def f(name: String): String = name\n")
  }

  test("canonical: application binds tightly, grouping after a keyword does not") {
    val src = "def f(c: Bool): Int32 = if (c) g (1) else 2\ndef g(x: Int32): Int32 = x\n"
    val out = canonical(src)
    assert(out.contains("if (c)"), s"grouping after a keyword keeps its space: $out")
    assert(out.contains("g(1)"), s"application is tight: $out")
  }

  test("canonical: a lambda arrow keeps its spaces") {
    // `->` with whitespace is the function arrow; without it, it is struct access.
    val src = "def f(l: List[Int32]): List[Int32] = List.map(x -> x + 1, l)\n"
    assert(canonical(src) == src)
  }

  test("canonical: a struct arrow stays tight") {
    // Spacing here is not style: `s->size` and `s -> size` lex differently.
    val src =
      """struct Counter[r] {
        |    mut size: Int32
        |}
        |
        |mod Counter {
        |    pub def get(s: Counter[r]): Int32 \ r = s->size
        |}
        |""".stripMargin
    assert(canonical(src) == src)
  }

  test("canonical: qualified names and record selection stay tight") {
    val src = "def f(r: {x = Int32, y = Int32}): Int32 = r#x + r#y\n"
    assert(canonical(src) == src)
  }

  test("canonical: a minus sign stays attached to its literal") {
    // Detaching it is not cosmetic: `-9223372036854775808i64` is Int64's least
    // value and is representable only as a negative literal, so `- 9223...i64`
    // is out of range and the formatted program stops compiling.
    val src =
      """def f(): Int32 = -1
        |
        |def g(): Int64 = -9223372036854775808i64
        |""".stripMargin
    assert(canonical(src) == src)
  }

  test("canonical: subtraction keeps its spacing") {
    val src = "def f(x: Int32): Int32 = x - 1\n"
    assert(canonical(src) == src)
  }

  test("canonical: string literals are never altered") {
    val src = "def f(x: Int32): String = \"value    =    ${x}\"\n"
    assert(canonical(src) == src)
  }

  test("canonical: aligned match arms keep their alignment") {
    // docs/STYLE.md: "Pattern matches should align `=>`". Collapsing these runs of
    // spaces would reformat the corpus against the maintainers' written rule.
    val src =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) => x
        |    case None    => 0
        |}
        |""".stripMargin
    assert(canonical(src) == src)
  }

  test("canonical: indentation and blank lines are left alone") {
    val src =
      """def f(): Int32 =
        |        let x = 1;
        |        x
        |
        |
        |def g(): Int32 = 2
        |""".stripMargin
    assert(canonical(src) == src)
  }

  test("canonical: comments keep their text and position") {
    val src =
      """// leading
        |def f(): Int32 =
        |    let x = 1; // trailing
        |    x
        |""".stripMargin
    assert(canonical(src) == src)
  }

  test("canonical: formatting is idempotent") {
    val src = "def add(x:Int32,y:Int32):Int32=x+y\n"
    val once = canonical(src)
    assert(canonical(once) == once)
  }

  test("canonical: the default policy accepts canonical output unchanged") {
    // Upstream compatibility: canonical output must be a fixed point of the
    // default `flix format`. That holds trivially while the default reproduces its
    // input, so this guards the day it stops doing so rather than proving much now.
    val once = canonical("def add(x:Int32,y:Int32):Int32=x+y\n")
    val tree = reparseAt(exampleFlix, "TestCanonical.flix", once, restoreTo = None).tree
    assert(PrettyPrinter.format(tree) == once)
  }

  test("canonical: every character survives across the examples corpus") {
    checkCharactersSurvive(ExampleSamples)
  }

  test("canonical: every character survives across the standard library") {
    checkCharactersSurvive(StdlibSamples)
  }

  test("canonical: output is stable and moves no comment (examples)") {
    for (sample <- ExampleSamples) {
      val once = PrettyPrinter.format(sample.original.tree, Canonical)
      val reparsed = sample.reparse(once).tree
      assert(PrettyPrinter.format(reparsed, Canonical) == once,
        s"Canonical formatting is not idempotent for ${sample.path}:\n" +
          firstDivergence(once, PrettyPrinter.format(reparsed, Canonical)))
      assert(anchors(sample.original.tree) == anchors(reparsed),
        s"Canonical formatting moved a comment in ${sample.path}")
    }
  }

  /**
    * Asserts that canonical formatting changes only whitespace.
    *
    * Cheap enough for both corpora because it needs no reparse: it compares the
    * output against the input with all whitespace removed, which is exactly the
    * part the policy is allowed to change.
    */
  private def checkCharactersSurvive(samples: List[Sample]): Unit = {
    for (sample <- samples) {
      val out = PrettyPrinter.format(sample.original.tree, Canonical)
      assert(dense(out) == dense(sample.content),
        s"Canonical formatting changed more than whitespace in ${sample.path}")
    }
  }

  /** The comments of `tree` paired with their place in the non-comment token stream. */
  private def anchors(tree: ca.uwaterloo.flix.language.ast.SyntaxTree.Tree): Vector[(String, Int)] =
    TokenStream.commentAnchors(tree).map(a => (a.text, a.ordinal))

  /** `s` with all whitespace removed. */
  private def dense(s: String): String = s.filterNot(_.isWhitespace)
}
