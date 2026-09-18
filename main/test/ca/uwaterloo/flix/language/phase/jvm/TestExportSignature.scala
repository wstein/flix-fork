/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.{CD_String, CD_boolean, CD_int}

class TestExportSignature extends AnyFunSuite {

  private val Integer = ClassDesc.ofInternalName("java/lang/Integer")
  private val JavaList = ClassDesc.ofInternalName("java/util/List")
  private val Optional = ClassDesc.ofInternalName("java/util/Optional")

  test("an exact class keeps its descriptor") {
    val sig = ExportSignature.Exact(CD_String)
    assert(sig.javaType.descriptorString() == "Ljava/lang/String;")
    assert(sig.typeArgument == "Ljava/lang/String;")
  }

  test("a primitive type argument is named by its box") {
    val sig = ExportSignature.Boxed(CD_int, Integer)
    assert(sig.javaType.descriptorString() == "Ljava/lang/Integer;")
    assert(sig.typeArgument == "Ljava/lang/Integer;")
  }

  test("an applied class erases arguments in its descriptor and retains them in its signature") {
    val sig = ExportSignature.Applied(Optional, List(ExportSignature.Exact(CD_String)))
    assert(sig.javaType.descriptorString() == "Ljava/util/Optional;")
    assert(sig.typeArgument == "Ljava/util/Optional<Ljava/lang/String;>;")
  }

  test("nested applications retain nested generic signatures") {
    val inner = ExportSignature.Applied(JavaList, List(ExportSignature.Boxed(CD_int, Integer)))
    val sig = ExportSignature.Applied(Optional, List(inner))
    assert(sig.typeArgument == "Ljava/util/Optional<Ljava/util/List<Ljava/lang/Integer;>;>;")
  }

  test("source names use Java syntax") {
    assert(ExportSignature.Exact(CD_int).sourceName == "int")
    assert(ExportSignature.Exact(CD_boolean).sourceName == "boolean")
    assert(ExportSignature.Exact(CD_String).sourceName == "java.lang.String")
    assert(ExportSignature.Boxed(CD_int, Integer).sourceName == "java.lang.Integer")
  }

  test("an applied source name states the arguments erased by its descriptor") {
    val sig = ExportSignature.Applied(Optional, List(ExportSignature.Boxed(CD_int, Integer)))
    assert(sig.sourceName == "java.util.Optional<java.lang.Integer>")
  }

  test("array source names use brackets") {
    val array = CD_int.arrayType().arrayType()
    assert(ExportSignature.Exact(array).sourceName == "int[][]")
    assert(ExportSignature.Exact(array).javaType.descriptorString() == "[[I")
  }

  test("an empty application degrades to the plain class") {
    val sig = ExportSignature.Applied(JavaList, Nil)
    assert(sig.typeArgument == "Ljava/util/List;")
    assert(sig.sourceName == "java.util.List")
  }
}
