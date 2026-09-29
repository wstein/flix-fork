package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{Kind, SimpleType, SourceLocation, Symbol, Type, TypeConstructor}
import org.scalatest.funsuite.AnyFunSuite

class TestJvmTypeMangler extends AnyFunSuite {
  private val loc = SourceLocation.Unknown
  private def builtin(tc: TypeConstructor): Type = Type.Cst(tc, loc)
  private def nominal(name: String): Type = {
    val sym = new Symbol.EnumSym(None, Nil, name, loc)
    builtin(TypeConstructor.Enum(sym, Kind.Star))
  }
  private def applyType(head: Type, args: Type*): Type =
    args.foldLeft(head)((current, arg) => Type.Apply(current, arg, loc))
  private val noGenerated: Symbol => GeneratedJvmKey = _ => fail("unexpected generated symbol")

  test("primitive and nominal arguments have golden spellings") {
    assert(JvmTypeMangler.monomorph(List(builtin(TypeConstructor.Int32), builtin(TypeConstructor.Str)), noGenerated) ==
      "I5Int326StringE")
    val color = nominal("Color")
    assert(JvmTypeMangler.monomorph(List(color, color), noGenerated) == "I5ColorS_E")
  }

  test("nested argument boundaries remain explicit") {
    val map = nominal("Map")
    val list = nominal("List")
    val nested = applyType(map, builtin(TypeConstructor.Str), applyType(list, builtin(TypeConstructor.Int32)))
    assert(JvmTypeMangler.monomorph(List(nested), noGenerated) == "I3MapI6String4ListI5Int32EEE")
  }

  test("erasure specializations use a flat primitive-or-Obj list") {
    assert(JvmTypeMangler.erasure(List(SimpleType.Int32, SimpleType.Object)) == "Int32$Obj")
  }

  test("demangling reverses un-compacted specialization vectors") {
    assert(JvmTypeDemangler.demangle("Def$map$I5Int326StringE") == Right("map(Int32, String)"))
    assert(JvmTypeDemangler.demangle("Def$swap$I5ColorS_E") == Right("swap(Color, Color)"))
    assert(JvmTypeDemangler.demangle("Def$index$I3MapI6String4ListI5Int32EEE") ==
      Right("index(Map[String, List[Int32]])"))
    assert(JvmTypeDemangler.demangle("Case$Option$Obj$None") == Right("Option[Obj].None"))
    assert(JvmTypeDemangler.demangle("Def$twice$IF3_I4PureF2_IS_5Int32S0_ES0_S0_EE").isRight)
    assert(JvmTypeDemangler.demangle("Def$map$$$$$abc$$$$$tail").isLeft)
  }
}
