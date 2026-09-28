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
  * A real `java.lang.Record` generated for an exported tuple's Java-facing element types, built
  * by [[GenExportedProduct]].
  *
  * One class is shared by every exported tuple with the same element types, the same way the
  * compiler's own internal `Tuple` class is shared by shape. Component names are synthetic
  * (`component0`, `component1`, ...) since a Flix tuple carries no field names.
  */
object GenExportedTuple {

  /** Returns the class shared by every exported tuple whose Java-facing elements are `elms`. */
  def desc(elms: List[ClassDesc]): ClassDesc =
    mkDesc(DevFlixGen, mkClassName("Tuple", elms.map(sourceSafeName)))

  def genByteCode(elms: List[ClassDesc])(implicit flix: Flix): Array[Byte] =
    GenExportedProduct.genByteCode(desc(elms), components(elms))

  def IndexField(elms: List[ClassDesc], i: Int): InstanceField =
    GenExportedProduct.IndexField(desc(elms), componentName(i), elms(i))

  def Constructor(elms: List[ClassDesc]): ConstructorMethod = GenExportedProduct.Constructor(desc(elms), elms)

  def AccessorMethod(elms: List[ClassDesc], i: Int): InstanceMethod =
    GenExportedProduct.AccessorMethod(desc(elms), componentName(i), elms(i))

  private def componentName(i: Int): String = s"component$i"

  private def components(elms: List[ClassDesc]): List[(String, ClassDesc)] =
    elms.zipWithIndex.map { case (tpe, i) => componentName(i) -> tpe }

  /** Returns a valid class-name segment naming `elm` precisely, unlike the coarse erased name shared by every reference type. */
  private def sourceSafeName(elm: ClassDesc): String =
    elm.displayName().replaceAll("[^a-zA-Z0-9]", "_")
}
