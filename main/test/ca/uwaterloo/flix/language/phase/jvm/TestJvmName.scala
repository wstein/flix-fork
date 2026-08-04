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
    assert(name.matches("Option\\$[1-9A-HJ-NP-Za-km-z]{11}\\$None"))
    assertResult(specialized.name)(Symbol.specializedEnumSym(option, List(SimpleType.Int32)).name)
  }

  test("generated classes sit beside the namespace class, never beneath it") {
    // The namespace class of `mod Acme.Api` is `Acme.Api`, so putting its defs in a package of
    // that name would make one name denote both a class and a package.
    assertResult(JvmName(List("Acme"), "Api$Def$get"))(
      JvmName.mkNamespacedClassName(List("Acme", "Api"), "Def", "get"))
    assertResult(JvmName(List("A", "B"), "C$Clo$run"))(
      JvmName.mkNamespacedClassName(List("A", "B", "C"), "Clo", "run"))
  }

  test("namespaces with no parent package are generated under dev.flix.gen") {
    // `mod List` has nowhere to sit beside, and the unnamed package is not an option: Java cannot
    // import from it, and two Flix libraries on one classpath would be free to collide there.
    assertResult(JvmName(JvmName.DevFlixGen, "List$Def$map"))(
      JvmName.mkNamespacedClassName(List("List"), "Def", "map"))
    assertResult(JvmName(JvmName.DevFlixGen, "Def$main"))(
      JvmName.mkNamespacedClassName(Nil, "Def", "main"))
  }
}
