package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.CompilerConstants
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.util.{InternalCompilerException, Options}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmLexicalOrigins extends AnyFunSuite with TestUtils {
  private val owner = GeneratedJvmKey("def", List("example"))

  private def declaration(source: String): TypedAst.Def = {
    val flix = new Flix().setOptions(Options.TestWithLibNix).addVirtualPath(CompilerConstants.VirtualTestFile, source)
    val (root, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    root.get.defs.values.find(_.sym.name == "example").get
  }

  private def capture(source: String): JvmLexicalOrigins = {
    val decl = declaration(source)
    JvmLexicalOrigins.capture(decl.exp, owner, decl.spec.fparams.toList)
  }

  private def keys(source: String): List[GeneratedJvmKey] = capture(source).entries.map(_._2)

  test("alpha renaming and comments preserve lambda origins") {
    assert(keys("def example(a: Int32): Int32 -> (Int32, Int32) = x -> (x, a)") ==
      keys("// shift locations\ndef example(b: Int32): Int32 -> (Int32, Int32) = renamed -> (renamed, b)"))
  }

  test("distinguishable sibling insertion and reordering preserve existing groups") {
    val before = keys("def example(): (Int32 -> (Int32, Int32), Int32 -> (Int32, Int32)) = (x -> (x, 1), x -> (x, 2))")
    val after = keys("def example(): (Int32 -> (Int32, Int32), Int32 -> (Int32, Int32), Int32 -> (Int32, Int32)) = (x -> (x, 3), x -> (x, 2), x -> (x, 1))")
    assert(before.toSet.subsetOf(after.toSet))
  }

  test("only identical sibling groups gain ordinals") {
    val before = keys("def example(): (Int32 -> (Int32, Int32), Int32 -> (Int32, Int32)) = (x -> (x, 1), x -> (x, 2))")
    val after = keys("def example(): (Int32 -> (Int32, Int32), Int32 -> (Int32, Int32), Int32 -> (Int32, Int32)) = (x -> (x, 1), y -> (y, 1), x -> (x, 2))")
    assert(after.distinct.size == 3)
    assert(before.head == after.head)
    assert(before.last == after.last)
  }

  test("captured external parameters retain their binding identity") {
    assert(keys("def example(a: Int32, b: Int32): Int32 -> (Int32, Int32, Int32) = x -> (x, a, b)") !=
      keys("def example(a: Int32, b: Int32): Int32 -> (Int32, Int32, Int32) = x -> (x, b, a)"))
  }

  test("renaming a local binder preserves origins") {
    assert(keys("def example(): Int32 -> (Int32, Int32) = { let a = 1; x -> (x, a) }") ==
      keys("def example(): Int32 -> (Int32, Int32) = { let renamed = 1; x -> (x, renamed) }"))
  }

  test("lookups use object identity and missing lookups fail closed") {
    val decl = declaration("def example(): Int32 -> Int32 = x -> x")
    val origins = JvmLexicalOrigins.capture(decl.exp, owner, decl.spec.fparams.toList)
    val lambda = decl.exp.asInstanceOf[TypedAst.Expr.Lambda]
    assert(origins.get(lambda).nonEmpty)
    assert(origins.get(lambda.copy()).isEmpty)
    intercept[InternalCompilerException] { origins.originOf(lambda.copy()) }
  }

  test("nested same-shaped match binders distinguish external captures") {
    val prefix = "def example(a: Int32, b: Int32): Int32 -> (Int32, Int32, Int32) = match a { case outer => match b { case inner => "
    assert(keys(prefix + "x -> (x, outer, inner) } }") != keys(prefix + "x -> (x, inner, outer) } }"))
    assert(keys(prefix + "x -> (x, outer, inner) } }") ==
      keys("def example(a: Int32, b: Int32): Int32 -> (Int32, Int32, Int32) = match a { case renamed => match b { case other => x -> (x, renamed, other) } }"))
  }

  test("local region origins survive alpha renaming") {
    assert(keys("def example(): Unit = region rc { let f = (value: Region[rc]) -> value; let _ = f(rc); () }") ==
      keys("def example(): Unit = region renamed { let f = (value: Region[renamed]) -> value; let _ = f(renamed); () }"))
  }

  test("local definitions survive binder renaming") {
    assert(keys("def example(a: Int32): Int32 = { def inner(x) = x; inner(a) }") ==
      keys("def example(b: Int32): Int32 = { def renamed(y) = y; renamed(b) }"))
  }

  test("named holes retain source names including generated-looking names") {
    val first = "def example(): Int32 -> (Int32, Int32) = x -> (x, ?h123)"
    assert(keys(first) == keys("// move source\n" + first))
    assert(keys(first) != keys(first.replace("?h123", "?h456")))
    assert(keys(first) != keys(first.replace("?h123", "???")))
  }

  test("unnamed holes ignore allocation identities and comment insertion") {
    val source = "def example(): Int32 -> (Int32, Int32) = x -> (x, ???)"
    assert(keys(source) == keys("def earlier(): Int32 = ???\n// shift source\n" + source))
  }

  test("expression holes encode and visit the enclosed expression") {
    val first = capture("def example(): Int32 -> Int32 = x -> x?")
    val renamed = capture("def example(): Int32 -> Int32 = renamed -> renamed?")
    assert(first.entries.map(_._2) == renamed.entries.map(_._2))
    assert(first.allEntries.exists(_._1.isInstanceOf[TypedAst.Expr.HoleWithExp]))
    assert(first.allEntries.exists(_._1.isInstanceOf[TypedAst.Expr.Var]))
    assert(first.entries.map(_._2) != keys("def example(): Int32 -> Int32 = x -> x"))
  }
}
