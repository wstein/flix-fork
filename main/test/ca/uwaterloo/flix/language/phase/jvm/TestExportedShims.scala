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
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
  * Tests the descriptors of the shim methods generated for `@Export`ed defs.
  *
  * These descriptors are the program's Java-facing API, so they must name the declared types rather
  * than the erased ones: a def returning `String` has to appear to Java as returning
  * `java.lang.String`, not `Object`, or every call site needs a cast.
  */
class TestExportedShims extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** Compiles `input` and returns the descriptors of the methods of `className`, by method name. */
  private def descriptorsOf(input: String, className: String): Map[String, String] = {
    val out = Files.createTempDirectory("flix-export-test")
    try {
      val opts = Options.DefaultTest.copy(outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, input)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      read(out.resolve("class").resolve(s"$className.class"))
    } finally {
      deleteRecursively(out)
    }
  }

  /** Returns the descriptors of the methods of the class file at `path`, by method name. */
  private def read(path: Path): Map[String, String] = {
    assert(Files.exists(path), s"expected a generated class at $path")
    val descriptors = mutable.Map.empty[String, String]
    new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        descriptors += (name -> descriptor)
        null
      }
    }, 0)
    descriptors.toMap
  }

  /** Deletes `path` and everything below it. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
    ()
  }

  test("an exported def keeps String in its descriptor") {
    val descriptors = descriptorsOf(
      """mod Mod {
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |}
        |
        |def main(): Unit \ IO = println(Mod.greet("world"))
        |""".stripMargin, "Mod")
    assert(descriptors.get("greet").contains("(Ljava/lang/String;)Ljava/lang/String;"))
  }

  test("an exported def keeps a Java type in its descriptor") {
    val descriptors = descriptorsOf(
      """mod Mod {
        |    import java.io.File
        |
        |    @Export
        |    pub def pathOf(f: File): String \ IO = f.getPath()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Mod")
    assert(descriptors.get("pathOf").contains("(Ljava/io/File;)Ljava/lang/String;"))
  }

  test("an exported def erases a generic Java type to its raw class") {
    val descriptors = descriptorsOf(
      """mod Mod {
        |    import java.util.ArrayList
        |
        |    @Export
        |    pub def sizeOf(l: ArrayList[String]): Int32 \ IO = l.size()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Mod")
    assert(descriptors.get("sizeOf").contains("(Ljava/util/ArrayList;)I"))
  }

  test("an exported def keeps primitives unboxed") {
    val descriptors = descriptorsOf(
      """mod Mod {
        |    @Export
        |    pub def add(x: Int32, y: Int32): Int32 = x + y
        |}
        |
        |def main(): Unit \ IO = println(Mod.add(1, 2))
        |""".stripMargin, "Mod")
    assert(descriptors.get("add").contains("(II)I"))
  }

}
