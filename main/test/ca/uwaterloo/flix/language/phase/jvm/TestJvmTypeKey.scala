package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{Kind, Name, SimpleType, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.ast.jvm.{JavaField, JavaFieldRef, JavaMethod, JavaMethodRef, JavaType, JavaTypeParameter, JavaTypeVariable, JavaTypeVariableOwner}
import ca.uwaterloo.flix.language.ast.shared.{RegionScope, VarText}
import ca.uwaterloo.flix.language.ast.shared.SymUse.{AssocTypeSymUse, TypeAliasSymUse}
import ca.uwaterloo.flix.util.InternalCompilerException
import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.{ClassDesc, MethodTypeDesc}
import scala.collection.immutable.SortedSet

class TestJvmTypeKey extends AnyFunSuite {
  private val loc = SourceLocation.Unknown

  private def encode(tpe: Type): String = JvmTypeKey.encode(tpe, Nil)

  private def encodeSimple(tpe: SimpleType): String =
    JvmTypeKey.encodeSimple(tpe, _ => throw InternalCompilerException("Missing test origin.", loc))

  private def variable(id: Int, name: String, kind: Kind = Kind.Star): Symbol.KindedTypeVarSym =
    new Symbol.KindedTypeVarSym(id, VarText.SourceText(name), kind, false, RegionScope.Top, loc)

  test("concrete types and ordered arguments have distinct deterministic encodings") {
    val types = List(Type.Int32, Type.Int64, Type.Str, Type.mkTuple(List(Type.Int32, Type.Str), loc),
      Type.mkTuple(List(Type.Str, Type.Int32), loc), Type.mkArray(Type.Int32, Type.Pure, loc))
    assert(types.map(encode).distinct.size == types.size)
    assert(types.map(encode) == types.map(encode))
  }

  test("variables are alpha renamed by supplied binding order, not first occurrence") {
    val first = variable(1, "a")
    val second = variable(2, "b")
    val renamedFirst = variable(900, "x")
    val renamedSecond = variable(800, "y")
    def pair(left: Symbol.KindedTypeVarSym, right: Symbol.KindedTypeVarSym): Type =
      Type.mkTuple(List(Type.Var(right, loc), Type.Var(left, loc)), loc)
    assert(JvmTypeKey.encode(pair(first, second), List(first, second)) ==
      JvmTypeKey.encode(pair(renamedFirst, renamedSecond), List(renamedFirst, renamedSecond)))
    assert(JvmTypeKey.encode(pair(first, second), List(first, second)) !=
      JvmTypeKey.encode(pair(first, second), List(second, first)))
  }

  test("unbound and duplicate variables fail closed") {
    val sym = variable(3, "a")
    intercept[InternalCompilerException] { encode(Type.Var(sym, loc)) }
    intercept[InternalCompilerException] { JvmTypeKey.encode(Type.Var(sym, loc), List(sym, sym)) }
  }

  test("lexical type shapes exclude residual inference variable identity") {
    val first = variable(1, "inferred", Kind.Eff)
    val second = variable(99, "other", Kind.Eff)
    def lexical(tpe: Type, parameters: List[Symbol.KindedTypeVarSym] = Nil): String =
      JvmTypeKey.encodeLexical(tpe, parameters, _ => throw InternalCompilerException("Unexpected symbol.", loc))
    val left = Type.Var(first, loc)
    val right = Type.Var(second, loc)
    assert(lexical(left) == lexical(right))
    assert(lexical(left) != lexical(left, List(first)))
    assert(lexical(Type.mkTuple(List(Type.mkUnion(left, right, loc), left), loc)) ==
      lexical(Type.mkTuple(List(Type.mkUnion(right, left, loc), left), loc)))
    assert(lexical(Type.Int32) != lexical(Type.Str))
    intercept[InternalCompilerException] { encode(left) }
  }

  test("effects distinguish arrows and their union order is canonical") {
    val first = Type.Cst(TypeConstructor.Effect(Symbol.mkEffSym("Example.First"), Kind.Eff), loc)
    val second = Type.Cst(TypeConstructor.Effect(Symbol.mkEffSym("Example.Second"), Kind.Eff), loc)
    def arrow(effect: Type): Type = Type.mkArrowWithEffect(Type.Int32, effect, Type.Int32, loc)
    assert(encode(arrow(Type.Pure)) != encode(arrow(first)))
    assert(encode(arrow(Type.mkUnion(first, second, loc))) == encode(arrow(Type.mkUnion(second, first, loc))))
    assert(encode(Type.mkUnion(first, Type.mkUnion(second, first, loc), loc)) == encode(Type.mkUnion(second, first, loc)))
  }

  test("enum and struct arguments are retained") {
    val enm = Symbol.mkEnumSym("Example.Box")
    val struct = new Symbol.StructSym(None, List("Example"), "Box", loc)
    val types = List(Type.mkEnum(enm, List(Type.Int32), loc), Type.mkEnum(enm, List(Type.Str), loc),
      Type.mkStruct(struct, List(Type.Int32), loc), Type.mkStruct(struct, List(Type.Str), loc))
    assert(types.map(encode).distinct.size == types.size)
  }

  test("namespace components and text are framed without delimiter ambiguity") {
    def enm(namespace: List[String], name: String): Type =
      Type.Cst(TypeConstructor.Enum(new Symbol.EnumSym(None, namespace, name, loc), Kind.Star), loc)
    val types = List(enm(List("a.b"), "c"), enm(List("a", "b"), "c"), enm(List("a"), "b.c"),
      enm(List("λ:1"), "|雪"), enm(List("λ"), ":1|雪"))
    assert(types.map(encode).distinct.size == types.size)
    assert(encode(enm(List("a"), "b")) == encode(enm(List("a"), "b")))
  }

  test("generated symbols require semantic origins and ignore counters") {
    def enm(id: Int): Type =
      Type.Cst(TypeConstructor.Enum(new Symbol.EnumSym(Some(id), List("Example"), "Box", loc), Kind.Star), loc)
    intercept[InternalCompilerException] { encode(enm(1)) }
    val origin: Symbol => GeneratedJvmKey = _ => GeneratedJvmKey("enum-specialization", List("Example.Box", "argument"))
    assert(JvmTypeKey.encode(enm(1), Nil, origin) == JvmTypeKey.encode(enm(999), Nil, origin))
    assert(JvmTypeKey.encode(enm(1), Nil, origin) !=
      JvmTypeKey.encode(enm(1), Nil, _ => GeneratedJvmKey("enum-specialization", List("other"))))
    val region = Type.mkRegion(new Symbol.RegionSym(7, "r", loc), loc)
    intercept[InternalCompilerException] { encode(region) }
    assert(JvmTypeKey.encode(region, Nil, origin).nonEmpty)
  }

  test("error constructors have no stable semantic identity") {
    intercept[InternalCompilerException] { encode(Type.Cst(TypeConstructor.Error(123, Kind.Star), loc)) }
  }

  test("all nullary constructors have separate tags") {
    import TypeConstructor._
    val constructors = List(Void, AnyType, Unit, Null, Bool, Char, Float32, Float64, BigDecimal,
      Int8, Int16, Int32, Int64, BigInt, Str, Regex, RecordRowEmpty, Record, Extensible,
      SchemaRowEmpty, Schema, Sender, Receiver, Lazy, Array, ArrayWithoutRegion, Vector,
      True, False, Not, And, Or, Pure, Univ, Complement, Union, Intersection, Difference,
      SymmetricDiff, RegionToStar, RegionWithoutRegion)
    assert(constructors.map(tc => encode(Type.Cst(tc, loc))).distinct.size == constructors.size)
  }

  test("constructor payloads and kinds are significant") {
    import TypeConstructor._
    val enm = Symbol.mkEnumSym("Example.Box")
    val constructors = List(Arrow(2), Arrow(3), ArrowWithoutEffect(2), Tuple(2), Tuple(3),
      Relation(2), Lattice(2), RecordRowExtend(Name.Label("a", loc)), RecordRowExtend(Name.Label("b", loc)),
      SchemaRowExtend(Name.Pred("a", loc)), SchemaRowExtend(Name.Pred("b", loc)),
      Enum(enm, Kind.Star), Enum(enm, Kind.mkArrow(1)),
      Native(ClassDesc.of("java.util.List"), 0), Native(ClassDesc.of("java.util.List"), 1))
    assert(constructors.map(tc => encode(Type.Cst(tc, loc))).distinct.size == constructors.size)
    val star = variable(1, "a", Kind.Star)
    val effect = variable(2, "a", Kind.Eff)
    assert(JvmTypeKey.encode(Type.Var(star, loc), List(star)) != JvmTypeKey.encode(Type.Var(effect, loc), List(effect)))
  }

  test("aliases are transparent including aliases of set operators") {
    val sym = new Symbol.TypeAliasSym(List("Example"), "Alias", loc)
    def alias(inner: Type): Type = Type.Alias(TypeAliasSymUse(sym, loc), Nil, inner, loc)
    assert(encode(alias(Type.Int32)) == encode(Type.Int32))
    val first = Type.Cst(TypeConstructor.Effect(Symbol.mkEffSym("Example.A"), Kind.Eff), loc)
    val second = Type.Cst(TypeConstructor.Effect(Symbol.mkEffSym("Example.B"), Kind.Eff), loc)
    val applied = Type.mkApply(alias(Type.Cst(TypeConstructor.Union, loc)), List(first, second), loc)
    assert(encode(applied) == encode(Type.mkUnion(second, first, loc)))
  }

  test("associated types retain owner argument and result kind") {
    def assoc(owner: String, arg: Type, kind: Kind): Type = {
      val sym = new Symbol.AssocTypeSym(Symbol.mkTraitSym(owner), "Element", loc)
      Type.AssocType(AssocTypeSymUse(sym, loc), arg, kind, loc)
    }
    val types = List(assoc("Example.A", Type.Int32, Kind.Star), assoc("Example.B", Type.Int32, Kind.Star),
      assoc("Example.A", Type.Str, Kind.Star), assoc("Example.A", Type.Int32, Kind.Eff))
    assert(types.map(encode).distinct.size == types.size)
  }

  test("case sets sort semantic members independently of collection ordering") {
    val enm = new Symbol.RestrictableEnumSym(List("Example"), "Choice", Nil, loc)
    val first = new Symbol.RestrictableCaseSym(enm, "First", loc)
    val second = new Symbol.RestrictableCaseSym(enm, "Second", loc)
    val order = Ordering.by[Symbol.RestrictableCaseSym, String](_.name)
    val forward = SortedSet(first, second)(order)
    val backward = SortedSet(first, second)(order.reverse)
    assert(encode(Type.Cst(TypeConstructor.CaseSet(forward, enm), loc)) ==
      encode(Type.Cst(TypeConstructor.CaseSet(backward, enm), loc)))
    val constructors = List(TypeConstructor.RestrictableEnum(enm, Kind.Star),
      TypeConstructor.CaseComplement(enm), TypeConstructor.CaseUnion(enm),
      TypeConstructor.CaseIntersection(enm), TypeConstructor.CaseSymmetricDiff(enm),
      TypeConstructor.CaseSet(forward, enm), TypeConstructor.CaseSet(SortedSet(first)(order), enm))
    assert(constructors.map(tc => encode(Type.Cst(tc, loc))).distinct.size == constructors.size)
  }

  test("unresolved JVM forms and conversions retain their discriminators") {
    val clazz = ClassDesc.of("java.lang.String")
    val name = Name.Ident("value", loc)
    val members = List(Type.JvmMember.JvmConstructor(clazz, List(Type.Int32)),
      Type.JvmMember.JvmField(loc, Type.Str, name), Type.JvmMember.JvmMethod(Type.Str, name, List(Type.Int32)),
      Type.JvmMember.JvmStaticMethod(clazz, name, List(Type.Int32)),
      Type.JvmMember.JvmStaticMethod(clazz, name, List(Type.Str)))
    val types = members.map(Type.UnresolvedJvmType(_, loc)) :::
      List(Type.JvmToType(Type.Str, loc), Type.JvmToEff(Type.Str, loc))
    assert(types.map(encode).distinct.size == types.size)
  }

  test("resolved JVM members include signatures and generic binding order") {
    val clazz = ClassDesc.of("java.lang.Object")
    val ref = JavaMethodRef(clazz, "identity", MethodTypeDesc.of(clazz, clazz), false)
    def method(name: String): JavaMethod = {
      val variable = JavaTypeVariable(JavaTypeVariableOwner.Method(ref), name)
      val parameter = JavaTypeParameter(variable, List(JavaType.NonGeneric(clazz)))
      JavaMethod(ref, 1, List(parameter), List("ignored"), List(JavaType.Variable(variable, clazz)),
        JavaType.Variable(variable, clazz), false, false)
    }
    val first = method("T")
    val renamed = method("U")
    def methodType(value: JavaMethod): Type = Type.Cst(TypeConstructor.JvmMethod(value, Nil), loc)
    assert(encode(methodType(first)) == encode(methodType(renamed)))
    assert(encode(methodType(first)) == encode(methodType(first.copy(parameterNames = List("other")))))
    assert(encode(methodType(first)) != encode(methodType(first.copy(ref = ref.copy(name = "other")))))
    assert(encode(methodType(first)) != encode(Type.Cst(TypeConstructor.JvmConstructor(first), loc)))
    val field = JavaField(JavaFieldRef(clazz, "value", clazz), 1,
      JavaType.Parameterized(clazz, List(JavaType.GenericArray(JavaType.NonGeneric(clazz), clazz),
        JavaType.Wildcard(List(JavaType.NonGeneric(clazz)), Nil, clazz))))
    assert(encode(Type.Cst(TypeConstructor.JvmField(field), loc)).nonEmpty)
    assert(encode(Type.Cst(TypeConstructor.JvmField(field), loc)) !=
      encode(Type.Cst(TypeConstructor.JvmField(field.copy(ref = field.ref.copy(name = "other"))), loc)))
  }

  test("malformed Unicode is rejected") {
    val malformed = new String(Array(0xd800.toChar))
    val enm = new Symbol.EnumSym(None, List(malformed), "Box", loc)
    intercept[InternalCompilerException] { encode(Type.Cst(TypeConstructor.Enum(enm, Kind.Star), loc)) }
  }

  test("record rows are canonical under distinct label reordering") {
    def extend(name: String, value: Type, tail: Type): Type = Type.mkRecordRowExtend(Name.Label(name, loc), value, tail, loc)
    val empty = Type.mkRecordRowEmpty(loc)
    val forward = extend("a", Type.Int32, extend("b", Type.Str, empty))
    val backward = extend("b", Type.Str, extend("a", Type.Int32, empty))
    assert(encode(forward) == encode(backward))
    assert(encode(Type.mkRecord(forward, loc)) == encode(Type.mkRecord(backward, loc)))
    assert(encode(forward) != encode(extend("a", Type.Str, extend("b", Type.Int32, empty))))
    assert(encode(extend("a:b", Type.Int32, empty)) != encode(extend("a", Type.Int32, extend("b", Type.Int32, empty))))
  }

  test("schema rows are canonical under predicate reordering") {
    def extend(name: String, value: Type, tail: Type): Type = Type.mkSchemaRowExtend(Name.Pred(name, loc), value, tail, loc)
    val empty = Type.mkSchemaRowEmpty(loc)
    val relation = Type.mkRelation(List(Type.Int32), loc)
    val lattice = Type.mkLattice(List(Type.Str), loc)
    val forward = extend("A", relation, extend("B", lattice, empty))
    val backward = extend("B", lattice, extend("A", relation, empty))
    assert(encode(forward) == encode(backward))
    assert(encode(Type.mkSchema(forward, loc)) == encode(Type.mkSchema(backward, loc)))
    assert(encode(forward) != encode(extend("A", lattice, extend("B", relation, empty))))
  }

  test("simple primitive and compound forms have distinct encodings") {
    import SimpleType._
    val types = List(Void, AnyType, Unit, Bool, Char, Float32, Float64, BigDecimal, Int8, Int16,
      Int32, Int64, BigInt, String, Regex, Region, Null, Array(Int32), Lazy(Int32), Tuple(List(Int32)),
      Arrow(List(Int32), Int32), RecordEmpty, RecordExtend("a", Int32, RecordEmpty), ExtensibleEmpty,
      ExtensibleExtend(Name.Pred("A", loc), List(Int32), ExtensibleEmpty), Native(ClassDesc.of("java.lang.Object")))
    assert(types.map(encodeSimple).distinct.size == types.size)
    assert(encodeSimple(Int32) != encode(Type.Int32))
  }

  test("simple nominal symbols use framed source identity and retain arguments") {
    val first = Symbol.mkEnumSym("Example.Box")
    val same = Symbol.mkEnumSym("Example.Box")
    val other = Symbol.mkEnumSym("Other.Box")
    val struct = new Symbol.StructSym(None, List("Example"), "Box", loc)
    val types = List(SimpleType.Enum(first, List(SimpleType.Int32)), SimpleType.Enum(first, List(SimpleType.String)),
      SimpleType.Enum(other, List(SimpleType.Int32)), SimpleType.Struct(struct, List(SimpleType.Int32)),
      SimpleType.Struct(struct, List(SimpleType.String)))
    assert(types.map(encodeSimple).distinct.size == types.size)
    assert(encodeSimple(types.head) == encodeSimple(SimpleType.Enum(same, List(SimpleType.Int32))))
    val dotted = new Symbol.EnumSym(None, List("a.b"), "c", loc)
    val split = new Symbol.EnumSym(None, List("a", "b"), "c", loc)
    assert(encodeSimple(SimpleType.Enum(dotted, Nil)) != encodeSimple(SimpleType.Enum(split, Nil)))
  }

  test("simple generated enum and struct identity requires origin and ignores counters") {
    val origin: Symbol => GeneratedJvmKey = _ => GeneratedJvmKey("specialization", List("owner", "argument"))
    def enm(id: Int): SimpleType = SimpleType.Enum(new Symbol.EnumSym(Some(id), List("Example"), "Box", loc), Nil)
    def struct(id: Int): SimpleType = SimpleType.Struct(new Symbol.StructSym(Some(id), List("Example"), "Box", loc), Nil)
    List(enm(1), struct(1)).foreach { tpe => intercept[InternalCompilerException] { encodeSimple(tpe) } }
    assert(JvmTypeKey.encodeSimple(enm(1), origin) == JvmTypeKey.encodeSimple(enm(999), origin))
    assert(JvmTypeKey.encodeSimple(struct(1), origin) == JvmTypeKey.encodeSimple(struct(999), origin))
    assert(JvmTypeKey.encodeSimple(enm(1), origin) != JvmTypeKey.encodeSimple(struct(1), origin))
    assert(JvmTypeKey.encodeSimple(enm(1), origin) !=
      JvmTypeKey.encodeSimple(enm(1), _ => GeneratedJvmKey("specialization", List("other"))))
    assert(JvmTypeKey.encodeSimple(SimpleType.Array(enm(1)), origin) ==
      JvmTypeKey.encodeSimple(SimpleType.Array(enm(999)), origin))
    assert(JvmTypeKey.encodeSimple(enm(1), origin) != encodeSimple(SimpleType.Enum(Symbol.mkEnumSym("Example.Box"), Nil)))
  }

  test("simple arguments arrow results and native descriptors are significant") {
    import SimpleType._
    val enm = Symbol.mkEnumSym("Example.Box")
    val struct = new Symbol.StructSym(None, List("Example"), "Box", loc)
    val pairs = List(
      Array(Int32) -> Array(String), Lazy(Int32) -> Lazy(String),
      Tuple(List(Int32, String)) -> Tuple(List(String, Int32)),
      Enum(enm, List(Int32, String)) -> Enum(enm, List(String, Int32)),
      Struct(struct, List(Int32, String)) -> Struct(struct, List(String, Int32)),
      Arrow(List(Int32, String), Bool) -> Arrow(List(String, Int32), Bool),
      Arrow(List(Int32), Bool) -> Arrow(List(Int32), String),
      Arrow(Nil, Int32) -> Arrow(List(Int32), Int32),
      Native(ClassDesc.of("java.lang.String")) -> Native(ClassDesc.of("java.lang.Object")))
    pairs.foreach { case (left, right) => assert(encodeSimple(left) != encodeSimple(right)) }
  }

  test("simple record and extensible rows sort labels and preserve payload order and tails") {
    import SimpleType._
    val record = RecordExtend("a", Int32, RecordExtend("b", String, RecordEmpty))
    assert(encodeSimple(record) == encodeSimple(RecordExtend("b", String, RecordExtend("a", Int32, RecordEmpty))))
    assert(encodeSimple(record) != encodeSimple(RecordExtend("a", String, RecordExtend("b", Int32, RecordEmpty))))
    assert(encodeSimple(RecordExtend("a", Int32, RecordExtend("a", String, RecordEmpty))) !=
      encodeSimple(RecordExtend("a", String, RecordExtend("a", Int32, RecordEmpty))))
    assert(encodeSimple(RecordExtend("a:b", Int32, RecordEmpty)) != encodeSimple(RecordExtend("a", Int32, RecordExtend("b", Int32, RecordEmpty))))
    def extend(name: java.lang.String, args: List[SimpleType], tail: SimpleType): SimpleType =
      ExtensibleExtend(Name.Pred(name, loc), args, tail)
    val row = extend("A", List(Int32, String), extend("B", Nil, ExtensibleEmpty))
    assert(encodeSimple(row) == encodeSimple(extend("B", Nil, extend("A", List(Int32, String), ExtensibleEmpty))))
    assert(encodeSimple(row) != encodeSimple(extend("A", List(String, Int32), extend("B", Nil, ExtensibleEmpty))))
    assert(encodeSimple(RecordExtend("a", Int32, RecordEmpty)) != encodeSimple(RecordExtend("a", Int32, AnyType)))
    assert(encodeSimple(extend("A", Nil, ExtensibleEmpty)) != encodeSimple(extend("A", Nil, AnyType)))
  }

  test("row normalization retains open tails and duplicate label order") {
    def extend(name: String, value: Type, tail: Type): Type = Type.mkRecordRowExtend(Name.Label(name, loc), value, tail, loc)
    val tail = variable(41, "row", Kind.RecordRow)
    val renamedTail = variable(900, "other", Kind.RecordRow)
    val first = extend("a", Type.Int32, extend("b", Type.Str, Type.Var(tail, loc)))
    val second = extend("b", Type.Str, extend("a", Type.Int32, Type.Var(renamedTail, loc)))
    assert(JvmTypeKey.encode(first, List(tail)) == JvmTypeKey.encode(second, List(renamedTail)))
    intercept[InternalCompilerException] { encode(first) }
    val empty = Type.mkRecordRowEmpty(loc)
    assert(encode(extend("a", Type.Int32, extend("a", Type.Str, empty))) !=
      encode(extend("a", Type.Str, extend("a", Type.Int32, empty))))
  }
}
