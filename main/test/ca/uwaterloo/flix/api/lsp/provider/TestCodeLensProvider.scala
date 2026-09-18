/*
 * Copyright 2026 Flix Authors
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
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.{SecurityContext, SourceName}
import ca.uwaterloo.flix.util.Options
import org.json4s.JsonAST.JString
import org.scalatest.funsuite.AnyFunSuite

class TestCodeLensProvider extends AnyFunSuite {

  test("Test CodeLens carries the fully-qualified test symbol") {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.DefaultTest)
    val sourceName = SourceName.PathName(CompilerConstants.VirtualTestFile)
    val source =
      """mod Suite {
        |    @Test
        |    pub def alpha(): Unit = ()
        |
        |    @Test
        |    pub def beta(): Unit = ()
        |}
        |""".stripMargin

    flix.addSource(CompilerConstants.VirtualTestFile, source, sctx)

    val root = flix.check() match {
      case (Some(r), _) => r
      case (None, errors) => fail(s"Compilation failed: $errors")
    }
    val commands = CodeLensProvider.processCodeLens(sourceName)(root)
      .flatMap(_.command)
      .filter(_.command == "flix.cmdTests")

    assert(commands.map(_.arguments).toSet == Set(
      List(JString("Suite.alpha")),
      List(JString("Suite.beta"))
    ))
  }

}
