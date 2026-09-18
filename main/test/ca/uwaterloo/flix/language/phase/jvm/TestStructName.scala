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
  * Tests that `--Xdebug` records which struct a value is, and what its fields are called.
  *
  * A struct is compiled to a class shared by every struct of the same erased shape --
  * `Struct$Int32$Obj` serves all of them -- whose fields are named `field0`, `field1` by position.
  * A reader holding one has its values and no idea what any of them is: a channel shows as
  * `field0…field5`, and the standard library's own B+ tree, whose nodes are structs, cannot be
  * walked without deciding from types alone which array is which.
  *
  * The name cannot live on the class, for the same reason the tag name could not. It is written
  * into the value.
  */
class TestStructName extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """|mod Counter {
       |    pub struct Counter[r] {
       |        mut count: Int32,
       |        label: String
       |    }
       |
       |    pub def make(rc: Region[r], n: Int32): Counter[r] \ r =
       |        new Counter @ rc { count = n, label = "hits" }
       |
       |    pub def total(c: Counter[r]): Int32 \ r = c->count + String.length(c->label)
       |}
       |
       |def main(): Unit \ IO =
       |    region rc {
       |        let c = Counter.make(rc, 3);
       |        println(Counter.total(c))
       |    }
       |""".stripMargin

  private val NameField: String = "struct"

  test("without --Xdebug a struct class has no name field") {
    assertResult(Nil)(fieldsOf(compile(xdebug = false)).filter(_ == NameField))
  }

  test("without --Xdebug nothing writes a struct name") {
    assertResult(Nil)(structNameWrites(compile(xdebug = false)))
  }

  test("with --Xdebug the name and the field names are recorded together") {
    // Together, and in `field` order, because that is what makes them usable: the class names its
    // fields by position, so a reader pairs the two lists up.
    assert(
      structNameWrites(compile(xdebug = true)).contains("Counter{count,label}"),
      s"expected Counter{count,label}, got ${structNameWrites(compile(xdebug = true))}",
    )
  }

  test("the recorded name is the one in the source, not the specialised symbol") {
    // A specialised struct symbol carries a fresh id -- `Counter$224018` -- which is compiler
    // bookkeeping. A reader wants the name they wrote.
    val written = structNameWrites(compile(xdebug = true))
    assert(written.forall(!_.contains("$")), s"a recorded name carries a compiler id: $written")
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

  /** Every field declared by a generated `Struct$…` class. */
  private def fieldsOf(classes: Iterable[JvmClass]): List[String] = {
    val found = mutable.ListBuffer.empty[String]
    for (clazz <- classes if clazz.name.displayName().startsWith("Struct$")) {
      new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
        override def visitField(access: Int, name: String, descriptor: String, signature: String, value: Any): FieldVisitor = {
          found += name
          null
        }
      }, 0)
    }
    found.toList
  }

  /**
    * Every constant written into the struct-name field.
    *
    * Read as the pair it is emitted as -- the constant pushed, then the field it is stored into --
    * because either half alone proves nothing.
    */
  private def structNameWrites(classes: Iterable[JvmClass]): List[String] =
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
              if (opcode == Opcodes.PUTFIELD && owner.startsWith("Struct$") && name == NameField) {
                pending.foreach(found += _)
              }
              pending = None
            }

            override def visitInsn(opcode: Int): Unit = {
              // DUP sits between the constant and the store; anything else touched the stack.
              if (opcode != Opcodes.DUP) pending = None
            }
          }
      }, 0)
      found.toList
    }
}
