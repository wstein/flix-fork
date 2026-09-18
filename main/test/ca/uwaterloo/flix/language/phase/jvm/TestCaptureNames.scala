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
import ca.uwaterloo.flix.language.phase.jvm.JvmClass
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, FieldVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/**
  * Tests that `--Xdebug` records what a closure's captured values are called.
  *
  * A lambda is lifted into a class of its own holding its captures in `clo0`, `clo1`. The values are
  * what distinguishes one closure of a definition from another -- `curriedMultiply` captured `6` --
  * and `clo0` says only where that value sits. The names are in the `LocalVariableTable`, but keyed
  * on JVM slots, so pairing one back to a field means redoing the frame method's offset arithmetic.
  *
  * Unlike a tag or a struct name this can be a constant on the class: a closure class belongs to one
  * lifted lambda, so its captures have one set of names.
  */
class TestCaptureNames extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """|def curriedMultiply(x: Int32): Int32 -> Int32 = y -> x * y
       |
       |def main(): Unit \ IO =
       |    let sep = "-";
       |    let both = (a -> b -> "${a}${sep}${b}");
       |    println(curriedMultiply(6)(7));
       |    println(both("l")("r"))
       |""".stripMargin

  private val CaptureNamesField: String = "cloNames"

  test("without --Xdebug no capture names are recorded") {
    assertResult(Map.empty[String, String])(recordedNames(compile(xdebug = false)))
  }

  test("with --Xdebug a capture is recorded under the name it was written with") {
    val names = recordedNames(compile(xdebug = true))
    assert(
      names.exists { case (cls, value) => cls.contains("curriedMultiply") && value == "x" },
      s"expected `x` for curriedMultiply, got $names",
    )
  }

  test("several captures keep their order, which is the order of the fields") {
    // `clo0`, `clo1` are positions, so the names are only usable if they line up with them.
    val names = recordedNames(compile(xdebug = true))
    assert(names.values.exists(_ == "a,sep"), s"expected a two-capture closure, got $names")
  }

  test("a capture nobody named is recorded as one, rather than being left out") {
    // Compiler-introduced captures exist -- a lambda that captures nothing is still given one -- and
    // dropping them from the list would shift every name after it onto the wrong value.
    val names = recordedNames(compile(xdebug = true))
    assert(names.values.exists(_ == "_"), s"expected an unnamed capture, got $names")
  }

  test("a function with no captures records nothing") {
    // There is no list to write, and an empty constant would say there is one.
    val names = recordedNames(compile(xdebug = true))
    assert(names.keys.forall(_.contains("Clo$")), s"a non-closure class carries capture names: $names")
  }

  private def compile(xdebug: Boolean): Iterable[JvmClass] = {
    val opts = Options.DefaultTest.copy(xdebug = xdebug)
    val flix = new Flix().setOptions(opts)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = Program)
    flix.compile() match {
      case Result.Ok(result) => result.getClasses.values
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  /** The capture names each generated class records, keyed by class file name. */
  private def recordedNames(classes: Iterable[JvmClass]): Map[String, String] = {
    val found = mutable.Map.empty[String, String]
    for (clazz <- classes) {
      new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
        override def visitField(access: Int, name: String, descriptor: String, signature: String, value: Any): FieldVisitor = {
          if (name == CaptureNamesField) {
            Option(value).foreach(v => found += clazz.name.toString -> v.toString)
          }
          null
        }
      }, 0)
    }
    found.toMap
  }
}
