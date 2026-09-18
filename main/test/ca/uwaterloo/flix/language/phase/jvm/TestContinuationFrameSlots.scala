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
import org.json4s.{JArray, JInt, JObject, JString}
import org.json4s.native.JsonMethods
import org.objectweb.asm.{ClassReader, ClassVisitor, FieldVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

/** Tests debugger metadata for values saved in suspended continuation objects. */
class TestContinuationFrameSlots extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """eff Ask {
      |    def ask(): Int32
      |}
      |
      |def work(prefix: Int32): Int32 \ Ask =
      |    let before = prefix + 1;
      |    let answer = Ask.ask();
      |    let after = answer + 1;
      |    before + after
      |
      |def main(): Unit \ IO =
      |    run { println(work(40)) } with handler Ask {
      |        def ask(resume) = resume(1)
      |    }
      |""".stripMargin

  test("release classes do not carry continuation-slot metadata") {
    assertResult(None)(metadata(compile(xdebug = false), "Def$work"))
  }

  test("a pc records only source variables live when it suspends") {
    val json = metadata(compile(xdebug = true), "Def$work").getOrElse(fail("missing frameSlots"))
    val slots = slotsAt(json, pc = 1)

    assertResult(List(
      Slot("arg0", "prefix", "Int32", "parameter"),
      Slot("l0", "before", "Int32", "local"),
    ))(slots)
    assert(!slots.exists(_.name == "answer"), "the effect result is not initialized while the call is suspended")
    assert(!slots.exists(_.name == "after"), "a later binding must not appear before its initializer")
  }

  private case class Slot(field: String, name: String, tpe: String, kind: String)

  private def compile(xdebug: Boolean): Iterable[JvmClass] = {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = xdebug))
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = Program)
    flix.compile() match {
      case Result.Ok(result) => result.getClasses.values
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  private def metadata(classes: Iterable[JvmClass], displayName: String): Option[String] = {
    val clazz = classes.find(_.name.displayName() == displayName).getOrElse(fail(s"missing $displayName"))
    var found: Option[String] = None
    new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitField(access: Int, name: String, descriptor: String, signature: String, value: Any): FieldVisitor = {
        if (name == "frameSlots") found = Option(value).map(_.toString)
        null
      }
    }, 0)
    found
  }

  private def slotsAt(json: String, pc: Int): List[Slot] = JsonMethods.parse(json) match {
    case JObject(root) if root.collectFirst { case ("formatVersion", JInt(n)) => n.toInt }.contains(1) =>
      val pcs = root.collectFirst { case ("pcs", JObject(values)) => values }.getOrElse(fail("missing pcs"))
      pcs.collectFirst { case (key, JArray(values)) if key == pc.toString =>
        values.collect { case JObject(fields) =>
          def string(name: String): String = fields.collectFirst { case (`name`, JString(value)) => value }
            .getOrElse(fail(s"missing $name"))
          Slot(string("field"), string("name"), string("type"), string("kind"))
        }
      }.getOrElse(fail(s"missing pc $pc"))
    case _ => fail("malformed frameSlots metadata")
  }
}
