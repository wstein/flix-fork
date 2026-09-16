package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.CompilerConstants
import ca.uwaterloo.flix.language.ast.{SemanticOp, TypedAst}
import ca.uwaterloo.flix.util.{InternalCompilerException, Options}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmLexicalOrigins extends AnyFunSuite with TestUtils {
  test("capture requires explicit declaration-backed origin callbacks") {
    assertDoesNotCompile("""JvmLexicalOrigins.capture(null, GeneratedJvmKey("definition", Nil), Nil)""")
  }

  private def checked(source: String): TypedAst.Root = {
    val flix = new Flix().setOptions(Options.TestWithLibNix).addVirtualPath(CompilerConstants.VirtualTestFile, source)
    val (root, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    root.get
  }

  private def capture(source: String): JvmLexicalOrigins = {
    val root = checked(source)
    val decl = root.defs.values.find(_.sym.name == "example").get
    JvmSourceOrigins.capture(root).body(decl.sym)
  }

  private def keys(source: String): List[GeneratedJvmKey] = capture(source).entries.map(_._2)

  test("sequential let fingerprint evaluations grow linearly") {
    List(16, 32, 64).foreach { size =>
      val bindings = (1 to size).map { index =>
        val previous = if (index == 1) "seed" else s"value${index - 1}"
        s"let value$index = $previous;"
      }.mkString(" ")
      val origins = capture(s"def example(seed: Int32): Int32 = { $bindings value$size }")
      assert(origins.fingerprintEvaluations > 0)
      assert(origins.fingerprintEvaluations <= 8L * (size + 1),
        s"$size lets required ${origins.fingerprintEvaluations} fingerprint evaluations")
    }
  }

  test("nonbinding chain fingerprint evaluations grow linearly") {
    List(16, 32, 64).foreach { size =>
      val root = checked("def example(value: Bool): Bool = value")
      val decl = root.defs.values.find(_.sym.name == "example").get
      val body = (1 to size).foldLeft(decl.exp) { (inner, _) =>
        TypedAst.Expr.Unary(SemanticOp.BoolOp.Not, inner, inner.tpe, inner.eff, inner.loc)
      }
      val modified = root.copy(defs = root.defs.updated(decl.sym, decl.copy(exp = body)))
      val origins = JvmSourceOrigins.capture(modified).body(decl.sym)
      assert(origins.fingerprintEvaluations > 0)
      assert(origins.fingerprintEvaluations <= 2L * (size + 1),
        s"$size unary nodes required ${origins.fingerprintEvaluations} fingerprint evaluations")
    }
  }

  test("let expression origins do not depend on the chain tail") {
    val prefix = "def example(seed: Int32): Int32 = { let first = seed; let second = first; "
    val before = capture(prefix + "second }")
    val after = capture(prefix + "if (true) second else 3 }")
    def letKeys(origins: JvmLexicalOrigins): List[GeneratedJvmKey] = origins.allEntries.collect {
      case (_: TypedAst.Expr.Let, key) => key
    }
    assert(letKeys(before).size == 2)
    assert(letKeys(before) == letKeys(after))
  }

  test("nested lambda fingerprints preserve binding context") {
    val original = "def example(): Int32 -> (Int32 -> (Int32, Int32)) = outer -> inner -> (outer, inner)"
    val renamed = "def example(): Int32 -> (Int32 -> (Int32, Int32)) = first -> second -> (first, second)"
    val swapped = "def example(): Int32 -> (Int32 -> (Int32, Int32)) = outer -> inner -> (inner, outer)"
    assert(keys(original) == keys(renamed))
    assert(keys(original) != keys(swapped))
  }

  test("error expressions require an error-free typed AST") {
    val invalid = new Flix().setOptions(Options.TestWithLibNix)
      .addVirtualPath(CompilerConstants.VirtualTestFile, "def broken(): Int32 = true")
    val (_, errors) = invalid.check()
    assert(errors.nonEmpty)
    val root = checked("def example(): Int32 = 1")
    val decl = root.defs.values.find(_.sym.name == "example").get
    val error = TypedAst.Expr.Error(errors.head, decl.exp.tpe, decl.exp.eff)
    val modified = root.copy(defs = root.defs.updated(decl.sym, decl.copy(exp = error)))
    val exception = intercept[InternalCompilerException] { JvmSourceOrigins.capture(modified) }
    assert(exception.getMessage.contains("error-free typed AST"))
  }

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
    val root = checked("def example(): Int32 -> Int32 = x -> x")
    val decl = root.defs.values.find(_.sym.name == "example").get
    val origins = JvmSourceOrigins.capture(root).body(decl.sym)
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
