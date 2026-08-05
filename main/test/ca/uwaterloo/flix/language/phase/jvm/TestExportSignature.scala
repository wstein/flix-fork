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

  test("an application with no arguments is named as the plain class") {
    // `L…<>;` is not a signature, so an empty argument list must degrade to the descriptor rather
    // than produce something no Java compiler can parse. `ExportPlan` never reaches this -- a bare
    // class is an `Exact` there -- but a caller building signatures from source syntax has no such
    // guarantee, and this is the case it would hit first.
    val sig = ExportSignature.Applied(JvmName.JavaList, Nil)
    assertResult("Ljava/util/List;")(sig.typeArgument)
    assertResult(sig.javaType.toDescriptor)(sig.typeArgument)
  }
}
