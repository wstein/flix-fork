/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm.classes

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.jvm.JavaClasses
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.IsFinal
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Visibility.{IsPrivate, IsPublic}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Volatility.NotVolatile
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.{ConstructorMethod, InstanceField, InstanceMethod}
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.Mangle.{DevFlixGen, mkClassName, mkDesc}
import ca.uwaterloo.flix.language.phase.jvm.{ClassMaker, MethodTypeDescs}
import org.objectweb.asm.MethodVisitor

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.{CD_Object, CD_boolean, CD_byte, CD_char, CD_double, CD_float, CD_int, CD_long, CD_short}

/**
  * A real `java.lang.Record` generated for an exported tuple's Java-facing element types.
  *
  * One class is shared by every exported tuple with the same element types, the same way the
  * compiler's own internal `Tuple` class is shared by shape. Component names are synthetic
  * (`component0`, `component1`, ...) since a Flix tuple carries no field names.
  */
object GenExportedTuple {

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

  /** Returns the class shared by every exported tuple whose Java-facing elements are `elms`. */
  def desc(elms: List[ClassDesc]): ClassDesc =
    mkDesc(DevFlixGen, mkClassName("Tuple", elms.map(sourceSafeName)))

  def genByteCode(elms: List[ClassDesc])(implicit flix: Flix): Array[Byte] = {
    val cm = ClassMaker.mkClass(desc(elms), IsFinal, superClass = JavaClasses.Record)

    for (i <- elms.indices) {
      cm.mkField(IndexField(elms, i), IsPrivate, IsFinal, NotVolatile)
      cm.mkRecordComponent(componentName(i), elms(i))
    }
    cm.mkConstructor(Constructor(elms), IsPublic, constructorIns(elms)(_))
    for (i <- elms.indices) {
      cm.mkMethod(Nil, AccessorMethod(elms, i), IsPublic, IsFinal, accessorIns(elms, i)(_))
    }
    cm.mkMethod(Nil, EqualsMethod(elms), IsPublic, IsFinal, equalsIns(elms)(_))
    cm.mkMethod(Nil, HashCodeMethod(elms), IsPublic, IsFinal, hashCodeIns(elms)(_))
    cm.mkMethod(Nil, ToStringMethod(elms), IsPublic, IsFinal, toStringIns(elms)(_))

    cm.closeClassMaker()
  }

  private def componentName(i: Int): String = s"component$i"

  def IndexField(elms: List[ClassDesc], i: Int): InstanceField = InstanceField(desc(elms), componentName(i), elms(i))

  def Constructor(elms: List[ClassDesc]): ConstructorMethod = ConstructorMethod(desc(elms), elms)

  def AccessorMethod(elms: List[ClassDesc], i: Int): InstanceMethod =
    InstanceMethod(desc(elms), componentName(i), MethodTypeDescs.mkDescriptor()(elms(i)))

  private def EqualsMethod(elms: List[ClassDesc]): InstanceMethod =
    InstanceMethod(desc(elms), "equals", MethodTypeDescs.mkDescriptor(CD_Object)(CD_boolean))

  private def HashCodeMethod(elms: List[ClassDesc]): InstanceMethod =
    InstanceMethod(desc(elms), "hashCode", MethodTypeDescs.mkDescriptor()(CD_int))

  private def ToStringMethod(elms: List[ClassDesc]): InstanceMethod =
    InstanceMethod(desc(elms), "toString", MethodTypeDescs.mkDescriptor()(JavaClasses.String))

  /** `[] --> return` */
  private def constructorIns(elms: List[ClassDesc])(implicit mv: MethodVisitor): Unit =
    withNames(1, elms) { case (_, variables) =>
      thisLoad()
      INVOKESPECIAL(ConstructorMethod(JavaClasses.Record, Nil))
      for ((elm, i) <- variables.zipWithIndex) {
        thisLoad()
        elm.load()
        PUTFIELD(IndexField(elms, i))
      }
      RETURN()
    }

  /** `[] --> return` */
  private def accessorIns(elms: List[ClassDesc], i: Int)(implicit mv: MethodVisitor): Unit = {
    thisLoad()
    GETFIELD(IndexField(elms, i))
    xReturn(elms(i))
  }

  /** `[other: Object] --> return` */
  private def equalsIns(elms: List[ClassDesc])(implicit mv: MethodVisitor): Unit =
    withName(1, CD_Object) { other =>
      thisLoad()
      other.load()
      ifCondition(Condition.ACMPEQ) {
        ICONST_1()
        IRETURN()
      }
      other.load()
      INSTANCEOF(desc(elms))
      ifCondition(Condition.EQ) {
        ICONST_0()
        IRETURN()
      }
      withName(2, desc(elms)) { that =>
        other.load()
        CHECKCAST(desc(elms))
        that.store()
        for ((elm, i) <- elms.zipWithIndex) {
          thisLoad()
          GETFIELD(IndexField(elms, i))
          that.load()
          GETFIELD(IndexField(elms, i))
          notEqual(elm) {
            ICONST_0()
            IRETURN()
          }
        }
      }
      ICONST_1()
      IRETURN()
    }

  /** `[self: elm, other: elm] --> []`, running `notEqualBranch` when the two are unequal. */
  private def notEqual(elm: ClassDesc)(notEqualBranch: => Unit)(implicit mv: MethodVisitor): Unit = elm match {
    case CD_boolean | CD_char | CD_byte | CD_short | CD_int =>
      ifCondition(Condition.ICMPNE)(notEqualBranch)
    case CD_long =>
      LCMP()
      ifCondition(Condition.NE)(notEqualBranch)
    case CD_float =>
      FCMPG()
      ifCondition(Condition.NE)(notEqualBranch)
    case CD_double =>
      DCMPG()
      ifCondition(Condition.NE)(notEqualBranch)
    case _ =>
      INVOKESTATIC(JavaClasses.Objects, "equals", MethodTypeDescs.mkDescriptor(CD_Object, CD_Object)(CD_boolean))
      ifCondition(Condition.EQ)(notEqualBranch)
  }

  /** `[] --> return` */
  private def hashCodeIns(elms: List[ClassDesc])(implicit mv: MethodVisitor): Unit =
    withName(1, CD_int) { h =>
      ICONST_0()
      h.store()
      for ((elm, i) <- elms.zipWithIndex) {
        h.load()
        pushInt(31)
        IMUL()
        thisLoad()
        GETFIELD(IndexField(elms, i))
        elementHash(elm)
        IADD()
        h.store()
      }
      h.load()
      IRETURN()
    }

  /** `[v: elm] --> [hash: int]` */
  private def elementHash(elm: ClassDesc)(implicit mv: MethodVisitor): Unit = Wrappers.get(elm) match {
    case Some(boxed) => INVOKESTATIC(boxed, "hashCode", MethodTypeDescs.mkDescriptor(elm)(CD_int))
    case None => INVOKESTATIC(JavaClasses.Objects, "hashCode", MethodTypeDescs.mkDescriptor(CD_Object)(CD_int))
  }

  /** `[] --> return` */
  private def toStringIns(elms: List[ClassDesc])(implicit mv: MethodVisitor): Unit = {
    val StringBuilder = JavaClasses.StringBuilder
    NEW(StringBuilder)
    DUP()
    INVOKESPECIAL(ConstructorMethod(StringBuilder, Nil))
    appendConst(desc(elms).displayName() + "[")
    for (i <- elms.indices) {
      if (i > 0) appendConst(", ")
      appendConst(s"${componentName(i)}=")
      thisLoad()
      GETFIELD(IndexField(elms, i))
      appendElement(elms(i))
    }
    appendConst("]")
    INVOKEVIRTUAL(StringBuilder, "toString", MethodTypeDescs.mkDescriptor()(JavaClasses.String))
    ARETURN()
  }

  /** `[sb: StringBuilder] --> [sb: StringBuilder]` */
  private def appendConst(s: String)(implicit mv: MethodVisitor): Unit = {
    pushString(s)
    INVOKEVIRTUAL(JavaClasses.StringBuilder, "append", MethodTypeDescs.mkDescriptor(JavaClasses.String)(JavaClasses.StringBuilder))
  }

  /** `[sb: StringBuilder, v: elm] --> [sb: StringBuilder]` */
  private def appendElement(elm: ClassDesc)(implicit mv: MethodVisitor): Unit = {
    val argument = elm match {
      case CD_boolean => CD_boolean
      case CD_char => CD_char
      case CD_byte | CD_short | CD_int => CD_int
      case CD_long => CD_long
      case CD_float => CD_float
      case CD_double => CD_double
      case _ => CD_Object
    }
    INVOKEVIRTUAL(JavaClasses.StringBuilder, "append", MethodTypeDescs.mkDescriptor(argument)(JavaClasses.StringBuilder))
  }

  /** Returns a valid class-name segment naming `elm` precisely, unlike the coarse erased name shared by every reference type. */
  private def sourceSafeName(elm: ClassDesc): String =
    elm.displayName().replaceAll("[^a-zA-Z0-9]", "_")
}
