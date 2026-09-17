package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.{AtomicOp, MonoAst, SourceLocation, Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{Constant, SecurityContext}
import ca.uwaterloo.flix.util.{InternalCompilerException, Options}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmCompilationOrigins extends AnyFunSuite {
  private val loc = SourceLocation.Unknown
  private val owner = GeneratedJvmKey("definition", List("example"))
  private def literal(value: Int): MonoAst.Expr = MonoAst.Expr.Cst(Constant.Int32(value), Type.Int32, loc)

  test("freezing publishes strict names and releases mutable provenance") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val sym = new Symbol.DefnSym(Some(1), Nil, "example", loc)
    val discarded = new Symbol.DefnSym(Some(2), Nil, "discarded", loc)
    val source = literal(0)
    origins.record(source, owner)
    origins.symbols.register(sym, owner)
    origins.symbols.register(discarded, GeneratedJvmKey("discarded", Nil))
    intercept[InternalCompilerException] { origins.nameTable }
    origins.freeze(List(sym))
    assert(origins.nameTable.suffix(sym) == JvmNameTable.build(List(sym -> owner)).suffix(sym))
    intercept[InternalCompilerException] { origins.nameTable.suffix(discarded) }
    intercept[InternalCompilerException] { origins.symbols.origin(sym) }
    intercept[InternalCompilerException] { origins.expression(source) }
    intercept[InternalCompilerException] { origins.record(source, owner) }
    intercept[InternalCompilerException] { origins.freeze(List(sym)) }
    origins.close()
    intercept[InternalCompilerException] { origins.nameTable }
  }

  test("a failed freeze releases provenance without publishing a partial table") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val sym = new Symbol.DefnSym(Some(1), Nil, "missing", loc)
    val source = literal(0)
    origins.record(source, owner)
    intercept[InternalCompilerException] { origins.freeze(List(sym)) }
    intercept[InternalCompilerException] { origins.nameTable }
    intercept[InternalCompilerException] { origins.expression(source) }
    intercept[InternalCompilerException] { origins.record(source, owner) }
    intercept[InternalCompilerException] { origins.freeze(Nil) }
    origins.close()
  }

  test("transfers preserve source identity and give generated children separate roles") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val source = literal(0)
    origins.record(source, owner)
    val first = literal(1)
    val second = literal(1)
    val result = MonoAst.Expr.ApplyAtomic(AtomicOp.Tuple, List(first, second), Type.mkTuple(List(Type.Int32, Type.Int32), loc), Type.Pure, loc)
    origins.transfer(source, result, "test-lowering")
    assert(origins.expression(result) == owner)
    assert(origins.expression(first) != origins.expression(second))
    val repeat = literal(1)
    origins.transfer(first, repeat, "identity")
    assert(origins.expression(first) == origins.expression(repeat))
  }

  test("elimination retains the returned child's existing origin") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val source = literal(0)
    val child = literal(1)
    origins.record(source, owner)
    val key = GeneratedJvmKey("child", Nil)
    origins.record(child, key)
    assert(origins.transfer(source, child, "eliminate") eq child)
    assert(origins.expression(child) == key)
  }

  test("missing origins and conflicting records fail closed") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val source = literal(0)
    intercept[InternalCompilerException] { origins.transfer(source, literal(1), "missing") }
    origins.record(source, owner)
    origins.record(source, owner)
    intercept[InternalCompilerException] { origins.record(source, GeneratedJvmKey("other", Nil)) }
    intercept[InternalCompilerException] { origins.expression(literal(0)) }
  }

  test("clone contexts distinguish call sites without changing source entries") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val source = literal(0)
    origins.record(source, owner)
    val first = origins.cloneTree(source, literal(0), GeneratedJvmKey("call", List("first")), "inline")
    val second = origins.cloneTree(source, literal(0), GeneratedJvmKey("call", List("second")), "inline")
    assert(origins.expression(first) != origins.expression(second))
    assert(origins.expression(source) == owner)
  }

  test("pruning removes old trees and close releases all state") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val source = literal(0)
    val survivor = literal(1)
    origins.record(source, owner)
    origins.transfer(source, survivor, "copy")
    origins.retainExpressions(List(survivor))
    intercept[InternalCompilerException] { origins.expression(source) }
    assert(origins.expression(survivor) == owner)
    val sym = new Symbol.DefnSym(Some(1), Nil, "example", loc)
    origins.symbols.register(sym, owner)
    origins.close()
    intercept[InternalCompilerException] { origins.expression(survivor) }
    intercept[InternalCompilerException] { origins.symbols.origin(sym) }
    intercept[InternalCompilerException] { origins.record(source, owner) }
  }

  test("a failed compilation releases its context and compiler reuse gets fresh state") {
    val flix = new Flix()
    var previous: Option[JvmCompilationOrigins] = None
    intercept[IllegalStateException] {
      flix.withJvmOrigins(TypedAst.empty) {
        previous = Some(flix.jvmOrigins)
        throw new IllegalStateException("test failure")
      }
    }
    intercept[InternalCompilerException] { flix.jvmOrigins }
    intercept[InternalCompilerException] { previous.get.record(literal(0), owner) }
    flix.withJvmOrigins(TypedAst.empty) {
      assert(flix.jvmOrigins ne previous.get)
      intercept[InternalCompilerException] { flix.withJvmOrigins(TypedAst.empty)(()) }
    }
    intercept[InternalCompilerException] { flix.jvmOrigins }
  }

  test("closing a frozen symbol registry still releases expression entries") {
    val origins = new JvmCompilationOrigins(new JvmProvenance)
    val source = literal(0)
    origins.record(source, owner)
    origins.symbols.freeze(Nil)
    origins.close()
    origins.close()
    intercept[InternalCompilerException] { origins.expression(source) }
  }

  test("source pruning removes unreachable bodies without losing default implementation origins") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibNix)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text =
      "trait Identity[a] { pub def makeThunk(value: a): Unit -> a = () -> value }\n" +
        "pub def discarded(): Int32 = 1")
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val dropped = root.defs.values.find(_.sym.text == "discarded").get
    val sig = root.sigs.values.find(_.sym.name == "makeThunk").get
    val defaultSym = new Symbol.DefnSym(None, sig.sym.namespace, sig.sym.name, sig.loc)
    val origins = JvmCompilationOrigins.capture(root)
    origins.retainSource(root.copy(defs = Map.empty))
    intercept[InternalCompilerException] { origins.expression(dropped.exp) }
    intercept[InternalCompilerException] { origins.symbols.origin(dropped.sym) }
    assert(origins.symbols.origin(defaultSym).family == "default-implementation")
    assert(origins.expression(sig.exp.get) != null)
    origins.close()
  }
}
