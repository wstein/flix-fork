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
  }

  test("only the first namespace segment becomes a package") {
    // Using the parent package instead moves the clash down one level rather than removing it:
    // the defs of `mod A.B.C` would land in the package `A.B`, which is the facade class `A.B`.
    assertResult(JvmName(List("A"), "B$C$Clo$run"))(
      JvmName.mkNamespacedClassName(List("A", "B", "C"), "Clo", "run"))
    assertResult(JvmName(List("A"), "B$C$D$Def$f"))(
      JvmName.mkNamespacedClassName(List("A", "B", "C", "D"), "Def", "f"))
  }

  test("a facade nests the same way its generated classes do") {
    // The two-level name is what Java callers write, so it may not move; anything deeper is a
    // sibling of it rather than a member of a package named after it.
    assertResult(JvmName(List("Acme"), "Api"))(JvmName.facadeOfNamespace(List("Acme", "Api")))
    assertResult(JvmName(List("Acme"), "Api$Deep"))(
      JvmName.facadeOfNamespace(List("Acme", "Api", "Deep")))
    assertResult(JvmName(List("Acme"), "Api$Deep$Deeper"))(
      JvmName.facadeOfNamespace(List("Acme", "Api", "Deep", "Deeper")))
  }

  test("a facade and its own generated classes never disagree about the package") {
    // They are emitted by different code paths, so a change to one that misses the other
    // reintroduces the clash silently -- the facade would name the package its defs sit in.
    for (ns <- List(List("A"), List("A", "B"), List("A", "B", "C"), List("A", "B", "C", "D"))) {
      val facade = JvmName.facadeOfNamespace(ns)
      val defn = JvmName.mkNamespacedClassName(ns, "Def", "f")
      assert(facade.pkg != defn.pkg :+ facade.name, s"the facade of $ns names the package of its defs")
      if (ns.lengthIs > 1) assertResult(defn.pkg, s"facade and defs disagree for $ns")(facade.pkg)
    }
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
