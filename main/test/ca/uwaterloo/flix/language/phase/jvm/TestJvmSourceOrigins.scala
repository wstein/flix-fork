package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

class TestJvmSourceOrigins extends AnyFunSuite {

  test("capture includes every standard library body and default implementation") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibAll)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text =
      "pub def example(value: Int32): Int32 -> Int32 = argument -> argument + value")
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val captured = JvmSourceOrigins.capture(root)
    root.defs.keys.foreach(sym => assert(captured.body(sym) != null))
    root.instances.values.flatMap(_.defs).foreach(defn => assert(captured.body(defn.sym) != null))
    root.sigs.values.filter(_.exp.nonEmpty).foreach { sig =>
      val sym = new Symbol.DefnSym(None, sig.sym.trt.namespace :+ sig.sym.trt.name, sig.sym.name, sig.loc)
      assert(captured.body(sym) != null)
    }
    val example = root.defs.values.find(_.sym.text == "example").get
    assert(captured.body(example.sym).entries.nonEmpty)
    captured.releaseBodies()
    intercept[ca.uwaterloo.flix.util.InternalCompilerException] { captured.body(example.sym) }
    assert(captured.provenance.origin(example.sym).family == "definition")
  }

  test("anonymous class origins transfer to the symbol registry before releasing source bodies") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text =
      """import java.lang.Runnable
        |pub def example(): Runnable \ IO = new Runnable {
        |  def $run(_this: Runnable): Unit = ()
        |}
        |""".stripMargin)
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val captured = JvmSourceOrigins.capture(root)
    val example = root.defs.values.find(_.sym.text == "example").get
    val classes = captured.body(example.sym).entries.collect {
      case (TypedAst.Expr.NewObject(sym, _, _, _, _, _, _), key) => sym -> key
    }
    assert(classes.size == 1)
    captured.releaseBodies()
    classes.foreach { case (sym, key) => assert(captured.provenance.origin(sym) == key) }
  }

  test("inferred local types and regions do not expose allocation order") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    def keys(source: String): List[GeneratedJvmKey] = {
      val flix = new Flix().setOptions(Options.TestWithLibAll)
      flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text = source)
      val (result, errors) = flix.check()
      assert(errors.isEmpty, errors.mkString("\n"))
      val root = result.get
      val example = root.defs.values.find(_.sym.text == "example").get
      JvmSourceOrigins.capture(root).body(example.sym).entries.map(_._2)
    }
    val source = """pub def example(value: Int32): Int32 = region rc {
                   |  let reference: Ref[Int32, _] = Ref.fresh(rc, value);
                   |  let read = () -> Ref.get(reference);
                   |  read()
                   |}
                   |""".stripMargin
    val original = keys(source)
    assert(original.nonEmpty)
    assert(original == keys("pub def earlier(value: Bool): Bool = value\n" +
      source.replace("reference", "renamed").replace("rc", "local")))
  }

  test("direct lexical capture uses the same registry as source capture") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text =
      "enum Choice { case Selected(Int32) }\npub def example(): Int32 -> Choice = value -> Choice.Selected(value)")
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val example = root.defs.values.find(_.sym.text == "example").get
    val captured = JvmSourceOrigins.capture(root)
    val direct = JvmLexicalOrigins.capture(example.exp, captured.provenance.origin(example.sym), example.spec.fparams.toList,
      (tpe, locals) => JvmTypeKey.encodeLexical(tpe, example.spec.declaredScheme.quantifiers,
        sym => locals.getOrElse(sym, captured.provenance.origin(sym))), captured.provenance.origin)
    assert(captured.body(example.sym).entries.map(_._2) == direct.entries.map(_._2))
  }

  test("lexical capture retains source parameter and let names before lowering") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile,
      """pub def example(value: Int32): Int32 = {
        |  let named = value + 1;
        |  named
        |}
        |""".stripMargin)
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val example = root.defs.values.find(_.sym.text == "example").get
    val bindings = JvmSourceOrigins.capture(root).body(example.sym).bindings
    assert(bindings.map(_.name).toSet == Set("value", "named"))
    assert(bindings.map(_.kind).toSet == Set("parameter", "let"))
    assert(bindings.map(_.tpe).toSet == Set("Int32"))
    assert(bindings.forall(_.loc.isReal))
  }

  test("default implementations retain their declaration family during lexical capture") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibNix)
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text =
      "trait Identity[a] { pub def makeThunk(value: a): Unit -> a = () -> value }")
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val sig = root.sigs.values.find(_.sym.name == "makeThunk").get
    val implementation = new Symbol.DefnSym(None, sig.sym.trt.namespace :+ sig.sym.trt.name, sig.sym.name, sig.loc)
    val captured = JvmSourceOrigins.capture(root)
    assert(captured.provenance.origin(implementation).family == "default-implementation")
    assert(captured.body(implementation).entries.nonEmpty)
  }
}
