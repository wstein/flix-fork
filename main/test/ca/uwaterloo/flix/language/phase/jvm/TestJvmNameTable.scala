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

  test("a specialization uses the spelling recorded beside its key") {
    val sym = symbol(1)
    val entry = List(sym -> key("map", "Int32", "String"))
    val spelling = "I5Int326StringE"
    val stable = JvmNameTable.build(entry, JvmNameTable.DefaultWidth, Map.empty,
      Map(sym -> spelling), JvmNameTable.Mode.Stable)
    val counter = JvmNameTable.build(entry, JvmNameTable.DefaultWidth, Map.empty,
      Map(sym -> spelling), JvmNameTable.Mode.Counter)
    assert(stable.suffix(sym) == spelling)
    assert(counter.suffix(sym) == "1")
  }

  test("duplicate displayed specializations use stable provenance hashes") {
    val first = symbol(1)
    val second = symbol(2)
    val entries = List(first -> key("first"), second -> key("second"))
    val hashed = JvmNameTable.build(entries, JvmNameTable.DefaultWidth)
    val spelled = JvmNameTable.build(entries, JvmNameTable.DefaultWidth, Map.empty,
      Map(first -> "I5Int32E", second -> "I5Int32E"), JvmNameTable.Mode.Stable)
    assert(spelled.suffix(first) == hashed.suffix(first))
    assert(spelled.suffix(second) == hashed.suffix(second))
    assert(spelled.suffix(first) != spelled.suffix(second))
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

  test("the suffix has the width it is given") {
    val entries = (1 to 50).map(id => symbol(id) -> key(id.toString))
    for (width <- List(4, 12, 20, JvmNameTable.MaxWidth)) {
      val table = JvmNameTable.build(entries, width)
      entries.foreach { case (sym, _) => assert(table.suffix(sym).matches(s"[0-9a-z]{$width}")) }
    }
  }

  test("a narrower width keeps the same names' trailing digits apart only as far as it can") {
    // Widening never changes which keys collide at a width that has none: names at width 8 are
    // the low-order digits of names at width 12, since both reduce the same digest.
    val entries = (1 to 50).map(id => symbol(id) -> key(id.toString))
    val narrow = JvmNameTable.build(entries, 8)
    val wide = JvmNameTable.build(entries, 12)
    entries.foreach { case (sym, _) => assert(wide.suffix(sym).endsWith(narrow.suffix(sym))) }
  }

  test("counter mode names a symbol by its own counter, as upstream does") {
    val defn = symbol(42)
    val anon = new Symbol.AnonClassSym(7, SourceLocation.Unknown)
    val table = JvmNameTable.build(List(defn -> key("a"), anon -> GeneratedJvmKey("anonymous-class", List("b"))),
      JvmNameTable.DefaultWidth, Map.empty, JvmNameTable.Mode.Counter)
    assert(table.suffix(defn) == "42")
    assert(table.suffix(anon) == "7")
  }

  test("a collision names the flag, and says whether the width is below the supported one") {
    val entries = List(symbol(1) -> key("first"), symbol(2) -> key("second"))
    val narrow = intercept[InternalCompilerException] {
      JvmNameTable.buildWithDigest(entries, 2, _ => BigInt(0))
    }
    assert(narrow.getMessage.contains("--Xsymbol-hash-length"))
    assert(narrow.getMessage.contains("below the default"))
    val default = intercept[InternalCompilerException] {
      JvmNameTable.buildWithDigest(entries, JvmNameTable.DefaultWidth, _ => BigInt(0))
    }
    assert(default.getMessage.contains("--Xsymbol-hash-length"))
    assert(!default.getMessage.contains("below the default"))
  }

  test("a width outside the supported range is refused") {
    intercept[InternalCompilerException] { JvmNameTable.build(Nil, -1) }
    intercept[InternalCompilerException] { JvmNameTable.build(Nil, 0) }
    intercept[InternalCompilerException] { JvmNameTable.build(Nil, JvmNameTable.MaxWidth + 1) }
  }

  test("generated specializations use readable names or counters end to end") {
    import ca.uwaterloo.flix.api.Flix
    import ca.uwaterloo.flix.util.{Options, Result}
    val program =
      """@DontInline
        |def twice(f: a -> a, x: a): a = f(f(x))
        |def main(): Unit \ IO = { println(twice(x -> x + 1, 1)); println(twice(s -> s + "!", "a")) }
        |""".stripMargin
    def suffixesOf(width: Int, mode: JvmNameTable.Mode = JvmNameTable.Mode.Stable): Set[String] = {
      val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xsymbolHashLength = width, xsymbolNames = mode))
      flix.addSource(ca.uwaterloo.flix.api.CompilerConstants.VirtualTestFile, text = program,
        sctx = ca.uwaterloo.flix.language.ast.shared.SecurityContext.Unrestricted)
      val result = flix.compile() match {
        case Result.Ok(r) => r
        case Result.Err(errors) => fail(errors.map(_.summary).mkString(", "))
      }
      val names = result.getClasses.keys.map(_.displayName()).toSet
      val suffixes = names.collect { case name if name.startsWith("Def$twice$") => name.stripPrefix("Def$twice$") }
      assert(suffixes.nonEmpty, s"no specialization of twice among ${names.filter(_.contains("twice"))}")
      suffixes
    }
    val wide = suffixesOf(20)
    assert(wide.nonEmpty && wide.forall(_.startsWith("I")), wide)
    assert(suffixesOf(8) == wide)
    val counters = suffixesOf(20, JvmNameTable.Mode.Counter)
    assert(counters.nonEmpty && counters.forall(_.matches("[0-9]+")), counters)
  }
}
