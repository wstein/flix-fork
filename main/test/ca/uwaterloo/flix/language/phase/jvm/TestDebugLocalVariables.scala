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
import org.objectweb.asm.{ClassReader, ClassVisitor, Label, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

class TestDebugLocalVariables extends AnyFunSuite {

  private val Program =
    """def compute(a: Int32, b: Int32): Int32 = {
      |    let x = a + 1;
      |    let y = b + 2;
      |    x + y
      |}
      |
      |def main(): Unit \ IO = println(compute(1, 2))
      |""".stripMargin

  test("debug build records source parameters in static methods") {
    val names = localNamesOfCompute(xdebug = true)
    assert(Set("a", "b").subsetOf(names), s"Expected source parameters, got: $names")
    assert(!names.contains("anf"), s"Compiler-generated binding leaked into debug metadata: $names")
  }

  test("release build does not gain local-variable metadata") {
    assert(localNamesOfCompute(xdebug = false).isEmpty)
  }

  test("debug compilation finalizes source bindings under a stable class name") {
    val bindings = compile(xdebug = true).getDebugDefinitions.getOrElse("Def$compute", fail("Missing debug definition for compute."))
    assert(bindings.map(_.name).toSet == Set("a", "b", "x", "y"))
    assert(compile(xdebug = false).getDebugDefinitions.isEmpty)
  }

  private def localNamesOfCompute(xdebug: Boolean): Set[String] = {
    val result = compile(xdebug)
    val clazz = result.getClasses.values.find(_.name.displayName() == "Def$compute")
      .getOrElse(fail(s"Expected a generated compute class, got: ${result.getClasses.keys}"))
    val names = mutable.Set.empty[String]
    new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != ClassMaker.StaticApplyMethodName) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLocalVariable(name: String, descriptor: String, signature: String, start: Label, end: Label, index: Int): Unit = names += name
        }
      }
    }, 0)
    names.toSet
  }

  private def compile(xdebug: Boolean) = {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(entryPoint = Some(Symbol.mkDefnSym("main")), xdebug = xdebug))
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
    flix.compile() match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Expected a successful compilation, got: $errors")
    }
  }
}
