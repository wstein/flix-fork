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
import scala.jdk.CollectionConverters.*

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

  test("List results cross as typed unmodifiable java.util.List values") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def strings(_x: Int32): List[String] = "a" :: "b" :: Nil
        |    @Export pub def ints(_x: Int32): List[Int32] = 1 :: 2 :: Nil
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
    assert(methods("strings") == ("(I)Ljava/util/List;", "(I)Ljava/util/List<Ljava/lang/String;>;"))
    assert(methods("ints") == ("(I)Ljava/util/List;", "(I)Ljava/util/List<Ljava/lang/Integer;>;"))

    val output = Files.createTempDirectory("flix-export-list")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val strings = clazz.getMethod("strings", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.List[String]]
        val ints = clazz.getMethod("ints", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.List[Integer]]
        assert(strings.asScala.toList == List("a", "b"))
        assert(ints.asScala.map(_.intValue()).toList == List(1, 2))
        assertThrows[UnsupportedOperationException](strings.add("c"))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Vector results cross as typed unmodifiable java.util.List values") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def strings(_x: Int32): Vector[String] = Vector#{"a", "b"}
        |    @Export pub def ints(_x: Int32): Vector[Int32] = Vector#{1, 2}
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
    assert(methods("strings") == ("(I)Ljava/util/List;", "(I)Ljava/util/List<Ljava/lang/String;>;"))
    assert(methods("ints") == ("(I)Ljava/util/List;", "(I)Ljava/util/List<Ljava/lang/Integer;>;"))

    val output = Files.createTempDirectory("flix-export-vector")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val strings = clazz.getMethod("strings", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.List[String]]
        val ints = clazz.getMethod("ints", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.List[Integer]]
        assert(strings.asScala.toList == List("a", "b"))
        assert(ints.asScala.map(_.intValue()).toList == List(1, 2))
        assertThrows[UnsupportedOperationException](strings.add("c"))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Chain results cross as typed unmodifiable java.util.Collection values") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def strings(_x: Int32): Chain[String] =
        |        Chain.append(Chain.append(Chain.singleton("a"), Chain.singleton("b")), Chain.singleton("c"))
        |    @Export pub def ints(_x: Int32): Chain[Int32] =
        |        Chain.append(Chain.append(Chain.singleton(1), Chain.singleton(2)), Chain.singleton(3))
        |    @Export pub def empty(_x: Int32): Chain[Int32] = Chain.empty()
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
    assert(methods("strings") == ("(I)Ljava/util/Collection;", "(I)Ljava/util/Collection<Ljava/lang/String;>;"))
    assert(methods("ints") == ("(I)Ljava/util/Collection;", "(I)Ljava/util/Collection<Ljava/lang/Integer;>;"))

    val output = Files.createTempDirectory("flix-export-chain")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val strings = clazz.getMethod("strings", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Collection[String]]
        val ints = clazz.getMethod("ints", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Collection[Integer]]
        val empty = clazz.getMethod("empty", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Collection[Integer]]
        assert(strings.asScala.toList == List("a", "b", "c"))
        assert(ints.asScala.map(_.intValue()).toList == List(1, 2, 3))
        assert(empty.isEmpty)
        assertThrows[UnsupportedOperationException](strings.add("d"))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Set results cross as typed unmodifiable java.util.Set values") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def strings(_x: Int32): Set[String] = Set#{"a", "b", "c"}
        |    @Export pub def ints(_x: Int32): Set[Int32] = Set#{1, 2, 3}
        |    @Export pub def empty(_x: Int32): Set[Int32] = Set#{}
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
    assert(methods("strings") == ("(I)Ljava/util/Set;", "(I)Ljava/util/Set<Ljava/lang/String;>;"))
    assert(methods("ints") == ("(I)Ljava/util/Set;", "(I)Ljava/util/Set<Ljava/lang/Integer;>;"))

    val output = Files.createTempDirectory("flix-export-set")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val strings = clazz.getMethod("strings", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Set[String]]
        val ints = clazz.getMethod("ints", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Set[Integer]]
        val empty = clazz.getMethod("empty", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Set[Integer]]
        assert(strings.asScala == Set("a", "b", "c"))
        assert(ints.asScala.map(_.intValue()) == Set(1, 2, 3))
        assert(empty.isEmpty)
        assertThrows[UnsupportedOperationException](strings.add("d"))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Map results cross as typed unmodifiable java.util.Map values") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def ages(_x: Int32): Map[String, Int32] = Map#{"a" => 1, "b" => 2}
        |    @Export pub def empty(_x: Int32): Map[String, Int32] = Map#{}
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
    assert(methods("ages") == ("(I)Ljava/util/Map;", "(I)Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;"))

    val output = Files.createTempDirectory("flix-export-map")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val ages = clazz.getMethod("ages", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Map[String, Integer]]
        val empty = clazz.getMethod("empty", Integer.TYPE).invoke(null, Int.box(0)).asInstanceOf[java.util.Map[String, Integer]]
        assert(ages.asScala.view.mapValues(_.intValue()).toMap == Map("a" -> 1, "b" -> 2))
        assert(empty.isEmpty)
        assertThrows[UnsupportedOperationException](ages.put("c", 3))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Tuple results cross as a generated java.lang.Record") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def pair(_x: Int32): (Int32, String) = (1, "hello")
        |    @Export pub def another(_x: Int32): (Int32, String) = (2, "world")
        |}
        |""".stripMargin)

    val output = Files.createTempDirectory("flix-export-tuple")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val pair = clazz.getDeclaredMethods.find(_.getName == "pair").get.invoke(null, Int.box(0))
        val another = clazz.getDeclaredMethods.find(_.getName == "another").get.invoke(null, Int.box(0))
        val recordClass = pair.getClass

        assert(recordClass.isRecord)
        assert(another.getClass eq recordClass, "same-shaped tuples share one generated record class")

        val components = recordClass.getRecordComponents
        assert(components.length == 2)
        assert(recordClass.getMethod("component0").invoke(pair) == Int.box(1))
        assert(recordClass.getMethod("component1").invoke(pair) == "hello")

        val pairAgain = clazz.getDeclaredMethods.find(_.getName == "pair").get.invoke(null, Int.box(0))
        assert(pair == pairAgain)
        assert(pair.hashCode() == pairAgain.hashCode())
        assert(pair != another)
        assert(pair.toString.contains("1"))
        assert(pair.toString.contains("hello"))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("Record results cross as a generated java.lang.Record with the source field names") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def person(_x: Int32): {name = String, age = Int32} = {name = "hello", age = 1}
        |    @Export pub def another(_x: Int32): {name = String, age = Int32} = {name = "world", age = 2}
        |}
        |""".stripMargin)

    val output = Files.createTempDirectory("flix-export-record")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val person = clazz.getDeclaredMethods.find(_.getName == "person").get.invoke(null, Int.box(0))
        val another = clazz.getDeclaredMethods.find(_.getName == "another").get.invoke(null, Int.box(0))
        val recordClass = person.getClass

        assert(recordClass.isRecord)
        assert(another.getClass eq recordClass, "same-shaped records share one generated record class")

        val componentNames = recordClass.getRecordComponents.map(_.getName).toSet
        assert(componentNames == Set("name", "age"))
        assert(recordClass.getMethod("name").invoke(person) == "hello")
        assert(recordClass.getMethod("age").invoke(person) == Int.box(1))

        val personAgain = clazz.getDeclaredMethods.find(_.getName == "person").get.invoke(null, Int.box(0))
        assert(person == personAgain)
        assert(person.hashCode() == personAgain.hashCode())
        assert(person != another)
        assert(person.toString.contains("hello"))
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("generic Java types retain arguments in exported parameters and results") {
    val result = compile(
      """mod Acme.Api {
        |    import java.util.ArrayList
        |    @Export pub def echo(xs: ArrayList[String]): ArrayList[String] = xs
        |}
        |""".stripMargin)

    val facade = result.getClasses(Mangle.namespaceFacadeDesc(List("Acme", "Api"))).bytecode
    var member: Option[(String, String)] = None
    new ClassReader(facade).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name == "echo") member = Some(descriptor -> signature)
        null
      }
    }, ClassReader.SKIP_CODE)
    assert(member.contains(
      "(Ljava/util/ArrayList;)Ljava/util/ArrayList;" ->
        "(Ljava/util/ArrayList<Ljava/lang/String;>;)Ljava/util/ArrayList<Ljava/lang/String;>;"))

    val output = Files.createTempDirectory("flix-export-generic-java")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val values = new java.util.ArrayList[String]()
        values.add("hello")
        assert(clazz.getMethod("echo", classOf[java.util.ArrayList[?]]).invoke(null, values) eq values)
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("a Unit-returning export still retains a generic parameter's signature") {
    val result = compile(
      """mod Acme.Api {
        |    import java.util.ArrayList
        |    @Export pub def consume(_xs: ArrayList[String]): Unit = ()
        |}
        |""".stripMargin)

    val facade = result.getClasses(Mangle.namespaceFacadeDesc(List("Acme", "Api"))).bytecode
    var member: Option[(String, String)] = None
    new ClassReader(facade).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name == "consume") member = Some(descriptor -> signature)
        null
      }
    }, ClassReader.SKIP_CODE)
    assert(member.contains("(Ljava/util/ArrayList;)V" -> "(Ljava/util/ArrayList<Ljava/lang/String;>;)V"))

    val output = Files.createTempDirectory("flix-export-unit-generic")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val values = new java.util.ArrayList[String]()
        values.add("hello")
        assert(clazz.getMethod("consume", classOf[java.util.ArrayList[?]]).invoke(null, values) == null)
      } finally loader.close()
    } finally deleteRecursively(output)
  }

  test("a nullary export's Unit parameter still verifies") {
    val result = compile(
      """mod Acme.Api {
        |    @Export pub def hello(): String = "hi"
        |}
        |""".stripMargin)

    val facade = result.getClasses(Mangle.namespaceFacadeDesc(List("Acme", "Api"))).bytecode
    var member: Option[String] = None
    new ClassReader(facade).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name == "hello") member = Some(descriptor)
        null
      }
    }, ClassReader.SKIP_CODE)
    assert(member.isDefined)

    val output = Files.createTempDirectory("flix-export-nullary")
    try {
      writeClasses(result, output)
      val loader = new URLClassLoader(Array(output.toUri.toURL), getClass.getClassLoader)
      try {
        val clazz = loader.loadClass("Acme.Api")
        val method = clazz.getDeclaredMethods.find(_.getName == "hello").get
        assert(method.invoke(null) == "hi")
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
