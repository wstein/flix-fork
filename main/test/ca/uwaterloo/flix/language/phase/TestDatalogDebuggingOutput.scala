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
package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{DatalogDebug, Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}

/**
  * Tests that `--Xdatalog-debug` produces the trace it promises, end to end.
  *
  * [[TestDatalogDebugging]] covers the phase that enables the switches. This runs a program to
  * confirm the enabled switches actually reach standard out, and that each choice reports only
  * its own section.
  */
class TestDatalogDebuggingOutput extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** A transitive closure over three edges, so the model holds derived facts as well as inputs. */
  private val Program: String =
    """
      |def main(): Unit \ IO =
      |    let db = #{
      |        Edge(1, 2). Edge(2, 3).
      |        Path(x, y) :- Edge(x, y).
      |        Path(x, z) :- Path(x, y), Edge(y, z).
      |    };
      |    println(query db select (x, y) from Path(x, y))
      |""".stripMargin

  /** Returns everything the program prints when compiled with `choices` requested. */
  private def trace(choices: Set[DatalogDebug]): String = {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdatalogDebug = choices))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)

    val main = flix.compile().toResult match {
      case Result.Ok(result) => result.getMain.getOrElse(fail("the test program must define main"))
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }

    // The generated code and the solver both write to java.lang.System.out, which
    // Console.withOut does not redirect.
    val captured = new ByteArrayOutputStream()
    val original = System.out
    try {
      System.setOut(new PrintStream(captured, true))
      main(Array.empty)
    } finally {
      System.setOut(original)
    }
    captured.toString
  }

  test("without the flag nothing is traced") {
    val out = trace(Set.empty)
    assert(!out.contains("Datalog Input"))
    assert(!out.contains("Minimal Model"))
    assert(!out.contains("Relation Algebra Machine"))
  }

  test("rules reports the program but not the model") {
    val out = trace(Set(DatalogDebug.Rules))
    assert(out.contains("Datalog Input"))
    assert(out.contains("Path%"), "the rules themselves should appear")
    assert(!out.contains("Minimal Model"))
    assert(!out.contains("Relation Algebra Machine"))
  }

  test("facts reports the model but not the program") {
    val out = trace(Set(DatalogDebug.Facts))
    assert(out.contains("Minimal Model"))
    assert(!out.contains("Datalog Input"))
    assert(!out.contains("Relation Algebra Machine"))
  }

  test("ram reports the machine but neither the program nor the model") {
    val out = trace(Set(DatalogDebug.Ram))
    assert(out.contains("Relation Algebra Machine"))
    assert(!out.contains("Datalog Input"))
    assert(!out.contains("Minimal Model"))
  }

  test("facts are rendered as values rather than JVM identities") {
    val out = trace(Set(DatalogDebug.Facts))
    assert(!out.contains("Tag$"), s"boxed values must not leak their representation:\n$out")
    assert(out.contains("(1, 3)"), s"the derived fact Path(1, 3) should appear:\n$out")
  }

  test("the program still computes the right answer while tracing") {
    assert(trace(DatalogDebug.All).contains("Vector#{(1, 2), (1, 3), (2, 3)}"))
  }

}
