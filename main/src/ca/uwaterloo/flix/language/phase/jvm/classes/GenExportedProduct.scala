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
import ca.uwaterloo.flix.language.phase.jvm.{ClassMaker, MethodTypeDescs}
import org.objectweb.asm.MethodVisitor

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.{CD_Object, CD_boolean, CD_byte, CD_char, CD_double, CD_float, CD_int, CD_long, CD_short}

/**
  * The shared engine behind every generated `java.lang.Record`: a real record class named
  * `desc`, with one component per `(name, type)` pair, a canonical constructor, and hand-written
  * `equals`/`hashCode`/`toString` -- `Record` makes all three abstract, so a concrete subclass
  * must implement them itself.
  *
  * `GenExportedTuple` and `GenExportedRecord` both generate a class this way; they differ only in
  * how they name the class and its components, not in how the class is built once named.
  */
object GenExportedProduct {

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

  def genByteCode(desc: ClassDesc, components: List[(String, ClassDesc)])(implicit flix: Flix): Array[Byte] = {
    val cm = ClassMaker.mkClass(desc, IsFinal, superClass = JavaClasses.Record)

    for ((name, tpe) <- components) {
      cm.mkField(IndexField(desc, name, tpe), IsPrivate, IsFinal, NotVolatile)
      cm.mkRecordComponent(name, tpe)
    }
    cm.mkConstructor(Constructor(desc, components.map(_._2)), IsPublic, constructorIns(desc, components)(_))
    for ((name, tpe) <- components) {
      cm.mkMethod(Nil, AccessorMethod(desc, name, tpe), IsPublic, IsFinal, accessorIns(desc, name, tpe)(_))
    }
    cm.mkMethod(Nil, EqualsMethod(desc), IsPublic, IsFinal, equalsIns(desc, components)(_))
    cm.mkMethod(Nil, HashCodeMethod(desc), IsPublic, IsFinal, hashCodeIns(desc, components)(_))
    cm.mkMethod(Nil, ToStringMethod(desc), IsPublic, IsFinal, toStringIns(desc, components)(_))

    cm.closeClassMaker()
  }

  def IndexField(desc: ClassDesc, name: String, tpe: ClassDesc): InstanceField = InstanceField(desc, name, tpe)

  def Constructor(desc: ClassDesc, types: List[ClassDesc]): ConstructorMethod = ConstructorMethod(desc, types)

  def AccessorMethod(desc: ClassDesc, name: String, tpe: ClassDesc): InstanceMethod =
    InstanceMethod(desc, name, MethodTypeDescs.mkDescriptor()(tpe))

  private def EqualsMethod(desc: ClassDesc): InstanceMethod =
    InstanceMethod(desc, "equals", MethodTypeDescs.mkDescriptor(CD_Object)(CD_boolean))

  private def HashCodeMethod(desc: ClassDesc): InstanceMethod =
    InstanceMethod(desc, "hashCode", MethodTypeDescs.mkDescriptor()(CD_int))

  private def ToStringMethod(desc: ClassDesc): InstanceMethod =
    InstanceMethod(desc, "toString", MethodTypeDescs.mkDescriptor()(JavaClasses.String))

  /** `[] --> return` */
  private def constructorIns(desc: ClassDesc, components: List[(String, ClassDesc)])(implicit mv: MethodVisitor): Unit =
    withNames(1, components.map(_._2)) { case (_, variables) =>
      thisLoad()
      INVOKESPECIAL(ConstructorMethod(JavaClasses.Record, Nil))
      for ((variable, (name, tpe)) <- variables.zip(components)) {
        thisLoad()
        variable.load()
        PUTFIELD(IndexField(desc, name, tpe))
      }
      RETURN()
    }

  /** `[] --> return` */
  private def accessorIns(desc: ClassDesc, name: String, tpe: ClassDesc)(implicit mv: MethodVisitor): Unit = {
    thisLoad()
    GETFIELD(IndexField(desc, name, tpe))
    xReturn(tpe)
  }

  /** `[other: Object] --> return` */
  private def equalsIns(desc: ClassDesc, components: List[(String, ClassDesc)])(implicit mv: MethodVisitor): Unit =
    withName(1, CD_Object) { other =>
      thisLoad()
      other.load()
      ifCondition(Condition.ACMPEQ) {
        ICONST_1()
        IRETURN()
      }
      other.load()
      INSTANCEOF(desc)
      ifCondition(Condition.EQ) {
        ICONST_0()
        IRETURN()
      }
      withName(2, desc) { that =>
        other.load()
        CHECKCAST(desc)
        that.store()
        for ((name, tpe) <- components) {
          thisLoad()
          GETFIELD(IndexField(desc, name, tpe))
          that.load()
          GETFIELD(IndexField(desc, name, tpe))
          notEqual(tpe) {
            ICONST_0()
            IRETURN()
          }
        }
      }
      ICONST_1()
      IRETURN()
    }

  /** `[self: tpe, other: tpe] --> []`, running `notEqualBranch` when the two are unequal. */
  private def notEqual(tpe: ClassDesc)(notEqualBranch: => Unit)(implicit mv: MethodVisitor): Unit = tpe match {
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
  private def hashCodeIns(desc: ClassDesc, components: List[(String, ClassDesc)])(implicit mv: MethodVisitor): Unit =
    withName(1, CD_int) { h =>
      ICONST_0()
      h.store()
      for ((name, tpe) <- components) {
        h.load()
        pushInt(31)
        IMUL()
        thisLoad()
        GETFIELD(IndexField(desc, name, tpe))
        elementHash(tpe)
        IADD()
        h.store()
      }
      h.load()
      IRETURN()
    }

  /** `[v: tpe] --> [hash: int]` */
  private def elementHash(tpe: ClassDesc)(implicit mv: MethodVisitor): Unit = Wrappers.get(tpe) match {
    case Some(boxed) => INVOKESTATIC(boxed, "hashCode", MethodTypeDescs.mkDescriptor(tpe)(CD_int))
    case None => INVOKESTATIC(JavaClasses.Objects, "hashCode", MethodTypeDescs.mkDescriptor(CD_Object)(CD_int))
  }

  /** `[] --> return` */
  private def toStringIns(desc: ClassDesc, components: List[(String, ClassDesc)])(implicit mv: MethodVisitor): Unit = {
    val StringBuilder = JavaClasses.StringBuilder
    NEW(StringBuilder)
    DUP()
    INVOKESPECIAL(ConstructorMethod(StringBuilder, Nil))
    appendConst(desc.displayName() + "[")
    for (((name, tpe), i) <- components.zipWithIndex) {
      if (i > 0) appendConst(", ")
      appendConst(s"$name=")
      thisLoad()
      GETFIELD(IndexField(desc, name, tpe))
      appendElement(tpe)
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

  /** `[sb: StringBuilder, v: tpe] --> [sb: StringBuilder]` */
  private def appendElement(tpe: ClassDesc)(implicit mv: MethodVisitor): Unit = {
    val argument = tpe match {
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
}
