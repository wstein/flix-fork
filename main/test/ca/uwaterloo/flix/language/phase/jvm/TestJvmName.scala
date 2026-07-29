/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import org.scalatest.funsuite.AnyFunSuite

class TestJvmName extends AnyFunSuite {

  test("generated class names contain a fixed-width, identifier-safe hash") {
    val name = JvmName.mkClassName("Option", "None")
    assert(name.matches("Option\\$h[1-9A-HJ-NP-Za-km-z]{13}\\$None"))
  }

  test("generated class names are stable and distinguish canonical inputs") {
    assertResult(JvmName.mkClassName("Option", "None"))(JvmName.mkClassName("Option", "None"))
    assert(JvmName.mkClassName("Option", "None") != JvmName.mkClassName("Option", "Some"))
  }
}
