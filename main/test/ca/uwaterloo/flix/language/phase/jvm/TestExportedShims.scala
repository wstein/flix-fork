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

  /** Compiles `input` and returns the simple names of every class it generated. */
  private def classNamesOf(input: String): Set[String] = {
    val out = Files.createTempDirectory("flix-export-test")
    try {
      val opts = Options.DefaultTest.copy(outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, input)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      val classes = out.resolve("class")
      val names = mutable.Set.empty[String]
      Files.walk(classes).forEach { p =>
        val name = p.getFileName.toString
        if (name.endsWith(".class")) names += name.dropRight(".class".length)
      }
      names.toSet
    } finally {
      deleteRecursively(out)
    }
  }

  /** Compiles `input` and returns the generic signature of `className`, if it declares one. */
  private def classSignatureOf(input: String, className: String): Option[String] = {
    val out = Files.createTempDirectory("flix-export-test")
    try {
      val opts = Options.DefaultTest.copy(outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, input)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      val path = out.resolve("class").resolve(s"$className.class")
      assert(Files.exists(path), s"expected a generated class at $path")
      var signature = Option.empty[String]
      new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
        override def visit(version: Int, access: Int, name: String, sig: String, superName: String, interfaces: Array[String]): Unit =
          signature = Option(sig)
      }, 0)
      signature
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

  test("an exported generic Java parameter declares its type arguments") {
    // The declared type reaches the code generator for parameters as well as for the return, so a
    // caller passing an `ArrayList<String>` needs no cast and takes no unchecked conversion.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    import java.util.HashMap
        |
        |    @Export
        |    pub def sizeOf(l: ArrayList[String]): Int32 \ IO = l.size()
        |
        |    @Export
        |    pub def sizeOfInts(l: ArrayList[Int32]): Int32 \ IO = l.size()
        |
        |    @Export
        |    pub def merge(a: ArrayList[String], b: HashMap[String, Int32]): Int32 \ IO =
        |        a.size() + b.size()
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("sizeOf").contains("(Ljava/util/ArrayList;)I"))
    assert(signatures.get("sizeOf").contains("(Ljava/util/ArrayList<Ljava/lang/String;>;)I"))
    // A type argument is a reference, so a primitive one is boxed in a parameter too.
    assert(signatures.get("sizeOfInts").contains("(Ljava/util/ArrayList<Ljava/lang/Integer;>;)I"))
    assert(
      signatures
        .get("merge")
        .contains("(Ljava/util/ArrayList<Ljava/lang/String;>;Ljava/util/HashMap<Ljava/lang/String;Ljava/lang/Integer;>;)I")
    )
  }

  test("a signature covers the parameters and the result together") {
    // A `Signature` attribute describes the whole method or it is malformed, so a converted result
    // beside a generic parameter has to produce one string, and a part with nothing to declare has
    // to repeat its descriptor rather than be omitted.
    val signatures = signaturesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |
        |    @Export
        |    pub def firstOf(l: ArrayList[String]): Option[String] \ IO =
        |        if (l.isEmpty()) None else Some(l.get(0))
        |
        |    @Export
        |    pub def countOf(l: ArrayList[String], n: Int32): Int32 \ IO = l.size() + n
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(
      signatures
        .get("firstOf")
        .contains("(Ljava/util/ArrayList<Ljava/lang/String;>;)Ljava/util/Optional<Ljava/lang/String;>;")
    )
    // The `Int32` parameter has nothing to declare, so it repeats as `I`.
    assert(signatures.get("countOf").contains("(Ljava/util/ArrayList<Ljava/lang/String;>;I)I"))
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

  test("an exported Set is presented as a java.util.Set") {
    // The interface, not the generated view class behind it. A caller must never be able to name
    // the view: it is keyed on the erased element, so `Set[String]` and `Set[Regex]` share one,
    // and its name says so.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): Set[String] = Set#{"a", "b"}
        |
        |    @Export
        |    pub def numbers(): Set[Int32] = Set#{1, 2}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("names").contains("()Ljava/util/Set;"))
    assert(signatures.get("names").contains("()Ljava/util/Set<Ljava/lang/String;>;"))
    assert(signatures.get("numbers").contains("()Ljava/util/Set<Ljava/lang/Integer;>;"))
  }

  test("one view class serves every reference element") {
    // A view is keyed on the *erased* element, which is as generic as the JVM lets it be: every
    // reference element shares one class, exactly as an erased `SetView<E>` would.
    //
    // A primitive element gets its own class, and that is not a missed generalization. The view
    // reads the tree node's key field, and for `Set[Int32]` that field really is an `int` -- the
    // tag class is `Tag$Obj$Obj$Int32$Obj$Obj` and its `v2` has descriptor `I`. A field reference
    // carries its descriptor, so no single class can read both an `int` and an `Object` there.
    // Java generics erase to `Object`; Flix's specialization does not. These extra classes are
    // precisely where a generic view would have needed `Set<int>`, which Java cannot express
    // either.
    val names = classNamesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.time.DayOfWeek
        |
        |    @Export
        |    pub def a(): Set[String] = Set#{"x"}
        |
        |    @Export
        |    pub def b(): Set[Regex] = Set#{}
        |
        |    @Export
        |    pub def c(): Set[DayOfWeek] = Set#{}
        |
        |    @Export
        |    pub def d(): Set[Int32] = Set#{1}
        |
        |    @Export
        |    pub def e(): Set[Float64] = Set#{1.0f64}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin)
    val views = names.filter(_.startsWith("SetView"))
    // Three reference elements, two primitives -- three classes, not five.
    assertResult(3)(views.size)
    assert(views.exists(_.endsWith("Obj")), s"expected a shared reference view, got: $views")
  }

  test("an exported Map is presented as a java.util.Map") {
    // Two type arguments rather than one, so the signature is the first place a key and a value
    // could be swapped or one of them dropped.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def ages(): Map[String, Int32] = Map#{"a" => 1}
        |
        |    @Export
        |    pub def codes(): Map[Int32, String] = Map#{1 => "a"}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("ages").contains("()Ljava/util/Map;"))
    assert(signatures.get("ages").contains("()Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;"))
    assert(signatures.get("codes").contains("()Ljava/util/Map<Ljava/lang/Integer;Ljava/lang/String;>;"))
  }

  test("an exported tuple is presented as a dev.flix.runtime record") {
    // The element types survive only in the signature: the class is generic, so its descriptor
    // says `Tuple2` and nothing about what is in it. A primitive element appears boxed there for
    // the same reason it does inside an `Optional` -- a type argument is a reference.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def pair(): (Int32, String) = (1, "s")
        |
        |    @Export
        |    pub def triple(): (Bool, Float64, String) = (true, 1.0f64, "s")
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("pair").contains("()Ldev/flix/runtime/Tuple2;"))
    assert(signatures.get("pair").contains("()Ldev/flix/runtime/Tuple2<Ljava/lang/Integer;Ljava/lang/String;>;"))
    assert(descriptors.get("triple").contains("()Ldev/flix/runtime/Tuple3;"))
    assert(signatures.get("triple").contains(
      "()Ldev/flix/runtime/Tuple3<Ljava/lang/Boolean;Ljava/lang/Double;Ljava/lang/String;>;"))
  }

  test("one tuple class serves every arity-2 tuple") {
    // Unlike a view, which is keyed on the erased element and so multiplies with the primitives,
    // this class is keyed on arity alone. Nothing about the element types reaches its bytecode:
    // they are its type parameters, and a parameter erases to `Object` whatever it is bound to.
    val names = classNamesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def a(): (Int32, String) = (1, "s")
        |
        |    @Export
        |    pub def b(): (String, Bool) = ("s", true)
        |
        |    @Export
        |    pub def c(): (Regex, Float64) = (regex"a", 1.0f64)
        |
        |    @Export
        |    pub def d(): (Int32, String, Bool) = (1, "s", true)
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin)
    assertResult(Set("Tuple2", "Tuple3"))(names.filter(_.matches("Tuple\\d+")))
  }

  test("a tuple class declares the type parameters its shim supplies") {
    // A shim's signature applies `Tuple2` to two arguments, which is only well-formed if the class
    // itself declares two parameters. Without the class signature the arguments name nothing and
    // Scala and Kotlin reject the raw type -- the same failure the method signatures exist to
    // prevent, one level up.
    val signature = classSignatureOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def pair(): (Int32, String) = (1, "s")
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "dev/flix/runtime/Tuple2")
    assertResult(Some("<T1:Ljava/lang/Object;T2:Ljava/lang/Object;>Ljava/lang/Record;"))(signature)
  }

  test("an exported enum is presented as a class beside its namespace") {
    // Named as J1 names every other class a Java caller writes, so `mod Pkg.Mod` gives
    // `Pkg.Mod$Colour` rather than a name under `dev.flix.gen`. No signature: the class takes no
    // type arguments, so the descriptor already says everything about it.
    val (descriptors, signatures) = membersOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |
        |    @Export
        |    pub def favourite(): Colour = Colour.Red
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg/Mod")
    assert(descriptors.get("favourite").contains("()LPkg/Mod$Colour;"))
    assert(!signatures.contains("favourite"), s"an enum needs no signature, got: ${signatures.get("favourite")}")
  }

  test("an exported enum's private tag classes stay private") {
    // The Flix representation is one singleton class per case. Those keep their `dev.flix.gen`
    // names and are *not* what crosses -- the generated Java enum is a separate class, and both
    // exist in the same output.
    val names = classNamesOf(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |
        |    @Export
        |    pub def favourite(): Colour = Colour.Red
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin)
    // Simple names, so the `Pkg` package does not appear; the point is that the Java enum sits
    // beside the `Mod` facade while the tag classes keep their mangled `dev.flix.gen` names.
    assert(names.contains("Mod$Colour"), s"expected a generated Java enum, got: $names")
    assert(names.contains("Pkg$dotMod$dotColour$Red"), s"expected the tag classes to remain, got: $names")
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
      "List[BigInt]" -> "1ii :: Nil",
      "Set[String]" -> "Set#{\"s\"}",
      "Set[Int32]" -> "Set#{1}",
      "Set[Float64]" -> "Set#{1.0f64}",
      "Set[BigInt]" -> "Set#{1ii}",
      "Map[String, Int32]" -> "Map#{\"s\" => 1}",
      "Map[Int32, String]" -> "Map#{1 => \"s\"}",
      "Map[String, Float64]" -> "Map#{\"s\" => 1.0f64}",
      "Map[BigInt, BigInt]" -> "Map#{1ii => 1ii}",
      "(Int32, String)" -> "(1, \"s\")",
      "(String, Regex)" -> "(\"s\", regex\"a\")",
      "(Bool, Float64, BigInt)" -> "(true, 1.0f64, 1ii)",
      "Colour" -> "Colour.Red"
    )
    val defs = returnTypes.zipWithIndex.map {
      case ((tpe, value), i) => s"    @Export\n    pub def f$i(): $tpe = $value"
    }.mkString("\n\n")
    val (descriptors, signatures) = membersOf(
      s"""mod Pkg { }
         |mod Pkg.Mod {
         |    pub enum Colour { case Red, case Green }
         |
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
