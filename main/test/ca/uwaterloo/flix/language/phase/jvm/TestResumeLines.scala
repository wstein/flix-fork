/*
 * Copyright 2026 Werner Stein
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
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, FieldVisitor, Label, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/** Tests the resume-line metadata consumed by the IntelliJ continuation renderer. */
class TestResumeLines extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """eff Ask {
      |    def ask(): String
      |}
      |
      |def one(): String \ Ask = Ask.ask()
      |
      |def two(): String \ Ask = Ask.ask()
      |
      |def both(): String \ Ask =
      |    let a = one();
      |    let b = two();
      |    a + b
      |
      |def main(): Unit \ IO =
      |    run {
      |        println(both())
      |    } with handler Ask {
      |        def ask(resume) = resume("!")
      |    }
      |""".stripMargin

  test("release classes do not carry resume-line metadata") {
    assertResult(None)(recordedLines(classBytes(compile(xdebug = false), "Def$both")))
  }

  test("debug classes record the source line for every continuation pc") {
    val bytes = classBytes(compile(xdebug = true), "Def$both")
    val lines = recordedLines(bytes).getOrElse(fail("missing pcLines"))
    assertResult(List(10, 11))(lines)
  }

  test("recorded lines agree with tableswitch targets and the JVM line table") {
    val bytes = classBytes(compile(xdebug = true), "Def$both")
    assertResult(switchTargetLines(bytes))(recordedLines(bytes).getOrElse(fail("missing pcLines")))
  }

  private def compile(xdebug: Boolean): Iterable[JvmClass] = {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = xdebug))
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = Program)
    flix.compile() match {
      case Result.Ok(result) => result.getClasses.values
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  private def classBytes(classes: Iterable[JvmClass], displayName: String): Array[Byte] =
    classes.find(_.name.displayName() == displayName).map(_.bytecode)
      .getOrElse(fail(s"missing generated class $displayName"))

  private def recordedLines(bytes: Array[Byte]): Option[List[Int]] = {
    var found: Option[String] = None
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitField(access: Int, name: String, descriptor: String, signature: String, value: Any): FieldVisitor = {
        if (name == "pcLines") found = Option(value).map(_.toString)
        null
      }
    }, 0)
    found.map(_.split(',').map(_.toInt).toList)
  }

  /** Reconstructs the line in effect at each target of the continuation pc tableswitch. */
  private def switchTargetLines(bytes: Array[Byte]): List[Int] = {
    var targets: List[Label] = Nil
    val lineAt = mutable.Map.empty[Label, Int]
    var current = -1
    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != "applyFrame") return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLineNumber(line: Int, start: Label): Unit = current = line
          override def visitLabel(label: Label): Unit = lineAt(label) = current
          override def visitTableSwitchInsn(min: Int, max: Int, dflt: Label, labels: Label*): Unit =
            if (targets.isEmpty) targets = labels.toList
        }
      }
    }, 0)
    targets.map(label => lineAt.getOrElse(label, -1))
  }
}
