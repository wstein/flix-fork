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
  private def descriptorsOf(input: String, className: String): Map[String, String] =
    membersOf(input, className)._1

  /**
    * Compiles `input` and returns the generic signatures of the methods of `className`.
    *
    * Only methods that have one appear: a signature is present exactly when the descriptor alone
    * would lose type arguments.
    */
  private def signaturesOf(input: String, className: String): Map[String, String] =
    membersOf(input, className)._2

  /** Compiles `input` and returns the descriptors and signatures of the methods of `className`. */
  private def membersOf(input: String, className: String): (Map[String, String], Map[String, String]) = {
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

  /** Returns the descriptors and signatures of the methods of the class file at `path`. */
  private def read(path: Path): (Map[String, String], Map[String, String]) = {
    assert(Files.exists(path), s"expected a generated class at $path")
    val descriptors = mutable.Map.empty[String, String]
    val signatures = mutable.Map.empty[String, String]
    new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        descriptors += (name -> descriptor)
        if (signature != null) signatures += (name -> signature)
        null
      }
    }, 0)
    (descriptors.toMap, signatures.toMap)
  }

  /** Deletes `path` and everything below it. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
    ()
  }

  test("an exported def keeps String in its descriptor") {
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |}
        |
        |def main(): Unit \ IO = println(Pkg.Mod.greet("world"))
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("greet").contains("(Ljava/lang/String;)Ljava/lang/String;"))
  }

  test("an exported def keeps a Java type in its descriptor") {
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.io.File
        |
        |    @Export
        |    pub def pathOf(f: File): String \ IO = f.getPath()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("pathOf").contains("(Ljava/io/File;)Ljava/lang/String;"))
  }

  test("an exported def erases a generic Java type to its raw class") {
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |
        |    @Export
        |    pub def sizeOf(l: ArrayList[String]): Int32 \ IO = l.size()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("sizeOf").contains("(Ljava/util/ArrayList;)I"))
  }

  test("an exported generic Java return type declares its type arguments") {
    // The descriptor can only say `ArrayList`. The arguments survive as far as the declared type
    // and nowhere further, so this is the last point at which they can be written down.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    import java.util.HashMap
        |
        |    @Export
        |    pub def strings(): ArrayList[String] \ IO = new ArrayList()
        |
        |    @Export
        |    pub def ints(): ArrayList[Int32] \ IO = new ArrayList()
        |
        |    @Export
        |    pub def nested(): ArrayList[ArrayList[String]] \ IO = new ArrayList()
        |
        |    @Export
        |    pub def mapping(): HashMap[String, Int32] \ IO = new HashMap()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("strings").contains("()Ljava/util/ArrayList;"))
    assert(signatures.get("strings").contains("()Ljava/util/ArrayList<Ljava/lang/String;>;"))
    // A type argument is a reference, so a primitive one is boxed -- as in `Optional<Integer>`.
    assert(signatures.get("ints").contains("()Ljava/util/ArrayList<Ljava/lang/Integer;>;"))
    assert(signatures.get("nested").contains("()Ljava/util/ArrayList<Ljava/util/ArrayList<Ljava/lang/String;>;>;"))
    assert(signatures.get("mapping").contains("()Ljava/util/HashMap<Ljava/lang/String;Ljava/lang/Integer;>;"))
  }

  test("a Java type argument keeps its own Java name") {
    // The way to give a Flix enum a representation that can cross is a real Java enum: it has a
    // stable Java type of its own, so it needs no conversion and no description beyond its name.
    // A Flix enum in the same position is rejected by the front end -- see
    // TestEntryPoints "Test.ExportTypeArgument" -- because naming its generated class in a
    // signature would publish exactly what the boundary hides.
    val signatures = signaturesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    import java.time.DayOfWeek
        |
        |    @Export
        |    pub def days(): ArrayList[DayOfWeek] \ IO = new ArrayList()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(signatures.get("days").contains("()Ljava/util/ArrayList<Ljava/time/DayOfWeek;>;"))
  }
  test("an exported def returning Option is presented as Optional") {
    // The Flix representation of `Option` is a tag class the backend is free to rename, so it is
    // converted at the boundary rather than exposed.
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def find(k: String): Option[String] = if (k == "a") Some("alpha") else None
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("find").contains("(Ljava/lang/String;)Ljava/util/Optional;"))
  }

  test("an exported Option declares its element type in the signature") {
    // A descriptor cannot express `Optional<String>`. Without the signature the element type is
    // lost: Java still compiles if the use site names the type and takes an unchecked conversion,
    // while Scala 3 and Kotlin both reject the raw value.
    val signatures = signaturesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def find(k: String): Option[String] = if (k == "a") Some("alpha") else None
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(signatures.get("find").contains("(Ljava/lang/String;)Ljava/util/Optional<Ljava/lang/String;>;"))
  }

  test("an exported Option boxes a primitive element type") {
    // `Optional` holds references, so the `int` a `Some` carries has to become an `Integer`.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def size(k: String): Option[Int32] = if (k == "a") Some(42) else None
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("size").contains("(Ljava/lang/String;)Ljava/util/Optional;"))
    assert(signatures.get("size").contains("(Ljava/lang/String;)Ljava/util/Optional<Ljava/lang/Integer;>;"))
  }

  test("an exported def without type arguments has no signature") {
    // A signature that merely repeats the descriptor is noise, and would claim generic information
    // where there is none.
    val signatures = signaturesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(!signatures.contains("greet"))
  }

  test("an exported nullary def takes no parameters") {
    // Flix gives a nullary function a single `Unit` parameter; Java should not see it.
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def answer(): Int32 = 42
        |}
        |
        |def main(): Unit \ IO = println(Pkg.Mod.answer())
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("answer").contains("()I"))
  }

  test("an exported def returning Unit returns void") {
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def shout(s: String): Unit \ IO = println(s)
        |}
        |
        |def main(): Unit \ IO = Pkg.Mod.shout("hi")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("shout").contains("(Ljava/lang/String;)V"))
  }

  test("an exported nullary def returning Unit is void of no arguments") {
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def hello(): Unit \ IO = println("hello")
        |}
        |
        |def main(): Unit \ IO = Pkg.Mod.hello()
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("hello").contains("()V"))
  }

  test("an exported def keeps primitives unboxed") {
    val descriptors = descriptorsOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def add(x: Int32, y: Int32): Int32 = x + y
        |}
        |
        |def main(): Unit \ IO = println(Pkg.Mod.add(1, 2))
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("add").contains("(II)I"))
  }

  test("an exported List is presented as a java.util.List") {
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: Nil
        |
        |    @Export
        |    pub def numbers(): List[Int32] = 1 :: 2 :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("names").contains("()Ljava/util/List;"))
    assert(signatures.get("names").contains("()Ljava/util/List<Ljava/lang/String;>;"))
    // A `List` holds references, so a primitive element is boxed -- as an `Optional` element is.
    assert(signatures.get("numbers").contains("()Ljava/util/List<Ljava/lang/Integer;>;"))
  }

  test("no return type the front end accepts leaks the Flix representation") {
    // `EntryPoints` decides what may be exported and `ExportPlan` decides how, over different type
    // representations, so a gate widened ahead of a plan compiles into a shim that falls through
    // and returns the internal tag class. That failure is silent -- a working method with a wrong
    // type -- so the invariant is asserted here rather than left to review: every return type the
    // gate admits is compiled, and no generated class may appear in the API it produces.
    val returnTypes = List(
      "Bool" -> "true",
      "Char" -> "'c'",
      "Int8" -> "1i8",
      "Int16" -> "1i16",
      "Int32" -> "1",
      "Int64" -> "1i64",
      "Float32" -> "1.0f32",
      "Float64" -> "1.0f64",
      "String" -> "\"s\"",
      "BigInt" -> "1ii",
      "BigDecimal" -> "1.0ff",
      "Regex" -> "regex\"a\"",
      "Option[String]" -> "Some(\"s\")",
      "Option[Int32]" -> "Some(1)",
      "Option[Float64]" -> "Some(1.0f64)",
      "Option[BigInt]" -> "Some(1ii)",
      "Option[Regex]" -> "Some(regex\"a\")",
      "List[String]" -> "\"s\" :: Nil",
      "List[Int32]" -> "1 :: Nil",
      "List[Float64]" -> "1.0f64 :: Nil",
      "List[BigInt]" -> "1ii :: Nil"
    )
    val defs = returnTypes.zipWithIndex.map {
      case ((tpe, value), i) => s"    @Export\n    pub def f$i(): $tpe = $value"
    }.mkString("\n\n")
    val (descriptors, signatures) = membersOf(
      s"""mod Pkg { }
         |mod Pkg.Mod {
         |$defs
         |}
         |
         |def main(): Unit \\ IO = println("built")
         |""".stripMargin, "Pkg/Mod")

    // Every generated class lives under this package, so naming one in a descriptor or a signature
    // is exactly the leak, whatever the backend happens to have called it.
    val leaks = (descriptors ++ signatures).filter { case (_, d) => d.contains(JvmName.DevFlixGen.mkString("/")) }
    assert(leaks.isEmpty, s"exported members name a generated class: $leaks")
    assert(descriptors.size >= returnTypes.size, s"expected a shim per exported def, got: ${descriptors.keys}")
  }

}
