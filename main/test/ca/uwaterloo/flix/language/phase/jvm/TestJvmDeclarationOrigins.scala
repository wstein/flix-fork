package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

class TestJvmDeclarationOrigins extends AnyFunSuite {

  private def checked(source: String): TypedAst.Root = {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibNix)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text = source)
    val (root, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    root.get
  }

  private def definitions(root: TypedAst.Root): Map[String, GeneratedJvmKey] = {
    val provenance = JvmDeclarationOrigins.capture(root)
    root.defs.values.map(defn => defn.sym.text -> provenance.origin(defn.sym)).toMap
  }

  test("declaration origins survive unrelated definitions and line shifts") {
    val original = definitions(checked("pub def target(value: Int32): Int32 = value"))
    val edited = definitions(checked("// shifted\npub def extra(): Bool = true\npub def target(value: Int32): Int32 = value"))
    assert(original("target") == edited("target"))
  }

  test("declaration identity does not depend on its body") {
    val original = definitions(checked("pub def target(value: Int32): Int32 = value"))
    val edited = definitions(checked("pub def target(_value: Int32): Int32 = 42"))
    assert(original("target") == edited("target"))
  }

  test("instance members are identified by trait and instance head") {
    val root = checked("""
      |trait Choose[a] { pub def selectValue(value: a): Int32 }
      |instance Choose[Int32] { pub def selectValue(value: Int32): Int32 = value }
      |instance Choose[Bool] { pub def selectValue(value: Bool): Int32 = if (value) 1 else 0 }
      |""".stripMargin)
    val registry = JvmDeclarationOrigins.capture(root)
    val members = root.instances.values.flatMap(_.defs).toList
    assert(members.size == 2)
    assert(members.map(defn => registry.origin(defn.sym)).distinct.size == 2)
    assert(members.forall(_.sym.id.nonEmpty))
  }

  test("instance member origins survive counter changes and reordering") {
    val first = "instance Choose[Int32] { pub def selectValue(value: Int32): Int32 = value }"
    val second = "instance Choose[Bool] { pub def selectValue(value: Bool): Int32 = if (value) 1 else 0 }"
    val declaration = "trait Choose[a] { pub def selectValue(value: a): Int32 }\n"
    def keys(root: TypedAst.Root): Set[GeneratedJvmKey] = {
      val registry = JvmDeclarationOrigins.capture(root)
      root.instances.values.flatMap(_.defs).map(defn => registry.origin(defn.sym)).toSet
    }
    assert(keys(checked(declaration + first + "\n" + second)) == keys(checked(declaration + second + "\n" + first)))
  }

  test("type parameter renaming preserves generic instance origins") {
    def keys(parameter: String): Set[GeneratedJvmKey] = {
      val root = checked(s"""
        |enum Box[$parameter] { case Box($parameter) }
        |trait Choose[a] { pub def selectValue(value: a): Int32 }
        |instance Choose[Box[$parameter]] { pub def selectValue(_value: Box[$parameter]): Int32 = 1 }
        |""".stripMargin)
      val registry = JvmDeclarationOrigins.capture(root)
      root.instances.values.flatMap(_.defs).map(defn => registry.origin(defn.sym)).toSet
    }
    assert(keys("a") == keys("renamed"))
  }

  test("default implementations have explicit origins") {
    val root = checked("trait Choose[a] { pub def selectValue(_value: a): Int32 = 42 }")
    val registry = JvmDeclarationOrigins.capture(root)
    val sig = root.sigs.values.find(_.sym.name == "selectValue").get
    val sym = new Symbol.DefnSym(None, sig.sym.trt.namespace :+ sig.sym.trt.name, sig.sym.name, sig.loc)
    assert(registry.origin(sym).family == "default-implementation")
    assert(registry.origin(sig.sym).family == "signature")
  }

  test("derived members and standard library declarations can be captured") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibAll)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text = "enum Color with Eq, Order, ToString { case Red, case Blue }")
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val registry = JvmDeclarationOrigins.capture(root)
    root.instances.values.foreach { instance =>
      instance.defs.foreach { defn => assert(registry.origin(defn.sym).family == "instance-member") }
    }
    root.defs.keys.foreach(sym => assert(registry.origin(sym).family == "definition"))
  }
}
