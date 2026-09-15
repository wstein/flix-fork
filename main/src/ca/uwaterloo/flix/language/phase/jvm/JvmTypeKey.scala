package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{Kind, SimpleType, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.ast.jvm.{JavaField, JavaMethod, JavaMethodRef, JavaType, JavaTypeParameter, JavaTypeVariable, JavaTypeVariableOwner}
import ca.uwaterloo.flix.util.InternalCompilerException

import java.util.Base64

object JvmTypeKey {

  /**
    * Encodes semantic types using versioned, length-framed fields. Locations, variable names,
    * allocation counters, and alias spelling are omitted. Parameters must be supplied in binding
    * order, including effect parameters; unbound variables and erroneous types are rejected.
    * Generated enum/struct and region symbols require a canonical origin from the caller.
    *
    * Associative commutative operators and case sets are sorted by encoded semantic identity.
    * Idempotent operators also discard duplicates. Record and schema rows are sorted by label,
    * preserving the order of repeated labels and the open tail. This is not a Boolean solver: callers must
    * normalize other algebraic equivalences (e.g. distributivity), associated types, and JVM
    * member resolution before comparing encodings across those reductions.
    */
  def encode(tpe: Type, parameters: List[Symbol.KindedTypeVarSym],
             symbolOrigin: Symbol => GeneratedJvmKey = _ => fail("Missing generated symbol origin.")): String = {
    if (parameters.distinct.length != parameters.length) fail("Duplicate type parameter binding.")
    val encoder = new Encoder(parameters.zipWithIndex.toMap, symbolOrigin)
    Base64.getEncoder.encodeToString(GeneratedJvmKey("semantic-type-v1", List(encoder.tpe(Type.eraseAliases(tpe)))).bytes)
  }

  /**
    * Encodes simplified types in a separate versioned domain. Nominal source symbols and
    * generated origins use the same identity rules as encode. Arguments and arrow results
    * are retained; record and extensible rows sort labels while preserving repeated-label
    * order and tails. Effects already erased from SimpleType cannot be recovered here.
    */
  def encodeSimple(tpe: SimpleType, symbolOrigin: Symbol => GeneratedJvmKey): String = {
    val encoder = new Encoder(Map.empty, symbolOrigin)
    Base64.getEncoder.encodeToString(GeneratedJvmKey("semantic-simple-type-v1", List(encoder.simple(tpe))).bytes)
  }

  /**
    * Encodes a source-level type shape, discarding residual inference-variable identity.
    * This is not specialization identity: generated symbols must use the strict encoder.
    */
  private[jvm] def encodeLexical(tpe: Type, parameters: List[Symbol.KindedTypeVarSym],
                                 symbolOrigin: Symbol => GeneratedJvmKey): String = {
    if (parameters.distinct.length != parameters.length) fail("Duplicate type parameter binding.")
    val encoder = new Encoder(parameters.zipWithIndex.toMap, symbolOrigin, lexical = true)
    Base64.getEncoder.encodeToString(GeneratedJvmKey("lexical-type-v1", List(encoder.tpe(Type.eraseAliases(tpe)))).bytes)
  }

  private def fail(message: String): Nothing =
    throw InternalCompilerException(message, SourceLocation.Unknown)

  private def number(value: Int): String = String.valueOf(value)

  private def node(tag: String, fields: String*): String =
    (tag +: fields).map(part => number(part.length) + ":" + part).mkString("[", "", "]")

  private def sequence(fields: Iterable[String]): String = node("list", fields.toSeq: _*)

  private class Encoder(bindings: Map[Symbol.KindedTypeVarSym, Int], origin: Symbol => GeneratedJvmKey,
                        lexical: Boolean = false) {
    def simple(value: SimpleType): String = value match {
      case SimpleType.Void => node("void")
      case SimpleType.AnyType => node("any")
      case SimpleType.Unit => node("unit")
      case SimpleType.Bool => node("bool")
      case SimpleType.Char => node("char")
      case SimpleType.Float32 => node("float32")
      case SimpleType.Float64 => node("float64")
      case SimpleType.BigDecimal => node("big-decimal")
      case SimpleType.Int8 => node("int8")
      case SimpleType.Int16 => node("int16")
      case SimpleType.Int32 => node("int32")
      case SimpleType.Int64 => node("int64")
      case SimpleType.BigInt => node("big-int")
      case SimpleType.String => node("string")
      case SimpleType.Regex => node("regex")
      case SimpleType.Region => node("region")
      case SimpleType.Null => node("null")
      case SimpleType.Array(element) => node("array", simple(element))
      case SimpleType.Lazy(element) => node("lazy", simple(element))
      case SimpleType.Tuple(elements) => node("tuple", sequence(elements.map(simple)))
      case SimpleType.Enum(sym, args) => node("enum", symbol(sym), sequence(args.map(simple)))
      case SimpleType.Struct(sym, args) => node("struct", symbol(sym), sequence(args.map(simple)))
      case SimpleType.Arrow(args, result) => node("arrow", sequence(args.map(simple)), simple(result))
      case SimpleType.RecordEmpty => node("record-empty")
      case SimpleType.RecordExtend(_, _, _) => simpleRow(value, isRecord = true)
      case SimpleType.ExtensibleEmpty => node("extensible-empty")
      case SimpleType.ExtensibleExtend(_, _, _) => simpleRow(value, isRecord = false)
      case SimpleType.Native(clazz) => node("native", clazz.descriptorString())
    }

    private def simpleRow(value: SimpleType, isRecord: Boolean): String = {
      def collect(rest: SimpleType): (List[(String, List[SimpleType])], SimpleType) = rest match {
        case SimpleType.RecordExtend(label, field, tail) if isRecord =>
          val (fields, remainder) = collect(tail)
          ((label, List(field)) :: fields, remainder)
        case SimpleType.ExtensibleExtend(pred, args, tail) if !isRecord =>
          val (fields, remainder) = collect(tail)
          ((pred.name, args) :: fields, remainder)
        case _ => (Nil, rest)
      }
      val (fields, tail) = collect(value)
      val sorted = fields.sortBy(_._1).map { case (label, args) => node("field", label, sequence(args.map(simple))) }
      node(if (isRecord) "record-row" else "extensible-row", sequence(sorted), simple(tail))
    }

    private def named(tag: String, namespace: List[String], name: String): String =
      node(tag, sequence(namespace), name)

    private def generated(sym: Symbol): String =
      node("generated", Base64.getEncoder.encodeToString(origin(sym).bytes))

    private def symbol(sym: Symbol): String = sym match {
      case enum: Symbol.EnumSym =>
        if (enum.id.nonEmpty) node("enum", generated(enum)) else named("enum", enum.namespace, enum.text)
      case struct: Symbol.StructSym =>
        if (struct.id.nonEmpty) node("struct", generated(struct)) else named("struct", struct.namespace, struct.text)
      case enum: Symbol.RestrictableEnumSym => named("restrictable-enum", enum.namespace, enum.name)
      case caze: Symbol.RestrictableCaseSym => node("case", symbol(caze.enumSym), caze.name)
      case effect: Symbol.EffSym => named("effect", effect.namespace, effect.name)
      case traitSym: Symbol.TraitSym => named("trait", traitSym.namespace, traitSym.name)
      case assoc: Symbol.AssocTypeSym => node("associated-type", symbol(assoc.trt), assoc.name)
      case region: Symbol.RegionSym => node("region", generated(region))
      case _ => fail("Unsupported symbol in semantic type encoding.")
    }

    private def kind(value: Kind): String = value match {
      case Kind.Wild => node("wild")
      case Kind.WildCaseSet => node("wild-case-set")
      case Kind.Star => node("star")
      case Kind.Eff => node("eff")
      case Kind.Bool => node("bool")
      case Kind.RecordRow => node("record-row")
      case Kind.SchemaRow => node("schema-row")
      case Kind.Predicate => node("predicate")
      case Kind.Jvm => node("jvm")
      case Kind.CaseSet(sym) => node("case-set", symbol(sym))
      case Kind.Arrow(left, right) => node("arrow", kind(left), kind(right))
      case Kind.Error => fail("Erroneous kind has no semantic type key.")
    }

    private def associative(tc: TypeConstructor): Boolean = tc match {
      case TypeConstructor.Union | TypeConstructor.Intersection | TypeConstructor.SymmetricDiff |
           TypeConstructor.And | TypeConstructor.Or | TypeConstructor.CaseUnion(_) |
           TypeConstructor.CaseIntersection(_) | TypeConstructor.CaseSymmetricDiff(_) => true
      case _ => false
    }

    private def operands(value: Type, operator: TypeConstructor): List[Type] = value match {
      case Type.Alias(_, _, expanded, _) => operands(expanded, operator)
      case Type.Apply(Type.Apply(Type.Cst(tc, _), left, _), right, _) if tc == operator =>
        operands(left, operator) ::: operands(right, operator)
      case _ => List(value)
    }

    private def row(value: Type, isRecord: Boolean): String = {
      def collect(rest: Type): (List[(String, Type)], Type) = rest match {
        case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(label), _), field, _), tail, _) if isRecord =>
          val (fields, remainder) = collect(tail)
          ((label.name, field) :: fields, remainder)
        case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.SchemaRowExtend(pred), _), field, _), tail, _) if !isRecord =>
          val (fields, remainder) = collect(tail)
          ((pred.name, field) :: fields, remainder)
        case _ => (Nil, rest)
      }
      val (fields, tail) = collect(value)
      val sorted = fields.sortBy(_._1).map { case (label, field) => node("field", label, tpe(field)) }
      node(if (isRecord) "record-row" else "schema-row", sequence(sorted), tpe(tail))
    }

    def tpe(value: Type): String = value match {
      case Type.Var(sym, _) => bindings.get(sym) match {
        case Some(index) => node("var", number(index), kind(sym.kind))
        case None if lexical => node("inferred", kind(sym.kind))
        case None => fail("Unbound type variable.")
      }
      case Type.Cst(tc, _) => node("constant", constructor(tc))
      case Type.Alias(_, _, expanded, _) => tpe(expanded)
      case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(_), _), _, _), _, _) => row(value, isRecord = true)
      case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.SchemaRowExtend(_), _), _, _), _, _) => row(value, isRecord = false)
      case Type.Apply(Type.Apply(Type.Cst(tc, _), _, _), _, _) if associative(tc) =>
        val sorted = operands(value, tc).map(tpe).sorted
        val canonical = tc match {
          case TypeConstructor.SymmetricDiff | TypeConstructor.CaseSymmetricDiff(_) => sorted
          case _ => sorted.distinct
        }
        if (canonical.size == 1) canonical.head else node("set-operation", constructor(tc), sequence(canonical))
      case Type.Apply(left, right, _) => node("apply", tpe(left), tpe(right))
      case Type.AssocType(symUse, arg, resultKind, _) => node("assoc", symbol(symUse.sym), tpe(arg), kind(resultKind))
      case Type.JvmToType(inner, _) => node("jvm-to-type", tpe(inner))
      case Type.JvmToEff(inner, _) => node("jvm-to-eff", tpe(inner))
      case Type.UnresolvedJvmType(member, _) => member match {
        case Type.JvmMember.JvmConstructor(clazz, args) => node("unresolved-constructor", clazz.descriptorString(), sequence(args.map(tpe)))
        case Type.JvmMember.JvmField(_, receiver, name) => node("unresolved-field", tpe(receiver), name.name)
        case Type.JvmMember.JvmMethod(receiver, name, args) => node("unresolved-method", tpe(receiver), name.name, sequence(args.map(tpe)))
        case Type.JvmMember.JvmStaticMethod(clazz, name, args) =>
          node("unresolved-static-method", clazz.descriptorString(), name.name, sequence(args.map(tpe)))
      }
    }

    private def constructor(tc: TypeConstructor): String = tc match {
      case TypeConstructor.Void => node("void")
      case TypeConstructor.AnyType => node("any")
      case TypeConstructor.Unit => node("unit")
      case TypeConstructor.Null => node("null")
      case TypeConstructor.Bool => node("bool")
      case TypeConstructor.Char => node("char")
      case TypeConstructor.Float32 => node("float32")
      case TypeConstructor.Float64 => node("float64")
      case TypeConstructor.BigDecimal => node("big-decimal")
      case TypeConstructor.Int8 => node("int8")
      case TypeConstructor.Int16 => node("int16")
      case TypeConstructor.Int32 => node("int32")
      case TypeConstructor.Int64 => node("int64")
      case TypeConstructor.BigInt => node("big-int")
      case TypeConstructor.Str => node("string")
      case TypeConstructor.Regex => node("regex")
      case TypeConstructor.Arrow(arity) => node("arrow", number(arity))
      case TypeConstructor.ArrowWithoutEffect(arity) => node("arrow-without-effect", number(arity))
      case TypeConstructor.RecordRowEmpty => node("record-row-empty")
      case TypeConstructor.RecordRowExtend(label) => node("record-row-extend", label.name)
      case TypeConstructor.Record => node("record")
      case TypeConstructor.Extensible => node("extensible")
      case TypeConstructor.SchemaRowEmpty => node("schema-row-empty")
      case TypeConstructor.SchemaRowExtend(pred) => node("schema-row-extend", pred.name)
      case TypeConstructor.Schema => node("schema")
      case TypeConstructor.Sender => node("sender")
      case TypeConstructor.Receiver => node("receiver")
      case TypeConstructor.Lazy => node("lazy")
      case TypeConstructor.Enum(sym, declaredKind) => node("enum", symbol(sym), kind(declaredKind))
      case TypeConstructor.Struct(sym, declaredKind) => node("struct", symbol(sym), kind(declaredKind))
      case TypeConstructor.RestrictableEnum(sym, declaredKind) => node("restrictable-enum", symbol(sym), kind(declaredKind))
      case TypeConstructor.Native(desc, arity) => node("native", desc.descriptorString(), number(arity))
      case TypeConstructor.JvmConstructor(method) => node("jvm-constructor", javaMethod(method, Nil))
      case TypeConstructor.JvmMethod(method, parameters) => node("jvm-method", javaMethod(method, parameters))
      case TypeConstructor.JvmField(field) => node("jvm-field", javaField(field))
      case TypeConstructor.Array => node("array")
      case TypeConstructor.ArrayWithoutRegion => node("array-without-region")
      case TypeConstructor.Vector => node("vector")
      case TypeConstructor.Tuple(arity) => node("tuple", number(arity))
      case TypeConstructor.Relation(arity) => node("relation", number(arity))
      case TypeConstructor.Lattice(arity) => node("lattice", number(arity))
      case TypeConstructor.True => node("true")
      case TypeConstructor.False => node("false")
      case TypeConstructor.Not => node("not")
      case TypeConstructor.And => node("and")
      case TypeConstructor.Or => node("or")
      case TypeConstructor.Pure => node("pure")
      case TypeConstructor.Univ => node("univ")
      case TypeConstructor.Complement => node("complement")
      case TypeConstructor.Union => node("union")
      case TypeConstructor.Intersection => node("intersection")
      case TypeConstructor.Difference => node("difference")
      case TypeConstructor.SymmetricDiff => node("symmetric-diff")
      case TypeConstructor.Effect(sym, declaredKind) => node("effect", symbol(sym), kind(declaredKind))
      case TypeConstructor.CaseComplement(sym) => node("case-complement", symbol(sym))
      case TypeConstructor.CaseUnion(sym) => node("case-union", symbol(sym))
      case TypeConstructor.CaseIntersection(sym) => node("case-intersection", symbol(sym))
      case TypeConstructor.CaseSymmetricDiff(sym) => node("case-symmetric-diff", symbol(sym))
      case TypeConstructor.CaseSet(syms, enumSym) => node("case-set", symbol(enumSym), sequence(syms.iterator.map(symbol).toList.sorted))
      case TypeConstructor.Region(sym) => node("region", symbol(sym))
      case TypeConstructor.RegionToStar => node("region-to-star")
      case TypeConstructor.RegionWithoutRegion => node("region-without-region")
      case TypeConstructor.Error(_, _) => fail("Erroneous type has no semantic type key.")
    }

    private def methodRef(ref: JavaMethodRef): String =
      node("method-ref", ref.owner.descriptorString(), ref.name, ref.descriptor.descriptorString(), String.valueOf(ref.isInterface))

    private def javaVariable(variable: JavaTypeVariable, parameters: Map[JavaTypeVariable, Int]): String = parameters.get(variable) match {
      case Some(index) => node("bound", number(index))
      case None =>
        val owner = variable.owner match {
          case JavaTypeVariableOwner.Class(desc) => node("class", desc.descriptorString())
          case JavaTypeVariableOwner.Method(ref) => node("method", methodRef(ref))
          case JavaTypeVariableOwner.Unknown => fail("Java type variable has no declaring origin.")
        }
        node("java-variable", owner, variable.name)
    }

    private def javaType(value: JavaType, parameters: Map[JavaTypeVariable, Int]): String = {
      def encodeType(inner: JavaType): String = javaType(inner, parameters)
      value match {
        case JavaType.NonGeneric(erasure) => node("non-generic", erasure.descriptorString())
        case JavaType.GenericArray(component, erasure) => node("generic-array", encodeType(component), erasure.descriptorString())
        case JavaType.Parameterized(erasure, args) => node("parameterized", erasure.descriptorString(), sequence(args.map(encodeType)))
        case JavaType.Variable(variable, erasure) => node("variable", javaVariable(variable, parameters), erasure.descriptorString())
        case JavaType.Wildcard(upper, lower, erasure) =>
          node("wildcard", sequence(upper.map(encodeType).distinct.sorted), sequence(lower.map(encodeType).distinct.sorted), erasure.descriptorString())
      }
    }

    private def javaMethod(method: JavaMethod, classParameters: List[JavaTypeParameter]): String = {
      val parameters = (classParameters ::: method.typeParameters).map(_.variable).zipWithIndex.toMap
      def bounds(parameter: JavaTypeParameter): String = sequence(parameter.upperBounds.map(javaType(_, parameters)).distinct.sorted)
      node("method", methodRef(method.ref), number(method.modifiers), sequence(classParameters.map(bounds)),
        sequence(method.typeParameters.map(bounds)), sequence(method.parameterTypes.map(javaType(_, parameters))),
        javaType(method.returnType, parameters), String.valueOf(method.isConstructor), String.valueOf(method.isVarArgs))
    }

    private def javaField(field: JavaField): String =
      node("field", field.ref.owner.descriptorString(), field.ref.name, field.ref.descriptor.descriptorString(),
        number(field.modifiers), javaType(field.fieldType, Map.empty))
  }
}
