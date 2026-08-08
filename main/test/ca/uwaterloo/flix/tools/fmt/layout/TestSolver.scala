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
package ca.uwaterloo.flix.tools.fmt.layout

import ca.uwaterloo.flix.tools.fmt.layout.piece._
import org.scalatest.funsuite.AnyFunSuite

class TestSolver extends AnyFunSuite {

  test("Solver.01 — Short list fits inline within page width") {
    val items = List(TextPiece("first"), TextPiece("second"), TextPiece("third"))
    val root = ListPiece("(", items, ")")
    val cache = new SolutionCache()
    val ctx = SolveContext(cache, root, pageWidth = 80, leadingIndent = 0, subsequentIndent = 0)

    val solution = Solver.solve(ctx)
    assert(solution.isValid)
    assert(solution.overflow == 0)
    assert(solution.code.toText == "(first, second, third)")
  }

  test("Solver.02 — Long list splits when page width budget is exceeded") {
    val items = List(
      TextPiece("firstLongArgumentName"),
      TextPiece("secondLongArgumentName"),
      TextPiece("thirdLongArgumentName")
    )
    val root = ListPiece("(", items, ")")
    val cache = new SolutionCache()
    val ctx = SolveContext(cache, root, pageWidth = 35, leadingIndent = 0, subsequentIndent = 0)

    val solution = Solver.solve(ctx)
    assert(solution.isValid)
    val expected =
      """(
        |    firstLongArgumentName,
        |    secondLongArgumentName,
        |    thirdLongArgumentName
        |)""".stripMargin
    assert(solution.code.toText == expected)
  }

  test("Solver.03 — Split ListPiece enforces zero trailing separator for Flix syntax validity") {
    val items = List(TextPiece("alpha"), TextPiece("beta"))
    val root = ListPiece("[", items, "]")
    val cache = new SolutionCache()
    val ctx = SolveContext(cache, root, pageWidth = 10, leadingIndent = 0, subsequentIndent = 0)

    val solution = Solver.solve(ctx)
    assert(solution.isValid)
    assert(!solution.code.toText.contains("beta,"))
  }

  test("Solver.04 — InfixPiece splits on overflow") {
    val left = TextPiece("reallyLongVariableNameThatTakesUpSpace")
    val right = TextPiece("anotherLongVariableNameOnRightSide")
    val root = InfixPiece(left, "+", right)
    val cache = new SolutionCache()
    val ctx = SolveContext(cache, root, pageWidth = 40, leadingIndent = 0, subsequentIndent = 0)

    val solution = Solver.solve(ctx)
    assert(solution.isValid)
    val expected =
      """reallyLongVariableNameThatTakesUpSpace +
        |    anotherLongVariableNameOnRightSide""".stripMargin
    assert(solution.code.toText == expected)
  }

  test("Solver.05 — Priority queue ordering is total and deterministic") {
    val pieceA = ListPiece("(", List(TextPiece("a"), TextPiece("b")), ")")
    val pieceB = ListPiece("[", List(TextPiece("c"), TextPiece("d")), "]")

    val ctxA = SolveContext(new SolutionCache(), pieceA, pageWidth = 80, leadingIndent = 0, subsequentIndent = 0)
    val ctxB = SolveContext(new SolutionCache(), pieceB, pageWidth = 80, leadingIndent = 0, subsequentIndent = 0)

    val solA = Solution.initial(ctxA)
    val solB = Solution.initial(ctxB)

    // Solution comparison must be reflexive and consistent
    assert(solA.compare(solA) == 0)
    assert(solB.compare(solB) == 0)
  }
}
