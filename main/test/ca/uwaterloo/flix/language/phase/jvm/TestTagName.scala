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
import org.objectweb.asm.{ClassReader, ClassVisitor, FieldVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

/**
  * Tests that `--Xdebug` records which case a tagged value is.
  *
  * A case with terms is compiled to a class shared by every case of the same erased shape:
  * `Tag$Obj` is `Some`, `Ok`, `Cons` and every other one-object case at once, and the only thing
  * separating them at runtime is an ordinal. The enum that ordinal indexes into is erased, so a
  * debugger holding such a value can say `#1("/home/…")` and nothing better -- `1` is not a name,
  * and nothing reachable from the value says which enum to count cases of.
  *
  * So the name is written into the value as it is built, under `--Xdebug` only, exactly as line
  * numbers and local-variable names are. An optimized build carries neither the field nor the write.
  */
class TestTagName extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** A case with terms and a case without, so both representations are covered. */
  private val Program: String =
    """
      |pub enum Shade with ToString {
      |    case Plain,
      |    case Mixed(Int32)
      |}
      |
      |@DontInline
      |pub def mix(n: Int32): Shade = Shade.Mixed(n)
      |
      |@DontInline
      |pub def plain(): Shade = Shade.Plain
      |
      |def main(): Unit \ IO =
      |    println(mix(1));
      |    println(plain())
      |""".stripMargin

  private val TaggedClass: String = "Tagged$"

  private val NameField: String = "tag"

  test("without --Xdebug the base class has no tag field") {
    // The field is debug information, and an optimized build pays for none of it.
    assertResult(Nil)(fieldsOf(compile(xdebug = false), "Tagged$.class").filter(_._1 == NameField))
  }

  test("without --Xdebug nothing writes a tag name") {
    assertResult(Nil)(tagNameWrites(compile(xdebug = false)))
  }

  test("with --Xdebug the base class carries a String tag field") {
    assertResult(List((NameField, "Ljava/lang/String;")))(
      fieldsOf(compile(xdebug = true), "Tagged$.class").filter(_._1 == NameField),
    )
  }

  test("with --Xdebug a case with terms records its own name") {
    // The case this exists for: `Mixed` is built into a shared `Tag$…` class, so the name is the
    // only thing that can tell a reader what it is looking at.
    assert(
      tagNameWrites(compile(xdebug = true)).contains("Shade.Mixed"),
      s"expected a write of \"Shade.Mixed\", got ${tagNameWrites(compile(xdebug = true))}",
    )
  }

  test("with --Xdebug a case without terms records its name too") {
    // Its class already carries the name, so this is redundant for a reader that parses class names
    // -- and that is the point: every tagged value answers the same question the same way, and no
    // reader needs a second rule for the nullary ones.
    assert(
      tagNameWrites(compile(xdebug = true)).contains("Shade.Plain"),
      s"expected a write of \"Shade.Plain\", got ${tagNameWrites(compile(xdebug = true))}",
    )
  }

  test("the recorded name is the source name, not the mangled one") {
    // Nothing in the language reads this field -- pattern matching compares ordinals -- so it has no
    // reason to be a legal JVM identifier, and a reader that had to undo the mangling would be one
    // more rule to keep in step with the compiler.
    val program =
      """
        |pub enum Op with ToString {
        |    case Plus(Int32)
        |}
        |
        |@DontInline
        |pub def op(n: Int32): Op = Op.Plus(n)
        |
        |def main(): Unit \ IO = println(op(1))
        |""".stripMargin
    assert(tagNameWrites(compile(xdebug = true, program = program)).contains("Op.Plus"))
  }

  /** Compiles [[Program]] and returns the directory holding the generated classes. */
  private def compile(xdebug: Boolean, program: String = Program): Iterable[JvmClass] = {
    val opts = Options.DefaultTest.copy(xdebug = xdebug)
    val flix = new Flix().setOptions(opts)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = program)
    flix.compile() match {
      case Result.Ok(result) => result.getClasses.values
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  /** The fields declared by the class file named `fileName`, as name and descriptor. */
  private def fieldsOf(classes: Iterable[JvmClass], fileName: String): List[(String, String)] = {
    val clazz = classes.find(_.name.displayName() + ".class" == fileName)
      .getOrElse(fail(s"no $fileName was generated"))
    val found = mutable.ListBuffer.empty[(String, String)]
    new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitField(access: Int, name: String, descriptor: String, signature: String, value: Any): FieldVisitor = {
        found += ((name, descriptor))
        null
      }
    }, 0)
    found.toList
  }

  /**
    * Every constant written into the tag-name field, across the whole build.
    *
    * Read as the pair it is emitted as -- the constant pushed, then the field it is stored into --
    * because either half alone proves nothing: a string in the constant pool may be there for any
    * reason, and a `PUTFIELD` says nothing about what it stored.
    */
  private def tagNameWrites(classes: Iterable[JvmClass]): List[String] =
    classes.toList.flatMap { clazz =>
      val found = mutable.ListBuffer.empty[String]
      new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
        override def visitMethod(access: Int, mname: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor =
          new MethodVisitor(Opcodes.ASM9) {
            private var pending: Option[String] = None

            override def visitLdcInsn(value: Any): Unit = {
              pending = value match {
                case s: String => Some(s)
                case _ => None
              }
            }

            override def visitFieldInsn(opcode: Int, owner: String, name: String, fieldDescriptor: String): Unit = {
              if (opcode == Opcodes.PUTFIELD && owner == TaggedClass && name == NameField) {
                pending.foreach(found += _)
              }
              pending = None
            }

            override def visitInsn(opcode: Int): Unit = {
              // DUP sits between the constant and the store on the tagged-value path, so it must not
              // clear what is pending; anything else did something with the stack and must.
              if (opcode != Opcodes.DUP) pending = None
            }
          }
      }, 0)
      found.toList
    }
}
