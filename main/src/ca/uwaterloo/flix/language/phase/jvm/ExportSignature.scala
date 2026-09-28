/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import java.lang.constant.ClassDesc

/**
  * The Java-facing name of a value crossing an export boundary.
  *
  * This describes only the caller-visible type. It does not authorize exporting that type and it
  * does not describe how a Flix runtime value is converted into it. Keeping those responsibilities
  * separate lets syntax-only stub generation share this naming model without pretending codegen
  * can perform a conversion it has not implemented.
  */
sealed trait ExportSignature {

  /** The erased JVM type used by a method descriptor. */
  def javaType: ClassDesc

  /** The descriptor contributed when this signature appears as a generic type argument. */
  def typeArgument: String

  /** The spelling used in generated Java source. */
  final def sourceName: String = sourceNameWith(ExportSignature.qualifiedName)

  /**
    * The spelling used in generated Java source, with every class spelled by `spell`.
    *
    * A class may have to be spelled other than by its qualified name: inside a class `Acme`, the
    * name `Acme.Color` means a member of that class, never the package `Acme`.
    */
  def sourceNameWith(spell: ClassDesc => String): String

  /** Every class this signature names, type arguments included. */
  def classes: List[ClassDesc]
}

object ExportSignature {

  /** A type whose Flix and Java representations are identical. */
  case class Exact(javaType: ClassDesc) extends ExportSignature {
    override def typeArgument: String = javaType.descriptorString()

    override def sourceNameWith(spell: ClassDesc => String): String = sourceNameOf(javaType, spell)

    override def classes: List[ClassDesc] = classesOf(javaType)
  }

  /** A primitive in a reference-only position, represented by its Java box. */
  case class Boxed(primitive: ClassDesc, boxed: ClassDesc) extends ExportSignature {
    override def javaType: ClassDesc = boxed

    override def typeArgument: String = boxed.descriptorString()

    override def sourceNameWith(spell: ClassDesc => String): String = sourceNameOf(boxed, spell)

    override def classes: List[ClassDesc] = classesOf(boxed)
  }

  /** A Java class whose descriptor erases, but whose source and generic signature retain, arguments. */
  case class Applied(clazz: ClassDesc, targs: List[ExportSignature]) extends ExportSignature {
    override def javaType: ClassDesc = clazz

    override def typeArgument: String = {
      val descriptor = clazz.descriptorString()
      if (targs.isEmpty) descriptor
      else descriptor.stripSuffix(";") + targs.map(_.typeArgument).mkString("<", "", ">;")
    }

    override def sourceNameWith(spell: ClassDesc => String): String = {
      val name = sourceNameOf(clazz, spell)
      if (targs.isEmpty) name else s"$name<${targs.map(_.sourceNameWith(spell)).mkString(", ")}>"
    }

    override def classes: List[ClassDesc] = classesOf(clazz) ++ targs.flatMap(_.classes)
  }

  /** Returns the class `tpe` names, if any: none for a primitive, its element's for an array. */
  private def classesOf(tpe: ClassDesc): List[ClassDesc] =
    if (tpe.isPrimitive) Nil
    else if (tpe.isArray) classesOf(tpe.componentType())
    else List(tpe)

  /** Returns the qualified Java-source name of the class `tpe`, such as `java.util.List`. */
  def qualifiedName(tpe: ClassDesc): String = {
    val descriptor = tpe.descriptorString()
    descriptor.substring(1, descriptor.length - 1).replace('/', '.')
  }

  /** Converts a JVM type descriptor to its Java-source spelling, with classes spelled by `spell`. */
  private def sourceNameOf(tpe: ClassDesc, spell: ClassDesc => String): String = tpe.descriptorString() match {
    case "Z" => "boolean"
    case "C" => "char"
    case "B" => "byte"
    case "S" => "short"
    case "I" => "int"
    case "J" => "long"
    case "F" => "float"
    case "D" => "double"
    case "V" => "void"
    case descriptor if descriptor.startsWith("[") => sourceNameOf(tpe.componentType(), spell) + "[]"
    case descriptor if descriptor.startsWith("L") && descriptor.endsWith(";") => spell(tpe)
    case descriptor => throw new IllegalArgumentException(s"Unsupported JVM type descriptor: $descriptor")
  }
}
