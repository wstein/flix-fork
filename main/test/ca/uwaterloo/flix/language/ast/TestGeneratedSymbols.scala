/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.ast

import org.scalatest.funsuite.AnyFunSuite

class TestGeneratedSymbols extends AnyFunSuite {

  private val owner = Symbol.mkDefnSym("Example.test1")
  private val tpe = SimpleType.Arrow(List(SimpleType.Int32), SimpleType.Int32)

  test("generated definitions use stable Base58 names") {
    val generated = Symbol.generatedDefnSym(owner, "closure", 0, tpe, SourceLocation.Unknown)

    assert(generated.name.matches("test1\\$[1-9A-HJ-NP-Za-km-z]{11}"))
    assert(generated == Symbol.generatedDefnSym(owner, "closure", 0, tpe, SourceLocation.Unknown))
    assert(generated != Symbol.generatedDefnSym(owner, "local-def", 0, tpe, SourceLocation.Unknown))
  }

  test("definitions lifted from the same position are told apart by their occurrence") {
    // A desugared construct can lift several definitions from one source position with the same
    // signature. Without the occurrence they are given the same name, and the later one silently
    // replaces the earlier when the lifted definitions are folded into the root.
    val first = Symbol.generatedDefnSym(owner, "closure", 0, tpe, SourceLocation.Unknown)
    val second = Symbol.generatedDefnSym(owner, "closure", 1, tpe, SourceLocation.Unknown)

    assert(first != second)
  }

  test("definitions lifted from the same position are told apart by their signature") {
    val unary = SimpleType.Arrow(List(SimpleType.Int32), SimpleType.Int32)
    val binary = SimpleType.Arrow(List(SimpleType.Int32, SimpleType.Int32), SimpleType.Int32)

    assert(Symbol.generatedDefnSym(owner, "local-def", 0, unary, SourceLocation.Unknown) !=
      Symbol.generatedDefnSym(owner, "local-def", 0, binary, SourceLocation.Unknown))
  }
}
