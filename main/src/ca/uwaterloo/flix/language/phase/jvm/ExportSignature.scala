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
  def sourceName: String
}

object ExportSignature {

  /** A type whose Flix and Java representations are identical. */
  case class Exact(javaType: ClassDesc) extends ExportSignature {
    override def typeArgument: String = javaType.descriptorString()

    override def sourceName: String = sourceNameOf(javaType)
  }

  /** A primitive in a reference-only position, represented by its Java box. */
  case class Boxed(primitive: ClassDesc, boxed: ClassDesc) extends ExportSignature {
    override def javaType: ClassDesc = boxed

    override def typeArgument: String = boxed.descriptorString()

    override def sourceName: String = sourceNameOf(boxed)
  }

  /** A Java class whose descriptor erases, but whose source and generic signature retain, arguments. */
  case class Applied(clazz: ClassDesc, targs: List[ExportSignature]) extends ExportSignature {
    override def javaType: ClassDesc = clazz

    override def typeArgument: String = {
      val descriptor = clazz.descriptorString()
      if (targs.isEmpty) descriptor
      else descriptor.stripSuffix(";") + targs.map(_.typeArgument).mkString("<", "", ">;")
    }

    override def sourceName: String = {
      val name = sourceNameOf(clazz)
      if (targs.isEmpty) name else s"$name<${targs.map(_.sourceName).mkString(", ")}>"
    }
  }

  /** Converts a JVM class descriptor to its Java-source spelling. */
  private def sourceNameOf(tpe: ClassDesc): String = tpe.descriptorString() match {
    case "Z" => "boolean"
    case "C" => "char"
    case "B" => "byte"
    case "S" => "short"
    case "I" => "int"
    case "J" => "long"
    case "F" => "float"
    case "D" => "double"
    case "V" => "void"
    case descriptor if descriptor.startsWith("[") => sourceNameOf(tpe.componentType()) + "[]"
    case descriptor if descriptor.startsWith("L") && descriptor.endsWith(";") =>
      descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    case descriptor => throw new IllegalArgumentException(s"Unsupported JVM type descriptor: $descriptor")
  }
}
