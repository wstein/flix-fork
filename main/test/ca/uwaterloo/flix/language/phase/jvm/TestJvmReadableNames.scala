package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmReadableNames extends AnyFunSuite {

  private def defn(id: Option[Int], text: String): Symbol.DefnSym =
    new Symbol.DefnSym(id, List("Shop"), text, SourceLocation.Unknown)

  private def key(parts: String*): GeneratedJvmKey = GeneratedJvmKey("lexical-lambda", parts.toList)

  test("a symbol with a readable origin is named by its path") {
    val lambda = defn(Some(1), "price")
    val table = JvmNameTable.build(List(lambda -> key("a")), JvmNameTable.DefaultWidth,
      Map(lambda -> JvmReadableOrigin(List("Shop", "price"), List("discount", "0"), None)))
    assert(table.suffix(lambda) == "discount$0")
  }

  test("readable names that would coincide fall back to their hashes") {
    val first = defn(Some(1), "price")
    val second = defn(Some(2), "price")
    val same = JvmReadableOrigin(List("Shop", "price"), List("0"), None)
    val table = JvmNameTable.build(List(first -> key("a"), second -> key("b")), JvmNameTable.DefaultWidth,
      Map(first -> same, second -> same))
    assert(table.suffix(first).matches("[0-9a-z]{12}"))
    assert(table.suffix(second).matches("[0-9a-z]{12}"))
    assert(table.suffix(first) != table.suffix(second))
  }

  test("the same path under different owners names different classes, so both stay readable") {
    val inPrice = defn(Some(1), "price")
    val inTax = defn(Some(2), "tax")
    val table = JvmNameTable.build(List(inPrice -> key("a"), inTax -> key("b")), JvmNameTable.DefaultWidth,
      Map(inPrice -> JvmReadableOrigin(List("Shop", "price"), List("0"), None),
        inTax -> JvmReadableOrigin(List("Shop", "tax"), List("0"), None)))
    assert(table.suffix(inPrice) == "0")
    assert(table.suffix(inTax) == "0")
  }

  test("a lambda of a specialized owner is named after the owner's own suffix") {
    val owner = defn(Some(10), "map")
    val lambda = defn(Some(11), "map")
    val table = JvmNameTable.build(
      List(owner -> GeneratedJvmKey("specialization", List("map", "Int32")), lambda -> key("a")),
      JvmNameTable.DefaultWidth,
      Map(lambda -> JvmReadableOrigin(List("Shop", "map"), List("0"), Some(owner))))
    assert(table.suffix(lambda) == table.suffix(owner) + "$0")
  }

  test("a lambda of a mangled owner inherits its readable type arguments") {
    val owner = defn(Some(10), "map")
    val lambda = defn(Some(11), "map")
    val table = JvmNameTable.build(
      List(owner -> GeneratedJvmKey("specialization", List("map", "Int32")), lambda -> key("a")),
      JvmNameTable.DefaultWidth,
      Map(lambda -> JvmReadableOrigin(List("Shop", "map"), List("0"), Some(owner))),
      Map(owner -> "I5Int32E"), JvmNameTable.Mode.Stable)
    assert(table.suffix(lambda) == "I5Int32E$0")
  }

  test("an anonymous class is named with its owner, since its class name has none") {
    val anon = new Symbol.AnonClassSym(3, SourceLocation.Unknown)
    val table = JvmNameTable.build(List(anon -> GeneratedJvmKey("lexical-anonymous-class", List("a"))), JvmNameTable.DefaultWidth,
      Map(anon -> JvmReadableOrigin(List("Shop", "price"), List("0"), None)))
    assert(table.suffix(anon) == "Shop$price$0")
  }

  test("counter mode keeps counter names and ignores readable origins") {
    val lambda = defn(Some(5), "price")
    val table = JvmNameTable.build(List(lambda -> key("a")), JvmNameTable.DefaultWidth,
      Map(lambda -> JvmReadableOrigin(List("Shop", "price"), List("0"), None)), JvmNameTable.Mode.Counter)
    assert(table.suffix(lambda) == "5")
  }

  test("closures and anonymous classes in a compiled program get readable names") {
    val program =
      """mod Shop {
        |    import java.util.function.IntSupplier
        |    @DontInline
        |    pub def adjusters(n: Int32): List[Int32 -> Int32] = {
        |        def helper(x: Int32): Int32 = if (x > 100) helper(x - n) else x + n;
        |        let discount = x -> x - n;
        |        let surcharge = x -> helper(x);
        |        discount :: surcharge :: (x -> x * n) :: Nil
        |    }
        |    @DontInline
        |    pub def task(): IntSupplier \ IO = new IntSupplier { def getAsInt(_this: IntSupplier): Int32 = 42 }
        |}
        |def main(): Unit \ IO = {
        |    println(List.map(f -> f(10), Shop.adjusters(2)));
        |    println(Shop.task().getAsInt())
        |}
        |""".stripMargin
    val flix = new Flix().setOptions(Options.TestWithLibAll)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)(SecurityContext.Unrestricted)
    val result = flix.compile() match {
      case Result.Ok(r) => r
      case Result.Err(errors) => fail(errors.map(_.summary).mkString(", "))
    }
    val names = result.getClasses.keys.map(_.displayName()).toSet
    val closures = names.filter(_.startsWith("Clo$adjusters$"))
    assert(closures.contains("Clo$adjusters$discount$0"), closures)
    assert(closures.contains("Clo$adjusters$surcharge$0"), closures)
    assert(closures.contains("Clo$adjusters$0"), closures)
    assert(names.contains("Anon$Shop$task$0"), names.filter(_.startsWith("Anon$")))
    assert(names.contains("Def$adjusters$helper"), names.filter(_.contains("adjusters")))
  }
}
