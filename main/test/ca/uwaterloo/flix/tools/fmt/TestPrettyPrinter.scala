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
  * Unit tests for [[PrettyPrinter]] on small programs.
  *
  * The corpus-wide properties are checked by [[TestFormatterCorrectness]] and
  * [[TestFormatterStability]]. These cover the cases that are easy to state and
  * easy to regress, and the separator policy that layout rules will be built on.
  */
class TestPrettyPrinter extends TestFormatterCommon {

  /** Parses `src` as a standalone program and formats it. */
  private def roundTrip(src: String): String =
    PrettyPrinter.format(reparseAt(exampleFlix, "TestPrettyPrinter.flix", src, restoreTo = None).tree)

  test("format: a simple declaration round-trips exactly") {
    val src =
      """def f(): Int32 = 42
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: unusual spacing is preserved, since no layout rule applies yet") {
    val src =
      """def    f( ):Int32     =    42
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: comments round-trip in place") {
    val src =
      """// leading
        |def f(): Int32 =
        |    let x = 1;   // trailing
        |    // about y
        |    let y = 2;
        |    x + y // the sum
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: doc comments and annotations round-trip") {
    val src =
      """///
        |/// Checks that addition works.
        |///
        |@Test
        |def testAdd(): Unit \ Assert = Assert.assertEq(expected = 3, 1 + 2)
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: a block comment licence header round-trips byte for byte") {
    // The ` * ` continuation lines are one-space indented. Re-indenting them to a
    // multiple of four would rewrite the licence header of every file that has one.
    val src =
      """/*
        | * Copyright 2026 Example
        | *
        | * Licensed under the Apache License, Version 2.0
        | */
        |def f(): Int32 = 42
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: an escaped name keeps its dollar sign") {
    // The lexer leaves the `$` outside the name token, so a printer that read
    // token text directly would emit `def run`, renaming this to a keyword.
    val src =
      """def $run(): Int32 = 42
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: string interpolation is not altered") {
    val src =
      """def f(x: Int32): String = "value    =    ${x}"
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: blank lines between declarations are preserved") {
    val src =
      """def f(): Int32 = 1
        |
        |
        |def g(): Int32 = 2
        |""".stripMargin
    assert(roundTrip(src) == src)
  }

  test("format: is idempotent") {
    val src =
      """def f( ):Int32 = 42 // note
        |""".stripMargin
    val once = roundTrip(src)
    assert(roundTrip(once) == once)
  }

  test("format: a separator policy decides the gaps") {
    // Demonstrates the seam every layout rule plugs into: replacing the policy
    // changes whitespace and nothing else.
    val squash = new PrettyPrinter.Separators {
      override def between(
        left: Option[TokenStream.PrintableToken],
        right: Option[TokenStream.PrintableToken],
        original: String
      ): String = (left, right) match {
        case (Some(_), Some(_)) => if (original.isEmpty) "" else " "
        case _ => "" // trim the file's leading and trailing whitespace
      }
    }
    val src =
      """def f(): Int32 =
        |    42
        |""".stripMargin
    val tree = reparseAt(exampleFlix, "TestPrettyPrinter.flix", src, restoreTo = None).tree
    assert(PrettyPrinter.format(tree, squash) == "def f(): Int32 = 42")
  }
}
