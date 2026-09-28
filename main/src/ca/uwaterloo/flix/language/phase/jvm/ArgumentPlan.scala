/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{JvmAst, SimpleType, Symbol}
import ca.uwaterloo.flix.language.jvm.JavaClasses
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.StaticField
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.classes.{GenExportedEnum, GenExportedRecord, GenExportedTuple, GenNullaryTag, GenRecord, GenRecordEmpty, GenRecordExtend, GenTag, GenTagged, GenTuple}
import org.objectweb.asm.MethodVisitor

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.{CD_Object, CD_boolean, CD_byte, CD_char, CD_double, CD_float, CD_int, CD_long, CD_short}

/**
  * How a Java argument to an exported def becomes the Flix value the def expects: the reverse of
  * [[ExportPlan]], by the same rules and the same Java types.
  *
  * Every conversion builds Flix values directly -- tags, tuples, record extensions -- rather than
  * calling Flix code, which is why `Set` and `Map` have none: building a balanced `RedBlackTree`
  * is the standard library's job, not the shim's.
  */
sealed trait ArgumentPlan {

  /** The type the Java caller passes. */
  def signature: ExportSignature

  final def javaType: ClassDesc = signature.javaType

  /**
    * Converts the Java value on the operand stack, of [[javaType]], to its Flix value, which the
    * caller stores at the value's erased type.
    */
  def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit

  /** Converts a Java value held as `Object`, as a Java collection holds it, casting it first. */
  final def emitErased(nextLocal: Int)(implicit mv: MethodVisitor): Unit = {
    if (!javaType.isPrimitive && javaType != CD_Object) CHECKCAST(javaType)
    emit(nextLocal)
  }
}

object ArgumentPlan {

  private val Optional = ClassDesc.ofInternalName("java/util/Optional")
  private val JavaList = ClassDesc.ofInternalName("java/util/List")
  private val JavaCollection = ClassDesc.ofInternalName("java/util/Collection")
  private val JavaIterator = ClassDesc.ofInternalName("java/util/Iterator")
  private val ListIterator = ClassDesc.ofInternalName("java/util/ListIterator")

  private val Unboxers: Map[ClassDesc, (ClassDesc, String)] = Map(
    CD_boolean -> (ClassDesc.ofInternalName("java/lang/Boolean"), "booleanValue"),
    CD_char -> (ClassDesc.ofInternalName("java/lang/Character"), "charValue"),
    CD_byte -> (ClassDesc.ofInternalName("java/lang/Byte"), "byteValue"),
    CD_short -> (ClassDesc.ofInternalName("java/lang/Short"), "shortValue"),
    CD_int -> (ClassDesc.ofInternalName("java/lang/Integer"), "intValue"),
    CD_long -> (ClassDesc.ofInternalName("java/lang/Long"), "longValue"),
    CD_float -> (ClassDesc.ofInternalName("java/lang/Float"), "floatValue"),
    CD_double -> (ClassDesc.ofInternalName("java/lang/Double"), "doubleValue"),
  )

  /** A value whose Java and Flix representations are identical. */
  case class Identity(signature: ExportSignature) extends ArgumentPlan {
    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit = ()
  }

  /** A Java box, from a reference-only type argument position, unboxed to its primitive. */
  case class Unboxed(primitive: ClassDesc) extends ArgumentPlan {
    private val (boxed, method) = Unboxers(primitive)

    override def signature: ExportSignature = ExportSignature.Boxed(primitive, boxed)

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      INVOKEVIRTUAL(boxed, method, MethodTypeDescs.mkDescriptor()(primitive))
  }

  /**
    * Pushes a new data-carrying case `ordinal` of the tag class shaped `fields`, with every field
    * pushed by `pushField`, as `GenExpression.compileTag` builds one.
    */
  private def newTag(fields: List[ClassDesc], ordinal: Int)(pushField: Int => Unit)(implicit mv: MethodVisitor): Unit = {
    NEW(GenTag.desc(fields))
    DUP()
    INVOKESPECIAL(GenTag.Constructor(fields))
    DUP()
    pushInt(ordinal)
    PUTFIELD(GenTag.OrdinalField)
    for (i <- fields.indices) {
      DUP()
      pushField(i)
      PUTFIELD(GenTag.IndexField(fields, i))
    }
  }

  /** A `java.util.Optional` converted to a Flix `Option`. */
  case class FromOptional(element: ArgumentPlan, none: StaticField, someOrdinal: Int, someFields: List[ClassDesc]) extends ArgumentPlan {
    override def signature: ExportSignature = ExportSignature.Applied(Optional, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, Optional) { opt =>
        opt.store()
        opt.load()
        INVOKEVIRTUAL(Optional, "isPresent", MethodTypeDescs.mkDescriptor()(CD_boolean))
        ifConditionElse(Condition.NE) {
          newTag(someFields, someOrdinal) { _ =>
            opt.load()
            INVOKEVIRTUAL(Optional, "get", MethodTypeDescs.mkDescriptor()(CD_Object))
            element.emitErased(nextLocal + 1)
          }
        } {
          GETSTATIC(none)
        }
      }
  }

  /** A `java.util.List` converted to a Flix `List`, consed up from its last element. */
  case class FromList(element: ArgumentPlan, nil: StaticField, consOrdinal: Int, consFields: List[ClassDesc]) extends ArgumentPlan {
    override def signature: ExportSignature = ExportSignature.Applied(JavaList, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, ListIterator) { it =>
        withName(nextLocal + 1, CD_Object) { acc =>
          fromLastIterator(it)
          GETSTATIC(nil)
          acc.store()
          whileLoop(Condition.NE) {
            it.load()
            INVOKEINTERFACE(ListIterator, "hasPrevious", MethodTypeDescs.mkDescriptor()(CD_boolean))
          } {
            newTag(consFields, consOrdinal) {
              case 0 =>
                it.load()
                INVOKEINTERFACE(ListIterator, "previous", MethodTypeDescs.mkDescriptor()(CD_Object))
                element.emitErased(nextLocal + 2)
              case _ => acc.load()
            }
            acc.store()
          }
          acc.load()
        }
      }
  }

  /** Pops the `List` on the stack and stores in `it` a `ListIterator` positioned after its last element. */
  private def fromLastIterator(it: Variable)(implicit mv: MethodVisitor): Unit = {
    DUP()
    INVOKEINTERFACE(JavaList, "size", MethodTypeDescs.mkDescriptor()(CD_int))
    INVOKEINTERFACE(JavaList, "listIterator", MethodTypeDescs.mkDescriptor(CD_int)(ListIterator))
    it.store()
  }

  /** A `java.util.List` converted to a Flix `Vector`, an array of `component`. */
  case class FromVector(element: ArgumentPlan, component: ClassDesc) extends ArgumentPlan {
    override def signature: ExportSignature = ExportSignature.Applied(JavaList, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, JavaIterator) { it =>
        withName(nextLocal + 1, component.arrayType()) { arr =>
          withName(nextLocal + 2, CD_int) { index =>
            DUP()
            INVOKEINTERFACE(JavaList, "size", MethodTypeDescs.mkDescriptor()(CD_int))
            xNewArray(component)
            arr.store()
            INVOKEINTERFACE(JavaList, "iterator", MethodTypeDescs.mkDescriptor()(JavaIterator))
            it.store()
            ICONST_0()
            index.store()
            whileLoop(Condition.NE) {
              it.load()
              INVOKEINTERFACE(JavaIterator, "hasNext", MethodTypeDescs.mkDescriptor()(CD_boolean))
            } {
              arr.load()
              index.load()
              it.load()
              INVOKEINTERFACE(JavaIterator, "next", MethodTypeDescs.mkDescriptor()(CD_Object))
              element.emitErased(nextLocal + 3)
              if (!component.isPrimitive) CHECKCAST(component)
              xArrayStore(component)
              index.load()
              ICONST_1()
              IADD()
              index.store()
            }
            arr.load()
          }
        }
      }
  }

  /**
    * A `java.util.Collection` converted to a Flix `Chain`, built from its last element as
    * `Chain(One(x1), Chain(One(x2), ... One(xn)))`, which never holds an `Empty`.
    */
  case class FromChain(element: ArgumentPlan, empty: StaticField, oneOrdinal: Int, oneFields: List[ClassDesc], chainOrdinal: Int, chainFields: List[ClassDesc]) extends ArgumentPlan {
    override def signature: ExportSignature = ExportSignature.Applied(JavaCollection, List(element.signature))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, ListIterator) { it =>
        withName(nextLocal + 1, CD_Object) { acc =>
          // A copy, since a `Collection` need not be a `List` that can be walked backwards.
          NEW(ClassDesc.ofInternalName("java/util/ArrayList"))
          DUP_X1()
          SWAP()
          INVOKESPECIAL(ClassMaker.ConstructorMethod(ClassDesc.ofInternalName("java/util/ArrayList"), List(JavaCollection)))
          fromLastIterator(it)
          GETSTATIC(empty)
          acc.store()

          def pushOne(): Unit = newTag(oneFields, oneOrdinal) { _ =>
            it.load()
            INVOKEINTERFACE(ListIterator, "previous", MethodTypeDescs.mkDescriptor()(CD_Object))
            element.emitErased(nextLocal + 2)
          }

          it.load()
          INVOKEINTERFACE(ListIterator, "hasPrevious", MethodTypeDescs.mkDescriptor()(CD_boolean))
          ifConditionElse(Condition.NE) {
            pushOne()
            acc.store()
            whileLoop(Condition.NE) {
              it.load()
              INVOKEINTERFACE(ListIterator, "hasPrevious", MethodTypeDescs.mkDescriptor()(CD_boolean))
            } {
              newTag(chainFields, chainOrdinal) {
                case 0 => pushOne()
                case _ => acc.load()
              }
              acc.store()
            }
          } {}
          acc.load()
        }
      }
  }

  /** A generated tuple record converted to the compiler's own tuple, component by component. */
  case class FromTuple(elements: List[ArgumentPlan], flixFields: List[ClassDesc]) extends ArgumentPlan {
    private val javaFields: List[ClassDesc] = elements.map(_.javaType)

    override def signature: ExportSignature = ExportSignature.Exact(GenExportedTuple.desc(javaFields))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, javaType) { tuple =>
        tuple.store()
        NEW(GenTuple.desc(flixFields))
        DUP()
        for ((element, i) <- elements.zipWithIndex) {
          tuple.load()
          INVOKEVIRTUAL(GenExportedTuple.AccessorMethod(javaFields, i))
          element.emit(nextLocal + 1)
        }
        INVOKESPECIAL(GenTuple.Constructor(flixFields))
      }
  }

  /** A generated record converted to the compiler's own record, one extension per field. */
  case class FromRecord(labels: List[String], elements: List[ArgumentPlan], flixFields: List[ClassDesc]) extends ArgumentPlan {
    private val javaFields: List[(String, ClassDesc)] = labels.zip(elements.map(_.javaType))

    override def signature: ExportSignature = ExportSignature.Exact(GenExportedRecord.desc(javaFields))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, javaType) { record =>
        withName(nextLocal + 1, CD_Object) { acc =>
          record.store()
          GETSTATIC(GenRecordEmpty.SingletonField)
          acc.store()
          // Built from the last field, so the first is the outermost extension, as a literal is.
          for (((element, flixField), i) <- elements.zip(flixFields).zipWithIndex.reverse) {
            NEW(GenRecordExtend.desc(flixField))
            DUP()
            INVOKESPECIAL(GenRecordExtend.Constructor(flixField))
            DUP()
            pushString(labels(i))
            PUTFIELD(GenRecordExtend.LabelField(flixField))
            DUP()
            record.load()
            INVOKEVIRTUAL(GenExportedRecord.AccessorMethod(javaFields, i))
            element.emit(nextLocal + 2)
            PUTFIELD(GenRecordExtend.ValueField(flixField))
            DUP()
            acc.load()
            CHECKCAST(GenRecord.Desc)
            PUTFIELD(GenRecordExtend.RestField(flixField))
            acc.store()
          }
          acc.load()
        }
      }
  }

  /** A generated Java enum constant converted to its Flix case, by ordinal. */
  case class FromEnum(ns: List[String], singletons: List[StaticField]) extends ArgumentPlan {
    override def signature: ExportSignature = ExportSignature.Exact(Mangle.namespaceFacadeDesc(ns))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, CD_int) { ordinal =>
        INVOKEVIRTUAL(JavaClasses.Enum, "ordinal", MethodTypeDescs.mkDescriptor()(CD_int))
        ordinal.store()

        def select(cs: List[(StaticField, Int)]): Unit = cs match {
          case Nil => ACONST_NULL()
          case (last, _) :: Nil => GETSTATIC(last)
          case (singleton, i) :: rest =>
            ordinal.load()
            pushInt(i)
            ifConditionElse(Condition.ICMPEQ)(GETSTATIC(singleton))(select(rest))
        }

        select(singletons.zipWithIndex)
      }
  }

  /** One case of a data-carrying enum: its record, and how each field converts back. */
  case class ArgumentCase(name: String, ordinal: Int, elements: List[ArgumentPlan], flixFields: List[ClassDesc], singleton: Option[StaticField])

  /** A case record of a generated sealed interface converted to its Flix case. */
  case class FromSealed(ns: List[String], cases: List[ArgumentCase]) extends ArgumentPlan {
    override def signature: ExportSignature = ExportSignature.Exact(Mangle.namespaceFacadeDesc(ns))

    override def emit(nextLocal: Int)(implicit mv: MethodVisitor): Unit =
      withName(nextLocal, javaType) { value =>
        value.store()

        def construct(c: ArgumentCase): Unit = c.singleton match {
          case Some(singleton) => GETSTATIC(singleton)
          case None =>
            val record = GenExportedEnum.caseDesc(javaType, c.name)
            val javaFields = c.elements.map(_.javaType)
            newTag(c.flixFields, c.ordinal) { i =>
              value.load()
              CHECKCAST(record)
              INVOKEVIRTUAL(record, s"component$i", MethodTypeDescs.mkDescriptor()(javaFields(i)))
              c.elements(i).emit(nextLocal + 1)
            }
        }

        def select(cs: List[ArgumentCase]): Unit = cs match {
          case Nil => ACONST_NULL()
          case last :: Nil => construct(last)
          case c :: rest =>
            value.load()
            INSTANCEOF(GenExportedEnum.caseDesc(javaType, c.name))
            ifConditionElse(Condition.NE)(construct(c))(select(rest))
        }

        select(cases)
      }
  }

  /**
    * Returns the plans of an exported def's parameters, as a Java caller passes them, or `None` if
    * it is not exported or some parameter has no conversion.
    */
  def ofDef(defn: JvmAst.Def)(implicit root: JvmAst.Root, flix: Flix): Option[List[ArgumentPlan]] =
    if (!defn.ann.isExport) None
    else defn.exportedParamTypes.flatMap(tpes => traverse(tpes)(planAt(_, component = false)))

  /**
    * Returns the plan converting a Java value to a Flix value of the declared type `tpe`, by the
    * rules `ExportPlan.planAt` converts the other way: no container as a `component`.
    */
  private def planAt(tpe: SimpleType, component: Boolean)(implicit root: JvmAst.Root, flix: Flix): Option[ArgumentPlan] = {
    val container = !component
    tpe match {
      case SimpleType.Enum(sym, List(element)) if container && isStd(sym, "Option") =>
        for {
          none <- nullary(sym, List(element), "None")
          some <- caseOrdinal("Option", "Some")
          elementPlan <- elementPlan(element)
        } yield FromOptional(elementPlan, none, some, List(TypeDescs.toErasedClassDesc(element)))
      case SimpleType.Enum(sym, List(element)) if container && isStd(sym, "List") =>
        for {
          nil <- nullary(sym, List(element), "Nil")
          cons <- caseOrdinal("List", "Cons")
          elementPlan <- elementPlan(element)
        } yield FromList(elementPlan, nil, cons, List(TypeDescs.toErasedClassDesc(element), CD_Object))
      case SimpleType.Array(element) if container =>
        elementPlan(element).map(FromVector(_, runtimeDesc(element)))
      case SimpleType.Enum(sym, List(element)) if container && isStd(sym, "Chain") =>
        for {
          empty <- nullary(sym, List(element), "Empty")
          one <- caseOrdinal("Chain", "One")
          chain <- caseOrdinal("Chain", "Chain")
          elementPlan <- elementPlan(element)
        } yield FromChain(elementPlan, empty, one, List(TypeDescs.toErasedClassDesc(element)), chain, List(CD_Object, CD_Object))
      case SimpleType.Tuple(elms) =>
        traverse(elms)(planAt(_, component = true)).map(FromTuple(_, elms.map(TypeDescs.toErasedClassDesc)))
      case SimpleType.RecordEmpty | SimpleType.RecordExtend(_, _, _) =>
        for {
          fields <- recordFieldsOf(tpe)
          elementPlans <- traverse(fields.map(_._2))(planAt(_, component = true))
        } yield FromRecord(fields.map(_._1), elementPlans, fields.map(_._2).map(TypeDescs.toErasedClassDesc))
      case SimpleType.Enum(sym, Nil) => enumPlan(sym)
      case SimpleType.Native(_, targs) if container && targs.nonEmpty =>
        ExportPlan.signatureOf(tpe).map(Identity(_))
      case _ => ExportPlan.exact(tpe).filter(_ != ExportPlan.ToVoid).map(p => Identity(p.signature))
    }
  }

  /** Returns a plan for a value in a Java reference-only type argument position, unboxing a primitive. */
  private def elementPlan(tpe: SimpleType)(implicit root: JvmAst.Root, flix: Flix): Option[ArgumentPlan] = {
    val erased = TypeDescs.toErasedClassDesc(tpe)
    if (Unboxers.contains(erased)) Some(Unboxed(erased)) else planAt(tpe, component = false)
  }

  /**
    * Returns the plan of the monomorphic enum `sym`, which is its own only specialization: its
    * cases' nullary singletons are its own, and its fields' declared types are kept in
    * `root.exportedEnumFields`.
    */
  private def enumPlan(sym: Symbol.EnumSym)(implicit root: JvmAst.Root, flix: Flix): Option[ArgumentPlan] =
    root.enums.get(sym).flatMap { enm =>
      val cases = enm.cases.values.toList.sortBy(_.sym.ordinal)
      val ns = GenExportedEnum.companionNamespace(sym)
      if (cases.forall(_.tpes.isEmpty)) Some(FromEnum(ns, cases.map(c => GenNullaryTag.SingletonField(c.sym))))
      else for {
        declared <- root.exportedEnumFields.get(sym)
        argumentCases <- traverse(cases) { c =>
          declared.get(c.sym.name).flatMap(traverse(_)(planAt(_, component = true))).map { elements =>
            val singleton = if (c.tpes.isEmpty) Some(GenNullaryTag.SingletonField(c.sym)) else None
            ArgumentCase(c.sym.name, c.sym.ordinal, elements, c.tpes.map(TypeDescs.toClassDesc), singleton)
          }
        }
      } yield FromSealed(ns, argumentCases)
    }

  /**
    * Returns the singleton of the nullary case `name` of the standard library's `sym` applied to
    * `targs`, from the specialization `Eraser` registered for every enum an exported parameter
    * names.
    */
  private def nullary(sym: Symbol.EnumSym, targs: List[SimpleType], name: String)(implicit root: JvmAst.Root, flix: Flix): Option[StaticField] =
    for {
      specialized <- root.enumSpecializations.get((sym, targs.map(SimpleType.erase)))
      enm <- root.enums.get(specialized)
      caze <- enm.cases.values.find(_.sym.name == name)
    } yield GenNullaryTag.SingletonField(caze.sym)

  /** Returns the ordinal of the case `name` of the standard library's enum `enumName`. */
  private def caseOrdinal(enumName: String, name: String)(implicit root: JvmAst.Root): Option[Int] =
    root.enums.values.find(e => e.sym.namespace.isEmpty && e.sym.text == enumName)
      .flatMap(_.cases.values.find(_.sym.name == name)).map(_.sym.ordinal)

  /** Returns the JVM type a value of the declared type `tpe` has as an array element. */
  private def runtimeDesc(tpe: SimpleType)(implicit root: JvmAst.Root): ClassDesc = tpe match {
    case SimpleType.Enum(_, _) => GenTagged.Desc
    case SimpleType.Array(element) => runtimeDesc(element).arrayType()
    case _ => TypeDescs.toClassDesc(tpe)
  }

  private def recordFieldsOf(tpe: SimpleType): Option[List[(String, SimpleType)]] = tpe match {
    case SimpleType.RecordEmpty => Some(Nil)
    case SimpleType.RecordExtend(label, value, rest) => recordFieldsOf(rest).map((label, value) :: _)
    case _ => None
  }

  private def isStd(sym: Symbol.EnumSym, name: String): Boolean = sym.namespace.isEmpty && sym.text == name

  private def traverse[A, B](xs: List[A])(f: A => Option[B]): Option[List[B]] =
    xs.foldRight(Option(List.empty[B])) {
      case (x, acc) => for (values <- acc; value <- f(x)) yield value :: values
    }
}
