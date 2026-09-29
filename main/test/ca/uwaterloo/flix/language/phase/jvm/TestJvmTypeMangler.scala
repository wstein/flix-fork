package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{Kind, Name, SimpleType, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.ast.shared.SymUse.AssocTypeSymUse
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedSet

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

  test("commutative effect spellings follow the key's canonical order") {
    val first = builtin(TypeConstructor.Effect(Symbol.mkEffSym("Example.First"), Kind.Eff))
    val second = builtin(TypeConstructor.Effect(Symbol.mkEffSym("Example.Second"), Kind.Eff))
    val forward = Type.mkUnion(first, second, loc)
    val backward = Type.mkUnion(second, first, loc)
    val spelling = JvmTypeMangler.monomorph(List(forward), noGenerated)
    assert(spelling == JvmTypeMangler.monomorph(List(backward), noGenerated))
    assert(spelling.startsWith("IO5UnionI"), spelling)
  }

  test("record rows use label order and preserve their fields") {
    def extend(label: String, field: Type, tail: Type): Type =
      Type.mkRecordRowExtend(Name.Label(label, loc), field, tail, loc)
    val empty = Type.mkRecordRowEmpty(loc)
    val forward = extend("a", builtin(TypeConstructor.Int32), extend("b", builtin(TypeConstructor.Str), empty))
    val backward = extend("b", builtin(TypeConstructor.Str), extend("a", builtin(TypeConstructor.Int32), empty))
    val spelling = JvmTypeMangler.monomorph(List(forward), noGenerated)
    assert(spelling == JvmTypeMangler.monomorph(List(backward), noGenerated))
    assert(spelling.startsWith("IBIY1a5Int32Y1b6StringZ"), spelling)
    assert(JvmTypeDemangler.demangle("Def$record$" + spelling).isRight)
  }

  test("erasure specializations use a flat primitive-or-Obj list") {
    assert(JvmTypeMangler.erasure(List(SimpleType.Int32, SimpleType.Object)) == "Int32$Obj")
  }

  test("Java reference types keep their qualified names") {
    val javaList = builtin(TypeConstructor.Native(java.lang.constant.ClassDesc.of("java.util.List"), 1))
    val suffix = JvmTypeMangler.monomorph(List(applyType(javaList, builtin(TypeConstructor.Int32))), noGenerated)
    assert(suffix == "IJN4java4util4ListEI5Int32EE")
    assert(JvmTypeDemangler.demangle("Def$bridge$" + suffix) == Right("bridge(Java(java.util.List)[Int32])"))
  }

  test("source-name escaping preserves frame lengths and distinct spellings") {
    val dotted = JvmTypeMangler.monomorph(List(nominal("a.b")), noGenerated)
    val literalEscape = JvmTypeMangler.monomorph(List(nominal("a$dotb")), noGenerated)
    val unicode = JvmTypeMangler.monomorph(List(nominal("雪")), noGenerated)
    assert(dotted != literalEscape)
    assert(JvmTypeDemangler.demangle("Def$f$" + dotted) == Right("f(a.b)"))
    assert(JvmTypeDemangler.demangle("Def$f$" + literalEscape) == Right("f(a$dotb)"))
    assert(JvmTypeDemangler.demangle("Def$f$" + unicode) == Right("f(雪)"))
  }

  test("demangling reverses un-compacted specialization vectors") {
    assert(JvmTypeDemangler.demangle("Def$map$I5Int326StringE") == Right("map(Int32, String)"))
    assert(JvmTypeDemangler.demangle("Def$swap$I5ColorS_E") == Right("swap(Color, Color)"))
    assert(JvmTypeDemangler.demangle("Def$index$I3MapI6String4ListI5Int32EEE") ==
      Right("index(Map[String, List[Int32]])"))
    assert(JvmTypeDemangler.demangle("Case$Option$Obj$None") == Right("Option[Obj].None"))
    assert(JvmTypeDemangler.demangle("Anon$Shop$price$0") == Right("anonymous at Shop.price.0"))
    assert(JvmTypeDemangler.demangle("Def$twice$IF3_I4PureF2_IS_5Int32S0_ES0_S0_EE").isRight)
    assert(JvmTypeDemangler.demangle("Def$map$$$$$abc$$$$$tail").isLeft)
    val effect = builtin(TypeConstructor.Effect(Symbol.mkEffSym("Example.First"), Kind.Eff))
    val effectName = "Def$run$" + JvmTypeMangler.monomorph(List(effect, effect), noGenerated)
    assert(JvmTypeDemangler.demangle(effectName) == Right("run(effect Example.First, effect Example.First)"))
  }

  test("uncommon semantic types demangle to readable structure") {
    val assoc = new Symbol.AssocTypeSym(Symbol.mkTraitSym("Example.Items"), "Element", loc)
    val types = List(
      Type.AssocType(AssocTypeSymUse(assoc, loc), Type.Int32, Kind.Star, loc) -> "Example.Items.Element[Int32]: Star",
      Type.JvmToType(Type.Int32, loc) -> "JvmToType[Int32]",
      Type.JvmToEff(Type.Int32, loc) -> "JvmToEff[Int32]",
      Type.UnresolvedJvmType(Type.JvmMember.JvmStaticMethod(
        java.lang.constant.ClassDesc.of("java.lang.String"), Name.Ident("valueOf", loc), List(Type.Int32)), loc) ->
        "UnresolvedStaticMethod(Ljava/lang/String;, valueOf, [Int32])")
    types.foreach { case (tpe, expected) =>
      assert(JvmTypeKeyDemangler.demangle(JvmTypeKey.encode(tpe, Nil, noGenerated)) == Right(expected))
      val suffix = JvmTypeMangler.monomorph(List(tpe), noGenerated)
      assert(suffix.startsWith("IX"), suffix)
      assert(JvmTypeDemangler.demangle("Def$example$" + suffix) == Right("example(" + expected + ")"))
    }
  }

  test("uncommon constructors decode their embedded X key") {
    val region = Type.mkRegion(new Symbol.RegionSym(7, "r", loc), loc)
    val origin: Symbol => GeneratedJvmKey = _ => GeneratedJvmKey("region", List("Example.f", "r"))
    val suffix = JvmTypeMangler.monomorph(List(region), origin)
    assert(suffix.startsWith("IK"), suffix)
    assert(JvmTypeDemangler.demangle("Def$example$" + suffix) ==
      Right("example(Region[Region[generated(region, Example.f, r)]])"))
    val enm = new Symbol.RestrictableEnumSym(List("Example"), "Choice", Nil, loc)
    val first = new Symbol.RestrictableCaseSym(enm, "First", loc)
    val order = Ordering.by[Symbol.RestrictableCaseSym, String](_.name)
    val cases = builtin(TypeConstructor.CaseSet(SortedSet(first)(order), enm))
    val caseSuffix = JvmTypeMangler.monomorph(List(cases), noGenerated)
    assert(JvmTypeDemangler.demangle("Def$example$" + caseSuffix) ==
      Right("example(CaseSet[Example.Choice{Example.Choice.First}])"))
  }

  test("malformed uncommon type keys fail without throwing") {
    assert(JvmTypeDemangler.demangle("Def$example$IX2abE").isLeft)
    assert(JvmTypeKeyDemangler.demangle("AAAA").isLeft)
  }
}
