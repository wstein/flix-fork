/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import org.scalatest.funsuite.AnyFunSuite

class TestExportSignature extends AnyFunSuite {

  test("a bare class is named by its descriptor in both positions") {
    val sig = ExportSignature.Exact(BackendObjType.Native(JvmName.BigInteger).toTpe)
    assertResult("Ljava/math/BigInteger;")(sig.javaType.toDescriptor)
    assertResult("Ljava/math/BigInteger;")(sig.typeArgument)
  }

  test("a primitive is erased to itself and named by its box") {
    // The asymmetry is the point: a shim returning `Int32` returns `I`, but the *element* of a
    // container holding one is `Ljava/lang/Integer;`, because a type argument is a reference.
    val sig = ExportSignature.Boxed(BackendType.Int32, JvmName.Integer)
    assertResult("Ljava/lang/Integer;")(sig.javaType.toDescriptor)
    assertResult("Ljava/lang/Integer;")(sig.typeArgument)
  }

  test("an applied class erases its arguments in the descriptor and keeps them in the signature") {
    // This pair is the whole reason the type argument is tracked separately. The descriptor a JVM
    // resolves against cannot carry `<String>`; the generic signature a Java compiler reads must.
    val sig = ExportSignature.Applied(JvmName.Optional, List(ExportSignature.Exact(BackendType.String)))
    assertResult("Ljava/util/Optional;")(sig.javaType.toDescriptor)
    assertResult("Ljava/util/Optional<Ljava/lang/String;>;")(sig.typeArgument)
  }

  test("nested applications nest their signatures") {
    val inner = ExportSignature.Applied(JvmName.JavaList, List(ExportSignature.Boxed(BackendType.Int32, JvmName.Integer)))
    val sig = ExportSignature.Applied(JvmName.Optional, List(inner))
    assertResult("Ljava/util/Optional<Ljava/util/List<Ljava/lang/Integer;>;>;")(sig.typeArgument)
  }

  test("source names are the Java spelling, not the descriptor") {
    // The interesting half is the primitives: a stub declaring `I foo()` does not compile, and
    // nothing about `toDescriptor` would have caught it.
    assertResult("int")(ExportSignature.Exact(BackendType.Int32).sourceName)
    assertResult("boolean")(ExportSignature.Exact(BackendType.Bool).sourceName)
    assertResult("java.lang.String")(ExportSignature.Exact(BackendType.String).sourceName)
    assertResult("java.lang.Integer")(ExportSignature.Boxed(BackendType.Int32, JvmName.Integer).sourceName)
  }

  test("an applied class states in source exactly what the descriptor erases") {
    val sig = ExportSignature.Applied(JvmName.Optional, List(ExportSignature.Boxed(BackendType.Int32, JvmName.Integer)))
    assertResult("Ljava/util/Optional;")(sig.javaType.toDescriptor)
    assertResult("java.util.Optional<java.lang.Integer>")(sig.sourceName)
  }

  test("an array is written with brackets rather than a leading dimension") {
    assertResult("int[][]")(ExportSignature.Exact(BackendType.Array(BackendType.Array(BackendType.Int32))).sourceName)
    assertResult("[[I")(ExportSignature.Exact(BackendType.Array(BackendType.Array(BackendType.Int32))).javaType.toDescriptor)
  }

  test("a generated facade is written as its binary name, dollars and all") {
    // `mod Acme.Api.Deep` becomes the top-level class `Acme.Api$Deep`, not a nested class, so its
    // binary name is already legal Java source. A stub that rewrote the `$` to a `.` would name a
    // class that does not exist.
    val facade = JvmName.facadeOfNamespace(List("Acme", "Api", "Deep"))
    assertResult("Acme.Api$Deep")(ExportSignature.Exact(facade.toTpe).sourceName)
  }

  test("an application with no arguments is named as the plain class") {
    // `L…<>;` is not a signature, so an empty argument list must degrade to the descriptor rather
    // than produce something no Java compiler can parse. `ExportPlan` never reaches this -- a bare
    // class is an `Exact` there -- but a caller building signatures from source syntax has no such
    // guarantee, and this is the case it would hit first.
    val sig = ExportSignature.Applied(JvmName.JavaList, Nil)
    assertResult("Ljava/util/List;")(sig.typeArgument)
    assertResult(sig.javaType.toDescriptor)(sig.typeArgument)
    assertResult("java.util.List")(sig.sourceName)
  }
}
