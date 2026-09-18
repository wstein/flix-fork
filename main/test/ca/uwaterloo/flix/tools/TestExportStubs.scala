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

  test("unsupported reference and generic exports are refused rather than guessed") {
    val src =
      """mod Acme.Api {
        |    @Export pub def text(x: String): String = x
        |    @Export pub def list(x: List[Int32]): List[Int32] = x
        |}
        |""".stripMargin

    val (facades, unsupported) = stubs(src)
    assert(facades.isEmpty)
    assert(unsupported.map(_.name).toSet == Set("text", "list"))
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
      ExportStubs.write(first, root)
      val file = root.resolve("Acme/Api.java")
      assert(Files.readString(file).contains(" old("))
      Files.writeString(root.resolve("stale.java"), "stale")

      ExportStubs.write(second, root)
      assert(!Files.exists(root.resolve("stale.java")))
      val updated = Files.readString(file)
      assert(updated.contains(" fresh("))
      assert(!updated.contains(" old("))
    } finally deleteRecursively(root)
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
      ExportStubs.write(facades, stubRoot)
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
