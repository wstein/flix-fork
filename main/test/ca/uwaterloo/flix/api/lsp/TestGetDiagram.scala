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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.phase.SvgDocumentor
import ca.uwaterloo.flix.tools.pkg.PackageModules
import ca.uwaterloo.flix.util.Options
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import org.json4s.native.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

class TestGetDiagram extends AnyFunSuite {

  test("Request.parseGetDiagram: parses valid json-rpc getDiagram request") {
    val jsonStr = """{"id": "42", "request": "flix/getDiagram", "itemName": "Eq"}"""
    val json = parse(jsonStr)
    val parsed = Request.parseGetDiagram(json)
    assert(parsed == Ok(Request.GetDiagram("42", "Eq")), "Should successfully parse getDiagram request")
  }

  test("Request.parseGetDiagram: returns Err for non-getDiagram request") {
    val jsonStr = """{"id": "1", "request": "other/request"}"""
    val json = parse(jsonStr)
    val parsed = Request.parseGetDiagram(json)
    assert(parsed.isInstanceOf[Err[_, _]], "Should return Err for non-getDiagram request")
  }

  test("SvgDocumentor.generateAll: returns SVG diagram payload for documentable trait") {
    val input =
      """|pub trait Equatable[a] {
         |  pub def isEq(x: a, y: a): Bool
         |}
         |""".stripMargin

    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    implicit val flix: Flix = new Flix().setOptions(Options.Default)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, input)
    val root = flix.check() match {
      case (Some(r), _) => r
      case (None, errors) => fail(s"Compilation failed: $errors")
    }

    val module = ca.uwaterloo.flix.language.phase.Documentor.build(root, PackageModules.All)
    val diagrams = SvgDocumentor.generateAll(module)(flix)

    assert(diagrams.contains("Equatable.svg"), "Diagrams map should contain Equatable.svg")
    val svg = diagrams("Equatable.svg")
    assert(svg.contains("<svg"), "Generated payload should contain valid SVG markup")
    assert(svg.contains("Equatable"), "Generated payload should contain trait symbol name")
  }
}
