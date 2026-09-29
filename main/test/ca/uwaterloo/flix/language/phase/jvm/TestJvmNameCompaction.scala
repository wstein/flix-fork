package ca.uwaterloo.flix.language.phase.jvm

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets

class TestJvmNameCompaction extends AnyFunSuite {

  test("a short class name is unchanged") {
    assert(JvmNameCompaction.compact("Def$map$I5Int32E", 12) == "Def$map$I5Int32E")
  }

  test("an overlong class name keeps its ends and a fixed-width hash") {
    val name = "Def$start$" + "nested" * 50 + "$end"
    val compacted = JvmNameCompaction.compact(name, 12)
    assert(compacted.startsWith("Def$start$"))
    assert(compacted.endsWith("$end"))
    assert(compacted.matches(".*\\$\\$\\$\\$[0-9a-z]{12}\\$\\$\\$\\$.*"))
    assert((compacted + ".class").getBytes(StandardCharsets.UTF_8).length <= 240)
    assert(compacted == JvmNameCompaction.compact(name, 12))
  }

  test("the limit is UTF-8 bytes, not UTF-16 characters") {
    val name = "Anon$" + "é" * 120
    assert((name + ".class").length < 240)
    val compacted = JvmNameCompaction.compact(name, 12)
    assert(compacted != name)
    assert((compacted + ".class").getBytes(StandardCharsets.UTF_8).length <= 240)
  }
}
