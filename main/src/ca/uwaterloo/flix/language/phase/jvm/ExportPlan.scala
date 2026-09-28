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
import ca.uwaterloo.flix.language.phase.jvm.classes.{GenExportedEnum, GenExportedRecord, GenExportedTuple, GenRecord, GenRecordExtend, GenTag, GenTagged, GenTuple}
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

  /**
    * Converts a Flix value held at its erased type, as every container, tuple, record, and case
    * field holds a reference-typed value: as `Object`. The value is cast to [[flixType]] first,
    * which a nested plan needs and an exact one tolerates.
    */
  final def emitErased(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
    if (!flixType.isPrimitive && flixType != CD_Object) CHECKCAST(flixType)
    emit(nextLocal)
  }
}

object ExportPlan {

  private val Optional = ClassDesc.ofInternalName("java/util/Optional")
  private val JavaList = ClassDesc.ofInternalName("java/util/List")
  private val JavaCollection = ClassDesc.ofInternalName("java/util/Collection")
  private val JavaSet = ClassDesc.ofInternalName("java/util/Set")
  private val JavaMap = ClassDesc.ofInternalName("java/util/Map")

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
        element.emitErased(nextLocal)
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
            element.emitErased(nextLocal + 2)
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
              element.emitErased(nextLocal + 3)
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
                  element.emitErased(nextLocal + 3)
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

  /**
    * A Flix `Set` result converted to an unmodifiable eager Java set.
    *
    * `Set[t]` is a single-case wrapper around `RedBlackTree[t, Unit]`; `wrapperFields` unwraps
    * it once, unconditionally, since there is no other case to branch on. The tree itself has
    * more than one non-`Node` case (`Leaf`, and a transient `DoubleBlackLeaf` used mid-deletion),
    * so the walk branches on being `Node` rather than enumerating every case that is not; order
    * does not matter for a `Set`, so nothing tracks traversal direction, unlike `AsChain`.
    */
  case class AsSet(element: ExportPlan, wrapperFields: List[ClassDesc], nodeOrdinal: Int, nodeFields: List[ClassDesc]) extends ExportPlan {
    private val ArrayDeque = ClassDesc.ofInternalName("java/util/ArrayDeque")
    private val HashSet = ClassDesc.ofInternalName("java/util/HashSet")
    private val Collections = ClassDesc.ofInternalName("java/util/Collections")

    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Applied(JavaSet, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenTagged.Desc) { wrapper =>
        withName(nextLocal + 1, GenTagged.Desc) { node =>
          withName(nextLocal + 2, ArrayDeque) { stack =>
            withName(nextLocal + 3, HashSet) { acc =>
              wrapper.store()
              NEW(HashSet)
              DUP()
              INVOKESPECIAL(ClassMaker.ConstructorMethod(HashSet, Nil))
              acc.store()
              NEW(ArrayDeque)
              DUP()
              INVOKESPECIAL(ClassMaker.ConstructorMethod(ArrayDeque, Nil))
              stack.store()
              stack.load()
              wrapper.load()
              CHECKCAST(GenTag.desc(wrapperFields))
              GETFIELD(GenTag.IndexField(wrapperFields, 0))
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
                pushInt(nodeOrdinal)
                ifConditionElse(Condition.ICMPEQ) {
                  acc.load()
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 2))
                  element.emitErased(nextLocal + 4)
                  INVOKEVIRTUAL(HashSet, "add", MethodTypeDescs.mkDescriptor(CD_Object)(CD_boolean))
                  POP()
                  stack.load()
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 1))
                  INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
                  stack.load()
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 4))
                  INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
                } {
                  // Leaf or DoubleBlackLeaf: nothing to add or push.
                }
              }
              acc.load()
              INVOKESTATIC(Collections, "unmodifiableSet", MethodTypeDescs.mkDescriptor(JavaSet)(JavaSet))
            }
          }
        }
      }
    }
  }

  /** A Flix `Map` result converted to an unmodifiable eager Java map, by the same walk as `AsSet`. */
  case class AsMap(key: ExportPlan, value: ExportPlan, wrapperFields: List[ClassDesc], nodeOrdinal: Int, nodeFields: List[ClassDesc]) extends ExportPlan {
    private val ArrayDeque = ClassDesc.ofInternalName("java/util/ArrayDeque")
    private val HashMap = ClassDesc.ofInternalName("java/util/HashMap")
    private val Collections = ClassDesc.ofInternalName("java/util/Collections")

    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Applied(JavaMap, List(key.signature, value.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenTagged.Desc) { wrapper =>
        withName(nextLocal + 1, GenTagged.Desc) { node =>
          withName(nextLocal + 2, ArrayDeque) { stack =>
            withName(nextLocal + 3, HashMap) { acc =>
              wrapper.store()
              NEW(HashMap)
              DUP()
              INVOKESPECIAL(ClassMaker.ConstructorMethod(HashMap, Nil))
              acc.store()
              NEW(ArrayDeque)
              DUP()
              INVOKESPECIAL(ClassMaker.ConstructorMethod(ArrayDeque, Nil))
              stack.store()
              stack.load()
              wrapper.load()
              CHECKCAST(GenTag.desc(wrapperFields))
              GETFIELD(GenTag.IndexField(wrapperFields, 0))
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
                pushInt(nodeOrdinal)
                ifConditionElse(Condition.ICMPEQ) {
                  acc.load()
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 2))
                  key.emitErased(nextLocal + 4)
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 3))
                  value.emitErased(nextLocal + 4)
                  INVOKEVIRTUAL(HashMap, "put", MethodTypeDescs.mkDescriptor(CD_Object, CD_Object)(CD_Object))
                  POP()
                  stack.load()
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 1))
                  INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
                  stack.load()
                  node.load()
                  CHECKCAST(GenTag.desc(nodeFields))
                  GETFIELD(GenTag.IndexField(nodeFields, 4))
                  INVOKEVIRTUAL(ArrayDeque, "push", MethodTypeDescs.mkDescriptor(CD_Object)(CD_void))
                } {
                  // Leaf or DoubleBlackLeaf: nothing to put or push.
                }
              }
              acc.load()
              INVOKESTATIC(Collections, "unmodifiableMap", MethodTypeDescs.mkDescriptor(JavaMap)(JavaMap))
            }
          }
        }
      }
    }
  }

  /**
    * A Flix tuple result converted to a real, generated `java.lang.Record`.
    *
    * Unlike every converted container, a tuple's Java representation is not an existing JDK
    * type: `GenExportedTuple` generates one record class per distinct shape of Java-facing
    * element types, shared across every exported tuple with that shape. `flixFields` names the
    * compiler's own internal tuple class, read field by field and converted into the record's
    * constructor arguments; there is no case to branch on and no container to walk.
    */
  case class AsTuple(elements: List[ExportPlan], flixFields: List[ClassDesc]) extends ExportPlan {
    private val javaFields: List[ClassDesc] = elements.map(_.javaType)

    override def flixType: ClassDesc = GenTuple.desc(flixFields)

    override def signature: ExportSignature = ExportSignature.Exact(GenExportedTuple.desc(javaFields))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenTuple.desc(flixFields)) { tuple =>
        tuple.store()
        NEW(GenExportedTuple.desc(javaFields))
        DUP()
        for ((element, i) <- elements.zipWithIndex) {
          tuple.load()
          GETFIELD(GenTuple.IndexField(flixFields, i))
          element.emitErased(nextLocal + 1)
          // The tuple's own field is erased to Object; narrow reference types the way the
          // constructor's precise parameter type demands, since `element.emit` does not.
          if (!element.javaType.isPrimitive) CHECKCAST(element.javaType)
        }
        INVOKESPECIAL(GenExportedTuple.Constructor(javaFields))
      }
    }
  }

  /**
    * A Flix structural record result converted to a real, generated `java.lang.Record`.
    *
    * Unlike a tuple, whose compiler-internal class exposes its elements by index, a record's
    * internal representation (`GenRecord.Desc`, `GenRecordExtend`) only exposes fields by label,
    * one lookup at a time -- so each field is read with `lookupField`, not `GETFIELD`, and
    * `flixFields` names the field's own erased type to know which `RecordExtend$<Type>` the
    * lookup result must be cast to before its `value` field can be read.
    */
  case class AsRecord(labels: List[String], elements: List[ExportPlan], flixFields: List[ClassDesc]) extends ExportPlan {
    private val javaFields: List[(String, ClassDesc)] = labels.zip(elements.map(_.javaType))

    override def flixType: ClassDesc = GenRecord.Desc

    override def signature: ExportSignature = ExportSignature.Exact(GenExportedRecord.desc(javaFields))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenRecord.Desc) { record =>
        record.store()
        NEW(GenExportedRecord.desc(javaFields))
        DUP()
        for (((label, element), flixField) <- labels.zip(elements).zip(flixFields)) {
          record.load()
          pushString(label)
          INVOKEINTERFACE(GenRecord.LookupFieldMethod)
          CHECKCAST(GenRecordExtend.desc(flixField))
          GETFIELD(GenRecordExtend.ValueField(flixField))
          element.emitErased(nextLocal + 1)
          // The record field is erased to its own value type; narrow reference types the way
          // the constructor's precise parameter type demands, since `element.emit` does not.
          if (!element.javaType.isPrimitive) CHECKCAST(element.javaType)
        }
        INVOKESPECIAL(GenExportedRecord.Constructor(javaFields))
      }
    }
  }

  /**
    * A data-free Flix enum result converted to the constant of its generated Java enum.
    *
    * `constants` are the case names in ordinal order. A case's Flix ordinal is its Java constant's
    * ordinal, so the conversion is one array read and needs no per-case branch.
    */
  case class AsEnum(ns: List[String], constants: List[String]) extends ExportPlan {
    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Exact(Mangle.namespaceFacadeDesc(ns))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      GETFIELD(GenTagged.OrdinalField)
      INVOKESTATIC(GenExportedEnum.OfOrdinalMethod(javaType))
    }
  }

  /** One case of an exported data-carrying enum: its record's fields and how each converts. */
  case class SealedCase(name: String, ordinal: Int, flixFields: List[ClassDesc], elements: List[ExportPlan]) {
    /** The record components: synthetic names, as a tuple's, since a Flix case names no fields. */
    def components: List[(String, ClassDesc)] = elements.zipWithIndex.map { case (e, i) => s"component$i" -> e.javaType }
  }

  /**
    * A data-carrying Flix enum result converted to the nested record of its case, which
    * implements the enum's generated sealed interface.
    *
    * The case is chosen by comparing ordinals in turn; the last case needs no comparison.
    */
  case class AsSealed(ns: List[String], cases: List[SealedCase]) extends ExportPlan {
    override def flixType: ClassDesc = GenTagged.Desc

    override def signature: ExportSignature = ExportSignature.Exact(Mangle.namespaceFacadeDesc(ns))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
      withName(nextLocal, GenTagged.Desc) { value =>
        def construct(c: SealedCase): Unit = {
          val record = GenExportedEnum.caseDesc(javaType, c.name)
          NEW(record)
          DUP()
          for ((element, i) <- c.elements.zipWithIndex) {
            value.load()
            CHECKCAST(GenTag.desc(c.flixFields))
            GETFIELD(GenTag.IndexField(c.flixFields, i))
            element.emitErased(nextLocal + 1)
            if (!element.javaType.isPrimitive) CHECKCAST(element.javaType)
          }
          INVOKESPECIAL(ClassMaker.ConstructorMethod(record, c.elements.map(_.javaType)))
        }

        def select(cs: List[SealedCase]): Unit = cs match {
          case Nil => ()
          case last :: Nil => construct(last)
          case c :: rest =>
            value.load()
            GETFIELD(GenTagged.OrdinalField)
            pushInt(c.ordinal)
            ifConditionElse(Condition.ICMPEQ)(construct(c))(select(rest))
        }

        value.store()
        select(cases)
        CHECKCAST(javaType)
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

  /** Returns `plan` and every plan nested in it, each of which may name a class to generate. */
  def allOf(plan: ExportPlan): List[ExportPlan] = plan :: (plan match {
    case AsOptional(element, _, _) => allOf(element)
    case AsList(element, _, _) => allOf(element)
    case AsVector(element, _) => allOf(element)
    case AsChain(element, _, _, _, _) => allOf(element)
    case AsSet(element, _, _, _) => allOf(element)
    case AsMap(key, value, _, _, _) => allOf(key) ++ allOf(value)
    case AsTuple(elements, _) => elements.flatMap(allOf)
    case AsRecord(_, elements, _) => elements.flatMap(allOf)
    case AsSealed(_, cases) => cases.flatMap(_.elements).flatMap(allOf)
    case Identity(_) => Nil
    case ToVoid => Nil
    case GenericNative(_, _) => Nil
    case Boxed(_, _) => Nil
    case AsEnum(_, _) => Nil
  })

  /**
    * Where a converted value sits, which decides what it may be.
    *
    * A container is refused as a [[Position.Component]]: the record class generated for a tuple,
    * record, or enum case is shared by the erased shape of its components, so a `List<Integer>`
    * component would reach Java as a raw `List`. Anything else nests.
    */
  private sealed trait Position

  private object Position {
    /** An export's own result, the only place `Unit` crosses, as `void`. */
    case object Result extends Position

    /** A container's type argument, where a primitive is boxed. */
    case object Argument extends Position

    /** A tuple element, a record field, or an enum case's field. */
    case object Component extends Position
  }

  /** Returns the caller-visible signature derivable without compilation state. */
  def signatureOf(tpe: SimpleType): Option[ExportSignature] = signatureAt(tpe, Position.Result)

  private def signatureAt(tpe: SimpleType, pos: Position): Option[ExportSignature] = {
    val container = pos != Position.Component
    tpe match {
      case SimpleType.Unit if pos != Position.Result => None
      case SimpleType.Enum(sym, List(element)) if container && isOption(sym) => applied(Optional, List(element))
      case SimpleType.Enum(sym, List(element)) if container && isList(sym) => applied(JavaList, List(element))
      case SimpleType.Array(element) if container => applied(JavaList, List(element))
      case SimpleType.Enum(sym, List(element)) if container && isChain(sym) => applied(JavaCollection, List(element))
      case SimpleType.Enum(sym, List(element)) if container && isSet(sym) => applied(JavaSet, List(element))
      case SimpleType.Enum(sym, List(key, value)) if container && isMap(sym) => applied(JavaMap, List(key, value))
      case SimpleType.Native(clazz, targs) if container && targs.nonEmpty => applied(clazz, targs)
      case SimpleType.Tuple(elms) =>
        traverse(elms)(signatureAt(_, Position.Component)).map(sigs => ExportSignature.Exact(GenExportedTuple.desc(sigs.map(_.javaType))))
      case SimpleType.RecordEmpty => recordSignature(tpe)
      case SimpleType.RecordExtend(_, _, _) => recordSignature(tpe)
      // Only a monomorphic enum reaches here: `EntryPoints` refuses every other enum an export names.
      case SimpleType.Enum(sym, Nil) => Some(ExportSignature.Exact(GenExportedEnum.desc(sym)))
      case _ => exact(tpe).map(_.signature)
    }
  }

  /** The signature of the generated record class of the closed record type `tpe`. */
  private def recordSignature(tpe: SimpleType): Option[ExportSignature] =
    for {
      fields <- recordFieldsOf(tpe)
      sigs <- traverse(fields.map(_._2))(signatureAt(_, Position.Component))
    } yield ExportSignature.Exact(GenExportedRecord.desc(fields.map(_._1).zip(sigs.map(_.javaType))))

  /** The plan converting the closed record type `tpe` to its generated record class. */
  private def recordPlan(tpe: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    for {
      fields <- recordFieldsOf(tpe)
      elementPlans <- traverse(fields.map(_._2))(planAt(_, Position.Component))
    } yield AsRecord(fields.map(_._1), elementPlans, fields.map(_._2).map(TypeDescs.toErasedClassDesc))

  /** The signature of `clazz` applied to the signatures of `targs`, as type arguments. */
  private def applied(clazz: ClassDesc, targs: List[SimpleType]): Option[ExportSignature] =
    traverse(targs)(typeArgumentSignature).map(ExportSignature.Applied(clazz, _))

  private def typeArgumentSignature(tpe: SimpleType): Option[ExportSignature] =
    Wrappers.get(TypeDescs.toErasedClassDesc(tpe)).map(ExportSignature.Boxed(TypeDescs.toErasedClassDesc(tpe), _))
      .orElse(signatureAt(tpe, Position.Argument))

  /** Returns the executable conversion plan for an exported definition's result. */
  def ofDef(defn: ca.uwaterloo.flix.language.ast.JvmAst.Def)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    if (!defn.ann.isExport) None
    else defn.exportedReturnType.flatMap(planAt(_, Position.Result))

  /**
    * Returns the plan converting a Flix value of the declared type `tpe` to Java, as if returned.
    *
    * `CodeGen` plans an exported parameter's type this way too: the Java classes a parameter
    * names are the ones a result of the same type would, and must be generated the same way.
    */
  def ofType(tpe: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    planAt(tpe, Position.Result)

  /**
    * Returns the plan converting a Flix value whose declared type is `tpe`.
    *
    * Plans are built from the declared type, never from a specialized enum, since a value nested
    * inside another has none recorded: an `Option[Int32]` inside a `List` is known only as the
    * list's element type. A tag class's fields follow from erasure alone -- a reference-typed
    * field is `Object`, a primitive one stays primitive -- and a case's ordinal from its
    * declaration, which every specialization shares.
    */
  private def planAt(tpe: SimpleType, pos: Position)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = {
    val container = pos != Position.Component
    tpe match {
      case SimpleType.Unit => if (pos == Position.Result) Some(ToVoid) else None
      case SimpleType.Enum(sym, List(element)) if container && isOption(sym) => optionPlan(element)
      case SimpleType.Enum(sym, List(element)) if container && isList(sym) => listPlan(element)
      case SimpleType.Array(element) if container => vectorPlan(element)
      case SimpleType.Enum(sym, List(element)) if container && isChain(sym) => chainPlan(element)
      case SimpleType.Enum(sym, List(element)) if container && isSet(sym) => setPlan(element)
      case SimpleType.Enum(sym, List(key, value)) if container && isMap(sym) => mapPlan(key, value)
      case SimpleType.Native(clazz, targs) if container && targs.nonEmpty => traverse(targs)(typeArgumentSignature).map(GenericNative(clazz, _))
      case SimpleType.Tuple(elms) => traverse(elms)(planAt(_, Position.Component)).map(AsTuple(_, elms.map(TypeDescs.toErasedClassDesc)))
      case SimpleType.RecordEmpty => recordPlan(tpe)
      case SimpleType.RecordExtend(_, _, _) => recordPlan(tpe)
      case SimpleType.Enum(sym, Nil) => enumPlan(sym)
      case _ => exact(tpe)
    }
  }

  /** Returns a plan for a value in a Java reference-only type argument position, boxing a primitive. */
  private def elementPlan(tpe: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = {
    val erased = TypeDescs.toErasedClassDesc(tpe)
    Wrappers.get(erased).map(Boxed(erased, _)).orElse(planAt(tpe, Position.Argument))
  }

  /** Returns the fields of a closed record type in row order, which monomorphisation has sorted. */
  private def recordFieldsOf(tpe: SimpleType): Option[List[(String, SimpleType)]] = tpe match {
    case SimpleType.RecordEmpty => Some(Nil)
    case SimpleType.RecordExtend(label, value, rest) => recordFieldsOf(rest).map((label, value) :: _)
    case _ => None
  }

  /**
    * Builds the conversion of the monomorphic enum `sym`: a Java enum if no case carries data, a
    * sealed interface of records otherwise.
    *
    * A monomorphic enum is its own only specialization. Its fields are read at their erased
    * types, which name the case's tag class, and converted from their declared types, which
    * `root.enums` no longer has and `root.exportedEnumFields` keeps. Refuses a data-free enum
    * whose ordinals are not exactly `0 until n`, which would make a constant's position in the
    * Java enum disagree with its Flix case.
    */
  private def enumPlan(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    root.enums.get(sym).flatMap { enm =>
      val cases = enm.cases.values.toList.sortBy(_.sym.ordinal)
      val ns = GenExportedEnum.companionNamespace(sym)
      if (cases.forall(_.tpes.isEmpty)) {
        val contiguous = cases.map(_.sym.ordinal) == cases.indices.toList
        if (contiguous) Some(AsEnum(ns, cases.map(_.sym.name))) else None
      } else {
        for {
          declared <- root.exportedEnumFields.get(sym)
          sealedCases <- traverse(cases) { c =>
            declared.get(c.sym.name).flatMap(traverse(_)(planAt(_, Position.Component)))
              .map(SealedCase(c.sym.name, c.sym.ordinal, c.tpes.map(TypeDescs.toClassDesc), _))
          }
        } yield AsSealed(ns, sealedCases)
      }
    }

  /** Returns the ordinals of the cases of the standard library's enum `name`, by case name. */
  private def ordinalsOf(name: String)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Map[String, Int] =
    root.enums.values.find(e => e.sym.namespace.isEmpty && e.sym.text == name)
      .map(_.cases.values.map(c => c.sym.name -> c.sym.ordinal).toMap)
      .getOrElse(Map.empty)

  /** Builds an Optional conversion; `Some(t)` holds `t` erased. */
  private def optionPlan(element: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = {
    val ordinals = ordinalsOf("Option")
    for {
      none <- ordinals.get("None")
      _ <- ordinals.get("Some")
      elementPlan <- elementPlan(element)
    } yield AsOptional(elementPlan, none, List(TypeDescs.toErasedClassDesc(element)))
  }

  /** Builds a List conversion; `Cons(t, List[t])` holds `t` erased and the tail as `Object`. */
  private def listPlan(element: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = {
    val ordinals = ordinalsOf("List")
    for {
      nil <- ordinals.get("Nil")
      _ <- ordinals.get("Cons")
      elementPlan <- elementPlan(element)
    } yield AsList(elementPlan, nil, List(TypeDescs.toErasedClassDesc(element), CD_Object))
  }

  /**
    * Builds a `java.util.List` conversion from a `Vector`'s element type.
    *
    * `Array[t, r]` erases to the same `SimpleType.Array` this matches; `EntryPoints` is what
    * keeps a mutable, region-scoped `Array` from ever reaching this plan.
    */
  private def vectorPlan(element: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    elementPlan(element).map(AsVector(_, runtimeDesc(element)))

  /**
    * Returns the JVM type a value of the declared type `tpe` has at run time, as an array element.
    *
    * `TypeDescs.toClassDesc` reads a specialized type, in which every enum has lost its type
    * arguments; a declared one still has them, and every enum value is `Tagged` either way.
    */
  private def runtimeDesc(tpe: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): ClassDesc = tpe match {
    case SimpleType.Enum(_, _) => GenTagged.Desc
    case SimpleType.Array(element) => runtimeDesc(element).arrayType()
    case _ => TypeDescs.toClassDesc(tpe)
  }

  /** Builds a Chain conversion; `One(t)` holds `t` erased and `Chain(l, r)` its halves as `Object`. */
  private def chainPlan(element: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] = {
    val ordinals = ordinalsOf("Chain")
    for {
      empty <- ordinals.get("Empty")
      one <- ordinals.get("One")
      _ <- ordinals.get("Chain")
      elementPlan <- elementPlan(element)
    } yield AsChain(elementPlan, empty, one, List(TypeDescs.toErasedClassDesc(element)), List(CD_Object, CD_Object))
  }

  /**
    * Builds a Set conversion by unwrapping the standard library's single-case `Set` wrapper and
    * the `RedBlackTree` it carries, whose lone field is erased to `Object`.
    */
  private def setPlan(element: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    for {
      nodeOrdinal <- redBlackTreeNodeOrdinal
      elementPlan <- elementPlan(element)
    } yield AsSet(elementPlan, List(CD_Object), nodeOrdinal, redBlackTreeNodeFields(element, SimpleType.Unit))

  /**
    * Builds a Map conversion by unwrapping the standard library's single-case `Map` wrapper and
    * the `RedBlackTree` it carries, whose lone field is erased to `Object`.
    */
  private def mapPlan(key: SimpleType, value: SimpleType)(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[ExportPlan] =
    for {
      nodeOrdinal <- redBlackTreeNodeOrdinal
      keyPlan <- elementPlan(key)
      valuePlan <- elementPlan(value)
    } yield AsMap(keyPlan, valuePlan, List(CD_Object), nodeOrdinal, redBlackTreeNodeFields(key, value))

  /**
    * Returns the `Node` ordinal shared by every `RedBlackTree` specialization.
    *
    * There is no symbol here to look up the tree's own field types from, only its ordinals,
    * which every specialization of the same source declaration shares.
    */
  private def redBlackTreeNodeOrdinal(implicit root: ca.uwaterloo.flix.language.ast.JvmAst.Root): Option[Int] =
    ordinalsOf("RedBlackTree").get("Node")

  /**
    * Returns the field types of `RedBlackTree`'s `Node(color, left, key, value, right)` case for
    * the given key and value types, computed by ordinary erasure like every other tag's fields.
    */
  private def redBlackTreeNodeFields(key: SimpleType, value: SimpleType): List[ClassDesc] =
    List(CD_Object, CD_Object, TypeDescs.toErasedClassDesc(key), TypeDescs.toErasedClassDesc(value), CD_Object)

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

  private def isSet(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Set"

  private def isMap(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Map"
}
