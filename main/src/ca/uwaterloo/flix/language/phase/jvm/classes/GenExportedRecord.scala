/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm.classes

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.{ConstructorMethod, InstanceField, InstanceMethod}
import ca.uwaterloo.flix.language.phase.jvm.Mangle.{DevFlixGen, mkClassName, mkDesc}

import java.lang.constant.ClassDesc

/**
  * A real `java.lang.Record` generated for an exported structural record's fields, built by
  * [[GenExportedProduct]].
  *
  * One class is shared by every exported record with the same fields in the same order, the way
  * `GenExportedTuple` shares one class by element shape. Unlike a tuple, a structural record
  * names its fields, so the generated record's components keep those names instead of a
  * synthetic `component0`-style name.
  */
object GenExportedRecord {

  /** Returns the class shared by every exported record whose Java-facing fields are `fields`. */
  def desc(fields: List[(String, ClassDesc)]): ClassDesc =
    mkDesc(DevFlixGen, mkClassName("Record", fields.flatMap { case (label, tpe) => List(label, sourceSafeName(tpe)) }))

  def genByteCode(fields: List[(String, ClassDesc)])(implicit flix: Flix): Array[Byte] =
    GenExportedProduct.genByteCode(desc(fields), fields)

  def IndexField(fields: List[(String, ClassDesc)], i: Int): InstanceField = {
    val (label, tpe) = fields(i)
    GenExportedProduct.IndexField(desc(fields), label, tpe)
  }

  def Constructor(fields: List[(String, ClassDesc)]): ConstructorMethod =
    GenExportedProduct.Constructor(desc(fields), fields.map(_._2))

  def AccessorMethod(fields: List[(String, ClassDesc)], i: Int): InstanceMethod = {
    val (label, tpe) = fields(i)
    GenExportedProduct.AccessorMethod(desc(fields), label, tpe)
  }

  /** Returns a valid class-name segment naming `elm` precisely, unlike the coarse erased name shared by every reference type. */
  private def sourceSafeName(elm: ClassDesc): String =
    elm.displayName().replaceAll("[^a-zA-Z0-9]", "_")
}
