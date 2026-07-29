/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SimpleType, Symbol}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmName extends AnyFunSuite {

  test("ordinary generated class names retain their established form") {
    assertResult("Option$None")(JvmName.mkClassName("Option", "None"))
  }

  test("specialized enum cases replace the former fresh identifier with a stable hash") {
    val option = Symbol.mkEnumSym("Option")
    val specialized = Symbol.specializedEnumSym(option, List(SimpleType.Int32))
    val name = JvmName.mkClassName(specialized.name, "None")
    assert(name.matches("Option\\$[1-9A-HJ-NP-Za-km-z]{13}\\$None"))
    assertResult(specialized.name)(Symbol.specializedEnumSym(option, List(SimpleType.Int32)).name)
  }
}
