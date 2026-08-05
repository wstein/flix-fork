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
  *
  * The reflection is the *harness*, never the mechanism. Exported defs are `public static` methods
  * and every fixture calls them as ordinary javac-compiled static calls -- which is the whole point
  * of `@Export`, and what replaced the runtime-reflective bridge this scheme exists to retire. This
  * suite reflects only because it cannot statically reference classes generated during its own run,
  * and it loads them in isolated loaders so they never reach its own classpath.
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

  /** Compiles `sources` against `classpath`, returning the exit status, the diagnostics, and the output. */
  private def runJavac(sources: List[(String, String)], classpath: List[Path]): (Int, String, Path) = {
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
    val diagnostics = new java.io.ByteArrayOutputStream()
    val status = compiler.run(null, null, diagnostics, args*)
    (status, diagnostics.toString, classes)
  }

  /** Compiles `sources` against `classpath`, returning the output directory. */
  private def javac(sources: List[(String, String)], classpath: List[Path]): Path = {
    val (status, diagnostics, classes) = runJavac(sources, classpath)
    assert(status == 0, s"javac rejected the generated sources:\n$diagnostics")
    classes
  }

  /**
    * Compiles `src` against `jars` and returns how it failed, if it did.
    *
    * A thrown exception counts as a failure here, and today one of the cases below produces one:
    * reflecting over a Java class whose signature names a missing type lets
    * `ClassNotFoundException` escape `TypeReduction2` instead of becoming a diagnostic. That is a
    * defect in its own right -- see `docs/JOINT-COMPILATION.md` -- but it is not what these tests
    * are about, and pinning it as the expected outcome would turn a bug into a specification.
    */
  private def compileErrors(src: String, jars: List[Path]): Option[String] = {
    val out = Files.createTempDirectory("flix-export-stubs-expected-failure")
    try {
      val flix = new Flix().setOptions(Options.DefaultTest.copy(outputJvm = true, outputPath = out))
      jars.foreach(flix.addJar)
      flix.addVirtualPath(ModulePath, src)
      flix.compile().toResult match {
        case Result.Ok(_) => None
        case Result.Err(errors) => Some(errors.toString)
      }
    } catch {
      case e: Throwable => Some(s"${e.getClass.getName}: ${e.getMessage}")
    } finally deleteRecursively(out)
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

  test("criterion 3: a Java signature may name the Flix facade itself") {
    // Criteria 1 and 2 name the facade only in Java *bodies*, and a body is not read when Flix
    // resolves the class. A signature is: `Class.getMethods` loads every parameter and return type,
    // so a `Helper` whose signature mentions `Acme.Api` cannot be reflected over until that class
    // exists -- and at pass 2 the real one does not.
    //
    // This is what makes the stub more than a convenience for javac. It has to stay on the *Flix*
    // compile classpath too, and only be kept off the runtime one.
    val flixSrc =
      """mod Acme.Api {
        |    import com.example.Helper
        |
        |    @Export
        |    pub def greet(name: String): String = "Hello, ${name}!"
        |
        |    @Export
        |    pub def describe(): String \ IO = Helper.describe()
        |}
        |""".stripMargin

    val javaSrc =
      """package com.example;
        |
        |public final class Helper {
        |    public static String describe() {
        |        return "helper";
        |    }
        |
        |    // The facade in a signature rather than a body. Nothing calls this; its existence is
        |    // the whole point, because reflecting over `Helper` now has to resolve `Acme.Api`.
        |    public static Acme.Api passthrough(Acme.Api facade) {
        |        return facade;
        |    }
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(flixSrc)
    assert(unsupported.isEmpty, s"nothing should be refused, but got: $unsupported")
    val javaClasses = javac(
      List("Acme/Api.java" -> ExportStubs.javaSource(facades.head), "com/example/Helper.java" -> javaSrc), Nil)

    // Without the facade on the classpath, Flix cannot read `Helper` at all. Asserted rather than
    // assumed: it is the reason the stub may not simply be discarded after javac, and if it ever
    // stops being true the next assertion would pass for the wrong reason.
    val withoutFacade = Files.createTempDirectory("flix-export-stubs-no-facade")
    Files.walk(javaClasses.resolve("com")).filter(Files.isRegularFile(_)).forEach { file =>
      val target = withoutFacade.resolve(javaClasses.relativize(file))
      Files.createDirectories(target.getParent)
      Files.copy(file, target)
    }
    val errors = compileErrors(flixSrc, List(jarOf(withoutFacade)))
    assert(errors.isDefined, "Flix should not be able to read a Java class whose signature names a missing type")

    // With it, the whole scheme runs -- and the facade that answers at run time is still the real
    // one, because the compiler's output precedes the stub jar on the loader.
    withFacade(flixSrc, jars = List(jarOf(javaClasses))) { facade =>
      assertResult("helper")(facade.getMethod("describe").invoke(null))
      assertResult("Hello, Flix!")(facade.getMethod("greet", classOf[String]).invoke(null, "Flix"))
    }
  }

  test("criterion 4: removing an exported def fails the build at the Java call site") {
    // The failure mode this rules out is a stub that outlives what it stands for: Java compiles
    // against yesterday's facade and the mistake surfaces as a `NoSuchMethodError` in production.
    // Pass 0 derives the stub from the current source every time, so a def that is gone is gone
    // from the stub, and javac reports it where it is called.
    val javaSrc =
      """package com.example;
        |
        |public final class Helper {
        |    public static String viaFlix(String name) {
        |        return Acme.Api.greet(name);
        |    }
        |}
        |""".stripMargin

    val withoutGreet =
      """mod Acme.Api {
        |    @Export
        |    pub def farewell(name: String): String = "Bye, ${name}!"
        |}
        |""".stripMargin

    val (facades, _) = stubs(withoutGreet)
    val (status, diagnostics, _) = runJavac(
      List("Acme/Api.java" -> ExportStubs.javaSource(facades.head), "com/example/Helper.java" -> javaSrc), Nil)

    assert(status != 0, "javac must reject a call to a def that no longer exists")
    assert(diagnostics.contains("Helper.java"), s"the error must name the Java source, but was:\n$diagnostics")
    assert(diagnostics.contains("greet"), s"the error must name the missing method, but was:\n$diagnostics")
  }

  test("criterion 2: the mutual reference may cross a generic type") {
    // Criterion 1 crosses only `String`, which erasure cannot damage. Here every crossing carries
    // a type argument, in both directions and in both positions, so a stub that erased what it
    // describes would be caught by javac rather than passing quietly:
    //
    //   - `for (String s : Acme.Api.names())` does not compile against a raw `List`, because the
    //     element type would be `Object`.
    //   - `Acme.Api.sizeOf(List.of(...))` pins the argument side, which `ExportPlan.ofParameter`
    //     describes by a different route than the return side.
    //   - `Helper.strings().get(0)` is the direction the *Flix* resolver has to read a generic
    //     signature for: without it the element is `Object` and the def does not typecheck.
    val flixSrc =
      """mod Acme.Api {
        |    import com.example.Helper
        |    import java.util.{List => JList}
        |
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: Nil
        |
        |    @Export
        |    pub def sizeOf(xs: JList[String]): Int32 \ IO = xs.size()
        |
        |    @Export
        |    pub def firstFromJava(): String \ IO =
        |        let xs = Helper.strings();
        |        xs.get(0)
        |}
        |""".stripMargin

    val javaSrc =
      """package com.example;
        |
        |import java.util.List;
        |
        |public final class Helper {
        |    public static List<String> strings() {
        |        return List.of("java-a", "java-b");
        |    }
        |
        |    public static String firstViaFlix() {
        |        for (String s : Acme.Api.names()) {
        |            return s;
        |        }
        |        return "none";
        |    }
        |
        |    public static int sizeViaFlix() {
        |        return Acme.Api.sizeOf(List.of("x", "y", "z"));
        |    }
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(flixSrc)
    assert(unsupported.isEmpty, s"nothing should be refused, but got: $unsupported")
    val stubSource = ExportStubs.javaSource(facades.head)

    // Asserted directly as well as via javac, so a regression says *what* was lost rather than
    // only that some generated file stopped compiling.
    assert(stubSource.contains("java.util.List<java.lang.String> names()"), stubSource)
    assert(stubSource.contains("sizeOf(java.util.List<java.lang.String> arg0)"), stubSource)

    val javaClasses = javac(List("Acme/Api.java" -> stubSource, "com/example/Helper.java" -> javaSrc), Nil)
    deleteRecursively(javaClasses.resolve("Acme"))

    withFacade(flixSrc, jars = List(jarOf(javaClasses))) { facade =>
      // The stub and the facade must still agree once generics are involved -- this is where a
      // difference between the two readings would show up if there were one.
      for (method <- facades.head.methods) {
        val real = facade.getMethods.find(_.getName == method.name).get
        assertResult(reflected(real), s"stub and facade disagree about '${method.name}'")(declared(method))
      }

      // Flix read a generic Java signature.
      assertResult("java-a")(facade.getMethod("firstFromJava").invoke(null))

      val finalClasses = javac(List("com/example/Helper.java" -> javaSrc),
        List(Paths.get(facade.getProtectionDomain.getCodeSource.getLocation.toURI)))
      val loader = new URLClassLoader(Array[URL](finalClasses.toUri.toURL), facade.getClassLoader)
      try {
        val helper = loader.loadClass("com.example.Helper")
        assertResult("a")(helper.getMethod("firstViaFlix").invoke(null))
        assertResult(3)(helper.getMethod("sizeViaFlix").invoke(null))
      } finally loader.close()
    }
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
