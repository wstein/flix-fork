/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.SimpleType
import ca.uwaterloo.flix.language.jvm.JavaClasses
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

  /** A value whose Flix and Java representations are identical. */
  case class Identity(flixType: ClassDesc) extends ExportPlan {
    override def signature: ExportSignature = ExportSignature.Exact(flixType)

    override def emit()(implicit mv: MethodVisitor): Unit = ()
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
}
