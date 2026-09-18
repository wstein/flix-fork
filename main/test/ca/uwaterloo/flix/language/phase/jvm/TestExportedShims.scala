/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.Optional

class TestExportedShims extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  test("String crosses an exported shim without erasing its descriptor") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def echo(s: String): String = s
        |}
        |""".stripMargin)

    val facade = result.getClasses(Mangle.namespaceFacadeDesc(List("Acme", "Api"))).bytecode
    val methods = collection.mutable.ListBuffer.empty[(String, String)]
    new ClassReader(facade).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0)
          methods += name -> descriptor
        null
      }
    }, ClassReader.SKIP_CODE)
    assert(methods.contains("echo" -> "(Ljava/lang/String;)Ljava/lang/String;"))

    val output = Files.createTempDirectory("flix-export-string")
    try {
      for ((desc, clazz) <- result.getClasses) {
        val target = output.resolve(desc.descriptorString().stripPrefix("L").stripSuffix(";") + ".class")
        Files.createDirectories(target.getParent)
        Files.write(target, clazz.bytecode)
      }
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        assert(clazz.getMethod("echo", classOf[String]).invoke(null, "hello") == "hello")
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Option results cross as typed java.util.Optional values") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def someString(_x: Int32): Option[String] = Some("hello")
        |    @Export pub def noString(_x: Int32): Option[String] = None
        |    @Export pub def someInt(_x: Int32): Option[Int32] = Some(42)
        |}
        |""".stripMargin)

    val facade = result.getClasses(Mangle.namespaceFacadeDesc(List("Acme", "Api"))).bytecode
    val methods = collection.mutable.Map.empty[String, (String, String)]
    new ClassReader(facade).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if ((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0)
          methods(name) = descriptor -> signature
        null
      }
    }, ClassReader.SKIP_CODE)
    assert(methods("someString") == ("(I)Ljava/util/Optional;", "(I)Ljava/util/Optional<Ljava/lang/String;>;"))
    assert(methods("someInt") == ("(I)Ljava/util/Optional;", "(I)Ljava/util/Optional<Ljava/lang/Integer;>;"))

    val output = Files.createTempDirectory("flix-export-option")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        assert(clazz.getMethod("someString", Integer.TYPE).invoke(null, Int.box(0)) == Optional.of("hello"))
        assert(clazz.getMethod("noString", Integer.TYPE).invoke(null, Int.box(0)) == Optional.empty[String]())
        assert(clazz.getMethod("someInt", Integer.TYPE).invoke(null, Int.box(0)) == Optional.of(Int.box(42)))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  private def compile(program: String) = {
    val flix = new Flix().setOptions(Options.DefaultTest)
    flix.addSource(CompilerConstants.VirtualTestFile, program, sctx)
    flix.compile() match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"fixture must compile: $errors")
    }
  }

  private def writeClasses(result: ca.uwaterloo.flix.runtime.CompilationResult, output: Path): Unit = {
    for ((desc, clazz) <- result.getClasses) {
      val target = output.resolve(desc.descriptorString().stripPrefix("L").stripSuffix(";") + ".class")
      Files.createDirectories(target.getParent)
      Files.write(target, clazz.bytecode)
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try stream.forEach(deleteRecursively) finally stream.close()
    }
    Files.deleteIfExists(path)
    ()
  }
}
