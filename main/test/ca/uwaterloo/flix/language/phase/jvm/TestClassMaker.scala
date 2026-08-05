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

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.{IsFinal, NotFinal}
import org.objectweb.asm.{ClassReader, ClassVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests the class header `ClassMaker` writes.
  *
  * A class-level generic signature is the counterpart of the method-level one: a descriptor cannot
  * express a type argument, so a class implementing `java.util.List<T>` without a signature
  * implements it raw, which Scala 3 and Kotlin reject rather than warn about.
  *
  * No class the backend generates declares one today, because they exist after erasure and every
  * argument they could name is already `Object`. These tests cover the plumbing rather than a
  * caller, so that a generator which does have something to declare can rely on it.
  */
class TestClassMaker extends AnyFunSuite {

  private implicit val flix: Flix = new Flix()

  private val Name: JvmName = JvmName(List("dev", "flix", "test"), "Sample")

  /** Returns the class-level signature of `bytes`, if it has one. */
  private def signatureOf(bytes: Array[Byte]): Option[String] = {
    var found: Option[String] = None
    new ClassReader(bytes).accept(
      new ClassVisitor(Opcodes.ASM9) {
        override def visit(version: Int, access: Int, name: String, signature: String, superName: String, interfaces: Array[String]): Unit =
          found = Option(signature)
      },
      0
    )
    found
  }

  test("a class declares no signature by default") {
    // The default has to stay absent rather than empty: an empty `Signature` attribute is not the
    // same as no attribute, and every generated class relies on this path.
    val bytes = ClassMaker.mkClass(Name, IsFinal).closeClassMaker()

    assertResult(None)(signatureOf(bytes))
  }

  test("a class writes the signature it is given") {
    val signature = "Ljava/lang/Object;Ljava/util/List<Ljava/lang/String;>;"

    val bytes = ClassMaker
      .mkClass(Name, IsFinal, interfaces = List(JvmName.JavaList), signature = Some(signature))
      .closeClassMaker()

    assertResult(Some(signature))(signatureOf(bytes))
  }

  test("an abstract class writes the signature it is given") {
    val signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;"

    val bytes = ClassMaker.mkAbstractClass(Name, signature = Some(signature)).closeClassMaker()

    assertResult(Some(signature))(signatureOf(bytes))
  }

  test("an interface writes the signature it is given") {
    val signature = "<T:Ljava/lang/Object;>Ljava/lang/Object;"

    val bytes = ClassMaker.mkInterface(Name, signature = Some(signature)).closeClassMaker()

    assertResult(Some(signature))(signatureOf(bytes))
  }

  test("a signature does not disturb the rest of the header") {
    // The signature is one argument among several positional ones on `ClassWriter.visit`, so
    // passing it must not shift the superclass or the interface list.
    val bytes = ClassMaker
      .mkClass(
        Name,
        NotFinal,
        superClass = JvmName.Object,
        interfaces = List(JvmName.JavaList),
        signature = Some("Ljava/lang/Object;Ljava/util/List<Ljava/lang/String;>;")
      )
      .closeClassMaker()

    var superName: String = null
    var interfaces: List[String] = Nil
    new ClassReader(bytes).accept(
      new ClassVisitor(Opcodes.ASM9) {
        override def visit(version: Int, access: Int, name: String, signature: String, superN: String, ifaces: Array[String]): Unit = {
          superName = superN
          interfaces = ifaces.toList
        }
      },
      0
    )

    assertResult(JvmName.Object.toInternalName)(superName)
    assertResult(List(JvmName.JavaList.toInternalName))(interfaces)
  }
}
