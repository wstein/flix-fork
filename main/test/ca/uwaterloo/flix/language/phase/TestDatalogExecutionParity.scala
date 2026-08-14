/*
 * Copyright 2026 Magnus Madsen, Werner Stein
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

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{ExecutionMode, Options, Result}
import org.scalatest.funsuite.AnyFunSuite

class TestDatalogExecutionParity extends AnyFunSuite with TestUtils {

  private def runWithMode(src: String, mode: ExecutionMode): Unit = {
    val options = Options.TestWithLibAll.copy(xdatalogExecution = mode)
    val flix = new Flix().setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, src)
    flix.compile() match {
      case Result.Ok(res) =>
        val tests = JvmLoader.load(res).tests
        val (_, testFn) = tests.headOption.getOrElse(fail("No @Test found in compilation result"))
        testFn.run()
      case Result.Err(errors) =>
        fail(s"Compilation failed under mode $mode with errors: $errors")
    }
  }

  private def assertParity(src: String): Unit = {
    runWithMode(src, ExecutionMode.Parallel)
    runWithMode(src, ExecutionMode.Sequential)
  }

  test("Parity.TransitiveClosure") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testTransitiveClosure(): Unit \ Assert = {
        |    let p = #{
        |        Edge(1, 2).
        |        Edge(2, 3).
        |        Edge(3, 4).
        |        Edge(4, 5).
        |        Path(x, y) :- Edge(x, y).
        |        Path(x, z) :- Path(x, y), Edge(y, z).
        |    };
        |    let res = solve p;
        |    let q = query res select (x, y) from Path(x, y) |> Vector.sort;
        |    assertEq(expected = Vector#{(1, 2), (1, 3), (1, 4), (1, 5), (2, 3), (2, 4), (2, 5), (3, 4), (3, 5), (4, 5)}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.MultiIndex.MergeGroups") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testMultiIndex(): Unit \ Assert = {
        |    let p = #{
        |        RelA(1, 2, 3).
        |        RelA(2, 3, 4).
        |        RelA(3, 4, 5).
        |        RelB(1, 2, 30).
        |        RelB(2, 3, 40).
        |        Join1(x, y, z) :- RelA(x, y, z), RelB(x, y, _).
        |        Join2(x, y, z) :- RelA(x, y, _), RelB(x, _, z).
        |        Out(x, y, z) :- Join1(x, y, z).
        |        Out(x, y, z) :- Join2(x, y, z).
        |    };
        |    let res = solve p;
        |    let q = query res select (x, y, z) from Out(x, y, z) |> Vector.sort;
        |    assertEq(expected = Vector#{(1, 2, 3), (1, 2, 30), (2, 3, 4), (2, 3, 40)}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.Lattice.ConstantPropagation") {
    val src =
      """
        |use Assert.assertEq;
        |
        |enum Sign with Eq, Order, ToString {
        |    case Bot,
        |    case Pos,
        |    case Zero,
        |    case Neg,
        |    case Top
        |}
        |
        |instance LowerBound[Sign] {
        |    pub def minValue(): Sign = Sign.Bot
        |}
        |
        |instance PartialOrder[Sign] {
        |    pub def lessEqual(x: Sign, y: Sign): Bool = match (x, y) {
        |        case (Sign.Bot, _) => true
        |        case (x1, y1) if x1 == y1 => true
        |        case (_, Sign.Top) => true
        |        case _ => false
        |    }
        |}
        |
        |instance JoinLattice[Sign] {
        |    pub def leastUpperBound(x: Sign, y: Sign): Sign = match (x, y) {
        |        case (Sign.Bot, b) => b
        |        case (a, Sign.Bot) => a
        |        case (a, b) if a == b => a
        |        case _ => Sign.Top
        |    }
        |}
        |
        |instance MeetLattice[Sign] {
        |    pub def greatestLowerBound(x: Sign, y: Sign): Sign = match (x, y) {
        |        case (Sign.Top, b) => b
        |        case (a, Sign.Top) => a
        |        case (a, b) if a == b => a
        |        case _ => Sign.Bot
        |    }
        |}
        |
        |@Test
        |def testLattice(): Unit \ Assert = {
        |    let p = #{
        |        Val(1; Sign.Pos).
        |        Val(2; Sign.Zero).
        |        Val(3; Sign.Neg).
        |        Flow(1, 2).
        |        Flow(2, 3).
        |        Flow(3, 1).
        |        Val(dst; s) :- Flow(src, dst), Val(src; s).
        |    };
        |    let res = solve p;
        |    let q = query res select (node, sign) from Val(node; sign) |> Vector.sort;
        |    assertEq(expected = Vector#{(1, Sign.Top), (2, Sign.Top), (3, Sign.Top)}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.StratifiedNegation") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testStratifiedNegation(): Unit \ Assert = {
        |    let p = #{
        |        Node(1). Node(2). Node(3). Node(4). Node(5).
        |        Blocked(2). Blocked(4).
        |        Available(x) :- Node(x), not Blocked(x).
        |    };
        |    let res = solve p;
        |    let q = query res select x from Available(x) |> Vector.sort;
        |    assertEq(expected = Vector#{1, 3, 5}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.MultiStageDerivation") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testMultiStageDerivation(): Unit \ Assert = {
        |    let p = #{
        |        A(1). A(2).
        |        B(x) :- A(x).
        |        C(x, x + 10) :- B(x).
        |    };
        |    let res = solve p;
        |    let q = query res select (x, y) from C(x, y) |> Vector.sort;
        |    assertEq(expected = Vector#{(1, 11), (2, 12)}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.Provenance") {
    // Exercises `RelOp.ProvProject`, which is only reachable through `psolve`/`pquery`.
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testProvenance(): Unit \ Assert = {
        |    let p = #{
        |        Edge(1, 2). Edge(2, 3). Edge(3, 4).
        |        Path(x, y) :- Edge(x, y).
        |        Path(x, z) :- Path(x, y), Edge(y, z).
        |    };
        |    let pm = psolve p;
        |    let result = pquery pm select Path(1, 3) with {Edge, Path};
        |    let actual = result |> Vector.map(v -> ematch v {
        |        case Edge(x, y) => "Edge(${x}, ${y})"
        |        case Path(x, y) => "Path(${x}, ${y})"
        |    });
        |    assertEq(expected = Vector#{"Path(1, 3)", "Path(1, 2)", "Edge(1, 2)", "Edge(2, 3)"}, actual)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.FunctionalPredicate") {
    // Exercises `RelOp.Functional`.
    val src =
      """
        |use Assert.assertEq;
        |
        |def divisors(x: Int32): Vector[Int32] =
        |    Vector.range(1, x + 1) |> Vector.filter(d -> Int32.modulo(x, d) == 0)
        |
        |@Test
        |def testFunctional(): Unit \ Assert = {
        |    let p = #{
        |        Num(6). Num(10).
        |        Divisor(n, d) :- Num(n), let d = divisors(n).
        |    };
        |    let res = solve p;
        |    let q = query res select (n, d) from Divisor(n, d) |> Vector.sort;
        |    assertEq(expected = Vector#{(6, 1), (6, 2), (6, 3), (6, 6), (10, 1), (10, 2), (10, 5), (10, 10)}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.Inject") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testInject(): Unit \ Assert = {
        |    let facts = Vector#{1, 2, 3, 4};
        |    let p = inject facts into Seed/1;
        |    let rules = #{
        |        Even(x) :- Seed(x), if (Int32.modulo(x, 2) == 0).
        |        Doubled(x + x) :- Even(x).
        |    };
        |    let res = solve (p <+> rules);
        |    let q = query res select x from Doubled(x) |> Vector.sort;
        |    assertEq(expected = Vector#{4, 8}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.SolveProject") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testSolveProject(): Unit \ Assert = {
        |    let p = #{
        |        Edge(1, 2). Edge(2, 3).
        |        Path(x, y) :- Edge(x, y).
        |        Path(x, z) :- Path(x, y), Edge(y, z).
        |        Unrelated(42).
        |    };
        |    let res = solve p project Path;
        |    let paths = query res select (x, y) from Path(x, y) |> Vector.sort;
        |    assertEq(expected = Vector#{(1, 2), (1, 3), (2, 3)}, paths);
        |    let others: Vector[Int32] = query res select x from Unrelated(x);
        |    assertEq(expected = Vector#{}, others)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.JoinWithExistingModel") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testJoinExisting(): Unit \ Assert = {
        |    let base = solve #{ Base(1, 100). Base(2, 200). };
        |    let extra = #{
        |        Extra(1, 10). Extra(2, 20). Extra(3, 30).
        |        Combined(x, a + b) :- Base(x, a), Extra(x, b).
        |    };
        |    let res = solve (base <+> extra);
        |    let q = query res select (x, sum) from Combined(x, sum) |> Vector.sort;
        |    assertEq(expected = Vector#{(1, 110), (2, 220)}, q)
        |}
      """.stripMargin
    assertParity(src)
  }

  test("Parity.LargeRecursiveWorkload") {
    val src =
      """
        |use Assert.assertEq;
        |
        |@Test
        |def testLargeWorkload(): Unit \ Assert = {
        |    let facts = #{
        |        Step(0, 1). Step(1, 2). Step(2, 3). Step(3, 4). Step(4, 5).
        |        Step(5, 6). Step(6, 7). Step(7, 8). Step(8, 9). Step(9, 10).
        |        Reach(x, y) :- Step(x, y).
        |        Reach(x, z) :- Reach(x, y), Step(y, z).
        |    };
        |    let res = solve facts;
        |    let q = query res select (x, y) from Reach(x, y);
        |    assertEq(expected = 55, Vector.length(q))
        |}
      """.stripMargin
    assertParity(src)
  }
}
