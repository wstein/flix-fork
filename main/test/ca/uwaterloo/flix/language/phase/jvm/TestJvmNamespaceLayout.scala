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
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmNamespaceLayout extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  test("generated classes are siblings of their namespace facade at every depth") {
    val names = compile(
      """mod Acme {
        |    @Export
        |    pub def one(x: Int32): Int32 = x + 1
        |}
        |mod Acme.Api {
        |    @Export
        |    pub def two(x: Int32): Int32 = x + 2
        |}
        |mod Acme.Api.Deep {
        |    @Export
        |    pub def three(x: Int32): Int32 = x + 3
        |}
        |mod Acme.Api.Deep.Deeper {
        |    @Export
        |    pub def four(x: Int32): Int32 = x + 4
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin)

    assert(names.contains("dev.flix.gen.Acme"))
    assert(names.contains("Acme.Api"))
    assert(names.contains("Acme.Api$Deep"))
    assert(names.contains("Acme.Api$Deep$Deeper"))
    assert(names.exists(_.startsWith("Acme.Api$Def$two")))
    assert(names.exists(_.startsWith("Acme.Api$Deep$Def$three")))
    assert(names.exists(_.startsWith("Acme.Api$Deep$Deeper$Def$four")))

    val packages = names.flatMap { name =>
      val segments = name.split('.').toList
      segments.inits.filter(prefix => prefix.nonEmpty && prefix != segments).map(_.mkString("."))
    }
    assert(names.intersect(packages).isEmpty,
      s"generated classes also used as packages: ${names.intersect(packages).toList.sorted.mkString(", ")}")
  }

  test("a one-segment namespace keeps its implementation classes out of the unnamed package") {
    val names = compile(
      """mod PublicApi {
        |    @Export
        |    pub def answer(x: Int32): Int32 = x + 42
        |}
        |
        |def main(): Unit \ IO = println(PublicApi.answer(0))
        |""".stripMargin)

    assert(names.contains("dev.flix.gen.PublicApi"))
    assert(names.exists(_.startsWith("dev.flix.gen.PublicApi$Def$answer")))
    assert(names.contains("Root$"), "the root facade retains its historical binary name")
  }

  private def compile(program: String): Set[String] = {
    val flix = new Flix().setOptions(Options.DefaultTest)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = program)
    flix.compile() match {
      case Result.Ok(result) => result.getClasses.keysIterator.map { desc =>
        desc.descriptorString().stripPrefix("L").stripSuffix(";").replace('/', '.')
      }.toSet
      case Result.Err(errors) => fail(s"test program must compile: $errors")
    }
  }
}
