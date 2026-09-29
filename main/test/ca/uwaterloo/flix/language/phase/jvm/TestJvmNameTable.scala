package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}
import ca.uwaterloo.flix.util.InternalCompilerException
import org.scalatest.funsuite.AnyFunSuite

class TestJvmNameTable extends AnyFunSuite {

  private def symbol(id: Int): Symbol.DefnSym =
    new Symbol.DefnSym(Some(id), List("Example"), "map", SourceLocation.Unknown)

  private def key(parts: String*): GeneratedJvmKey =
    GeneratedJvmKey("specialization", parts.toList)

  test("names do not depend on internal counters") {
    val first = symbol(1)
    val second = symbol(999)
    val provenance = key("Example.map", "Int32 -> Int32")
    val left = JvmNameTable.build(List(first -> provenance), JvmNameTable.DefaultWidth)
    val right = JvmNameTable.build(List(second -> provenance), JvmNameTable.DefaultWidth)
    assert(left.suffix(first) == right.suffix(second))
    assert(first.id.contains(1))
    assert(second.id.contains(999))
  }

  test("key fields are framed without delimiter ambiguity") {
    val first = symbol(1)
    val second = symbol(2)
    val table = JvmNameTable.build(List(first -> key("a|b", "c"), second -> key("a", "b|c")), JvmNameTable.DefaultWidth)
    assert(table.suffix(first) != table.suffix(second))
  }

  test("key families are distinct") {
    val first = symbol(1)
    val second = symbol(2)
    val table = JvmNameTable.build(List(
      first -> GeneratedJvmKey("lambda", List("owner", "role")),
      second -> GeneratedJvmKey("anonymous-class", List("owner", "role"))
    ), JvmNameTable.DefaultWidth)
    assert(table.suffix(first) != table.suffix(second))
  }

  test("registration order does not affect names") {
    val entries = (1 to 20).map(id => symbol(id) -> key("owner", id.toString)).toList
    val forward = JvmNameTable.build(entries, JvmNameTable.DefaultWidth)
    val backward = JvmNameTable.build(entries.reverse, JvmNameTable.DefaultWidth)
    entries.foreach { case (sym, _) => assert(forward.suffix(sym) == backward.suffix(sym)) }
  }

  test("suffixes are exactly twelve lowercase base-36 characters") {
    val entries = (1 to 100).map(id => symbol(id) -> key(id.toString))
    val table = JvmNameTable.build(entries, JvmNameTable.DefaultWidth)
    entries.foreach { case (sym, _) => assert(table.suffix(sym).matches("[0-9a-z]{12}")) }
  }

  test("missing provenance fails without exposing a counter") {
    intercept[InternalCompilerException] {
      JvmNameTable.build(Nil, JvmNameTable.DefaultWidth).suffix(symbol(12))
    }
  }

  test("conflicting provenance for one symbol fails") {
    val sym = symbol(1)
    intercept[InternalCompilerException] {
      JvmNameTable.build(List(sym -> key("first"), sym -> key("second")), JvmNameTable.DefaultWidth)
    }
  }

  test("duplicate identical registrations are idempotent") {
    val sym = symbol(1)
    val entry = sym -> key("same")
    assert(JvmNameTable.build(List(entry, entry), JvmNameTable.DefaultWidth).suffix(sym) == JvmNameTable.build(List(entry), JvmNameTable.DefaultWidth).suffix(sym))
  }

  test("different keys with the same digest fail closed") {
    intercept[InternalCompilerException] {
      JvmNameTable.buildWithDigest(List(symbol(1) -> key("first"), symbol(2) -> key("second")), JvmNameTable.DefaultWidth, _ => BigInt(0))
    }
  }

  test("different symbols cannot silently share one provenance key") {
    intercept[InternalCompilerException] {
      JvmNameTable.build(List(symbol(1) -> key("same"), symbol(2) -> key("same")), JvmNameTable.DefaultWidth)
    }
  }

  test("field count and empty fields are significant") {
    val first = symbol(1)
    val second = symbol(2)
    val table = JvmNameTable.build(List(first -> key("owner"), second -> key("owner", "")), JvmNameTable.DefaultWidth)
    assert(table.suffix(first) != table.suffix(second))
  }

  test("leading zeros are retained") {
    val sym = symbol(1)
    assert(JvmNameTable.buildWithDigest(List(sym -> key("first")), JvmNameTable.DefaultWidth, _ => BigInt(1)).suffix(sym) == "000000000001")
  }

  test("version one encoding and suffix match the Unicode golden vector") {
    val origin = key("Example.map", "λ -> Int32")
    val encoded = java.util.HexFormat.of().formatHex(origin.bytes)
    assert(encoded == "00000001000000040000000d666c69782d6a766d2d6e616d650000000e7370656369616c697a6174696f6e0000000b4578616d706c652e6d61700000000bcebb202d3e20496e743332")
    val sym = symbol(1)
    assert(JvmNameTable.build(List(sym -> origin), JvmNameTable.DefaultWidth).suffix(sym) == "6ka8gyun2xzr")
  }

  test("modulo collisions are rejected across families") {
    val first = GeneratedJvmKey("lambda", List("owner"))
    val second = GeneratedJvmKey("anonymous-class", List("owner"))
    intercept[InternalCompilerException] {
      JvmNameTable.buildWithDigest(List(symbol(1) -> first, symbol(2) -> second), JvmNameTable.DefaultWidth,
        origin => if (origin == first) BigInt(1) else BigInt(36).pow(12) + 1)
    }
  }

  test("unpaired surrogates fail instead of being replaced") {
    val malformed = new String(Array(0xd800.toChar))
    intercept[InternalCompilerException] { JvmNameTable.build(List(symbol(1) -> key(malformed)), JvmNameTable.DefaultWidth) }
  }

}
