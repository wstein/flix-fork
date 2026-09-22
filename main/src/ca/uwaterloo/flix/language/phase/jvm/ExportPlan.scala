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
import java.lang.constant.ConstantDescs.{CD_Object, CD_boolean, CD_byte, CD_char, CD_double, CD_float, CD_int, CD_long, CD_short, CD_void}

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
  def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit
}

object ExportPlan {

  private val Optional = ClassDesc.ofInternalName("java/util/Optional")
  private val JavaList = ClassDesc.ofInternalName("java/util/List")
  private val JavaCollection = ClassDesc.ofInternalName("java/util/Collection")

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

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = ()
  }

  /** A Flix `Unit` result discarded so a Java caller sees `void`. */
  case object ToVoid extends ExportPlan {
    override def flixType: ClassDesc = CD_Object

    override def signature: ExportSignature = ExportSignature.Exact(CD_void)

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = POP()
  }

  /** A Java value passed through unchanged while retaining its generic arguments for callers. */
  case class GenericNative(clazz: ClassDesc, targs: List[ExportSignature]) extends ExportPlan {
    override def flixType: ClassDesc = clazz

    override def signature: ExportSignature = ExportSignature.Applied(clazz, targs)

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = ()
  }

  /** A primitive element boxed for a reference-only Java container. */
  case class Boxed(flixType: ClassDesc, boxed: ClassDesc) extends ExportPlan {
    override def signature: ExportSignature = ExportSignature.Boxed(flixType, boxed)

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      INVOKESTATIC(boxed, "valueOf", MethodTypeDescs.mkDescriptor(flixType)(boxed))
  }

  /** A Flix `Option` result converted to `java.util.Optional`. */
  case class AsOptional(element: ExportPlan, noneOrdinal: Int, someFields: List[ClassDesc]) extends ExportPlan {
    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Applied(Optional, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      DUP()
      GETFIELD(GenTagged.OrdinalField)
      pushInt(noneOrdinal)
      ifConditionElse(Condition.ICMPEQ) {
        POP()
        INVOKESTATIC(Optional, "empty", MethodTypeDescs.mkDescriptor()(Optional))
      } {
        CHECKCAST(GenTag.desc(someFields))
        GETFIELD(GenTag.IndexField(someFields, 0))
        element.emit(nextLocal)
        INVOKESTATIC(Optional, "ofNullable", MethodTypeDescs.mkDescriptor(CD_Object)(Optional))
      }
    }
  }

  /** A Flix `List` result converted to an unmodifiable eager Java list. */
  case class AsList(element: ExportPlan, nilOrdinal: Int, consFields: List[ClassDesc]) extends ExportPlan {
    private val ArrayList = ClassDesc.ofInternalName("java/util/ArrayList")
    private val Collections = ClassDesc.ofInternalName("java/util/Collections")

    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Applied(JavaList, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenTagged.Desc) { cursor =>
        withName(nextLocal + 1, ArrayList) { acc =>
          cursor.store()
          NEW(ArrayList)
          DUP()
          INVOKESPECIAL(ClassMaker.ConstructorMethod(ArrayList, Nil))
          acc.store()
          whileLoop(Condition.ICMPNE) {
            cursor.load()
            GETFIELD(GenTagged.OrdinalField)
            pushInt(nilOrdinal)
          } {
            acc.load()
            cursor.load()
            CHECKCAST(GenTag.desc(consFields))
            GETFIELD(GenTag.IndexField(consFields, 0))
            element.emit(nextLocal + 2)
            INVOKEVIRTUAL(ArrayList, "add", MethodTypeDescs.mkDescriptor(CD_Object)(CD_boolean))
            POP()
            cursor.load()
            CHECKCAST(GenTag.desc(consFields))
            GETFIELD(GenTag.IndexField(consFields, 1))
            CHECKCAST(GenTagged.Desc)
            cursor.store()
          }
          acc.load()
          INVOKESTATIC(Collections, "unmodifiableList", MethodTypeDescs.mkDescriptor(JavaList)(JavaList))
        }
      }
    }
  }

  /**
    * A Flix `Vector` result converted to an unmodifiable eager Java list.
    *
    * Unlike every other converted collection, `Vector` needs no `Tagged` unwrap: its Flix
    * representation already is the Java array named by `component.arrayType()`.
    */
  case class AsVector(element: ExportPlan, component: ClassDesc) extends ExportPlan {
    private val ArrayList = ClassDesc.ofInternalName("java/util/ArrayList")
    private val Collections = ClassDesc.ofInternalName("java/util/Collections")

    override def flixType: ClassDesc = component.arrayType()

    override def signature: ExportSignature = ExportSignature.Applied(JavaList, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, component.arrayType()) { arr =>
        withName(nextLocal + 1, CD_int) { index =>
          withName(nextLocal + 2, ArrayList) { acc =>
            arr.store()
            NEW(ArrayList)
            DUP()
            INVOKESPECIAL(ClassMaker.ConstructorMethod(ArrayList, Nil))
            acc.store()
            ICONST_0()
            index.store()
            whileLoop(Condition.ICMPNE) {
              index.load()
              arr.load()
              ARRAYLENGTH()
            } {
              acc.load()
              arr.load()
              index.load()
              xArrayLoad(component)
              element.emit(nextLocal + 3)
              INVOKEVIRTUAL(ArrayList, "add", MethodTypeDescs.mkDescriptor(CD_Object)(CD_boolean))
              POP()
              index.load()
              ICONST_1()
              IADD()
              index.store()
            }
            acc.load()
            INVOKESTATIC(Collections, "unmodifiableList", MethodTypeDescs.mkDescriptor(JavaList)(JavaList))
          }
        }
      }
    }
  }

  /**
    * A Flix `Chain` result converted to an unmodifiable eager Java collection.
    *
    * `Chain` offers no efficient indexed access, unlike `List` or `Vector`, so this is a
    * `Collection`, not a `List`. Its `Empty | One(t) | Chain(l, r)` shape is a binary tree rather
    * than a linear structure, so the walk needs an explicit stack instead of `AsList`'s single
    * cursor: `chainFields` pushes the right child before the left, so a LIFO pop visits elements
    * left-to-right.
    */
  case class AsChain(element: ExportPlan, emptyOrdinal: Int, oneOrdinal: Int, oneFields: List[ClassDesc], chainFields: List[ClassDesc]) extends ExportPlan {
    private val ArrayList = ClassDesc.ofInternalName("java/util/ArrayList")
    private val ArrayDeque = ClassDesc.ofInternalName("java/util/ArrayDeque")
    private val Collections = ClassDesc.ofInternalName("java/util/Collections")

    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Applied(JavaCollection, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenTagged.Desc) { node =>
        withName(nextLocal + 1, ArrayDeque) { stack =>
          withName(nextLocal + 2, ArrayList) { acc =>
            node.store()
            NEW(ArrayList)
            DUP()
            INVOKESPECIAL(ClassMaker.ConstructorMethod(ArrayList, Nil))
            acc.store()
            NEW(ArrayDeque)
            DUP()
            INVOKESPECIAL(ClassMaker.ConstructorMethod(ArrayDeque, Nil))
            stack.store()
            stack.load()
            node.load()
            INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
            whileLoop(Condition.ICMPNE) {
              stack.load()
              INVOKEVIRTUAL(ArrayDeque, "size", MethodTypeDescs.mkDescriptor()(CD_int))
              pushInt(0)
            } {
              stack.load()
              INVOKEVIRTUAL(ArrayDeque, "pop", MethodTypeDescs.mkDescriptor()(CD_Object))
              CHECKCAST(GenTagged.Desc)
              node.store()
              node.load()
              GETFIELD(GenTagged.OrdinalField)
              pushInt(emptyOrdinal)
              ifConditionElse(Condition.ICMPEQ) {
                // Empty: nothing to add or push.
              } {
                node.load()
                GETFIELD(GenTagged.OrdinalField)
                pushInt(oneOrdinal)
                ifConditionElse(Condition.ICMPEQ) {
                  acc.load()
                  node.load()
                  CHECKCAST(GenTag.desc(oneFields))
                  GETFIELD(GenTag.IndexField(oneFields, 0))
                  element.emit(nextLocal + 3)
                  INVOKEVIRTUAL(ArrayList, "add", MethodTypeDescs.mkDescriptor(CD_Object)(CD_boolean))
                  POP()
                } {
                  stack.load()
                  node.load()
                  CHECKCAST(GenTag.desc(chainFields))
                  GETFIELD(GenTag.IndexField(chainFields, 1))
                  INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
                  stack.load()
                  node.load()
                  CHECKCAST(GenTag.desc(chainFields))
                  GETFIELD(GenTag.IndexField(chainFields, 0))
                  INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
                }
              }
            }
            acc.load()
            INVOKESTATIC(Collections, "unmodifiableCollection", MethodTypeDescs.mkDescriptor(JavaCollection)(JavaCollection))
          }
        }
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
    case SimpleType.Native(clazz, Nil) => Some(Identity(clazz))
    case SimpleType.AnyType => Some(Identity(CD_Object))
    case SimpleType.Unit => Some(ToVoid)
    case _ => None
  }

  /** Returns the caller-visible signature derivable without compilation state. */
  def signatureOf(tpe: SimpleType): Option[ExportSignature] = tpe match {
    case SimpleType.Enum(sym, List(element)) if isOption(sym) =>
      typeArgumentPlan(element).map(sig => ExportSignature.Applied(Optional, List(sig)))
    case SimpleType.Enum(sym, List(element)) if isList(sym) =>
      typeArgumentPlan(element).map(sig => ExportSignature.Applied(JavaList, List(sig)))
    case SimpleType.Array(element) =>
      typeArgumentPlan(element).map(sig => ExportSignature.Applied(JavaList, List(sig)))
    case SimpleType.Enum(sym, List(element)) if isChain(sym) =>
      typeArgumentPlan(element).map(sig => ExportSignature.Applied(JavaCollection, List(sig)))
    case SimpleType.Native(clazz, targs) if targs.nonEmpty =>
      traverse(targs)(typeArgumentPlan).map(ExportSignature.Applied(clazz, _))
    case _ => exact(tpe).map(_.signature)
  }

  /** Returns the executable conversion plan for an exported definition's result. */
  def ofDef(defn: ca.uwaterloo.flix.language.ast.JvmAst.Def)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    if (!defn.ann.isExport) None
    else defn.exportedReturnType.flatMap {
      case SimpleType.Enum(sym, List(element)) if isOption(sym) => optionPlan(element, defn.unboxedType.tpe)
      case SimpleType.Enum(sym, List(element)) if isList(sym) => listPlan(element, defn.unboxedType.tpe)
      case SimpleType.Array(element) => vectorPlan(element)
      case SimpleType.Enum(sym, List(element)) if isChain(sym) => chainPlan(element, defn.unboxedType.tpe)
      case SimpleType.Native(clazz, targs) if targs.nonEmpty =>
        traverse(targs)(typeArgumentPlan).map(GenericNative(clazz, _))
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

  /** Builds a List conversion from the specialized enum retained by erasure. */
  private def listPlan(element: SimpleType, erased: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = erased match {
    case SimpleType.Enum(sym, Nil) =>
      val cases = root.enums(sym).cases.values
      for {
        nil <- cases.find(_.sym.name == "Nil")
        cons <- cases.find(c => c.sym.name == "Cons" && c.tpes.lengthCompare(2) == 0)
        elementPlan <- elementPlan(element, TypeDescs.toClassDesc(cons.tpes.head))
      } yield AsList(elementPlan, nil.sym.ordinal, cons.tpes.map(TypeDescs.toClassDesc))
    case _ => None
  }

  /**
    * Builds a `java.util.List` conversion from a `Vector`'s element type.
    *
    * `Array[t, r]` erases to the same `SimpleType.Array` this matches; `EntryPoints` is what
    * keeps a mutable, region-scoped `Array` from ever reaching this plan.
    */
  private def vectorPlan(element: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    elementPlan(element, TypeDescs.toClassDesc(element)).map(AsVector(_, TypeDescs.toClassDesc(element)))

  /** Builds a Chain conversion from the specialized enum retained by erasure. */
  private def chainPlan(element: SimpleType, erased: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = erased match {
    case SimpleType.Enum(sym, Nil) =>
      val cases = root.enums(sym).cases.values
      for {
        empty <- cases.find(_.sym.name == "Empty")
        one <- cases.find(c => c.sym.name == "One" && c.tpes.lengthCompare(1) == 0)
        chain <- cases.find(c => c.sym.name == "Chain" && c.tpes.lengthCompare(2) == 0)
        elementPlan <- elementPlan(element, TypeDescs.toClassDesc(one.tpes.head))
      } yield AsChain(elementPlan, empty.sym.ordinal, one.sym.ordinal, one.tpes.map(TypeDescs.toClassDesc), chain.tpes.map(TypeDescs.toClassDesc))
    case _ => None
  }

  /** Returns a plan for a value placed in a Java reference-only type argument position. */
  private def elementPlan(declared: SimpleType, erased: ClassDesc): Option[ExportPlan] =
    Wrappers.get(erased).map(Boxed(erased, _)).orElse(exact(declared))

  private def typeArgumentPlan(tpe: SimpleType): Option[ExportSignature] =
    Wrappers.get(TypeDescs.toErasedClassDesc(tpe)).map(ExportSignature.Boxed(TypeDescs.toErasedClassDesc(tpe), _))
      .orElse(signatureOf(tpe))

  private def traverse[A, B](xs: List[A])(f: A => Option[B]): Option[List[B]] =
    xs.foldRight(Option(List.empty[B])) {
      case (x, acc) => for (values <- acc; value <- f(x)) yield value :: values
    }

  private def isOption(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Option"

  private def isList(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "List"

  private def isChain(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Chain"
}
