/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.ast

import org.scalatest.funsuite.AnyFunSuite

class TestGeneratedSymbols extends AnyFunSuite {

  test("generated definitions use stable Base58 names") {
    val owner = Symbol.mkDefnSym("Example.test1")
    val tpe = SimpleType.Arrow(List(SimpleType.Int32), SimpleType.Int32)
    val generated = Symbol.generatedDefnSym(owner, "closure", tpe, SourceLocation.Unknown)

    assert(generated.name.matches("test1\\$[1-9A-HJ-NP-Za-km-z]{11}"))
    assert(generated == Symbol.generatedDefnSym(owner, "closure", tpe, SourceLocation.Unknown))
    assert(generated != Symbol.generatedDefnSym(owner, "local-def", tpe, SourceLocation.Unknown))
  }
}
