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

  private def characterization(origins: JvmLexicalOrigins): List[String] = {
    def entry(prefix: String, exp: TypedAst.Expr, key: GeneratedJvmKey): String =
      s"$prefix:${exp.getClass.getSimpleName}:${key.family}:${key.fields.mkString(":")}"
    List(s"fingerprintEvaluations:${origins.fingerprintEvaluations}") :::
      origins.entries.map { case (exp, key) => entry("entry", exp, key) } :::
      origins.allEntries.map { case (exp, key) => entry("all", exp, key) }
  }

  test("golden nested lambda origins and traversal order") {
    val origins = capture("def example(captured: Int32): Int32 -> (Int32 -> (Int32, Int32, Int32)) = outer -> inner -> (captured, outer, inner)")
    val actual = characterization(origins)
    assert(actual == List(
      "fingerprintEvaluations:15",
      "entry:Lambda:lexical-lambda:Z4EEGU/32ru4uYTUrOGvmYe86GrNGTY/2SCQj3fsCuQ=",
      "entry:Lambda:lexical-lambda:WBCtsc79/26rlV4dexWvS2JVCZjAotIiAIb8v/rX7VI=",
      "all:Lambda:lexical-lambda:Z4EEGU/32ru4uYTUrOGvmYe86GrNGTY/2SCQj3fsCuQ=",
      "all:Lambda:lexical-lambda:WBCtsc79/26rlV4dexWvS2JVCZjAotIiAIb8v/rX7VI=",
      "all:Tuple:lexical-expression:bAi4yp4Mrhtmm+UAA5BeBubCHRKK5xlm85Z/rTo/+CA=",
      "all:Var:lexical-expression:JllLOBht1hcj1zVTXd4fKsfeuVlT0I0sgm8g1ye0d/E=",
      "all:Var:lexical-expression:XzHa9bPR41QNVwWVaTkOVjNGurbk8domxd8z3GR2+EI=",
      "all:Var:lexical-expression:WDSaFNMFVMlTvRPjAmxSnVidp/K68Y6nBPYQab7KtQU="
    ))
  }

  test("golden recursive local definition origins and traversal order") {
    val source = """def example(): Int32 -> (Int32 -> Int32) = captured -> {
                   |    def inner(value) = if (true) captured else inner(value);
                   |    argument -> inner(argument)
                   |}
                   |""".stripMargin
    val actual = characterization(capture(source))
    assert(actual == List(
      "fingerprintEvaluations:25",
      "entry:Lambda:lexical-lambda:5i0cNh/2lBa/oH71TNUVdGX6vdqdgP/xSxlPEx2GrrE=",
      "entry:LocalDef:lexical-local-def:yN4uH45t26nECfJBgK7dt0M+AqTxCe8LRlns10EF5IM=",
      "entry:Lambda:lexical-lambda:k+pXZdQrW3glrJKt1rXvok0HAHx0AHdsrbF1sk4YLEM=",
      "all:Lambda:lexical-lambda:5i0cNh/2lBa/oH71TNUVdGX6vdqdgP/xSxlPEx2GrrE=",
      "all:LocalDef:lexical-local-def:yN4uH45t26nECfJBgK7dt0M+AqTxCe8LRlns10EF5IM=",
      "all:IfThenElse:lexical-expression:+BbM/+2rG6rO5+4gh++fIQCU1gX2narZNqrxmhNYa24=",
      "all:Cst:lexical-expression:EzG41EUWWjkOBJSyZjhscAyf0c57pXoGo47tdrm+hLg=",
      "all:Var:lexical-expression:m8OHLO45oFhbSBaVqv1uYBr9m5tFsH6yIMlly4juZL0=",
      "all:ApplyLocalDef:lexical-expression:kps8IrQCvd4ib4CLPRdDz7EnLy3c06Eou0gxt6T8Wgc=",
      "all:Var:lexical-expression:66nHKnPDPWAtoCPnG1Igyt4iO6nb6gdzZWRUNHMEyho=",
      "all:Lambda:lexical-lambda:k+pXZdQrW3glrJKt1rXvok0HAHx0AHdsrbF1sk4YLEM=",
      "all:ApplyLocalDef:lexical-expression:9ZJjV7nedQEcayyOkgwD0PHDc92x2eTf/MbN3s7k1gk=",
      "all:Var:lexical-expression:Nh33Saa2kqal9vvs3xvdv9Ma+3LNaaSgYhPvAZXwFI0="
    ))
  }

  test("golden anonymous constructor and method origins and traversal order") {
    val source = """import java.lang.Thread
                   |eff IO
                   |def example(label: String, number: Int32): Thread \ IO = new Thread {
                   |    def new(): Thread \ IO = super(label)
                   |    def toString(_this: Thread): String = {
                   |        let identity = (value: String) -> value;
                   |        identity(label)
                   |    }
                   |    def hashCode(_this: Thread): Int32 = number
                   |}
                   |""".stripMargin
    val root = checked(source)
    val decl = root.defs.values.find(_.sym.name == "example").get
    val original = decl.exp.asInstanceOf[TypedAst.Expr.NewObject]
    assert(original.constructors.nonEmpty)
    assert(original.methods.size == 2)
    val origins = JvmSourceOrigins.capture(root).body(decl.sym)
    val reversedBody = original.copy(methods = original.methods.reverse)
    val reversedRoot = root.copy(defs = root.defs.updated(decl.sym, decl.copy(exp = reversedBody)))
    val reversed = JvmSourceOrigins.capture(reversedRoot).body(decl.sym)
    assert(origins.originOf(original) == reversed.originOf(reversedBody))
    assert(origins.allEntries.map(_._2).toSet == reversed.allEntries.map(_._2).toSet)
    val actual = characterization(origins) ::: List("reversed-methods") ::: characterization(reversed)
    assert(actual == List(
      "fingerprintEvaluations:17",
      "entry:NewObject:lexical-anonymous-class:x+ZX+NgdG1W+uOVZ4BU5aQsJYVwZcgRbUiTH3ObosqY=",
      "entry:Lambda:lexical-lambda:OndSY2m9/a9DlSI7jUGuYpCnRPLQkMArqjT2/qZGIOM=",
      "all:NewObject:lexical-anonymous-class:x+ZX+NgdG1W+uOVZ4BU5aQsJYVwZcgRbUiTH3ObosqY=",
      "all:InvokeSuperConstructor:lexical-expression:PhMnV7WHjYgxq4svW59GiDyW8RlNV8L/g2Vn44lBj4c=",
      "all:Var:lexical-expression:SGgUMVbXz2OWuDHVr49UfaWWfOaRvRNW8d4NvSxbJFc=",
      "all:Let:lexical-expression:HXQDptCOKtr6UmC41Gfoyscq3HP71RxG4a0V4NKf+Ss=",
      "all:Lambda:lexical-lambda:OndSY2m9/a9DlSI7jUGuYpCnRPLQkMArqjT2/qZGIOM=",
      "all:Var:lexical-expression:txhuRCKtNcQVEGYakX7Ah45gQRMzD7MD+831anyb1O4=",
      "all:ApplyClo:lexical-expression:fPMaicP/82QPeubDqqy1HHGIkcGmiTwcAZzEu6TbUWQ=",
      "all:Var:lexical-expression:xePXZRpVuSTrGTNKe7kHCSQXdtf7yWjldecHI/wtnUw=",
      "all:Var:lexical-expression:DABQ2k3qiIH2aA6dgsH5XywS1ehRHjp18uVfyY1qxkU=",
      "all:Var:lexical-expression:m/dKpZc5xtL2Qivt/SV01Cm6zzA6MpeSRdR6DpJJ23Y=",
      "reversed-methods",
      "fingerprintEvaluations:17",
      "entry:NewObject:lexical-anonymous-class:x+ZX+NgdG1W+uOVZ4BU5aQsJYVwZcgRbUiTH3ObosqY=",
      "entry:Lambda:lexical-lambda:OndSY2m9/a9DlSI7jUGuYpCnRPLQkMArqjT2/qZGIOM=",
      "all:NewObject:lexical-anonymous-class:x+ZX+NgdG1W+uOVZ4BU5aQsJYVwZcgRbUiTH3ObosqY=",
      "all:InvokeSuperConstructor:lexical-expression:PhMnV7WHjYgxq4svW59GiDyW8RlNV8L/g2Vn44lBj4c=",
      "all:Var:lexical-expression:SGgUMVbXz2OWuDHVr49UfaWWfOaRvRNW8d4NvSxbJFc=",
      "all:Var:lexical-expression:m/dKpZc5xtL2Qivt/SV01Cm6zzA6MpeSRdR6DpJJ23Y=",
      "all:Let:lexical-expression:HXQDptCOKtr6UmC41Gfoyscq3HP71RxG4a0V4NKf+Ss=",
      "all:Lambda:lexical-lambda:OndSY2m9/a9DlSI7jUGuYpCnRPLQkMArqjT2/qZGIOM=",
      "all:Var:lexical-expression:txhuRCKtNcQVEGYakX7Ah45gQRMzD7MD+831anyb1O4=",
      "all:ApplyClo:lexical-expression:fPMaicP/82QPeubDqqy1HHGIkcGmiTwcAZzEu6TbUWQ=",
      "all:Var:lexical-expression:xePXZRpVuSTrGTNKe7kHCSQXdtf7yWjldecHI/wtnUw=",
      "all:Var:lexical-expression:DABQ2k3qiIH2aA6dgsH5XywS1ehRHjp18uVfyY1qxkU="
    ))
  }

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
