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
package ca.uwaterloo.flix.runtime

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.CompilerConstants
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.util.{Options, Result}
import dev.flix.runtime.Coverage
import org.scalatest.funsuite.AnyFunSuite

class TestCoverageRuntime extends AnyFunSuite {

  test("coverage sessions count independently") {
    Coverage.install(101L, 3)
    Coverage.install(202L, 3)
    try {
      Coverage.hit(101L, 1)
      Coverage.hit(101L, 1)
      Coverage.hit(202L, 2)

      assert(Coverage.snapshot(101L).toList == List(0L, 2L, 0L))
      assert(Coverage.snapshot(202L).toList == List(0L, 0L, 1L))
    } finally {
      Coverage.close(101L)
      Coverage.close(202L)
    }
  }

  test("coverage runtime is bundled only in coverage builds") {
    def classNames(coverage: Boolean): Set[String] = {
      val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = coverage))
      flix.addSource(CompilerConstants.VirtualTestFile, "pub def main(): Unit = ()", SecurityContext.Unrestricted)
      val result = flix.compile() match {
        case Result.Ok(compilation) => compilation
        case Result.Err(errors) => fail(errors.map(_.summary).mkString("; "))
      }
      result.getClasses.keys.map(ClassDescs.binaryNameOf).toSet
    }

    assert(classNames(coverage = true).contains("dev.flix.runtime.Coverage"))
    assert(!classNames(coverage = false).contains("dev.flix.runtime.Coverage"))
  }

  test("compiled function-entry probes execute in the loaded program session") {
    for (newMonomorphizer <- List(false, true)) {
      val flix = new Flix().setOptions(Options.DefaultTest.copy(coverage = true, xnewmono = newMonomorphizer))
      flix.addSource(CompilerConstants.VirtualTestFile,
        """import java.lang.System
          |
          |def answer(): Int64 \ IO = System.currentTimeMillis()
          |
          |pub def main(): Unit \ IO =
          |    let _ = answer();
          |    ()
          |""".stripMargin,
        SecurityContext.Unrestricted)

      val compilation = flix.compile() match {
        case Result.Ok(result) => result
        case Result.Err(errors) => fail(errors.map(_.summary).mkString("; "))
      }
      val session = compilation.getCoverageSession.getOrElse(fail("Expected coverage metadata"))
      val loaded = JvmLoader.load(compilation)
      val coverage = loaded.coverage.getOrElse(fail("Expected loaded coverage session"))
      try {
        loaded.main.getOrElse(fail("Expected main entry point"))(Array.empty)

        val byName = session.probes.map(_.qualifiedName).zip(coverage.snapshot()).toMap
        assert(session.probes.map(_.qualifiedName).toSet == Set("answer", "main"))
        assert(byName("answer") == 1L)
        assert(byName("main") == 1L)
      } finally {
        coverage.close()
      }
    }
  }

}
