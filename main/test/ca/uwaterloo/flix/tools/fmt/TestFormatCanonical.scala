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

class TestFormatCanonical extends TestFormatterCommon {

  private def formatCanonical(src: String): String = {
    val parsed = reparseAt(exampleFlix, "TestFormatCanonical.flix", src, restoreTo = None)
    PrettyPrinter.formatCanonical(parsed.tree, pageWidth = 80)
  }

  test("formatCanonical.01 — Simple function declaration character fidelity and idempotence") {
    val src = "def add(x: Int32, y: Int32): Int32 = x + y\n"
    val formatted = formatCanonical(src)
    assert(dense(formatted) == dense(src))
    assert(formatCanonical(formatted) == formatted)
  }

  test("formatCanonical.02 — If-else expression character fidelity and idempotence") {
    val src =
      """def f(x: Int32): String =
        |    if (x > 0) "positive" else "non-positive"
        |""".stripMargin
    val formatted = formatCanonical(src)
    assert(dense(formatted) == dense(src))
    assert(formatCanonical(formatted) == formatted)
  }

  test("formatCanonical.03 — Match expression character fidelity and idempotence") {
    val src =
      """def f(o: Option[Int32]): Int32 =
        |    match o {
        |        case Some(x) => x
        |        case None => 0
        |    }
        |""".stripMargin
    val formatted = formatCanonical(src)
    assert(dense(formatted) == dense(src))
    assert(formatCanonical(formatted) == formatted)
  }

  test("formatCanonical.04 — Determinism check across independent solve runs") {
    val src = "def compose(f: b -> c, g: a -> b, x: a): c = f(g(x))\n"
    val run1 = formatCanonical(src)
    val run2 = formatCanonical(src)
    assert(run1 == run2)
  }

  private def dense(s: String): String = s.filterNot(_.isWhitespace)
}
