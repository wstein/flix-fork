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
  * Tests for formatting a file that does not parse.
  *
  * A developer mid-edit has a broken program most of the time, so a formatter
  * that gives up on the whole file whenever one declaration is malformed is a
  * formatter that is unavailable exactly when it is being used. Instead the
  * declaration containing the parse error is reproduced verbatim and its siblings
  * are formatted normally.
  *
  * The declaration is the unit because the formatter already treats declarations
  * as independent. That also makes idempotence fall out: each declaration is
  * either formatted and stable, or untouched and trivially stable.
  */
class TestPartialFormatting extends TestFormatterCommon {

  /** Parses `src` whether or not it compiles, and formats it canonically. */
  private def canonical(src: String): String =
    parseTolerantly(exampleFlix, "TestPartialFormatting.flix", src)
      .map(PrettyPrinter.format(_, Canonical))
      .getOrElse(fail("the parser produced no tree at all"))

  test("partial: a healthy declaration beside a broken one is still formatted") {
    val src =
      """def healthy(x:Int32,y:Int32):Int32=x+y
        |
        |def broken(s: String): Option[Int32] =
        |    match s {
        |        case Some(n) => Some(n)
        |        case None =>
        |""".stripMargin
    val out = canonical(src)
    assert(out.contains("def healthy(x: Int32, y: Int32): Int32 = x + y"),
      s"the healthy declaration should be formatted:\n$out")
  }

  test("partial: the broken declaration is reproduced exactly") {
    // Its spacing is deliberately unusual. None of it may be normalised, because
    // the parser could not say what any of it means.
    val broken =
      """def broken(s:String):Option[Int32] =
        |    match s {
        |        case None =>
        |""".stripMargin
    val src = "def healthy(): Int32 = 1\n\n" + broken
    val out = canonical(src)
    assert(out.contains(broken.stripTrailing),
      s"the broken declaration should be untouched:\n$out")
  }

  test("partial: nothing is lost from a file that does not parse") {
    val src =
      """def healthy(x:Int32):Int32=x
        |
        |def broken(: =
        |
        |def alsoHealthy(y:Int32):Int32=y
        |""".stripMargin
    val out = canonical(src)
    assert(dense(out) == dense(src), s"characters went missing:\n$out")
  }

  test("partial: formatting a broken file is idempotent") {
    val src =
      """def healthy(x:Int32):Int32=x
        |
        |def broken(s: String): Option[Int32] =
        |    match s {
        |        case None =>
        |""".stripMargin
    val once = canonical(src)
    assert(canonical(once) == once, s"second pass changed the output:\n$once")
  }

  test("partial: a file whose every declaration is broken is reproduced") {
    val src =
      """def broken(: =
        |""".stripMargin
    assert(canonical(src) == src)
  }

  test("partial: a type error does not prevent formatting") {
    // Only *parse* errors quarantine. This parses cleanly and merely fails to
    // type check, so it is formatted like any other program.
    val src = "def wrong():Int32=\"not an int\"\n"
    assert(canonical(src) == "def wrong(): Int32 = \"not an int\"\n")
  }

  test("quarantined: marks exactly the tokens of the broken declaration") {
    val src =
      """def healthy(): Int32 = 1
        |
        |def broken(: =
        |""".stripMargin
    val tree = parseTolerantly(exampleFlix, "TestPartialFormatting.flix", src)
      .getOrElse(fail("the parser produced no tree at all"))
    val flags = TokenStream.quarantined(tree)
    val texts = TokenStream.tokens(tree).map(_.text)
    assert(flags.length == texts.length)
    // The second `def` opens the broken declaration, so it is the boundary: every
    // token before it belongs to the healthy one and must stay formattable, and
    // every token from it onward belongs to the broken one and must not.
    val boundary = texts.indexOf("def", 1)
    assert(boundary > 0, s"expected two declarations, got $texts")
    assert(flags.take(boundary).forall(!_),
      s"healthy tokens were quarantined: ${texts.zip(flags).take(boundary)}")
    assert(flags.drop(boundary).forall(identity),
      s"broken tokens were left formattable: ${texts.zip(flags).drop(boundary)}")
  }

  test("quarantined: a fully healthy file quarantines nothing") {
    val src = "def f(): Int32 = 1\n"
    val tree = parseTolerantly(exampleFlix, "TestPartialFormatting.flix", src)
      .getOrElse(fail("the parser produced no tree at all"))
    assert(!TokenStream.quarantined(tree).contains(true))
  }

  test("quarantined: is parallel to the token stream across the corpus") {
    // The two vectors are indexed together by the printer, so a length mismatch
    // would silently misalign every gap decision after the first.
    for (sample <- ExampleSamples) {
      val tree = sample.original.tree
      assert(TokenStream.quarantined(tree).length == TokenStream.tokens(tree).length,
        s"quarantine flags do not line up with tokens for ${sample.path}")
    }
  }

  /** `s` with all whitespace removed. */
  private def dense(s: String): String = s.filterNot(_.isWhitespace)
}
