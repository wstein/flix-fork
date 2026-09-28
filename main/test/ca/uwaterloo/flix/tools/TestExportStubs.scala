/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.{Origin, SecurityContext, Source, SourceName}
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.net.URLClassLoader
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider

class TestExportStubs extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted
  private val sourcePath = Path.of("Acme", "Api.flix")

  test("stub signatures agree with the emitted primitive export facade") {
    val src =
      """mod Acme.Api {
        |    @Export pub def combine(flag: Bool, x: Int32, y: Int64): Int64 =
        |        if (flag or x > 0) y else y
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty)
    val facade = facades.head
    assert(binaryName(facade.name) == "Acme.Api")
    assert(facade.methods.map(m => (m.name, m.params.map(_.sourceName), m.result.sourceName)) ==
      List(("combine", List("boolean", "int", "long"), "long")))

    val flix = new Flix().setOptions(Options.DefaultTest)
    flix.addSource(sourcePath, src, sctx)
    val result = flix.compile() match {
      case Result.Ok(r) => r
      case Result.Err(errors) => fail(s"fixture must compile: $errors")
    }
    val emitted = result.getClasses(facade.name).bytecode
    val methods = collection.mutable.ListBuffer.empty[(String, String)]
    new ClassReader(emitted).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0)
          methods += name -> descriptor
        null
      }
    }, ClassReader.SKIP_CODE)
    assert(methods.contains("combine" -> "(ZIJ)J"))
  }

  test("stubs can be derived before an imported Java class exists") {
    val src =
      """mod Acme.Api {
        |    import com.example.NotYetCompiled
        |    @Export pub def twice(x: Int32): Int32 = x + x
        |    pub def callJava(x: Int32): Int32 \ IO = NotYetCompiled.inc(x)
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty)
    assert(facades.head.methods.map(_.name) == List("twice"))
  }

  test("String is described exactly while unsupported generic exports are refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def text(x: String): String = x
        |    @Export pub def list(x: List[Int32]): List[Int32] = x
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(facades.flatMap(_.methods).map(_.name) == List("text"))
    assert(facades.head.methods.head.params.head.sourceName == "java.lang.String")
    assert(facades.head.methods.head.result.sourceName == "java.lang.String")
    assert(unsupported.map(_.name) == List("list"))
  }

  test("Option results are typed while Option parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def maybe(_x: Int32): Option[String] = None
        |    @Export pub def consume(_x: Option[String]): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("maybe"))
    assert(methods.head.result.sourceName == "java.util.Optional<java.lang.String>")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("java.util.Optional<java.lang.String> maybe(int arg0)"))
  }

  test("List results are typed while List parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): List[Int32] = Nil
        |    @Export pub def consume(_x: List[Int32]): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    assert(methods.head.result.sourceName == "java.util.List<java.lang.Integer>")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("java.util.List<java.lang.Integer> values(int arg0)"))
  }

  test("Vector results are typed while Vector parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): Vector[Int32] = Vector#{}
        |    @Export pub def consume(_x: Vector[Int32]): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    assert(methods.head.result.sourceName == "java.util.List<java.lang.Integer>")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("java.util.List<java.lang.Integer> values(int arg0)"))
  }

  test("Chain results are typed while Chain parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): Chain[Int32] = Chain.empty()
        |    @Export pub def consume(_x: Chain[Int32]): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    assert(methods.head.result.sourceName == "java.util.Collection<java.lang.Integer>")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("java.util.Collection<java.lang.Integer> values(int arg0)"))
  }

  test("Set results are typed while Set parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): Set[Int32] = Set#{}
        |    @Export pub def consume(_x: Set[Int32]): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    assert(methods.head.result.sourceName == "java.util.Set<java.lang.Integer>")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("java.util.Set<java.lang.Integer> values(int arg0)"))
  }

  test("Map results are typed while Map parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): Map[Int32, Int32] = Map#{}
        |    @Export pub def consume(_x: Map[Int32, Int32]): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    assert(methods.head.result.sourceName == "java.util.Map<java.lang.Integer, java.lang.Integer>")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("java.util.Map<java.lang.Integer, java.lang.Integer> values(int arg0)"))
  }

  test("Tuple results are typed while Tuple parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): (Int32, Bool) = (1, true)
        |    @Export pub def consume(_x: (Int32, Bool)): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    assert(methods.head.result.sourceName == "dev.flix.gen.Tuple$int$boolean")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("dev.flix.gen.Tuple$int$boolean values(int arg0)"))
  }

  test("Record results are typed while Record parameters remain refused") {
    val src =
      """mod Acme.Api {
        |    @Export pub def values(_x: Int32): {name = String, age = Int32} = {name = "a", age = 1}
        |    @Export pub def consume(_x: {name = String, age = Int32}): Int32 = 0
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    val methods = facades.flatMap(_.methods)
    assert(methods.map(_.name) == List("values"))
    // Monomorphisation sorts a record's fields by label, and the generated class is named after them.
    assert(methods.head.result.sourceName == "dev.flix.gen.Record$age$int$name$String")
    assert(unsupported.map(_.name) == List("consume"))
    assert(ExportStubs.javaSource(facades.head).contains("dev.flix.gen.Record$age$int$name$String values(int arg0)"))
  }

  test("staged: a data-free enum result is declared by the stubs, merged with its companion module") {
    val flixSrc =
      """mod Acme {
        |    pub enum Color { case Red, case Green, case Blue }
        |    @Export pub def warmest(_x: Int32): Color = Color.Red
        |}
        |mod Acme.Color {
        |    @Export pub def parse(s: String): Acme.Color = if (s == "green") Acme.Color.Green else Acme.Color.Blue
        |}
        |mod Acme.Api {
        |    use Acme.Color
        |    @Export pub def favorite(_x: Int32): Color = Color.Green
        |}
        |""".stripMargin
    val javaSrc =
      """String s = switch (Acme.Api.favorite(0)) {
        |    case Red -> "r";
        |    case Green -> "g";
        |    case Blue -> "b";
        |};
        |return s + Acme.Color.parse("green").ordinal() + dev.flix.gen.Acme.warmest(0);
        |""".stripMargin
    assert(runStaged(flixSrc, javaSrc) == "g1Red")
  }

  test("staged: a data-carrying enum result is declared as a sealed interface of records") {
    val flixSrc =
      """mod Acme {
        |    pub enum Shape { case Circle(Int32), case Rect(Int32, String), case Point }
        |}
        |mod Acme.Shape {
        |    @Export pub def unit(_x: Int32): Acme.Shape = Acme.Shape.Circle(1)
        |}
        |mod Acme.Api {
        |    use Acme.Shape
        |    @Export pub def rect(_x: Int32): Shape = Shape.Rect(2, "wide")
        |}
        |""".stripMargin
    val javaSrc =
      """java.util.function.Function<Acme.Shape, String> describe = s -> switch (s) {
        |    case Acme.Shape.Circle c -> "c" + c.component0();
        |    case Acme.Shape.Rect(int w, String h) -> "r" + w + h;
        |    case Acme.Shape.Point p -> "p";
        |};
        |return describe.apply(Acme.Api.rect(0)) + describe.apply(Acme.Shape.unit(0)) + describe.apply(new Acme.Shape.Point());
        |""".stripMargin
    assert(runStaged(flixSrc, javaSrc) == "r2widec1p")
  }

  test("an enum the stub generator cannot find unambiguously is refused") {
    val src =
      """mod Acme.Model {
        |    pub enum Color { case Red }
        |}
        |mod Acme.Api {
        |    @Export pub def favorite(_x: Int32): Color = ???
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(facades.flatMap(_.methods).isEmpty)
    assert(unsupported.map(_.name) == List("favorite"))
  }

  test("a companion export named like a Java enum method is refused, as the compiler refuses it") {
    val src =
      """mod Acme {
        |    pub enum Color { case Red }
        |    @Export pub def favorite(_x: Int32): Color = Color.Red
        |}
        |mod Acme.Color {
        |    @Export pub def valueOf(_s: String): Acme.Color = Acme.Color.Red
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.map(_.name) == List("valueOf"))
    assert(facades.flatMap(_.methods).map(_.name) == List("favorite"))
  }

  test("imported generic Java types retain arguments in both positions") {
    val src =
      """mod Acme.Api {
        |    import java.util.ArrayList
        |    @Export pub def echo(xs: ArrayList[String]): ArrayList[String] = xs
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty)
    val method = facades.head.methods.head
    assert(method.params.head.sourceName == "java.util.ArrayList<java.lang.String>")
    assert(method.result.sourceName == "java.util.ArrayList<java.lang.String>")
    assert(ExportStubs.javaSource(facades.head).contains(
      "java.util.ArrayList<java.lang.String> echo(java.util.ArrayList<java.lang.String> arg0)"))
  }

  test("generated sources use the sibling facade name and compile with javac") {
    val src = "mod Acme.Api.Deep { @Export pub def id(x: Int32): Int32 = x }"
    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty)
    val java = ExportStubs.javaSource(facades.head)
    assert(java.contains("package Acme;"))
    assert(java.contains("public final class Api$Deep"))
    assert(java.contains(ExportStubs.Marker))

    val root = Files.createTempDirectory("flix-export-stub")
    try {
      val file = root.resolve("Acme/Api$Deep.java")
      Files.createDirectories(file.getParent)
      Files.writeString(file, java)
      val compiler = ToolProvider.getSystemJavaCompiler
      assume(compiler != null, "test requires a JDK")
      assert(compiler.run(null, null, null, "-d", root.toString, file.toString) == 0)
    } finally deleteRecursively(root)
  }

  test("writing stubs replaces stale generated output") {
    val first = stubs("mod Acme.Api { @Export pub def old(x: Int32): Int32 = x }")._1
    val second = stubs("mod Acme.Api { @Export pub def fresh(x: Int32): Int32 = x }")._1
    val root = Files.createTempDirectory("flix-export-stub-write")
    try {
      ExportStubs.write(first, root).unsafeGet
      val file = root.resolve("Acme/Api.java")
      assert(Files.readString(file).contains(" old("))
      Files.writeString(root.resolve("stale.java"), "stale")

      ExportStubs.write(second, root).unsafeGet
      assert(!Files.exists(root.resolve("stale.java")))
      val updated = Files.readString(file)
      assert(updated.contains(" fresh("))
      assert(!updated.contains(" old("))
    } finally deleteRecursively(root)
  }

  test("writing stubs refuses a destination that is a file") {
    val destination = Files.createTempFile("flix-export-stub-file", ".txt")
    Files.writeString(destination, "keep me")

    ExportStubs.write(Nil, destination) match {
      case Result.Err(error) =>
        assert(error.path == destination)
        assert(error.message.contains("not a directory"))
      case Result.Ok(_) => fail("a file cannot be used as a stub output directory")
    }
    assert(Files.readString(destination) == "keep me")
  }

  test("staged compilation links Java against the real Flix facade") {
    val src =
      """mod Acme.Api {
        |    import com.example.Helper
        |    @Export pub def twice(x: Int32): Int32 = x + x
        |    pub def viaJava(x: Int32): Int32 \ IO = Helper.inc(x)
        |}
        |""".stripMargin
    val root = Files.createTempDirectory("flix-joint-compilation")
    try {
      val stubRoot = root.resolve("stubs")
      val javaClasses = root.resolve("java-classes")
      val flixClasses = root.resolve("flix-classes")
      val helperJar = root.resolve("helper.jar")
      val helperSource = root.resolve("com/example/Helper.java")

      val (facades, unsupported) = stubs(src)
      assert(unsupported.isEmpty)
      ExportStubs.write(facades, stubRoot).unsafeGet
      Files.createDirectories(helperSource.getParent)
      Files.writeString(helperSource,
        """package com.example;
          |public final class Helper {
          |    public static int inc(int x) { return Acme.Api.twice(x) + 1; }
          |}
          |""".stripMargin)
      Files.createDirectories(javaClasses)
      val compiler = ToolProvider.getSystemJavaCompiler
      assume(compiler != null, "test requires a JDK")
      assert(compiler.run(null, null, null,
        "-d", javaClasses.toString,
        helperSource.toString,
        stubRoot.resolve("Acme/Api.java").toString) == 0)

      val jar = new JarOutputStream(Files.newOutputStream(helperJar))
      try {
        jar.putNextEntry(new JarEntry("com/example/Helper.class"))
        jar.write(Files.readAllBytes(javaClasses.resolve("com/example/Helper.class")))
        jar.closeEntry()
      } finally jar.close()

      val flix = new Flix(jars = List(helperJar)).setOptions(Options.DefaultTest)
      flix.addSource(sourcePath, src, sctx)
      val result = flix.compile() match {
        case Result.Ok(r) => r
        case Result.Err(errors) => fail(s"Flix must compile against the Java half: $errors")
      }
      Files.createDirectories(flixClasses)
      for ((desc, clazz) <- result.getClasses) {
        val relative = desc.descriptorString().stripPrefix("L").stripSuffix(";") + ".class"
        val target = flixClasses.resolve(relative)
        Files.createDirectories(target.getParent)
        Files.write(target, clazz.bytecode)
      }

      val loader = new URLClassLoader(Array(flixClasses.toUri.toURL, helperJar.toUri.toURL), getClass.getClassLoader)
      try {
        val helper = loader.loadClass("com.example.Helper")
        assert(helper.getMethod("inc", Integer.TYPE).invoke(null, Int.box(20)) == Int.box(41))
      } finally loader.close()
    } finally deleteRecursively(root)
  }

  test("staged: a tuple result's class is declared by the stubs and links to the real one") {
    val flixSrc =
      """mod Acme.Api {
        |    @Export pub def pair(_x: Int32): (Int32, String) = (1, "hi")
        |}
        |""".stripMargin
    val javaSrc =
      """var p = Acme.Api.pair(0);
        |return p.component0() + p.component1() + p.equals(new dev.flix.gen.Tuple$int$String(1, "hi"));
        |""".stripMargin
    assert(runStaged(flixSrc, javaSrc) == "1hitrue")
  }

  test("staged: a record result's class is declared by the stubs and links to the real one") {
    val flixSrc =
      """mod Acme.Api {
        |    @Export pub def person(_x: Int32): {name = String, age = Int32} = {name = "ada", age = 36}
        |}
        |""".stripMargin
    val javaSrc =
      """var p = Acme.Api.person(0);
        |return p.name() + p.age();
        |""".stripMargin
    assert(runStaged(flixSrc, javaSrc) == "ada36")
  }

  /**
    * Compiles `javaBody`, the body of a `static String run()`, against the stubs of `flixSrc`
    * alone, then runs it against the real classes compiled from `flixSrc`, with no stub on the
    * classpath. A stub that disagrees with the real bytecode fails here, not in a user's build.
    */
  private def runStaged(flixSrc: String, javaBody: String): AnyRef = {
    val (facades, unsupported) = stubs(flixSrc)
    assert(unsupported.isEmpty, s"every export must have a stub: $unsupported")
    val root = Files.createTempDirectory("flix-staged")
    try {
      val stubRoot = root.resolve("stubs")
      val javaClasses = root.resolve("java-classes")
      val flixClasses = root.resolve("flix-classes")
      val mainSource = root.resolve("src/com/example/Main.java")
      ExportStubs.write(facades, stubRoot).unsafeGet
      Files.createDirectories(mainSource.getParent)
      Files.writeString(mainSource,
        s"""package com.example;
           |public final class Main {
           |    public static String run() {
           |        $javaBody
           |    }
           |}
           |""".stripMargin)
      Files.createDirectories(javaClasses)
      val compiler = ToolProvider.getSystemJavaCompiler
      assume(compiler != null, "test requires a JDK")
      val diagnostics = new java.io.ByteArrayOutputStream()
      // `-implicit:none` reads the stubs to type-check `Main` without emitting a class for them.
      val status = compiler.run(null, null, diagnostics,
        "-sourcepath", stubRoot.toString, "-implicit:none", "-d", javaClasses.toString, mainSource.toString)
      assert(status == 0, s"javac rejected the caller against the stubs:\n$diagnostics")

      val flix = new Flix().setOptions(Options.DefaultTest)
      flix.addSource(sourcePath, flixSrc, sctx)
      val result = flix.compile() match {
        case Result.Ok(r) => r
        case Result.Err(errors) => fail(s"fixture must compile: $errors")
      }
      for ((desc, clazz) <- result.getClasses) {
        val target = flixClasses.resolve(desc.descriptorString().stripPrefix("L").stripSuffix(";") + ".class")
        Files.createDirectories(target.getParent)
        Files.write(target, clazz.bytecode)
      }

      val loader = new URLClassLoader(Array(flixClasses.toUri.toURL, javaClasses.toUri.toURL), getClass.getClassLoader)
      try loader.loadClass("com.example.Main").getMethod("run").invoke(null)
      // A stub that disagrees with the real class surfaces as a `LinkageError`, which would abort
      // the whole suite if rethrown rather than failing this one test.
      catch { case e: java.lang.reflect.InvocationTargetException => fail(s"the caller failed against the real classes: ${e.getCause}", e.getCause) }
      finally loader.close()
    } finally deleteRecursively(root)
  }

  private def stubs(text: String): (List[ExportStubs.Facade], List[ExportStubs.Unsupported]) = {
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest)
    val source = Source.fromString(SourceName.PathName(sourcePath), Origin.User, sctx, text)
    ExportStubs.run(List(source))
  }

  private def binaryName(desc: java.lang.constant.ClassDesc): String =
    desc.descriptorString().stripPrefix("L").stripSuffix(";").replace('/', '.')

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try stream.forEach(deleteRecursively) finally stream.close()
    }
    Files.deleteIfExists(path)
    ()
  }
}
