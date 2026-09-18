/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.SimpleType
import ca.uwaterloo.flix.language.jvm.JavaClasses
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.classes.{GenTag, GenTagged}
import org.objectweb.asm.MethodVisitor

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.{CD_Object, CD_boolean, CD_byte, CD_char, CD_double, CD_float, CD_int, CD_long, CD_short}

/**
  * How an exported Flix value is represented to a Java caller.
  *
  * The initial plan is intentionally exact-only. Converted containers add plan nodes here, and
  * every such node must implement bytecode emission before `EntryPoints` admits its source type.
  */
sealed trait ExportPlan {
  def flixType: ClassDesc

  def signature: ExportSignature

  final def javaType: ClassDesc = signature.javaType

  /** Converts the Flix value currently on the operand stack to its Java representation. */
  def emit()(implicit mv: MethodVisitor): Unit
}

object ExportPlan {

  private val Optional = ClassDesc.ofInternalName("java/util/Optional")

  private val Wrappers: Map[ClassDesc, ClassDesc] = Map(
    CD_boolean -> ClassDesc.ofInternalName("java/lang/Boolean"),
    CD_char -> ClassDesc.ofInternalName("java/lang/Character"),
    CD_byte -> ClassDesc.ofInternalName("java/lang/Byte"),
    CD_short -> ClassDesc.ofInternalName("java/lang/Short"),
    CD_int -> ClassDesc.ofInternalName("java/lang/Integer"),
    CD_long -> ClassDesc.ofInternalName("java/lang/Long"),
    CD_float -> ClassDesc.ofInternalName("java/lang/Float"),
    CD_double -> ClassDesc.ofInternalName("java/lang/Double"),
  )

  /** A value whose Flix and Java representations are identical. */
  case class Identity(flixType: ClassDesc) extends ExportPlan {
    override def signature: ExportSignature = ExportSignature.Exact(flixType)

    override def emit()(implicit mv: MethodVisitor): Unit = ()
  }

  /** A primitive element boxed for a reference-only Java container. */
  case class Boxed(flixType: ClassDesc, boxed: ClassDesc) extends ExportPlan {
    override def signature: ExportSignature = ExportSignature.Boxed(flixType, boxed)

    override def emit()(implicit mv: MethodVisitor): Unit =
      INVOKESTATIC(boxed, "valueOf", MethodTypeDescs.mkDescriptor(flixType)(boxed))
  }

  /** A Flix `Option` result converted to `java.util.Optional`. */
  case class AsOptional(element: ExportPlan, noneOrdinal: Int, someFields: List[ClassDesc]) extends ExportPlan {
    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Applied(Optional, List(element.signature))

    override def emit()(implicit mv: MethodVisitor): Unit = {
      DUP()
      GETFIELD(GenTagged.OrdinalField)
      pushInt(noneOrdinal)
      ifConditionElse(Condition.ICMPEQ) {
        POP()
        INVOKESTATIC(Optional, "empty", MethodTypeDescs.mkDescriptor()(Optional))
      } {
        CHECKCAST(GenTag.desc(someFields))
        GETFIELD(GenTag.IndexField(someFields, 0))
        element.emit()
        INVOKESTATIC(Optional, "ofNullable", MethodTypeDescs.mkDescriptor(CD_Object)(Optional))
      }
    }
  }

  /** Returns the exact boundary plan currently supported for `tpe`. */
  def exact(tpe: SimpleType): Option[ExportPlan] = tpe match {
    case SimpleType.Bool => Some(Identity(CD_boolean))
    case SimpleType.Char => Some(Identity(CD_char))
    case SimpleType.Int8 => Some(Identity(CD_byte))
    case SimpleType.Int16 => Some(Identity(CD_short))
    case SimpleType.Int32 => Some(Identity(CD_int))
    case SimpleType.Int64 => Some(Identity(CD_long))
    case SimpleType.Float32 => Some(Identity(CD_float))
    case SimpleType.Float64 => Some(Identity(CD_double))
    case SimpleType.String => Some(Identity(JavaClasses.String))
    case SimpleType.Native(clazz) => Some(Identity(clazz))
    case SimpleType.AnyType => Some(Identity(CD_Object))
    case _ => None
  }

  /** Returns the caller-visible signature derivable without compilation state. */
  def signatureOf(tpe: SimpleType): Option[ExportSignature] = tpe match {
    case SimpleType.Enum(sym, List(element)) if isOption(sym) =>
      typeArgumentPlan(element).map(sig => ExportSignature.Applied(Optional, List(sig)))
    case _ => exact(tpe).map(_.signature)
  }

  /** Returns the executable conversion plan for an exported definition's result. */
  def ofDef(defn: ca.uwaterloo.flix.language.ast.JvmAst.Def)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    if (!defn.ann.isExport) None
    else defn.exportedReturnType.flatMap {
      case SimpleType.Enum(sym, List(element)) if isOption(sym) => optionPlan(element, defn.unboxedType.tpe)
      case declared => exact(declared)
    }

  /** Builds an Optional conversion from the specialized enum retained by erasure. */
  private def optionPlan(element: SimpleType, erased: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = erased match {
    case SimpleType.Enum(sym, Nil) =>
      val cases = root.enums(sym).cases.values
      for {
        none <- cases.find(_.sym.name == "None")
        some <- cases.find(c => c.sym.name == "Some" && c.tpes.lengthCompare(1) == 0)
        elementPlan <- elementPlan(element, TypeDescs.toClassDesc(some.tpes.head))
      } yield AsOptional(elementPlan, none.sym.ordinal, some.tpes.map(TypeDescs.toClassDesc))
    case _ => None
  }

  /** Returns a plan for a value placed in a Java reference-only type argument position. */
  private def elementPlan(declared: SimpleType, erased: ClassDesc): Option[ExportPlan] =
    Wrappers.get(erased).map(Boxed(erased, _)).orElse(exact(declared))

  private def typeArgumentPlan(tpe: SimpleType): Option[ExportSignature] =
    Wrappers.get(TypeDescs.toErasedClassDesc(tpe)).map(ExportSignature.Boxed(TypeDescs.toErasedClassDesc(tpe), _))
      .orElse(exact(tpe).map(_.signature))

  private def isOption(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Option"
}
