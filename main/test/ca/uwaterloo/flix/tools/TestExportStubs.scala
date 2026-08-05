/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.{Input, SecurityContext}
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import java.lang.reflect.Method
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path, Paths}
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider

/**
  * Tests the stub generator that breaks the Flix/Java build cycle.
  *
  * The stubs are only worth having if they describe the *same* boundary the backend emits, and
  * nothing in the types enforces that: `ExportStubs` reads weeded syntax while `ExportPlan` reads
  * resolved and monomorphized types. So the central test here compiles a program, reflects over the
  * facade the backend actually produced, and compares it with the stub -- if the two ever disagree,
  * a caller compiles against one and links against the other.
  */
class TestExportStubs extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** Where the fixtures put their module, and therefore what the facade is called. */
  private val ModulePath: Path = Paths.get("Acme", "Api.flix")
  private val FacadeName: String = "Acme.Api"

  /** Runs pass 0 over `src`. */
  private def stubs(src: String): (List[ExportStubs.Facade], List[ExportStubs.Unsupported]) = {
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest)
    ExportStubs.run(List(Input.VirtualFile(ModulePath, src, sctx)))
  }

  /** Compiles `src` and applies `f` to the facade the backend produced. */
  private def withFacade[A](src: String, jars: List[Path] = Nil)(f: Class[?] => A): A = {
    val out = Files.createTempDirectory("flix-export-stubs")
    try {
      val flix = new Flix().setOptions(Options.DefaultTest.copy(outputJvm = true, outputPath = out))
      jars.foreach(flix.addJar)
      flix.addVirtualPath(ModulePath, src)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the fixture must compile, but got: $errors")
      }
      // The jars go on the loader as well as on the compiler: Flix compiled against their
      // signatures, and the generated code calls into them.
      val urls = (out.resolve("class") :: jars).map(_.toUri.toURL).toArray
      val loader = new URLClassLoader(urls, getClass.getClassLoader)
      try f(loader.loadClass(FacadeName)) finally loader.close()
    } finally deleteRecursively(out)
  }

  /** Returns how a stub declares `method`, in the same spelling reflection uses. */
  private def declared(method: ExportStubs.Method): (String, List[String]) =
    (method.result.map(_.sourceName).getOrElse("void"), method.params.map(_.sourceName))

  /** Returns how the compiled facade declares `method`, generic arguments included. */
  private def reflected(method: Method): (String, List[String]) =
    (method.getGenericReturnType.getTypeName, method.getGenericParameterTypes.map(_.getTypeName).toList)

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
    ()
  }

  /** Compiles `sources` against `classpath`, returning the output directory. */
  private def javac(sources: List[(String, String)], classpath: List[Path]): Path = {
    val compiler = ToolProvider.getSystemJavaCompiler
    assume(compiler != null, "these tests need a JDK, not a JRE")
    val dir = Files.createTempDirectory("flix-export-stubs-javac")
    val files = sources.map { case (name, content) =>
      val file = dir.resolve(name)
      Files.createDirectories(file.getParent)
      Files.writeString(file, content)
      file.toString
    }
    val classes = Files.createDirectories(dir.resolve("classes"))
    val cp = classpath.map(_.toString).mkString(File.pathSeparator)
    val args = List("-d", classes.toString) ++ (if (cp.isEmpty) Nil else List("-cp", cp)) ++ files
    val status = compiler.run(null, null, null, args*)
    assert(status == 0, s"javac rejected the generated sources: ${sources.map(_._1).mkString(", ")}")
    classes
  }

  /**
    * Packages `classes` as a jar.
    *
    * `Flix.addJar` accepts only a `.jar`, never a directory of class files, so a build tool doing
    * joint compilation has to package the Java side between passes. That is a real constraint on
    * the scheme rather than a detail of this test.
    */
  private def jarOf(classes: Path): Path = {
    val jar = Files.createTempFile("flix-export-stubs", ".jar")
    val out = new JarOutputStream(Files.newOutputStream(jar))
    try {
      Files.walk(classes).filter(Files.isRegularFile(_)).forEach { file =>
        out.putNextEntry(new JarEntry(classes.relativize(file).toString.replace(File.separatorChar, '/')))
        out.write(Files.readAllBytes(file))
        out.closeEntry()
      }
    } finally out.close()
    jar
  }

  test("a stub describes the facade the backend actually emits") {
    // The property everything else rests on. `ExportStubs` reads weeded syntax and `ExportPlan`
    // reads monomorphized types; they are two readings of one boundary with no shared code path
    // below `ExportSignature`, so their agreement has to be asserted rather than assumed.
    val src =
      """mod Acme.Api {
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |
        |    @Export
        |    pub def count(x: Int32, y: Int64): Bool = x > 0 and y > 0i64
        |
        |    @Export
        |    pub def flag(b: Bool): Bool = not b
        |
        |    @Export
        |    pub def announce(): Unit \ IO = println("hi")
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty, s"nothing here should be refused, but got: $unsupported")
    val facade = facades.find(_.name.toBinaryName == FacadeName).getOrElse(fail(s"no facade for $FacadeName"))

    withFacade(src) { clazz =>
      for (method <- facade.methods) {
        val real = clazz.getMethods.find(_.getName == method.name)
          .getOrElse(fail(s"the compiled facade has no method '${method.name}'"))
        assertResult(reflected(real), s"stub and facade disagree about '${method.name}'")(declared(method))
      }
      assertResult(facade.methods.map(_.name).sorted)(
        clazz.getDeclaredMethods.filter(m => java.lang.reflect.Modifier.isPublic(m.getModifiers)).map(_.getName).sorted.toList)
    }
  }

  test("a stub describes converted containers exactly as the facade declares them") {
    // `Option` and `List` are the two types whose Java face is not their Flix face. The generic
    // argument is the part a descriptor erases, so it is also the part a stub can silently get
    // wrong -- `Optional` rather than `Optional<String>` still compiles at every call site that
    // assigns to `var`.
    val src =
      """mod Acme.Api {
        |    @Export
        |    pub def find(x: String): Option[String] = Some(x)
        |
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: Nil
        |
        |    @Export
        |    pub def counts(): List[Int32] = 1 :: 2 :: Nil
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty, s"nothing here should be refused, but got: $unsupported")
    val facade = facades.head

    assertResult("java.util.Optional<java.lang.String>")(
      facade.methods.find(_.name == "find").get.result.get.sourceName)
    assertResult("java.util.List<java.lang.Integer>")(
      facade.methods.find(_.name == "counts").get.result.get.sourceName)

    withFacade(src) { clazz =>
      for (method <- facade.methods) {
        val real = clazz.getMethods.find(_.getName == method.name).get
        assertResult(reflected(real), s"stub and facade disagree about '${method.name}'")(declared(method))
      }
    }
  }

  test("stubs are produced for a program that cannot compile yet") {
    // This is the whole point of pass 0. The Java class this module calls does not exist when the
    // stub is generated -- it is generated so that it *can* exist -- so a generator that needed the
    // program to resolve would be useless exactly when it is needed.
    val src =
      """mod Acme.Api {
        |    import com.example.NotYetCompiled
        |
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |
        |    pub def shout(s: String): String \ IO = NotYetCompiled.shout(s)
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty, s"nothing should be refused, but got: $unsupported")
    assertResult(List("greet"))(facades.head.methods.map(_.name))
  }

  test("an imported Java type is named from the import, since nothing else can name it") {
    val src =
      """mod Acme.Api {
        |    import java.util.ArrayList
        |
        |    @Export
        |    pub def empty(): ArrayList[String] \ IO = new ArrayList()
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(unsupported.isEmpty, s"nothing should be refused, but got: $unsupported")
    assertResult("java.util.ArrayList<java.lang.String>")(facades.head.methods.head.result.get.sourceName)
  }

  test("a type that cannot be accounted for is refused rather than guessed at") {
    // A wrong stub is worse than a missing one: it compiles, and the caller discovers the mistake
    // as a linkage error at run time. `Mystery` is not a builtin and is not imported, so at this
    // stage there is no evidence about what it is.
    val src =
      """mod Acme.Api {
        |    @Export
        |    pub def mystery(): Mystery = ???
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(facades.isEmpty, s"a refused def must not produce a facade, but got: $facades")
    assertResult(List("mystery"))(unsupported.map(_.name))
  }

  test("the generated source compiles") {
    val src =
      """mod Acme.Api {
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |
        |    @Export
        |    pub def find(x: String): Option[String] = Some(x)
        |}
        |""".stripMargin

    val (facades, _) = stubs(src)
    javac(List("Acme/Api.java" -> ExportStubs.javaSource(facades.head)), Nil)
  }

  test("criterion 1: Java and Flix may call each other within one source set") {
    // The acceptance criterion from `docs/JOINT-COMPILATION.md`. `Helper` calls an exported Flix
    // def and the same Flix module calls a method on `Helper`, with no ordering hint from the
    // author -- which has no valid build order until pass 0 supplies the missing face.
    val flixSrc =
      """mod Acme.Api {
        |    import com.example.Helper
        |
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |
        |    @Export
        |    pub def shout(name: String): String \ IO = Helper.shout(name)
        |}
        |""".stripMargin

    val javaSrc =
      """package com.example;
        |
        |public final class Helper {
        |    public static String shout(String s) {
        |        return s.toUpperCase();
        |    }
        |
        |    public static String viaFlix(String name) {
        |        return Acme.Api.greet(name);
        |    }
        |}
        |""".stripMargin

    // Pass 0: the Flix facade's Java face, derived without compiling anything.
    val (facades, unsupported) = stubs(flixSrc)
    assert(unsupported.isEmpty, s"nothing should be refused, but got: $unsupported")
    val stubSource = ExportStubs.javaSource(facades.head)

    // Pass 1: javac compiles the real Java class against the stub. Note what is *not* needed here
    // -- a stub for `Helper` itself. javac produces `Helper`'s own signatures as it goes, so the
    // second stub pass in the blueprint is only required when a Java signature names something
    // that does not exist yet, which is criterion 3 rather than this one.
    val javaClasses = javac(List("Acme/Api.java" -> stubSource, "com/example/Helper.java" -> javaSrc), Nil)

    // The stub must not survive into anything that runs. Deleting it here is what makes the rest
    // of this test evidence: whatever `Helper` links against afterwards is the real facade.
    deleteRecursively(javaClasses.resolve("Acme"))

    // Pass 2: Flix compiles against the real `Helper`.
    withFacade(flixSrc, jars = List(jarOf(javaClasses))) { facade =>
      // Flix called Java.
      assertResult("HELLO")(facade.getMethod("shout", classOf[String]).invoke(null, "hello"))

      // Pass 3: javac recompiles the Java class against the real facade, and Java calls Flix.
      val facadeDir = Paths.get(facade.getProtectionDomain.getCodeSource.getLocation.toURI)
      val finalClasses = javac(List("com/example/Helper.java" -> javaSrc), List(facadeDir))
      val loader = new URLClassLoader(Array[URL](finalClasses.toUri.toURL), facade.getClassLoader)
      try {
        val helper = loader.loadClass("com.example.Helper")
        assertResult("Hello, Java!")(helper.getMethod("viaFlix", classOf[String]).invoke(null, "Java"))
      } finally loader.close()
    }
  }
}
