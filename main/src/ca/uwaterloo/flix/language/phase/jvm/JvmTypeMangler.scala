package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SimpleType, SourceLocation, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.util.InternalCompilerException

import java.nio.charset.StandardCharsets
import scala.collection.mutable

/** Readable, framed spellings recorded beside specialization provenance. */
object JvmTypeMangler {
  def monomorph(args: List[Type], origin: Symbol => GeneratedJvmKey): String = {
    val encoder = new Encoder(origin)
    "I" + args.map(encoder.tpe).mkString + "E"
  }

  def erasure(args: List[SimpleType]): String = args.map(atom).mkString("$")

  private def atom(tpe: SimpleType): String = tpe match {
    case SimpleType.Bool => "Bool"
    case SimpleType.Char => "Char"
    case SimpleType.Float32 => "Float32"
    case SimpleType.Float64 => "Float64"
    case SimpleType.Int8 => "Int8"
    case SimpleType.Int16 => "Int16"
    case SimpleType.Int32 => "Int32"
    case SimpleType.Int64 => "Int64"
    case _ => "Obj"
  }

  /** Escape before length-prefixing: Mangle must not change a frame's byte length later. */
  private def framed(s: String): String = {
    val escaped = s.getBytes(StandardCharsets.UTF_8).iterator.map { byte =>
      val value = byte & 0xff
      if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
          (value >= '0' && value <= '9') || value == '_') value.toChar.toString
      else "$" + f"$value%02x"
    }.mkString
    escaped.length.toString + escaped
  }

  private def hex(bytes: Array[Byte]): String = bytes.iterator.map(b => f"${b & 0xff}%02x").mkString

  private class Encoder(origin: Symbol => GeneratedJvmKey) {
    private val substitutions = mutable.LinkedHashMap.empty[String, Int]

    def tpe(value: Type): String = {
      val t = Type.eraseAliases(value)
      val identity = JvmTypeKey.encode(t, Nil, origin)
      substitutions.get(identity) match {
        case Some(0) => "S_"
        case Some(n) => "S" + Integer.toString(n - 1, 36) + "_"
        case None =>
          val spelling = raw(t)
          substitutions(identity) = substitutions.size
          spelling
      }
    }

    private def raw(value: Type): String = value match {
      case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(_), _), _, _), _, _) => row(value, isRecord = true)
      case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.SchemaRowExtend(_), _), _, _), _, _) => row(value, isRecord = false)
      case Type.Apply(Type.Apply(Type.Cst(tc, _), _, _), _, _) if commutative(tc) => canonicalSet(value, tc)
      case Type.Apply(_, _, _) =>
        val (head, args) = flatten(value)
        raw(head) + "I" + args.map(tpe).mkString + "E"
      case Type.Cst(tc, _) => tc match {
        case TypeConstructor.Void => framed("Void")
        case TypeConstructor.AnyType => framed("Any")
        case TypeConstructor.Unit => framed("Unit")
        case TypeConstructor.Null => framed("Null")
        case TypeConstructor.Bool => framed("Bool")
        case TypeConstructor.Char => framed("Char")
        case TypeConstructor.Float32 => framed("Float32")
        case TypeConstructor.Float64 => framed("Float64")
        case TypeConstructor.BigDecimal => framed("BigDecimal")
        case TypeConstructor.Int8 => framed("Int8")
        case TypeConstructor.Int16 => framed("Int16")
        case TypeConstructor.Int32 => framed("Int32")
        case TypeConstructor.Int64 => framed("Int64")
        case TypeConstructor.BigInt => framed("BigInt")
        case TypeConstructor.Str => framed("String")
        case TypeConstructor.Regex => framed("Regex")
        case TypeConstructor.Arrow(arity) => "F" + arity + "_"
        case TypeConstructor.ArrowWithoutEffect(arity) => "W" + arity + "_"
        case TypeConstructor.Array => framed("Array")
        case TypeConstructor.ArrayWithoutRegion => framed("ArrayWithoutRegion")
        case TypeConstructor.Vector => framed("Vector")
        case TypeConstructor.Lazy => framed("Lazy")
        case TypeConstructor.Tuple(arity) => "T" + arity + "_"
        case TypeConstructor.Relation(arity) => "R" + arity + "_"
        case TypeConstructor.Lattice(arity) => "L" + arity + "_"
        case TypeConstructor.Record => framed("Record")
        case TypeConstructor.Schema => framed("Schema")
        case TypeConstructor.Extensible => framed("Extensible")
        case TypeConstructor.RecordRowEmpty => framed("RecordRowEmpty")
        case TypeConstructor.SchemaRowEmpty => framed("SchemaRowEmpty")
        case TypeConstructor.Sender => framed("Sender")
        case TypeConstructor.Receiver => framed("Receiver")
        case TypeConstructor.Pure => framed("Pure")
        case TypeConstructor.Univ => framed("Univ")
        case TypeConstructor.True => framed("True")
        case TypeConstructor.False => framed("False")
        case TypeConstructor.Not => framed("Not")
        case TypeConstructor.And => framed("And")
        case TypeConstructor.Or => framed("Or")
        case TypeConstructor.Union => framed("Union")
        case TypeConstructor.Intersection => framed("Intersection")
        case TypeConstructor.Complement => framed("Complement")
        case TypeConstructor.Difference => framed("Difference")
        case TypeConstructor.SymmetricDiff => framed("SymmetricDiff")
        case TypeConstructor.Enum(sym, _) => nominal(sym.namespace, sym.text, sym.id.nonEmpty, sym)
        case TypeConstructor.Struct(sym, _) => nominal(sym.namespace, sym.text, sym.id.nonEmpty, sym)
        case TypeConstructor.RestrictableEnum(sym, _) => nominal(sym.namespace, sym.name, generated = false, sym)
        case TypeConstructor.Effect(sym, _) => "Q" + nominal(sym.namespace, sym.name, generated = false, sym)
        case TypeConstructor.Native(desc, _) =>
          val descriptor = desc.descriptorString()
          if (descriptor.startsWith("L") && descriptor.endsWith(";"))
            "J" + "N" + descriptor.substring(1, descriptor.length - 1).split('/').map(framed).mkString + "E"
          else "J" + framed(hex(descriptor.getBytes(StandardCharsets.UTF_8)))
        case other => "K" + framed(other.getClass.getSimpleName.stripSuffix("$")) + fallback(value)
      }
      case _ => fallback(value)
    }

    private def nominal(namespace: List[String], name: String, generated: Boolean, sym: Symbol): String = {
      if (generated) "G" + framed(hex(origin(sym).bytes))
      else if (namespace.isEmpty) framed(name)
      else "N" + (namespace :+ name).map(framed).mkString + "E"
    }

    private def fallback(value: Type): String =
      "X" + framed(hex(JvmTypeKey.encode(value, Nil, origin).getBytes(StandardCharsets.UTF_8)))

    private def commutative(tc: TypeConstructor): Boolean = tc match {
      case TypeConstructor.Union | TypeConstructor.Intersection | TypeConstructor.SymmetricDiff |
           TypeConstructor.And | TypeConstructor.Or => true
      case _ => false
    }

    private def canonicalSet(value: Type, operator: TypeConstructor): String = {
      def operands(tpe: Type): List[Type] = tpe match {
        case Type.Alias(_, _, expanded, _) => operands(expanded)
        case Type.Apply(Type.Apply(Type.Cst(tc, _), left, _), right, _) if tc == operator =>
          operands(left) ::: operands(right)
        case other => List(other)
      }
      val keyed = operands(value).map(tpe => JvmTypeKey.encode(tpe, Nil, origin) -> tpe).sortBy(_._1)
      val canonical = if (operator == TypeConstructor.SymmetricDiff) keyed else keyed.distinctBy(_._1)
      if (canonical.lengthIs == 1) raw(canonical.head._2)
      else "O" + framed(operator.getClass.getSimpleName.stripSuffix("$")) +
        "I" + canonical.map { case (_, tpe0) => tpe(tpe0) }.mkString + "E"
    }

    private def row(value: Type, isRecord: Boolean): String = {
      def collect(rest: Type): (List[(String, Type)], Type) = rest match {
        case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(label), _), field, _), tail, _) if isRecord =>
          val (fields, remainder) = collect(tail)
          ((label.name, field) :: fields, remainder)
        case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.SchemaRowExtend(pred), _), field, _), tail, _) if !isRecord =>
          val (fields, remainder) = collect(tail)
          ((pred.name, field) :: fields, remainder)
        case other => (Nil, other)
      }
      val (fields, tail) = collect(value)
      val ordered = fields.sortBy(_._1).map { case (label, field) => "Y" + framed(label) + tpe(field) }.mkString
      (if (isRecord) "B" else "H") + "I" + ordered + "Z" + tpe(tail) + "E"
    }

    private def flatten(value: Type): (Type, List[Type]) = {
      def loop(t: Type, args: List[Type]): (Type, List[Type]) = t match {
        case Type.Apply(head, arg, _) => loop(head, arg :: args)
        case head => (head, args)
      }
      loop(value, Nil)
    }
  }
}
