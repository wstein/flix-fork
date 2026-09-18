package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{JvmAst, SimpleType, SourceLocation, Symbol, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{Annotation, Annotations, Constant, Modifiers}
import ca.uwaterloo.flix.language.phase.jvm.classes.GenNamespace
import ca.uwaterloo.flix.util.InternalCompilerException
import org.scalatest.funsuite.AnyFunSuite

class TestJvmNamespaceNames extends AnyFunSuite {
  private val loc = SourceLocation.Unknown
  private val namespace = List("Example")
  private val origin = GeneratedJvmKey("definition", List("namespace-entry-point"))

  private def definition(id: Option[Int], exported: Boolean): JvmAst.Def = {
    val sym = new Symbol.DefnSym(id, namespace, "entryPoint", loc)
    val annotations = if (exported) Annotations(List(Annotation.Export(loc))) else Annotations.Empty
    JvmAst.Def(annotations, Modifiers.Empty, sym, Nil, Nil, Nil, 0,
      JvmAst.Expr.Cst(Constant.Int32(42), loc), SimpleType.Int32, JvmAst.UnboxedType(SimpleType.Int32), None, loc)
  }

  private def shimName(id: Option[Int], exported: Boolean): String = {
    implicit val flix: Flix = new Flix()
    flix.withJvmOrigins(TypedAst.empty) {
      val defn = definition(id, exported)
      flix.jvmOrigins.symbols.register(defn.sym, origin)
      flix.jvmOrigins.freeze(List(defn.sym))
      val method = GenNamespace.ShimMethod(namespace, defn)
      assert(method.d.descriptorString() == "()I")
      if (!exported) {
        val spelling = if (id.isDefined) defn.sym.text + Flix.Delimiter + flix.jvmOrigins.nameTable.suffix(defn.sym) else defn.sym.text
        assert(method.name == "m_" + Mangle.mangle(spelling))
      }
      method.name
    }
  }

  test("non-export namespace shims replace allocation counters with frozen suffixes") {
    assert(shimName(Some(123), exported = false) == shimName(Some(98765), exported = false))
    assert(shimName(None, exported = false) == "m_entryPoint")
  }

  test("export namespace shims preserve source text without prefixes or suffixes") {
    for (id <- List(None, Some(123), Some(98765))) {
      assert(shimName(id, exported = true) == "entryPoint")
    }
  }

  test("all namespace shims require frozen mappings including exports and idless definitions") {
    implicit val flix: Flix = new Flix()
    flix.withJvmOrigins(TypedAst.empty) {
      flix.jvmOrigins.freeze(Nil)
      for (id <- List(None, Some(123)); exported <- List(false, true)) {
        val defn = definition(id, exported)
        val error = intercept[InternalCompilerException] {
          GenNamespace.ShimMethod(namespace, defn)
        }
        assert(error.getMessage.contains("Missing JVM naming provenance"))
      }
    }
  }
}
