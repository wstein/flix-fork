/*
 * Copyright 2026 Magnus Madsen, Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

class TestDebugLineNumbers extends AnyFunSuite {

  private val Program =
    """def main(): Unit \ IO = {
      |    let a = 1;
      |    let b = a + 1;
      |    println(b)
      |}
      |""".stripMargin

  test("debug build records every statement line in a retained function") {
    val lines = linesOfMain(Program, xdebug = true)
    assert(Set(2, 3, 4).subsetOf(lines), s"Expected statement lines 2, 3, and 4, got: $lines")
  }

  test("release build does not gain statement locations from the debug policy") {
    val lines = linesOfMain(Program, xdebug = false)
    assert(!Set(2, 3, 4).subsetOf(lines), s"Expected release line table to retain its existing narrower policy, got: $lines")
  }

  private def linesOfMain(program: String, xdebug: Boolean): Set[Int] = {
    val options = Options.DefaultTest.copy(entryPoint = Some(Symbol.mkDefnSym("main")), xdebug = xdebug)
    val flix = new Flix().setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = program)

    val result = flix.compile() match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Expected a successful compilation, got: $errors")
    }

    val clazz = result.getClasses.values.find(_.name.displayName() == "Def$main")
      .getOrElse(fail(s"Expected a generated main class, got: ${result.getClasses.keys}"))
    val lines = mutable.Set.empty[Int]
    new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != ClassMaker.StaticApplyMethodName) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLineNumber(line: Int, start: org.objectweb.asm.Label): Unit = lines += line
        }
      }
    }, 0)
    lines.toSet
  }
}
