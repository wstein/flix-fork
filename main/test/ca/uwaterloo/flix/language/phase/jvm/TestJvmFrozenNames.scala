package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{JvmAst, SourceLocation, Symbol, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{Annotations, Modifiers}
import ca.uwaterloo.flix.language.phase.jvm.classes.GenNullaryTag
import ca.uwaterloo.flix.util.InternalCompilerException
import org.scalatest.funsuite.AnyFunSuite

class TestJvmFrozenNames extends AnyFunSuite {
  private val loc = SourceLocation.Unknown

  private def descriptors(counter: Int): List[String] = {
    implicit val flix: Flix = new Flix()
    flix.withJvmOrigins(TypedAst.empty) {
      val definition = new Symbol.DefnSym(Some(counter), List("Example"), "function", loc)
      val enumeration = new Symbol.EnumSym(Some(counter), List("Example"), "Choice", loc)
      val anonymous = new Symbol.AnonClassSym(counter, loc)
      val symbols = List(definition, enumeration, anonymous)
      symbols.zip(List("definition", "enum", "anonymous")).foreach { case (sym, family) =>
        flix.jvmOrigins.symbols.register(sym, GeneratedJvmKey(family, List("semantic-origin")))
      }
      flix.jvmOrigins.freeze(symbols)
      List(
        GenFunAndClosureClasses.defnDesc(definition),
        GenFunAndClosureClasses.closureDesc(definition),
        GenNullaryTag.desc(new Symbol.CaseSym(enumeration, "Empty", 0, loc)),
        GenAnonymousClasses.desc(anonymous)
      ).map(_.descriptorString())
    }
  }

  test("all counter-bearing JVM descriptor families use semantic names") {
    assert(descriptors(123) == descriptors(98765))
  }

  test("descriptor lookups reject absent frozen mappings instead of using counters") {
    implicit val flix: Flix = new Flix()
    flix.withJvmOrigins(TypedAst.empty) {
      flix.jvmOrigins.freeze(Nil)
      val definition = new Symbol.DefnSym(Some(123), Nil, "missing", loc)
      val enumeration = new Symbol.EnumSym(Some(123), Nil, "Missing", loc)
      intercept[InternalCompilerException] { GenFunAndClosureClasses.defnDesc(definition) }
      intercept[InternalCompilerException] { GenFunAndClosureClasses.closureDesc(definition) }
      intercept[InternalCompilerException] { GenNullaryTag.desc(new Symbol.CaseSym(enumeration, "Empty", 0, loc)) }
      intercept[InternalCompilerException] { GenAnonymousClasses.desc(new Symbol.AnonClassSym(123, loc)) }
    }
  }

  test("source definition spelling remains unchanged but requires a frozen mapping") {
    implicit val flix: Flix = new Flix()
    val definition = new Symbol.DefnSym(None, Nil, "example", loc)
    flix.withJvmOrigins(TypedAst.empty) {
      flix.jvmOrigins.symbols.register(definition, GeneratedJvmKey("definition", List("example")))
      intercept[InternalCompilerException] { GenFunAndClosureClasses.defnDesc(definition) }
      flix.jvmOrigins.freeze(List(definition))
      assert(GenFunAndClosureClasses.defnDesc(definition).descriptorString() == "LDef$example;")
    }
    intercept[InternalCompilerException] { GenFunAndClosureClasses.defnDesc(definition) }
  }

  test("code generation requires origins for emitted enums even without expression references") {
    implicit val flix: Flix = new Flix()
    flix.withJvmOrigins(TypedAst.empty) {
      val enumeration = new Symbol.EnumSym(Some(123), Nil, "Missing", loc)
      val caze = new Symbol.CaseSym(enumeration, "Empty", 0, loc)
      val declaration = JvmAst.Enum(Annotations.Empty, Modifiers.Empty, enumeration,
        Map(caze -> JvmAst.Case(caze, Nil, loc)), loc)
      val root = JvmAst.Root(Map.empty, Map(enumeration -> declaration), Map.empty, Map.empty,
        Set.empty, Nil, None, Set.empty, Map.empty)
      val error = intercept[InternalCompilerException] { CodeGen.run(root) }
      assert(error.getMessage.contains("Missing JVM naming provenance"))
      intercept[InternalCompilerException] { flix.jvmOrigins.nameTable }
    }
  }
}
