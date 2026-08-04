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
  * Tests for the vertical layout of `match` and for produced `=>` alignment.
  *
  * These are the two rules the evidence settles outright: the corpus holds 1,868
  * `match` expressions and not one written inline, and `docs/STYLE.md` requires
  * *"Pattern matches should align `=>`"*. Everything else vertical — pipelines,
  * Datalog clause bodies, general indentation — is still preserved rather than
  * decided, and the corpus properties in [[TestCanonical]] guard that.
  */
class TestMatchLayout extends TestFormatterCommon {

  /** Parses `src` as a standalone program and formats it canonically. */
  private def canonical(src: String): String =
    PrettyPrinter.format(
      reparseAt(exampleFlix, "TestMatchLayout.flix", src, restoreTo = None).tree,
      Canonical
    )

  test("match: an inline match is broken onto one arm per line") {
    val src = "def f(o: Option[Int32]): Int32 = match o { case Some(x) => x\n  case None => 0 }\n"
    val expected =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) => x
        |    case None    => 0
        |}
        |""".stripMargin
    assert(canonical(src) == expected)
  }

  test("match: arms are indented one unit from the line the keyword sits on") {
    val src =
      """def f(o: Option[Int32]): Int32 =
        |    match o {
        |case Some(x) => x
        |case None => 0
        |    }
        |""".stripMargin
    val expected =
      """def f(o: Option[Int32]): Int32 =
        |    match o {
        |        case Some(x) => x
        |        case None    => 0
        |    }
        |""".stripMargin
    assert(canonical(src) == expected)
  }

  test("match: alignment is produced, not merely preserved") {
    // Neither input is aligned; both must come out aligned and identical, which is
    // what separates producing alignment from keeping what an author typed.
    val cramped =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) => x
        |    case None => 0
        |}
        |""".stripMargin
    val overpadded =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x)          => x
        |    case None                => 0
        |}
        |""".stripMargin
    assert(canonical(cramped) == canonical(overpadded))
    assert(canonical(cramped).contains("case None    => 0"))
  }

  test("match: a blank line starts a new alignment group") {
    val src =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) => x
        |
        |    case None => 0
        |}
        |""".stripMargin
    val out = canonical(src)
    // The groups are aligned independently, so the lone arm in each takes a
    // single space rather than being padded to the other's width.
    assert(out.contains("case Some(x) => x"), out)
    assert(out.contains("case None => 0"), out)
  }

  test("match: one very wide arm does not push the others across the screen") {
    val src =
      """def f(o: Option[Option[Option[Int32]]]): Int32 = match o {
        |    case Some(Some(Some(aVeryLongIdentifierIndeed))) => aVeryLongIdentifierIndeed
        |    case Some(Some(None)) => 1
        |    case Some(None) => 2
        |    case None => 3
        |}
        |""".stripMargin
    val out = canonical(src)
    assert(out.contains("case Some(Some(Some(aVeryLongIdentifierIndeed))) => aVeryLongIdentifierIndeed"),
      s"the outlier should take a single space:\n$out")
    // The other three share one column, and it is set by the widest of *them*
    // rather than by the outlier.
    val columns = out.linesIterator
      .filter(l => l.contains("=>") && !l.contains("aVeryLong"))
      .map(_.indexOf("=>")).toList
    assert(columns.sizeIs == 3, s"expected three ordinary arms, got $columns:\n$out")
    assert(columns.distinct.sizeIs == 1, s"ordinary arms are not aligned together:\n$out")
    assert(columns.head < out.linesIterator.find(_.contains("aVeryLong")).get.indexOf("=>"),
      s"the outlier should not have set the column:\n$out")
  }

  test("match: a guard is part of the aligned prefix") {
    val src =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) if x > 0 => x
        |    case Some(_) => 0
        |    case None => 0
        |}
        |""".stripMargin
    val out = canonical(src)
    assert(out.contains("case Some(x) if x > 0 => x"), out)
    assert(out.contains("case Some(_)          => 0"), out)
  }

  test("match: a nested match indents against its own line") {
    val src =
      """def f(o: Option[Option[Int32]]): Int32 = match o {
        |    case Some(inner) => match inner { case Some(x) => x
        |        case None => 0 }
        |    case None => 0
        |}
        |""".stripMargin
    val out = canonical(src)
    assert(out.contains("    case Some(inner) => match inner {"), out)
    assert(out.contains("\n        case Some(x) => x"), out)
    assert(out.contains("\n        case None    => 0"), out)
  }

  test("match: an arm carrying a comment is left unaligned") {
    val src =
      """def f(o: Option[Int32]): Int32 = match o {
        |    case Some(x) /* keep */ => x
        |    case None => 0
        |}
        |""".stripMargin
    val out = canonical(src)
    assert(out.contains("/* keep */"), s"the comment survives:\n$out")
    assert(dense(out) == dense(src), s"characters went missing:\n$out")
  }

  test("match: layout is idempotent") {
    val src = "def f(o: Option[Int32]): Int32 = match o { case Some(x) => x\n  case None => 0 }\n"
    val once = canonical(src)
    assert(canonical(once) == once, s"second pass changed the output:\n$once")
  }

  test("match: a broken match is not laid out") {
    // Quarantine outranks the layout plan: nothing is arranged around code the
    // parser could not read.
    val src =
      """def healthy(o: Option[Int32]): Int32 = match o { case Some(x) => x
        |  case None => 0 }
        |
        |def broken(s: String): Int32 =
        |    match s {
        |        case None =>
        |""".stripMargin
    val out = parseTolerantly(exampleFlix, "TestMatchLayout.flix", src)
      .map(PrettyPrinter.format(_, Canonical))
      .getOrElse(fail("the parser produced no tree at all"))
    assert(out.contains("    case Some(x) => x"), s"the healthy match is laid out:\n$out")
    assert(out.contains("        case None =>\n"), s"the broken one is untouched:\n$out")
  }

  test("match: the default policy still reproduces its input exactly") {
    // Vertical layout belongs to the canonical mode. `flix format` must remain a
    // round trip, or every file in the corpus would be rewritten by the mode that
    // promises not to.
    val src = "def f(o: Option[Int32]): Int32 = match o { case Some(x) => x\n  case None => 0 }\n"
    val tree = reparseAt(exampleFlix, "TestMatchLayout.flix", src, restoreTo = None).tree
    assert(PrettyPrinter.format(tree) == src)
  }

  /** `s` with all whitespace removed. */
  private def dense(s: String): String = s.filterNot(_.isWhitespace)
}
