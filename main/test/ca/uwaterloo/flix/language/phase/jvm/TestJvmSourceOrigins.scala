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
    flix.addVirtualPath(CompilerConstants.VirtualTestFile,
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
    flix.addVirtualPath(CompilerConstants.VirtualTestFile,
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
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, source)
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

  test("declaration-backed and nominal lexical capture agree for source enum cases") {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibNix)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile,
      "enum Choice { case Selected(Int32) }\npub def example(): Int32 -> Choice = value -> Choice.Selected(value)")
    val (result, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = result.get
    val example = root.defs.values.find(_.sym.text == "example").get
    val captured = JvmSourceOrigins.capture(root)
    val nominal = JvmLexicalOrigins.capture(example.exp, captured.provenance.origin(example.sym), example.spec.fparams.toList)
    assert(captured.body(example.sym).entries.map(_._2) == nominal.entries.map(_._2))
  }
}
