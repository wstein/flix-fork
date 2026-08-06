/*
 * Copyright 2021 Jonathan Lindegaard Starup
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{JvmAst, SimpleType, SourceLocation, Symbol}
import ca.uwaterloo.flix.language.phase.jvm.BackendObjType.mkClassName
import ca.uwaterloo.flix.language.phase.jvm.BytecodeInstructions.*
import ca.uwaterloo.flix.language.phase.jvm.BytecodeInstructions.Branch.*
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.*
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.{IsFinal, NotFinal}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Visibility.{IsPrivate, IsPublic}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Volatility.{IsVolatile, NotVolatile}
import ca.uwaterloo.flix.language.phase.jvm.JvmName.MethodDescriptor.mkDescriptor
import ca.uwaterloo.flix.language.phase.jvm.JvmName.{DevFlixGen, DevFlixRuntime, MethodDescriptor, RootPackage}
import ca.uwaterloo.flix.util.InternalCompilerException
import org.objectweb.asm.{Label, MethodVisitor, Opcodes}

/**
  * Represents all Flix types that are objects on the JVM (array is an exception).
  */
sealed trait BackendObjType {
  /**
    * The `JvmName` that represents the type `Ref(Int)` refers to `"Ref$Int"`.
    */
  val jvmName: JvmName = this match {
    case BackendObjType.Unit => JvmName(DevFlixRuntime, mkClassName("Unit"))
    case BackendObjType.Lazy(tpe) => JvmName(DevFlixGen, mkClassName("Lazy", tpe))
    case BackendObjType.Tuple(elms) => JvmName(DevFlixGen, mkClassName("Tuple", elms))
    case BackendObjType.Struct(elms) => JvmName(DevFlixGen, mkClassName("Struct", elms))
    case BackendObjType.NullaryTag(enumName, sym, _) => JvmName(DevFlixGen, JvmName.mkClassName(enumName, sym))
    case BackendObjType.Tagged => JvmName(DevFlixGen, mkClassName("Tagged"))
    case BackendObjType.Tag(tpes) => JvmName(DevFlixGen, mkClassName("Tag", tpes))
    case BackendObjType.ExtTagged => JvmName(DevFlixGen, mkClassName("ExtTagged"))
    case BackendObjType.ExtTag(tpes) => JvmName(DevFlixGen, mkClassName("ExtTag", tpes))
    case BackendObjType.AbstractArrow(args, result) => JvmName(DevFlixGen, mkClassName(s"Clo${args.length}", args :+ result))
    case BackendObjType.Arrow(args, result) => JvmName(DevFlixGen, mkClassName(s"Fn${args.length}", args :+ result))
    case BackendObjType.Defn(sym) => JvmName.mkNamespacedClassName(sym.namespace, "Def", sym.name)
    case BackendObjType.RecordEmpty => JvmName(DevFlixGen, mkClassName(s"RecordEmpty"))
    case BackendObjType.RecordExtend(value) => JvmName(DevFlixGen, mkClassName("RecordExtend", value))
    case BackendObjType.Record => JvmName(DevFlixGen, mkClassName("Record"))
    case BackendObjType.ReifiedSourceLocation => JvmName(DevFlixRuntime, mkClassName("ReifiedSourceLocation"))
    case BackendObjType.Global => JvmName(DevFlixRuntime, "Global") // "Global" is fixed in source code, so should not be mangled and $ suffixed
    case BackendObjType.HoleError => JvmName(DevFlixRuntime, mkClassName("HoleError"))
    case BackendObjType.MatchError => JvmName(DevFlixRuntime, mkClassName("MatchError"))
    case BackendObjType.CastError => JvmName(DevFlixRuntime, mkClassName("CastError"))
    case BackendObjType.UnhandledEffectError => JvmName(DevFlixRuntime, mkClassName("UnhandledEffectError"))
    case BackendObjType.Region => JvmName(DevFlixRuntime, mkClassName("Region"))
    case BackendObjType.UncaughtExceptionHandler => JvmName(DevFlixRuntime, mkClassName("UncaughtExceptionHandler"))
    case BackendObjType.Main => JvmName(RootPackage, "Main")
    case BackendObjType.Namespace(Nil) => JvmName(DevFlixGen, s"Root${Flix.Delimiter}")
    case BackendObjType.Namespace(ns) => JvmName.facadeOfNamespace(ns)
    // Export views. Named after the element's *erased* type, which is what decides their bytecode:
    // `Set[String]` and `Set[Regex]` share a view, and only the shim's signature tells them apart.
    case BackendObjType.TreeSetView(key, None) => JvmName(DevFlixGen, mkClassName("SetView", key.flixType))
    case BackendObjType.TreeSetView(key, Some(v)) => JvmName(DevFlixGen, mkClassName("EntrySetView", List(key.flixType, v.flixType)))
    case BackendObjType.TreeIterator(key, None) => JvmName(DevFlixGen, mkClassName("SetIterator", key.flixType))
    case BackendObjType.TreeIterator(key, Some(v)) => JvmName(DevFlixGen, mkClassName("EntryIterator", List(key.flixType, v.flixType)))
    case BackendObjType.MapView(key, value) => JvmName(DevFlixGen, mkClassName("MapView", List(key.flixType, value.flixType)))
    case BackendObjType.ListView(element) => JvmName(DevFlixGen, mkClassName("ListView", element.flixType))
    case BackendObjType.VectorView(element) => JvmName(DevFlixGen, mkClassName("VectorView", element.flixType))
    case BackendObjType.ChainIterator(element) => JvmName(DevFlixGen, mkClassName("ChainIterator", element.flixType))
    // Not `ChainIterator` above, which is List's own cons-chain walker and has nothing to do with
    // the stdlib `Chain[t]` type -- named `ExportedChain*` throughout to keep the two unconfusable.
    case BackendObjType.ExportedChainView(element, _, _, _, _) => JvmName(DevFlixGen, mkClassName("ExportedChainView", element.flixType))
    case BackendObjType.ExportedChainIterator(element, _, _, _, _) => JvmName(DevFlixGen, mkClassName("ExportedChainIterator", element.flixType))
    // A caller writes this name in its own source, so unlike the views above it is not mangled and
    // does not live in the package of things the backend is free to rename. Only the arity varies:
    // the element types are its type *parameters*, supplied by the shim's signature.
    case BackendObjType.ExportTuple(arity) => JvmName(DevFlixRuntime, s"Tuple$arity")
    // Shared by shape, the same way `ExportTuple` is shared by arity: keyed on every field's label
    // *and* type, so two records agree on a name exactly when a caller constructing one from either
    // def would get an identical class either way.
    case BackendObjType.ExportRecord(fields) =>
      JvmName(DevFlixRuntime, JvmName.mkClassName("Record", fields.flatMap { case (label, plan) => List(label, BackendObjType.concreteTypeName(plan.javaType)) }))
    // A user type with a name of its own, so it is named beside its namespace like every other
    // class a Java caller writes -- `enum Color` in `mod Acme.Api` is `Acme.Api$Color`. Unlike the
    // views and the tuple record, nothing about it is keyed on a representation.
    case BackendObjType.ExportEnum(sym) =>
      JvmName(JvmName.packageOfNamespace(sym.namespace), JvmName.classPrefixOfNamespace(sym.namespace) + sym.name)
    // A sealed interface is a user type with a name, exactly like `ExportEnum`, so it is named the
    // same way. A case's own record is a user type too, nested one level further under its enum's
    // own name -- `enum Shape { case Circle(...) }` in `mod Acme.Api` gives the interface
    // `Acme.Api$Shape` and the record `Acme.Api$Shape$Circle`, matching what `javac` itself emits
    // for the equivalent hand-written nested record.
    case BackendObjType.ExportSealedEnum(sym) =>
      BackendObjType.namespacedClassName(sym.namespace, sym.name)
    case BackendObjType.ExportCaseRecord(caseSym, _) =>
      BackendObjType.namespacedClassName(caseSym.enumSym.namespace, caseSym.enumSym.name + Flix.Delimiter + caseSym.name)
    // Java classes
    case BackendObjType.Native(className) => className
    // Effects Runtime
    case BackendObjType.Result => JvmName(DevFlixRuntime, mkClassName("Result"))
    case BackendObjType.Value => JvmName(DevFlixRuntime, mkClassName("Value"))
    case BackendObjType.Frame => JvmName(DevFlixRuntime, mkClassName("Frame"))
    case BackendObjType.Thunk => JvmName(DevFlixRuntime, mkClassName("Thunk"))
    case BackendObjType.Suspension => JvmName(DevFlixRuntime, mkClassName("Suspension"))
    case BackendObjType.Frames => JvmName(DevFlixRuntime, mkClassName("Frames"))
    case BackendObjType.FramesCons => JvmName(DevFlixRuntime, mkClassName("FramesCons"))
    case BackendObjType.FramesNil => JvmName(DevFlixRuntime, mkClassName("FramesNil"))
    case BackendObjType.Resumption => JvmName(DevFlixRuntime, mkClassName("Resumption"))
    case BackendObjType.ResumptionCons => JvmName(DevFlixRuntime, mkClassName("ResumptionCons"))
    case BackendObjType.ResumptionNil => JvmName(DevFlixRuntime, mkClassName("ResumptionNil"))
    case BackendObjType.Handler => JvmName(DevFlixRuntime, mkClassName("Handler"))
    case BackendObjType.EffectCall => JvmName(DevFlixRuntime, mkClassName("EffectCall"))
    case BackendObjType.ResumptionWrapper(t) => JvmName(DevFlixRuntime, mkClassName("ResumptionWrapper", t))
  }

  /**
    * The JVM type descriptor of the form `"L<jvmName.toInternalName>;"`.
    */
  def toDescriptor: String = jvmName.toDescriptor

  /**
    * Returns `this` wrapped in `BackendType.Reference`.
    */
  def toTpe: BackendType.Reference = BackendType.Reference(this)

  /** `[] --> return` */
  protected def nullarySuperConstructor(superClass: ConstructorMethod)(implicit mv: MethodVisitor): Unit = {
    thisLoad()
    INVOKESPECIAL(superClass)
    RETURN()
  }

  /** `[] --> return` */
  protected def singletonStaticConstructor(thisConstructor: ConstructorMethod, singleton: StaticField)(implicit mv: MethodVisitor): Unit = {
    NEW(this.jvmName)
    DUP()
    INVOKESPECIAL(thisConstructor)
    PUTSTATIC(singleton)
    RETURN()
  }
}

object BackendObjType {

  private def mkClassName(prefix: String, tpe: BackendType): String = {
    JvmName.mkClassName(prefix, tpe.toErasedString)
  }

  /**
    * A name-safe, concrete-type-distinguishing string for `tpe`, for a naming key that (unlike
    * [[mkClassName]]'s own erased-string form) must tell two different reference types apart.
    *
    * A raw descriptor is not name-safe on its own: `Ljava/lang/String;` embeds `;`, a character
    * `JvmName.mangle` has no replacement for (it exists to mangle Flix operator names, not
    * arbitrary descriptors), and a class file with a `;` in its own name is rejected outright.
    * Dropping the `L`/`;` wrapper leaves the internal name (`java/lang/String`), whose only
    * remaining special character is `/`, which `mangle` already replaces. A primitive's descriptor
    * is a single letter and needs no such care.
    */
  private def concreteTypeName(tpe: BackendType): String = tpe match {
    case BackendType.Reference(ref) => ref.jvmName.toInternalName
    case _ => tpe.toDescriptor
  }

  private def mkClassName(prefix: String, tpes: List[BackendType]): String = {
    JvmName.mkClassName(prefix, tpes.map(_.toErasedString))
  }

  private def mkClassName(prefix: String): String = {
    JvmName.mkClassName(prefix)
  }

  /** The JVM name of a user-declared class named `name`, sitting beside the facade of `ns` per J1. */
  private def namespacedClassName(ns: List[String], name: String): JvmName =
    JvmName(JvmName.packageOfNamespace(ns), JvmName.classPrefixOfNamespace(ns) + name)

  case object Unit extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal)

      cm.mkStaticConstructor(StaticConstructorMethod(this.jvmName), singletonStaticConstructor(Constructor, SingletonField)(_))
      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkField(SingletonField, IsPublic, IsFinal, NotVolatile)

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def SingletonField: StaticField = StaticField(this.jvmName, "INSTANCE", this.toTpe)

  }

  case class Lazy(tpe: BackendType) extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(ExpField, IsPublic, NotFinal, IsVolatile)
      cm.mkField(ValueField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(LockField, IsPrivate, NotFinal, NotVolatile)
      cm.mkMethod(Nil, ForceMethod, IsPublic, IsFinal, forceIns(_))

      cm.closeClassMaker()
    }

    def ExpField: InstanceField = InstanceField(this.jvmName, "expression", BackendType.Object)

    def ValueField: InstanceField = InstanceField(this.jvmName, "value", tpe)

    private def LockField: InstanceField = InstanceField(this.jvmName, "lock", JvmName.ReentrantLock.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(BackendType.Object))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, BackendType.Object)(exp => {
        // super()
        thisLoad()
        INVOKESPECIAL(ClassConstants.Object.Constructor)
        // this.exp = exp
        thisLoad()
        exp.load()
        PUTFIELD(ExpField)
        // this.lock = new ReentrantLock()
        thisLoad()
        NEW(JvmName.ReentrantLock)
        DUP()
        INVOKESPECIAL(ClassConstants.ReentrantLock.Constructor)
        PUTFIELD(LockField)
        // return
        RETURN()
      })

    def ForceMethod: InstanceMethod = InstanceMethod(this.jvmName, "force", mkDescriptor()(tpe))

    /** `[] --> return tpe` */
    private def forceIns(implicit mv: MethodVisitor): Unit = {
      def unlockLock(): Unit = {
        thisLoad()
        GETFIELD(LockField)
        INVOKEVIRTUAL(ClassConstants.ReentrantLock.UnlockMethod)
      }

      thisLoad()
      GETFIELD(LockField)
      INVOKEVIRTUAL(ClassConstants.ReentrantLock.LockInterruptiblyMethod)
      tryCatch {
        thisLoad()
        GETFIELD(ExpField)
        // if the expression is not null, compute the value and erase the expression
        ifCondition(Condition.NONNULL) {
          thisLoad()
          // get expression as thunk
          DUP()
          GETFIELD(ExpField)
          CHECKCAST(Thunk.jvmName)
          // this.value = thunk.unwind()
          Result.unwindSuspensionFreeThunkToType(tpe, "during call to Lazy.force", SourceLocation.Unknown)
          PUTFIELD(ValueField)
          // this.exp = null
          thisLoad()
          pushNull()
          PUTFIELD(ExpField)
        }
        thisLoad()
        GETFIELD(ValueField)
      } {
        // catch
        unlockLock()
        ATHROW()
      }
      unlockLock()
      xReturn(tpe)
    }
  }

  case class Tuple(elms: List[BackendType]) extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      elms.indices.foreach(i => cm.mkField(IndexField(i), IsPublic, NotFinal, NotVolatile))
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))

      cm.closeClassMaker()
    }

    def IndexField(i: Int): InstanceField = InstanceField(this.jvmName, s"field$i", elms(i))

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, elms)

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withNames(1, elms) { case (_, variables) =>
        thisLoad()
        // super()
        DUP()
        INVOKESPECIAL(ClassConstants.Object.Constructor)
        // this.field$i = var$j
        for ((elm, i) <- variables.zipWithIndex) {
          DUP()
          elm.load()
          PUTFIELD(IndexField(i))
        }
        RETURN()
      }

  }

  case class Struct(elms: List[BackendType]) extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      elms.indices.foreach(i => cm.mkField(IndexField(i), IsPublic, NotFinal, NotVolatile))
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))

      cm.closeClassMaker()
    }

    def IndexField(i: Int): InstanceField = InstanceField(this.jvmName, s"field$i", elms(i))

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, elms)

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      withNames(1, elms) { case (_, variables) =>
        thisLoad()
        // super()
        DUP()
        INVOKESPECIAL(ClassConstants.Object.Constructor)
        // this.field$i = var$j
        // fields are numbered consecutively while variables skip indices based
        // on their stack size
        for ((elm, i) <- variables.zipWithIndex) {
          DUP()
          elm.load()
          PUTFIELD(IndexField(i))
        }
        RETURN()
      }
    }

  }

  case object Tagged extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkAbstractClass(this.jvmName)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))

      cm.mkField(OrdinalField, IsPublic, NotFinal, NotVolatile)

      cm.closeClassMaker()
    }

    def OrdinalField: InstanceField = InstanceField(this.jvmName, "ordinal", BackendType.Int32)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)
  }

  sealed trait TagType extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte]
  }

  case class NullaryTag(enumName: String, name: String, ordinal: Int) extends TagType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = Tagged.jvmName)

      cm.mkStaticConstructor(StaticConstructorMethod(this.jvmName), singletonStaticConstructor(Constructor, SingletonField)(_))
      cm.mkField(SingletonField, IsPublic, IsFinal, NotVolatile)
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))

      cm.closeClassMaker()
    }

    def SingletonField: StaticField = StaticField(this.jvmName, "singleton", this.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      INVOKESPECIAL(Tagged.Constructor)
      thisLoad()
      pushInt(ordinal)
      PUTFIELD(Tagged.OrdinalField)
      RETURN()
    }
  }

  case class Tag(elms: List[BackendType]) extends TagType {
    if (elms.isEmpty) throw InternalCompilerException(s"Unexpected nullary Tag type", SourceLocation.Unknown)

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = Tagged.jvmName)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(Tagged.Constructor)(_))
      elms.indices.foreach(i => cm.mkField(IndexField(i), IsPublic, NotFinal, NotVolatile))

      cm.closeClassMaker()
    }

    def OrdinalField: InstanceField = Tagged.OrdinalField

    def IndexField(i: Int): InstanceField = InstanceField(this.jvmName, s"v$i", elms(i))

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)
  }

  case object ExtTagged extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkAbstractClass(this.jvmName)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))

      cm.mkField(NameField, IsPublic, NotFinal, NotVolatile)

      cm.closeClassMaker()
    }

    def NameField: InstanceField = InstanceField(this.jvmName, "tag", BackendType.String)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    /** [...] -> [..., tagName] */
    def mkTagName(name: String)(implicit mv: MethodVisitor): Unit = pushString(JvmOps.getTagName(name))

    /** [..., tagName1, tagName2] --> [..., tagName1 == tagName2] */
    def eqTagName()(implicit mv: MethodVisitor): Unit = {
      // ACMP is okay since tag strings are loaded through ldc instructions
      ifConditionElse(Condition.ACMPEQ)(pushBool(true))(pushBool(false))
    }
  }

  case class ExtTag(elms: List[BackendType]) extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = ExtTagged.jvmName)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ExtTagged.Constructor)(_))
      elms.indices.foreach(i => cm.mkField(IndexField(i), IsPublic, NotFinal, NotVolatile))

      cm.closeClassMaker()
    }

    def NameField: InstanceField = ExtTagged.NameField

    def IndexField(i: Int): InstanceField = InstanceField(this.jvmName, s"v$i", elms(i))

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)
  }

  /**
    * (Int, String) -> Bool example:
    * public abstract class Clo2$Int$Obj$Bool extends Fn2$Int$Obj$Bool {
    * public Clo2$Int$Obj$Bool() { ... }
    * public abstract Clo2$Int$Obj$Bool getUniqueThreadClosure();
    * }
    */
  case class AbstractArrow(args: List[BackendType], result: BackendType) extends BackendObjType {

    def superClass: BackendObjType.Arrow = Arrow(args, result)

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkAbstractClass(this.jvmName, superClass.jvmName)
      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(superClass.Constructor)(_))

      cm.mkAbstractMethod(GetUniqueThreadClosureMethod)

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def GetUniqueThreadClosureMethod: AbstractMethod = AbstractMethod(this.jvmName, "getUniqueThreadClosure", mkDescriptor()(this.toTpe))

  }

  case class Arrow(args: List[BackendType], result: BackendType) extends BackendObjType {

    /**
      * Represents a function interface from `java.util.function`.
      */
    sealed trait FunctionInterface {
      /**
        * The JvmName of the interface.
        */
      def jvmName: JvmName = this match {
        case ObjFunction => JvmName.ObjFunction
        case ObjConsumer => JvmName.ObjConsumer
        case ObjPredicate => JvmName.ObjPredicate
        case IntFunction => JvmName.IntFunction
        case IntConsumer => JvmName.IntConsumer
        case IntPredicate => JvmName.IntPredicate
        case IntUnaryOperator => JvmName.IntUnaryOperator
        case LongFunction => JvmName.LongFunction
        case LongConsumer => JvmName.LongConsumer
        case LongPredicate => JvmName.LongPredicate
        case LongUnaryOperator => JvmName.LongUnaryOperator
        case DoubleFunction => JvmName.DoubleFunction
        case DoubleConsumer => JvmName.DoubleConsumer
        case DoublePredicate => JvmName.DoublePredicate
        case DoubleUnaryOperator => JvmName.DoubleUnaryOperator
      }

      /**
        * The required method of the interface.
        * These methods should do the same as a non-tail call in genExpression.
        */
      def functionMethod: InstanceMethod = this match {
        case ObjFunction => InstanceMethod(this.jvmName, "apply",
          mkDescriptor(BackendType.Object)(BackendType.Object))
        case ObjConsumer => InstanceMethod(this.jvmName, "accept",
          mkDescriptor(BackendType.Object)(VoidableType.Void))
        case ObjPredicate => InstanceMethod(this.jvmName, "test",
          mkDescriptor(BackendType.Object)(BackendType.Bool))
        case IntFunction => InstanceMethod(this.jvmName, "apply",
          mkDescriptor(BackendType.Int32)(BackendType.Object))
        case IntConsumer => InstanceMethod(this.jvmName, "accept",
          mkDescriptor(BackendType.Int32)(VoidableType.Void))
        case IntPredicate => InstanceMethod(this.jvmName, "test",
          mkDescriptor(BackendType.Int32)(BackendType.Bool))
        case IntUnaryOperator => InstanceMethod(this.jvmName, "applyAsInt",
          mkDescriptor(BackendType.Int32)(BackendType.Int32))
        case LongFunction => InstanceMethod(this.jvmName, "apply",
          mkDescriptor(BackendType.Int64)(BackendType.Object))
        case LongConsumer => InstanceMethod(this.jvmName, "accept",
          mkDescriptor(BackendType.Int64)(VoidableType.Void))
        case LongPredicate => InstanceMethod(this.jvmName, "test",
          mkDescriptor(BackendType.Int64)(BackendType.Bool))
        case LongUnaryOperator => InstanceMethod(this.jvmName, "applyAsLong",
          mkDescriptor(BackendType.Int64)(BackendType.Int64))
        case DoubleFunction => InstanceMethod(this.jvmName, "apply",
          mkDescriptor(BackendType.Float64)(BackendType.Object))
        case DoubleConsumer => InstanceMethod(this.jvmName, "accept",
          mkDescriptor(BackendType.Float64)(VoidableType.Void))
        case DoublePredicate => InstanceMethod(this.jvmName, "test",
          mkDescriptor(BackendType.Float64)(BackendType.Bool))
        case DoubleUnaryOperator => InstanceMethod(this.jvmName, "applyAsDouble",
          mkDescriptor(BackendType.Float64)(BackendType.Float64))
      }

      /**
        * The required method of the interface.
        * These methods should do the same as a non-tail call in genExpression.
        */
      def functionIns(implicit mv: MethodVisitor): Unit = this match {
        case ObjFunction =>
          thisLoad()
          DUP()
          ALOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          ARETURN()
        case ObjConsumer =>
          thisLoad()
          DUP()
          ALOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          RETURN()
        case ObjPredicate =>
          thisLoad()
          DUP()
          ALOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Bool, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          IRETURN()
        case IntFunction =>
          thisLoad()
          DUP()
          ILOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          ARETURN()
        case IntConsumer =>
          thisLoad()
          DUP()
          ILOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          RETURN()
        case IntPredicate =>
          thisLoad()
          DUP()
          ILOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Bool, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          IRETURN()
        case IntUnaryOperator =>
          thisLoad()
          DUP()
          ILOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Int32, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          IRETURN()
        case LongFunction =>
          thisLoad()
          DUP()
          LLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          ARETURN()
        case LongConsumer =>
          thisLoad()
          DUP()
          LLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          RETURN()
        case LongPredicate =>
          thisLoad()
          DUP()
          LLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Bool, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          IRETURN()
        case LongUnaryOperator =>
          thisLoad()
          DUP()
          LLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Int64, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          LRETURN()
        case DoubleFunction =>
          thisLoad()
          DUP()
          DLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          ARETURN()
        case DoubleConsumer =>
          thisLoad()
          DUP()
          DLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Object, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          RETURN()
        case DoublePredicate =>
          thisLoad()
          DUP()
          DLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Bool, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          IRETURN()
        case DoubleUnaryOperator =>
          thisLoad()
          DUP()
          DLOAD(1)
          PUTFIELD(ArgField(0))
          Result.unwindSuspensionFreeThunkToType(BackendType.Float64, s"in ${jvmName.toBinaryName}", SourceLocation.Unknown)
          DRETURN()
      }
    }

    // ClassMaker.Object -> ClassMaker.Object
    case object ObjFunction extends FunctionInterface

    // ClassMaker.Object -> Unit
    case object ObjConsumer extends FunctionInterface

    // ClassMaker.Object -> Bool
    case object ObjPredicate extends FunctionInterface

    // Int32 -> ClassMaker.Object
    case object IntFunction extends FunctionInterface

    // Int32 -> Unit
    case object IntConsumer extends FunctionInterface

    // Int32 -> Bool
    case object IntPredicate extends FunctionInterface

    // Int32 -> Int32
    case object IntUnaryOperator extends FunctionInterface

    // Int64 -> ClassMaker.Object
    case object LongFunction extends FunctionInterface

    // Int64 -> Unit
    case object LongConsumer extends FunctionInterface

    // Int64 -> Bool
    case object LongPredicate extends FunctionInterface

    // Int64 -> Int64
    case object LongUnaryOperator extends FunctionInterface

    // Float64 -> ClassMaker.Object
    case object DoubleFunction extends FunctionInterface

    // Float64 -> Unit
    case object DoubleConsumer extends FunctionInterface

    // Float64 -> Bool
    case object DoublePredicate extends FunctionInterface

    // Float64 -> Float64
    case object DoubleUnaryOperator extends FunctionInterface

    /**
      * Returns the specialized java function interfaces of the function type.
      */
    private def specialization(): List[FunctionInterface] = {
      (args, result) match {
        case (BackendType.Reference(BackendObjType.Native(JvmName.Object)) :: Nil, _) =>
          ObjFunction :: ObjConsumer :: ObjPredicate :: Nil
        case (BackendType.Int32 :: Nil, _) =>
          IntFunction :: IntConsumer :: IntPredicate :: IntUnaryOperator :: Nil
        case (BackendType.Int64 :: Nil, _) =>
          LongFunction :: LongConsumer :: LongPredicate :: LongUnaryOperator :: Nil
        case (BackendType.Float64 :: Nil, _) =>
          DoubleFunction :: DoubleConsumer :: DoublePredicate :: DoubleUnaryOperator :: Nil
        case _ => Nil
      }
    }

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val specializedInterface = specialization()
      val interfaces = Thunk.jvmName :: specializedInterface.map(_.jvmName)

      val cm = ClassMaker.mkAbstractClass(this.jvmName, superClass = JvmName.Object, interfaces)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      args.indices.foreach(argIndex => cm.mkField(ArgField(argIndex), IsPublic, NotFinal, NotVolatile))
      specializedInterface.foreach(i => cm.mkMethod(i.functionMethod, IsPublic, NotFinal, i.functionIns(_)))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def ArgField(index: Int): InstanceField = InstanceField(this.jvmName, s"arg$index", args(index))
  }

  case class Defn(sym: Symbol.DefnSym) extends BackendObjType

  case object RecordEmpty extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, interfaces = List(this.interface.jvmName))

      cm.mkStaticConstructor(StaticConstructorMethod(this.jvmName), singletonStaticConstructor(Constructor, SingletonField)(_))
      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkField(SingletonField, IsPublic, IsFinal, NotVolatile)
      cm.mkMethod(Nil, LookupFieldMethod, IsPublic, IsFinal, throwUnsupportedExc(_))
      cm.mkMethod(Nil, RestrictFieldMethod, IsPublic, IsFinal, throwUnsupportedExc(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def interface: Record.type = Record

    def SingletonField: StaticField = StaticField(this.jvmName, "INSTANCE", this.toTpe)

    private def LookupFieldMethod: InstanceMethod = interface.LookupFieldMethod.implementation(this.jvmName)

    private def RestrictFieldMethod: InstanceMethod = interface.RestrictFieldMethod.implementation(this.jvmName)

    private def throwUnsupportedExc(implicit mv: MethodVisitor): Unit = {
      throwUnsupportedOperationException(
        s"${Record.LookupFieldMethod.name} method shouldn't be called")
    }
  }

  case class RecordExtend(value: BackendType) extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, interfaces = List(Record.jvmName))

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkField(LabelField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(ValueField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(RestField, IsPublic, NotFinal, NotVolatile)
      cm.mkMethod(Nil, Record.LookupFieldMethod.implementation(this.jvmName), IsPublic, IsFinal, lookupFieldIns(_))
      cm.mkMethod(Nil, RestrictFieldMethod, IsPublic, IsFinal, restrictFieldIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def LabelField: InstanceField = InstanceField(this.jvmName, "label", BackendType.String)

    def ValueField: InstanceField = InstanceField(this.jvmName, "value", value)

    def RestField: InstanceField = InstanceField(this.jvmName, "rest", Record.toTpe)

    private def lookupFieldIns(implicit mv: MethodVisitor): Unit = {
      caseOnLabelEquality {
        case TrueBranch =>
          thisLoad()
          ARETURN()
        case FalseBranch =>
          thisLoad()
          GETFIELD(RestField)
          ALOAD(1)
          INVOKEINTERFACE(Record.LookupFieldMethod)
          ARETURN()
      }
    }

    def RestrictFieldMethod: InstanceMethod = Record.RestrictFieldMethod.implementation(this.jvmName)

    private def restrictFieldIns(implicit mv: MethodVisitor): Unit = {
      caseOnLabelEquality {
        case TrueBranch =>
          thisLoad()
          GETFIELD(RestField)
          ARETURN()
        case FalseBranch =>
          NEW(this.jvmName)
          DUP()
          INVOKESPECIAL(this.Constructor)
          DUP()
          thisLoad()
          GETFIELD(LabelField)
          PUTFIELD(LabelField)
          DUP()
          thisLoad()
          GETFIELD(ValueField)
          PUTFIELD(ValueField)
          DUP() // get the new restricted rest to put
          thisLoad()
          GETFIELD(RestField)
          ALOAD(1)
          INVOKEINTERFACE(Record.RestrictFieldMethod)
          PUTFIELD(RestField) // put the rest field and return
          ARETURN()
      }
    }

    /**
      * Compares the label of `this`and `ALOAD(1)` and executes the designated branch.
      */
    private def caseOnLabelEquality(cases: Branch => Unit)(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(LabelField)
      ALOAD(1)
      INVOKEVIRTUAL(ClassConstants.Object.EqualsMethod)
      branch(Condition.Bool)(cases)
    }
  }

  case object Record extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkInterface(this.jvmName)

      cm.mkInterfaceMethod(LookupFieldMethod)
      cm.mkInterfaceMethod(RestrictFieldMethod)

      cm.closeClassMaker()
    }

    def LookupFieldMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "lookupField",
      mkDescriptor(BackendType.String)(this.toTpe))

    def RestrictFieldMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "restrictField",
      mkDescriptor(BackendType.String)(this.toTpe))
  }

  /**
    * Represents a JVM type not represented in BackendObjType.
    * This should not be used for `java.lang.String` for example since `BackendObjType.String`
    * represents this type.
    */
  case class Native(className: JvmName) extends BackendObjType

  case object ReifiedSourceLocation extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))

      cm.mkField(SourceField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(BeginLineField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(BeginColField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(EndLineField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(EndColField, IsPublic, IsFinal, NotVolatile)

      cm.mkMethod(Nil, ToStringMethod, IsPublic, NotFinal, toStringIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(
      this.jvmName, List(BackendType.String, BackendType.Int32, BackendType.Int32, BackendType.Int32, BackendType.Int32)
    )

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      INVOKESPECIAL(ClassConstants.Object.Constructor)
      thisLoad()
      ALOAD(1)
      PUTFIELD(SourceField)
      thisLoad()
      ILOAD(2)
      PUTFIELD(BeginLineField)
      thisLoad()
      ILOAD(3)
      PUTFIELD(BeginColField)
      thisLoad()
      ILOAD(4)
      PUTFIELD(EndLineField)
      thisLoad()
      ILOAD(5)
      PUTFIELD(EndColField)
      RETURN()
    }

    private def SourceField: InstanceField =
      InstanceField(this.jvmName, "source", BackendType.String)

    private def BeginLineField: InstanceField =
      InstanceField(this.jvmName, "beginLine", BackendType.Int32)

    private def BeginColField: InstanceField =
      InstanceField(this.jvmName, "beginCol", BackendType.Int32)

    private def EndLineField: InstanceField =
      InstanceField(this.jvmName, "endLine", BackendType.Int32)

    private def EndColField: InstanceField =
      InstanceField(this.jvmName, "endCol", BackendType.Int32)

    private def ToStringMethod: InstanceMethod = ClassConstants.Object.ToStringMethod.implementation(this.jvmName)

    private def toStringIns(implicit mv: MethodVisitor): Unit = {
      // create string builder
      NEW(JvmName.StringBuilder)
      DUP()
      INVOKESPECIAL(ClassConstants.StringBuilder.Constructor)
      // build string
      thisLoad()
      GETFIELD(SourceField)
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
      pushString(":")
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
      thisLoad()
      GETFIELD(BeginLineField)
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendInt32Method)
      pushString(":")
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
      thisLoad()
      GETFIELD(BeginColField)
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendInt32Method)
      // create the string
      INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
      ARETURN()
    }
  }

  case object Global extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkStaticConstructor(StaticConstructorMethod(this.jvmName), staticConstructorIns(_))

      cm.mkField(CounterField, IsPrivate, IsFinal, NotVolatile)
      cm.mkStaticMethod(NewIdMethod, IsPublic, IsFinal, newIdIns(_))

      cm.mkField(ArgsField, IsPrivate, NotFinal, NotVolatile)
      cm.mkStaticMethod(GetArgsMethod, IsPublic, IsFinal, getArgsIns(_))
      cm.mkStaticMethod(SetArgsMethod, IsPublic, IsFinal, setArgsIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    private def staticConstructorIns(implicit mv: MethodVisitor): Unit = {
      NEW(JvmName.AtomicLong)
      DUP()
      invokeConstructor(JvmName.AtomicLong, MethodDescriptor.NothingToVoid)
      PUTSTATIC(CounterField)
      ICONST_0()
      ANEWARRAY(JvmName.String)
      PUTSTATIC(ArgsField)
      RETURN()
    }

    private def NewIdMethod: StaticMethod = StaticMethod(this.jvmName, "newId", mkDescriptor()(BackendType.Int64))

    private def newIdIns(implicit mv: MethodVisitor): Unit = {
      GETSTATIC(CounterField)
      INVOKEVIRTUAL(JvmName.AtomicLong, "getAndIncrement",
        MethodDescriptor(Nil, BackendType.Int64))
      LRETURN()
    }

    private def GetArgsMethod: StaticMethod = StaticMethod(this.jvmName, "getArgs", mkDescriptor()(BackendType.Array(BackendType.String)))

    private def getArgsIns(implicit mv: MethodVisitor): Unit = {
      GETSTATIC(ArgsField)
      ARRAYLENGTH()
      ANEWARRAY(JvmName.String)
      ASTORE(0)
      // the new array is now created, now to copy the args
      GETSTATIC(ArgsField)
      ICONST_0()
      ALOAD(0)
      ICONST_0()
      GETSTATIC(ArgsField)
      ARRAYLENGTH()
      arrayCopy()
      ALOAD(0)
      ARETURN()
    }

    def SetArgsMethod: StaticMethod =
      StaticMethod(this.jvmName, "setArgs", mkDescriptor(BackendType.Array(BackendType.String))(VoidableType.Void))

    private def setArgsIns(implicit mv: MethodVisitor): Unit = {
      ALOAD(0)
      ARRAYLENGTH()
      ANEWARRAY(JvmName.String)
      ASTORE(1)
      ALOAD(0)
      ICONST_0()
      ALOAD(1)
      ICONST_0()
      ALOAD(0)
      ARRAYLENGTH()
      arrayCopy()
      ALOAD(1)
      PUTSTATIC(ArgsField)
      RETURN()
    }

    private def CounterField: StaticField = StaticField(this.jvmName, "counter", JvmName.AtomicLong.toTpe)

    private def ArgsField: StaticField = StaticField(this.jvmName, "args", BackendType.Array(BackendType.String))

    private def arrayCopy()(implicit mv: MethodVisitor): Unit = {
      mv.visitMethodInstruction(Opcodes.INVOKESTATIC, JvmName.System, "arraycopy",
        MethodDescriptor(List(BackendType.Object, BackendType.Int32, BackendType.Object, BackendType.Int32,
          BackendType.Int32), VoidableType.Void), isInterface = false)
    }
  }

  case object HoleError extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, JvmName.FlixError)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      // These fields allow external equality checking.
      cm.mkField(HoleField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(LocationField, IsPublic, IsFinal, NotVolatile)

      cm.closeClassMaker()
    }

    private def HoleField: InstanceField = InstanceField(this.jvmName, "hole", BackendType.String)

    private def LocationField: InstanceField = InstanceField(this.jvmName, "location", ReifiedSourceLocation.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(BackendType.String, ReifiedSourceLocation.toTpe))

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      withName(1, BackendType.String) { hole =>
        withName(2, ReifiedSourceLocation.toTpe) { loc =>
          thisLoad()
          // create an error msg
          NEW(JvmName.StringBuilder)
          DUP()
          INVOKESPECIAL(ClassConstants.StringBuilder.Constructor)
          pushString("Hole '")
          INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
          hole.load()
          INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
          pushString("' at ")
          INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
          loc.load()
          INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
          INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
          INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
          INVOKESPECIAL(ClassConstants.FlixError.Constructor)
          // save the arguments locally
          thisLoad()
          hole.load()
          PUTFIELD(HoleField)
          thisLoad()
          loc.load()
          PUTFIELD(LocationField)
          RETURN()
        }
      }
    }
  }

  case object MatchError extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(MatchError.jvmName, IsFinal, superClass = JvmName.FlixError)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      // This field allows external equality checking.
      cm.mkField(LocationField, IsPublic, IsFinal, NotVolatile)

      cm.closeClassMaker()
    }

    private def LocationField: InstanceField = InstanceField(this.jvmName, "location", ReifiedSourceLocation.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(MatchError.jvmName, List(ReifiedSourceLocation.toTpe))

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      NEW(JvmName.StringBuilder)
      DUP()
      INVOKESPECIAL(ClassConstants.StringBuilder.Constructor)
      pushString("Non-exhaustive match at ")
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
      ALOAD(1)
      INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
      INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
      INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
      INVOKESPECIAL(ClassConstants.FlixError.Constructor)
      // save argument locally
      thisLoad()
      ALOAD(1)
      PUTFIELD(this.LocationField)
      RETURN()
    }
  }

  case object CastError extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.FlixError)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(ReifiedSourceLocation.toTpe, BackendType.String))

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      withName(1, ReifiedSourceLocation.toTpe)(loc => withName(2, BackendType.String)(msg => {
        thisLoad()
        NEW(JvmName.StringBuilder)
        DUP()
        INVOKESPECIAL(ClassConstants.StringBuilder.Constructor)
        msg.load()
        INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
        pushString(" at ")
        INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
        loc.load()
        INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
        INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)
        INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
        INVOKESPECIAL(ClassConstants.FlixError.Constructor)
        RETURN()
      }))
    }
  }

  case object UnhandledEffectError extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.FlixError)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      // This field allows external equality checking.
      cm.mkField(EffectNameField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(LocationField, IsPublic, IsFinal, NotVolatile)

      cm.closeClassMaker()
    }

    private def EffectNameField: InstanceField = InstanceField(this.jvmName, "effectName", BackendType.String)

    private def LocationField: InstanceField = InstanceField(this.jvmName, "location", ReifiedSourceLocation.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Suspension.toTpe, BackendType.String, ReifiedSourceLocation.toTpe))

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      withName(1, Suspension.toTpe)(suspension => withName(2, BackendType.String)(info => withName(3, ReifiedSourceLocation.toTpe)(loc => {
        def appendString(): Unit = INVOKEVIRTUAL(ClassConstants.StringBuilder.AppendStringMethod)

        thisLoad()
        NEW(JvmName.StringBuilder)
        DUP()
        INVOKESPECIAL(ClassConstants.StringBuilder.Constructor)
        pushString("Unhandled effect '")
        appendString()
        suspension.load()
        GETFIELD(Suspension.EffSymField)
        appendString()
        pushString("' (")
        appendString()
        info.load()
        appendString()
        pushString(") at ")
        appendString()
        loc.load()
        INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
        appendString()
        INVOKEVIRTUAL(ClassConstants.Object.ToStringMethod)
        INVOKESPECIAL(ClassConstants.FlixError.Constructor)
        // save arguments locally
        thisLoad()
        suspension.load()
        GETFIELD(Suspension.EffSymField)
        PUTFIELD(EffectNameField)
        thisLoad()
        loc.load()
        PUTFIELD(LocationField)
        RETURN()
      })))
    }
  }


  case object Region extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal)

      cm.mkField(ThreadsField, IsPrivate, IsFinal, NotVolatile)
      cm.mkField(RegionThreadField, IsPrivate, IsFinal, NotVolatile)
      cm.mkField(ChildExceptionField, IsPrivate, NotFinal, IsVolatile)
      cm.mkField(OnExitField, IsPrivate, IsFinal, NotVolatile)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))

      cm.mkMethod(Nil, SpawnMethod, IsPublic, IsFinal, spawnIns(_))
      cm.mkMethod(Nil, ExitMethod, IsPublic, IsFinal, exitIns(_))
      cm.mkMethod(Nil, ReportChildExceptionMethod, IsPublic, IsFinal, reportChildExceptionIns(_))
      cm.mkMethod(Nil, ReThrowChildExceptionMethod, IsPublic, IsFinal, reThrowChildExceptionIns(_))
      cm.mkMethod(Nil, RunOnExitMethod, IsPublic, IsFinal, runOnExitIns(_))

      cm.closeClassMaker()
    }

    // private final ConcurrentLinkedQueue<Thread> threads = new ConcurrentLinkedQueue<Thread>();
    private def ThreadsField: InstanceField = InstanceField(this.jvmName, "threads", JvmName.ConcurrentLinkedQueue.toTpe)

    // private final LinkedList<Runnable> onExit = new LinkedList<Runnable>();
    private def OnExitField: InstanceField = InstanceField(this.jvmName, "onExit", JvmName.LinkedList.toTpe)

    // private final Thread regionThread = Thread.currentThread();
    private def RegionThreadField: InstanceField = InstanceField(this.jvmName, "regionThread", JvmName.Thread.toTpe)

    // private volatile Throwable childException = null;
    private def ChildExceptionField: InstanceField = InstanceField(this.jvmName, "childException", JvmName.Throwable.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      INVOKESPECIAL(ClassConstants.Object.Constructor)
      thisLoad()
      NEW(JvmName.ConcurrentLinkedQueue)
      DUP()
      invokeConstructor(JvmName.ConcurrentLinkedQueue, MethodDescriptor.NothingToVoid)
      PUTFIELD(ThreadsField)
      thisLoad()
      INVOKESTATIC(ClassConstants.Thread.CurrentThreadMethod)
      PUTFIELD(RegionThreadField)
      thisLoad()
      ACONST_NULL()
      PUTFIELD(ChildExceptionField)
      thisLoad()
      NEW(JvmName.LinkedList)
      DUP()
      invokeConstructor(JvmName.LinkedList, MethodDescriptor.NothingToVoid)
      PUTFIELD(OnExitField)
      RETURN()
    }

    // final public void spawn(Runnable r) {
    //   Thread t = new Thread(r);
    //   t.setUncaughtExceptionHandler(new UncaughtExceptionHandler(this));
    //   t.start();
    //   threads.add(t);
    // }
    def SpawnMethod: InstanceMethod = InstanceMethod(this.jvmName, "spawn", mkDescriptor(JvmName.Runnable.toTpe)(VoidableType.Void))

    private def spawnIns(implicit mv: MethodVisitor): Unit = {
      INVOKESTATIC(ClassConstants.Thread.OfVirtualMethod)
      ALOAD(1)
      INVOKEINTERFACE(ClassConstants.ThreadBuilderOfVirtual.UnstartedMethod)
      storeWithName(2, JvmName.Thread.toTpe) { thread =>
        thread.load()
        NEW(BackendObjType.UncaughtExceptionHandler.jvmName)
        DUP()
        thisLoad()
        invokeConstructor(BackendObjType.UncaughtExceptionHandler.jvmName, mkDescriptor(BackendObjType.Region.toTpe)(VoidableType.Void))
        INVOKEVIRTUAL(ClassConstants.Thread.SetUncaughtExceptionHandlerMethod)
        thread.load()
        INVOKEVIRTUAL(ClassConstants.Thread.StartMethod)
        thisLoad()
        GETFIELD(ThreadsField)
        thread.load()
        INVOKEVIRTUAL(ClassConstants.ConcurrentLinkedQueue.AddMethod)
        POP()
        RETURN()
      }
    }

    // final public void exit() throws InterruptedException {
    //   Thread t;
    //   while ((t = threads.poll()) != null)
    //     t.join();
    //   for (Runnable r: onExit)
    //     r.run();
    // }
    def ExitMethod: InstanceMethod = InstanceMethod(this.jvmName, "exit", MethodDescriptor.NothingToVoid)

    private def exitIns(implicit mv: MethodVisitor): Unit = {
      withName(1, JvmName.Thread.toTpe) { t =>
        whileLoop(Condition.NONNULL) {
          thisLoad()
          GETFIELD(ThreadsField)
          INVOKEVIRTUAL(ClassConstants.ConcurrentLinkedQueue.PollMethod)
          CHECKCAST(JvmName.Thread)
          DUP()
          t.store()
        } {
          t.load()
          INVOKEVIRTUAL(ClassConstants.Thread.JoinMethod)
        }
        withName(2, JvmName.Iterator.toTpe) { i =>
          thisLoad()
          GETFIELD(OnExitField)
          INVOKEVIRTUAL(ClassConstants.LinkedList.IteratorMethod)
          i.store()
          whileLoop(Condition.NE) {
            i.load()
            INVOKEINTERFACE(ClassConstants.Iterator.HasNextMethod)
          } {
            i.load()
            INVOKEINTERFACE(ClassConstants.Iterator.NextMethod)
            CHECKCAST(JvmName.Runnable)
            INVOKEINTERFACE(ClassConstants.Runnable.RunMethod)
          }
        }
        RETURN()
      }
    }

    // final public void reportChildException(Throwable e) {
    //   childException = e;
    //   regionThread.interrupt();
    // }
    def ReportChildExceptionMethod: InstanceMethod = InstanceMethod(this.jvmName, "reportChildException", mkDescriptor(JvmName.Throwable.toTpe)(VoidableType.Void))

    private def reportChildExceptionIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      ALOAD(1)
      PUTFIELD(ChildExceptionField)
      thisLoad()
      GETFIELD(RegionThreadField)
      INVOKEVIRTUAL(ClassConstants.Thread.InterruptMethod)
      RETURN()
    }

    // final public void reThrowChildException() throws Throwable {
    //   if (childException != null)
    //     throw childException;
    // }
    def ReThrowChildExceptionMethod: InstanceMethod = InstanceMethod(this.jvmName, "reThrowChildException", MethodDescriptor.NothingToVoid)

    private def reThrowChildExceptionIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ChildExceptionField)
      ifCondition(Condition.NONNULL) {
        thisLoad()
        GETFIELD(ChildExceptionField)
        ATHROW()
      }
      RETURN()
    }

    // final public void runOnExit(Runnable r) {
    //   onExit.addFirst(r);
    // }
    private def RunOnExitMethod: InstanceMethod = InstanceMethod(this.jvmName, "runOnExit", mkDescriptor(JvmName.Runnable.toTpe)(VoidableType.Void))

    private def runOnExitIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(OnExitField)
      ALOAD(1)
      INVOKEVIRTUAL(ClassConstants.LinkedList.AddFirstMethod)
      RETURN()
    }
  }

  case object UncaughtExceptionHandler extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(JvmName.Thread$UncaughtExceptionHandler))

      cm.mkField(RegionField, IsPrivate, IsFinal, NotVolatile)
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkMethod(Nil, UncaughtExceptionMethod, IsPublic, IsFinal, uncaughtExceptionsIns(_))

      cm.closeClassMaker()
    }

    // private final Region r;
    private def RegionField: InstanceField = InstanceField(this.jvmName, "r", BackendObjType.Region.toTpe)

    // UncaughtExceptionHandler(Region r) { this.r = r; }
    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, BackendObjType.Region.toTpe :: Nil)

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      INVOKESPECIAL(ClassConstants.Object.Constructor)
      thisLoad()
      ALOAD(1)
      PUTFIELD(RegionField)
      RETURN()
    }

    // public void uncaughtException(Thread t, Throwable e) { r.reportChildException(e); }
    private def UncaughtExceptionMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "uncaughtException", ClassConstants.ThreadUncaughtExceptionHandler.UncaughtExceptionMethod.d)

    private def uncaughtExceptionsIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(RegionField)
      ALOAD(2)
      INVOKEVIRTUAL(Region.ReportChildExceptionMethod)
      RETURN()
    }
  }

  case object Main extends BackendObjType {

    def genByteCode(sym: Symbol.DefnSym)(implicit flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      cm.mkStaticMethod(MainMethod, IsPublic, NotFinal, mainIns(sym)(_))

      cm.closeClassMaker()
    }

    def MainMethod: StaticMethod = StaticMethod(this.jvmName, "main", mkDescriptor(BackendType.Array(BackendType.String))(VoidableType.Void))

    private def mainIns(sym: Symbol.DefnSym)(implicit mv: MethodVisitor): Unit = {
      val defName = BackendObjType.Defn(sym).jvmName
      withName(0, BackendType.Array(BackendType.String))(args => {
        args.load()
        INVOKESTATIC(Global.SetArgsMethod)
        NEW(defName)
        DUP()
        INVOKESPECIAL(defName, JvmName.ConstructorMethod, MethodDescriptor.NothingToVoid)
        DUP()
        GETSTATIC(Unit.SingletonField)
        PUTFIELD(InstanceField(defName, "arg0", BackendType.Object))
        Result.unwindSuspensionFreeThunk(s"in ${this.jvmName.toBinaryName}", SourceLocation.Unknown)
        POP()
        RETURN()
      })
    }
  }

  case class Namespace(ns: List[String]) extends BackendObjType {

    def genByteCode(defs: List[JvmAst.Def])(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal)

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))

      for (defn <- defs) {
        cm.mkStaticMethod(ShimMethod(defn), IsPublic, IsFinal, shimIns(defn)(root, _), shimSignature(defn))
      }

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    /**
      * The signature a shim method is given.
      *
      * An exported def is part of the program's Java-facing API, so its signature uses exact
      * types: `String` stays `java.lang.String` rather than collapsing to `Object`. The types
      * that survive this are restricted by `EntryPoints.isExportableType`, so the descriptor
      * only ever mentions classes that Java code can name.
      *
      * Every other shim (currently only `@Test`) is internal and stays erased.
      */
    def ShimMethod(defn: JvmAst.Def)(implicit root: JvmAst.Root): StaticMethod = {
      // Exported names are checked in Safety, so no mangling is needed.
      val name = if (defn.ann.isExport) defn.sym.name else "m_" + JvmName.mangle(defn.sym.name)
      StaticMethod(this.jvmName, name, MethodDescriptor(shimParamTypes(defn), shimResultType(defn)))
    }

    /** Returns the type `tpe` has in the shim method of `defn`. */
    private def shimType(defn: JvmAst.Def, tpe: SimpleType)(implicit root: JvmAst.Root): BackendType =
      if (defn.ann.isExport) BackendType.toBackendType(tpe) else BackendType.toErasedBackendType(tpe)

    /**
      * The parameters of the shim method of `defn`.
      *
      * Flix gives a nullary function a single `Unit` parameter. An exported one is presented to
      * Java as taking no parameters at all; [[shimIns]] supplies the unit value itself.
      */
    private def shimParamTypes(defn: JvmAst.Def)(implicit root: JvmAst.Root): List[BackendType] =
      if (dropsUnitParam(defn)) Nil
      else defn.fparams.map(fp => shimType(defn, fp.tpe))

    /**
      * The result of the shim method of `defn`. An exported def returning `Unit` returns `void`,
      * and one returning `Option` returns `java.util.Optional`.
      */
    private def shimResultType(defn: JvmAst.Def)(implicit root: JvmAst.Root): VoidableType =
      if (returnsVoid(defn)) VoidableType.Void
      else exportPlan(defn).map(_.javaType).getOrElse(shimType(defn, defn.unboxedType.tpe))

    /**
      * How the result of `defn` is converted for Java, if it needs converting.
      *
      * The plan is the single source of truth for the boundary: the Java type below, the signature,
      * the instructions in [[shimIns]], and the view classes `CodeGen` emits all read it, so they
      * cannot describe different things.
      */
    private def exportPlan(defn: JvmAst.Def)(implicit root: JvmAst.Root): Option[ExportPlan] =
      ExportPlan.ofDef(defn)

    /**
      * The generic signature of the shim method of `defn`, when it has type arguments to declare.
      *
      * A descriptor cannot express `Optional<String>`, so without this the element type is lost and
      * only Java can still be made to compile, by naming the type at the use site and accepting an
      * unchecked conversion. Scala 3 and Kotlin both reject the raw value outright.
      *
      * The signature restores the element type, not nullability: Kotlin reads even a signed return
      * as the platform type `Optional<String!>!`, because the shim carries no nullness annotations.
      *
      * A signature covers the whole method, so one is emitted when *either* the result or any
      * parameter has something to declare, and the parts with nothing to declare repeat their
      * descriptor. Writing only the interesting half is not an option: a `Signature` attribute
      * either describes every parameter and the result or it is malformed.
      */
    private def shimSignature(defn: JvmAst.Def)(implicit root: JvmAst.Root): Option[String] = {
      val paramPlans = shimParamSimpleTypes(defn).map(ExportPlan.ofParameter)
      val resultPlan = exportPlan(defn)
      val params = shimParamTypes(defn).zip(paramPlans).map {
        case (_, Some(plan)) => plan.typeArgument
        case (tpe, None) => tpe.toDescriptor
      }.mkString
      val result = resultPlan.map(_.typeArgument).getOrElse(shimResultType(defn).toDescriptor)
      val signature = s"($params)$result"
      // Emitted only when it says something the descriptor does not. Having a plan is not the same
      // as having type arguments: an exported enum is converted but is named by a plain class, so
      // its signature would repeat its descriptor exactly -- a legal but empty attribute, and one
      // that invites a reader to look for an argument that was never there.
      Option.when(signature != ShimMethod(defn).d.toDescriptor)(signature)
    }

    /** The declared types of the shim method's parameters, in the same order as [[shimParamTypes]]. */
    private def shimParamSimpleTypes(defn: JvmAst.Def): List[SimpleType] =
      if (dropsUnitParam(defn)) Nil else defn.fparams.map(_.tpe)

    /** Returns `true` if the shim method of `defn` hides the `Unit` parameter of a nullary function. */
    private def dropsUnitParam(defn: JvmAst.Def): Boolean = defn.ann.isExport && (defn.fparams match {
      case List(fp) => fp.tpe == SimpleType.Unit
      case _ => false
    })

    /** Returns `true` if the shim method of `defn` is declared `void`. */
    private def returnsVoid(defn: JvmAst.Def): Boolean =
      defn.ann.isExport && defn.unboxedType.tpe == SimpleType.Unit

    private def shimIns(defn: JvmAst.Def)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      val defnT = Defn(defn.sym)
      withNames(0, shimParamTypes(defn)) {
        case (nextLocal, args) =>
          NEW(defnT.jvmName)
          DUP()
          INVOKESPECIAL(ConstructorMethod(defnT.jvmName, Nil))
          if (dropsUnitParam(defn)) {
            // The parameter is not in the signature, so the unit value comes from its singleton.
            DUP()
            GETSTATIC(Unit.SingletonField)
            PUTFIELD(InstanceField(defnT.jvmName, "arg0", BackendType.Object))
          }
          for (((arg, fparam), index) <- args.zip(defn.fparams).zipWithIndex) {
            DUP()
            arg.load()
            // The argument fields of the function class are always erased, even when the shim
            // takes an exact type. Widening a reference to `Object` needs no cast.
            PUTFIELD(InstanceField(defnT.jvmName, s"arg$index", BackendType.toErasedBackendType(fparam.tpe)))
          }
          val hint = s"in shim method of ${defn.sym}"
          if (returnsVoid(defn)) {
            Result.unwindSuspensionFreeThunk(hint, defn.loc)
            POP()
            RETURN()
          } else if (exportPlan(defn).isDefined) {
            val plan = exportPlan(defn).get
            // Read at the representation the plan converts *from*, which is not always a tag: a
            // Java type that is only being described starts as itself, and reading it as a tag
            // emits a cast that fails verification rather than a conversion.
            Result.unwindSuspensionFreeThunkToType(plan.flixType, hint, defn.loc)
            plan.emit(defn.loc, nextLocal)
            xReturn(plan.javaType)
          } else {
            val resultType = shimType(defn, defn.unboxedType.tpe)
            Result.unwindSuspensionFreeThunkToType(resultType, hint, defn.loc)
            xReturn(resultType)
          }
      }
    }
  }

  /**
    * A class that exists to give a Flix value a Java form at the export boundary.
    *
    * Most of these are views, which present the value without copying it. [[ExportTuple]] is not:
    * a tuple has a fixed, small number of fields that are already materialized, so a view would
    * save no traversal and would re-convert each field on every access. The trait is what the two
    * have in common, which is where the class comes from rather than what it does.
    *
    * These are the only generated classes keyed on an [[ExportPlan]] rather than on a type in
    * `root.types`, so `CodeGen` collects them by walking the exported defs. The trait exists so it
    * can generate them without knowing which kind each one is.
    */
  sealed trait ExportClass extends BackendObjType {
    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte]
  }

  /**
    * A red-black tree presented to Java as an unmodifiable `java.util.Set`, without copying it.
    *
    * Both Flix collections backed by a tree reach Java through this class. With no `value` it is
    * the `Set` itself and iterates keys; with one it is the `entrySet` of a [[MapView]] and
    * iterates `Map.Entry`. The walk, the cached size and the emptiness test are the same either
    * way, which is the whole reason `Map` costs one extra class rather than three.
    *
    * It extends `java.util.AbstractSet`, which needs only `iterator()` and `size()` and supplies
    * the rest of the contract -- including `equals`, `hashCode`, and mutators that throw, since
    * every one of them is written in terms of `Iterator.remove`, whose default implementation
    * throws. Immutability is therefore not something this class implements; it is what it gets by
    * not overriding anything.
    *
    * The plans are the *erased* ones -- `Identity(Object)`, or the boxing of a primitive. Declared
    * types never reach here: they appear only in the shim's signature, which is why `Set[String]`
    * and `Set[Regex]` share one view class.
    *
    * See J10 for the contract this promises a caller and the cost it accepts.
    */
  case class TreeSetView(key: ExportPlan, value: Option[ExportPlan]) extends ExportClass {

    private def nodeTag: Tag = TreeIterator.nodeTag(key, value)

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.AbstractSet)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(TreeField, IsPrivate, IsFinal, NotVolatile)
      cm.mkField(SizeField, IsPrivate, NotFinal, NotVolatile)
      cm.mkMethod(Nil, IteratorMethod, IsPublic, IsFinal, iteratorIns(_))
      cm.mkMethod(Nil, SizeMethod, IsPublic, IsFinal, sizeIns(_))
      cm.mkMethod(Nil, IsEmptyMethod, IsPublic, IsFinal, isEmptyIns(_))
      cm.mkStaticMethod(CountMethod, IsPrivate, IsFinal, countIns(_))

      cm.closeClassMaker()
    }

    /** The iterator this view hands out. Derived rather than stored, so the two cannot disagree. */
    def iteratorType: TreeIterator = TreeIterator(key, value)

    private def TreeField: InstanceField = InstanceField(this.jvmName, "tree", Tagged.toTpe)

    /**
      * The cached size, or `-1` before it has been computed.
      *
      * The one piece of per-view state J10 admits: without it `AbstractCollection.isEmpty` and
      * every size-dependent method walk the tree again on each call. A sentinel rather than a
      * separate flag, because a size is never negative.
      */
    private def SizeField: InstanceField = InstanceField(this.jvmName, "size", BackendType.Int32)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { tree =>
        thisLoad()
        INVOKESPECIAL(JvmName.AbstractSet, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        thisLoad()
        tree.load()
        PUTFIELD(TreeField)
        thisLoad()
        pushInt(-1)
        PUTFIELD(SizeField)
        RETURN()
      }

    def IteratorMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "iterator", mkDescriptor()(BackendObjType.Native(JvmName.Iterator).toTpe))

    /** `[] --> return Iterator` */
    private def iteratorIns(implicit mv: MethodVisitor): Unit = {
      NEW(iteratorType.jvmName)
      DUP()
      thisLoad()
      GETFIELD(TreeField)
      INVOKESPECIAL(iteratorType.Constructor)
      ARETURN()
    }

    def SizeMethod: InstanceMethod = InstanceMethod(this.jvmName, "size", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def sizeIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(SizeField)
      ifCondition(Condition.LT) {
        thisLoad()
        thisLoad()
        GETFIELD(TreeField)
        INVOKESTATIC(CountMethod)
        PUTFIELD(SizeField)
      }
      thisLoad()
      GETFIELD(SizeField)
      xReturn(BackendType.Int32)
    }

    def IsEmptyMethod: InstanceMethod = InstanceMethod(this.jvmName, "isEmpty", mkDescriptor()(BackendType.Bool))

    /**
      * `[] --> return boolean`
      *
      * Overridden so that emptiness stays O(1) even before `size()` has been forced.
      * `AbstractCollection.isEmpty` is `size() == 0`, which would walk the whole tree.
      */
    private def isEmptyIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(TreeField)
      INSTANCEOF(nodeTag.jvmName)
      ifConditionElse(Condition.NE)(pushBool(false))(pushBool(true))
      xReturn(BackendType.Bool)
    }

    private def CountMethod: StaticMethod =
      StaticMethod(this.jvmName, "count", mkDescriptor(Tagged.toTpe)(BackendType.Int32))

    /**
      * `[] --> return int`
      *
      * Recursive rather than iterative: a red-black tree is balanced by construction, so the
      * recursion is O(log n) deep and cannot overflow the stack for any tree that fits in memory.
      *
      * The test is *for* a node rather than against a leaf, which covers `Leaf` and
      * `DoubleBlackLeaf` alike without naming either -- see [[TreeIterator.nodeTag]] for why
      * `instanceof` is exact here despite the tag class being shared.
      */
    private def countIns(implicit mv: MethodVisitor): Unit =
      withName(0, Tagged.toTpe) { tree =>
        tree.load()
        INSTANCEOF(nodeTag.jvmName)
        ifConditionElse(Condition.NE) {
          pushInt(1)
          tree.load()
          CHECKCAST(nodeTag.jvmName)
          GETFIELD(nodeTag.IndexField(TreeIterator.LeftIndex))
          CHECKCAST(Tagged.jvmName)
          INVOKESTATIC(CountMethod)
          IADD()
          tree.load()
          CHECKCAST(nodeTag.jvmName)
          GETFIELD(nodeTag.IndexField(TreeIterator.RightIndex))
          CHECKCAST(Tagged.jvmName)
          INVOKESTATIC(CountMethod)
          IADD()
        } {
          pushInt(0)
        }
        xReturn(BackendType.Int32)
      }
  }

  object TreeIterator {

    /**
      * The field indices of `RedBlackTree.Node(Color, left, key, value, right)`.
      *
      * Stated rather than read from the enum. `Set` and `Map` each declare that their single case
      * holds a `RedBlackTree`, but the eraser rewrites every enum-typed field to `Object`, so by
      * the time the backend sees it the tree's own enum -- and with it the ordinals and field types
      * of its cases -- is no longer reachable from the exported type. What pins this shape is
      * therefore the runtime tests, not the AST: get an index wrong and iteration returns the wrong
      * values or fails verification.
      */
    val LeftIndex: Int = 1
    val KeyIndex: Int = 2
    val ValueIndex: Int = 3
    val RightIndex: Int = 4

    /**
      * The tag class of `RedBlackTree.Node` for a tree with these key and value plans.
      *
      * `Color` and both subtrees are references, so they erase to `Object` and only the key and
      * value vary -- and a `Set`'s value is `Unit`, which is a reference too. This class is shared
      * with every other five-field tag of the same erasure, which is what makes it a sound test for
      * "is this a `Node`" *in this position*: the field it is read from holds a `RedBlackTree` and
      * nothing else, and the other two cases are nullary, so they are classes of their own rather
      * than tags.
      */
    def nodeTag(key: ExportPlan, value: Option[ExportPlan]): Tag =
      Tag(List(
        BackendType.Object,
        BackendType.Object,
        key.flixType,
        value.map(_.flixType).getOrElse(BackendType.Object),
        BackendType.Object
      ))
  }

  /**
    * The in-order walk behind a [[TreeSetView]].
    *
    * A stack of the nodes still owed, deepest-left first. `next` pops one, pushes the left spine
    * of its right child, and converts what that node contributes -- the key alone for a `Set`, or
    * an immutable `Map.Entry` of key and value. That is O(1) amortized per element and O(h) in
    * memory, which J10 notes is what *any* tree traversal costs: ascending order is not paid for,
    * it is the order the tree is already in.
    *
    * `remove` is not overridden, so `Iterator`'s default implementation throws, which is what
    * makes every inherited mutator on the view throw as well.
    */
  case class TreeIterator(key: ExportPlan, value: Option[ExportPlan]) extends ExportClass {

    private def nodeTag: Tag = TreeIterator.nodeTag(key, value)

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, interfaces = List(JvmName.Iterator))

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(StackField, IsPrivate, IsFinal, NotVolatile)
      cm.mkMethod(Nil, HasNextMethod, IsPublic, IsFinal, hasNextIns(_))
      cm.mkMethod(Nil, NextMethod, IsPublic, IsFinal, nextIns(root, _))
      cm.mkMethod(Nil, PushLeftSpineMethod, IsPrivate, IsFinal, pushLeftSpineIns(_))

      cm.closeClassMaker()
    }

    private def StackField: InstanceField =
      InstanceField(this.jvmName, "stack", BackendObjType.Native(JvmName.ArrayDeque).toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { tree =>
        thisLoad()
        INVOKESPECIAL(ClassConstants.Object.Constructor)
        thisLoad()
        NEW(JvmName.ArrayDeque)
        DUP()
        INVOKESPECIAL(JvmName.ArrayDeque, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        PUTFIELD(StackField)
        thisLoad()
        tree.load()
        INVOKESPECIAL(this.jvmName, PushLeftSpineMethod.name, PushLeftSpineMethod.d)
        RETURN()
      }

    def HasNextMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "hasNext", mkDescriptor()(BackendType.Bool))

    /** `[] --> return boolean` */
    private def hasNextIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(StackField)
      INVOKEVIRTUAL(JvmName.ArrayDeque, "isEmpty", mkDescriptor()(BackendType.Bool))
      ifConditionElse(Condition.EQ)(pushBool(true))(pushBool(false))
      xReturn(BackendType.Bool)
    }

    def NextMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "next", mkDescriptor()(BackendType.Object))

    /**
      * `[] --> return Object`
      *
      * Exhaustion is not checked here: `ArrayDeque.pop` throws `NoSuchElementException` on an
      * empty deque, which is exactly what `Iterator.next` is required to throw.
      */
    private def nextIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(1, nodeTag.toTpe) { node =>
        thisLoad()
        GETFIELD(StackField)
        INVOKEVIRTUAL(JvmName.ArrayDeque, "pop", mkDescriptor()(BackendType.Object))
        CHECKCAST(nodeTag.jvmName)
        node.store()
        // The right child owes everything under it once this node is handed out.
        thisLoad()
        node.load()
        GETFIELD(nodeTag.IndexField(TreeIterator.RightIndex))
        CHECKCAST(Tagged.jvmName)
        INVOKESPECIAL(this.jvmName, PushLeftSpineMethod.name, PushLeftSpineMethod.d)
        value match {
          case None =>
            node.load()
            GETFIELD(nodeTag.IndexField(TreeIterator.KeyIndex))
            key.emit(SourceLocation.Unknown, 2)
          case Some(v) =>
            // The JDK's own immutable entry, rather than a seventh generated class. Its `setValue`
            // throws, which is the same immutability the rest of the view has.
            NEW(JvmName.SimpleImmutableEntry)
            DUP()
            node.load()
            GETFIELD(nodeTag.IndexField(TreeIterator.KeyIndex))
            key.emit(SourceLocation.Unknown, 2)
            node.load()
            GETFIELD(nodeTag.IndexField(TreeIterator.ValueIndex))
            v.emit(SourceLocation.Unknown, 2)
            INVOKESPECIAL(JvmName.SimpleImmutableEntry, JvmName.ConstructorMethod,
              mkDescriptor(BackendType.Object, BackendType.Object)(VoidableType.Void))
        }
        ARETURN()
      }

    private def PushLeftSpineMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "pushLeftSpine", mkDescriptor(Tagged.toTpe)(VoidableType.Void))

    /** `[] --> return` */
    private def pushLeftSpineIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { cursor =>
        whileLoop(Condition.NE) {
          cursor.load()
          INSTANCEOF(nodeTag.jvmName)
        } {
          thisLoad()
          GETFIELD(StackField)
          cursor.load()
          INVOKEVIRTUAL(JvmName.ArrayDeque, "push", mkDescriptor(BackendType.Object)(VoidableType.Void))
          cursor.load()
          CHECKCAST(nodeTag.jvmName)
          GETFIELD(nodeTag.IndexField(TreeIterator.LeftIndex))
          CHECKCAST(Tagged.jvmName)
          cursor.store()
        }
        RETURN()
      }
  }

  /**
    * A Flix `Map` presented to Java as an unmodifiable `java.util.Map`, without copying it.
    *
    * `java.util.AbstractMap` is written almost entirely in terms of `entrySet()`, so this class is
    * little more than one: `get`, `containsKey`, `keySet`, `values`, `equals` and `hashCode` all
    * come from there, and `put` throws while `remove` throws through the entry set's iterator.
    *
    * The entry set is built once in the constructor rather than per call. `AbstractMap` caches
    * `keySet` and `values` but not `entrySet`, so a fresh one per call would also mean a fresh
    * size cache per call, and `size()` would walk the tree every time it was asked.
    */
  case class MapView(key: ExportPlan, value: ExportPlan) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.AbstractMap)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(EntriesField, IsPrivate, IsFinal, NotVolatile)
      cm.mkMethod(Nil, EntrySetMethod, IsPublic, IsFinal, entrySetIns(_))
      cm.mkMethod(Nil, SizeMethod, IsPublic, IsFinal, sizeIns(_))
      cm.mkMethod(Nil, IsEmptyMethod, IsPublic, IsFinal, isEmptyIns(_))

      cm.closeClassMaker()
    }

    /** The entry set backing this map, which is the tree view that does all the work. */
    def entrySetType: TreeSetView = TreeSetView(key, Some(value))

    /** Typed as the view rather than as `java.util.Set`, so its own methods can be called. */
    private def EntriesField: InstanceField =
      InstanceField(this.jvmName, "entries", entrySetType.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { tree =>
        thisLoad()
        INVOKESPECIAL(JvmName.AbstractMap, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        thisLoad()
        NEW(entrySetType.jvmName)
        DUP()
        tree.load()
        INVOKESPECIAL(entrySetType.Constructor)
        PUTFIELD(EntriesField)
        RETURN()
      }

    def EntrySetMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "entrySet", mkDescriptor()(BackendObjType.Native(JvmName.JavaSet).toTpe))

    /** `[] --> return Set` */
    private def entrySetIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(EntriesField)
      ARETURN()
    }

    def SizeMethod: InstanceMethod = InstanceMethod(this.jvmName, "size", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def sizeIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(EntriesField)
      INVOKEVIRTUAL(entrySetType.SizeMethod)
      xReturn(BackendType.Int32)
    }

    def IsEmptyMethod: InstanceMethod = InstanceMethod(this.jvmName, "isEmpty", mkDescriptor()(BackendType.Bool))

    /**
      * `[] --> return boolean`
      *
      * Overridden for the same reason as on the set view: `AbstractMap.isEmpty` is `size() == 0`,
      * which would walk the tree, whereas the entry set answers it from the root alone.
      */
    private def isEmptyIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(EntriesField)
      INVOKEVIRTUAL(entrySetType.IsEmptyMethod)
      xReturn(BackendType.Bool)
    }
  }

  object ChainIterator {
    /** The field indices of `List.Cons(head, tail)`. */
    val HeadIndex: Int = 0
    val TailIndex: Int = 1

    /**
      * The tag class of `List.Cons` for a chain whose elements erase to `element`.
      *
      * The same argument as [[TreeIterator.nodeTag]]: this class is shared with every other
      * two-field tag of the same erasure, and it is still an exact test for "is this a `Cons`" in
      * a field that holds a `List` and nothing else, because `Nil` is nullary and so a class of
      * its own.
      */
    def consTag(element: BackendType): Tag = Tag(List(element, BackendType.Object))
  }

  /**
    * A Flix `List` presented to Java as an unmodifiable `java.util.List`, without copying it.
    *
    * A cons chain has no indexing, so this extends `java.util.AbstractSequentialList` rather than
    * `AbstractList`, whose `get(i)` would make a full traversal quadratic. What that costs is
    * having to supply a real `ListIterator` -- nine methods rather than two -- which is why this
    * was the largest of the three views to build even though a chain is the simplest structure.
    *
    * Every mutator throws, as on the other two views, and here `AbstractList.add` and `set` throw
    * because they are written in terms of the list iterator's own mutators.
    */
  case class ListView(element: ExportPlan) extends ExportClass {

    private def consTag: Tag = ChainIterator.consTag(element.flixType)

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.AbstractSequentialList)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(ChainField, IsPrivate, IsFinal, NotVolatile)
      cm.mkField(SizeField, IsPrivate, NotFinal, NotVolatile)
      cm.mkMethod(Nil, ListIteratorMethod, IsPublic, IsFinal, listIteratorIns(_))
      cm.mkMethod(Nil, SizeMethod, IsPublic, IsFinal, sizeIns(_))
      cm.mkMethod(Nil, IsEmptyMethod, IsPublic, IsFinal, isEmptyIns(_))
      cm.mkStaticMethod(CountMethod, IsPrivate, IsFinal, countIns(_))

      cm.closeClassMaker()
    }

    /** The iterator this view hands out. Derived rather than stored, so the two cannot disagree. */
    def iteratorType: ChainIterator = ChainIterator(element)

    private def ChainField: InstanceField = InstanceField(this.jvmName, "chain", Tagged.toTpe)

    /** The cached size, or `-1` before it has been computed. See [[TreeSetView]]. */
    private def SizeField: InstanceField = InstanceField(this.jvmName, "size", BackendType.Int32)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { chain =>
        thisLoad()
        INVOKESPECIAL(JvmName.AbstractSequentialList, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        thisLoad()
        chain.load()
        PUTFIELD(ChainField)
        thisLoad()
        pushInt(-1)
        PUTFIELD(SizeField)
        RETURN()
      }

    def ListIteratorMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "listIterator",
        mkDescriptor(BackendType.Int32)(BackendObjType.Native(JvmName.JavaListIterator).toTpe))

    /**
      * `[] --> return ListIterator`
      *
      * The one method `AbstractSequentialList` leaves abstract, and the one everything else it
      * provides is written in terms of -- including `iterator()`, `get`, and every mutator.
      */
    private def listIteratorIns(implicit mv: MethodVisitor): Unit =
      withName(1, BackendType.Int32) { index =>
        NEW(iteratorType.jvmName)
        DUP()
        thisLoad()
        GETFIELD(ChainField)
        index.load()
        INVOKESPECIAL(iteratorType.Constructor)
        ARETURN()
      }

    def SizeMethod: InstanceMethod = InstanceMethod(this.jvmName, "size", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def sizeIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(SizeField)
      ifCondition(Condition.LT) {
        thisLoad()
        thisLoad()
        GETFIELD(ChainField)
        INVOKESTATIC(CountMethod)
        PUTFIELD(SizeField)
      }
      thisLoad()
      GETFIELD(SizeField)
      xReturn(BackendType.Int32)
    }

    def IsEmptyMethod: InstanceMethod = InstanceMethod(this.jvmName, "isEmpty", mkDescriptor()(BackendType.Bool))

    /** `[] --> return boolean`, from the head alone rather than through `size()`. */
    private def isEmptyIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ChainField)
      INSTANCEOF(consTag.jvmName)
      ifConditionElse(Condition.NE)(pushBool(false))(pushBool(true))
      xReturn(BackendType.Bool)
    }

    private def CountMethod: StaticMethod =
      StaticMethod(this.jvmName, "count", mkDescriptor(Tagged.toTpe)(BackendType.Int32))

    /**
      * `[] --> return int`
      *
      * Iterative, unlike the tree's recursive count: a chain is as deep as it is long, so
      * recursion would overflow the stack on a list the program had no trouble building.
      */
    private def countIns(implicit mv: MethodVisitor): Unit =
      withName(0, Tagged.toTpe) { cursor =>
        withName(1, BackendType.Int32) { count =>
          pushInt(0)
          count.store()
          whileLoop(Condition.NE) {
            cursor.load()
            INSTANCEOF(consTag.jvmName)
          } {
            count.load()
            pushInt(1)
            IADD()
            count.store()
            cursor.load()
            CHECKCAST(consTag.jvmName)
            GETFIELD(consTag.IndexField(ChainIterator.TailIndex))
            CHECKCAST(Tagged.jvmName)
            cursor.store()
          }
          count.load()
          xReturn(BackendType.Int32)
        }
      }
  }

  /**
    * The walk behind a [[ListView]], as a `java.util.ListIterator`.
    *
    * Forward iteration is O(1) per step and holds only a cursor and an index. Backward iteration
    * re-walks from the head, because a cons chain has no back-pointers: `previous` is O(n) per
    * step, which J10 accepts as the cost of the case this view is *not* built for.
    *
    * `remove`, `set` and `add` throw. `java.util.ListIterator` declares all nine of its methods
    * abstract -- there are no defaults to inherit as there are on `Iterator` -- so unlike the tree
    * iterator, immutability here is written rather than inherited.
    */
  case class ChainIterator(element: ExportPlan) extends ExportClass {

    private def consTag: Tag = ChainIterator.consTag(element.flixType)

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, interfaces = List(JvmName.JavaListIterator))

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(HeadField, IsPrivate, IsFinal, NotVolatile)
      cm.mkField(CursorField, IsPrivate, NotFinal, NotVolatile)
      cm.mkField(IndexField, IsPrivate, NotFinal, NotVolatile)
      cm.mkMethod(Nil, HasNextMethod, IsPublic, IsFinal, hasNextIns(_))
      cm.mkMethod(Nil, NextMethod, IsPublic, IsFinal, nextIns(root, _))
      cm.mkMethod(Nil, HasPreviousMethod, IsPublic, IsFinal, hasPreviousIns(_))
      cm.mkMethod(Nil, PreviousMethod, IsPublic, IsFinal, previousIns(root, _))
      cm.mkMethod(Nil, NextIndexMethod, IsPublic, IsFinal, nextIndexIns(_))
      cm.mkMethod(Nil, PreviousIndexMethod, IsPublic, IsFinal, previousIndexIns(_))
      cm.mkMethod(Nil, RemoveMethod, IsPublic, IsFinal, refuse(_))
      cm.mkMethod(Nil, SetMethod, IsPublic, IsFinal, refuse(_))
      cm.mkMethod(Nil, AddMethod, IsPublic, IsFinal, refuse(_))
      cm.mkStaticMethod(AdvanceMethod, IsPrivate, IsFinal, advanceIns(_))

      cm.closeClassMaker()
    }

    /** The chain this iterator started from, kept only so that `previous` can re-walk it. */
    private def HeadField: InstanceField = InstanceField(this.jvmName, "head", Tagged.toTpe)

    /** The node holding the element `next` would return, or `Nil` at the end. */
    private def CursorField: InstanceField = InstanceField(this.jvmName, "cursor", Tagged.toTpe)

    private def IndexField: InstanceField = InstanceField(this.jvmName, "index", BackendType.Int32)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe, BackendType.Int32))

    /**
      * `[] --> return`
      *
      * `AbstractSequentialList` calls this with an arbitrary index, so a bad one is rejected here
      * rather than allowed to walk off the end of the chain. `index == size` is legal: it is the
      * position after the last element.
      */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { chain =>
        withName(2, BackendType.Int32) { index =>
          thisLoad()
          INVOKESPECIAL(ClassConstants.Object.Constructor)
          index.load()
          ifCondition(Condition.LT) {
            throwWithMessage(JvmName.IndexOutOfBoundsException, "negative index")
          }
          thisLoad()
          chain.load()
          PUTFIELD(HeadField)
          thisLoad()
          chain.load()
          index.load()
          INVOKESTATIC(AdvanceMethod)
          PUTFIELD(CursorField)
          thisLoad()
          index.load()
          PUTFIELD(IndexField)
          RETURN()
        }
      }

    private def AdvanceMethod: StaticMethod =
      StaticMethod(this.jvmName, "advance", mkDescriptor(Tagged.toTpe, BackendType.Int32)(Tagged.toTpe))

    /**
      * `[] --> return Tagged`
      *
      * Returns the node `steps` places along `chain`, throwing if the chain runs out first. This
      * is the only place the O(n) of `previous` lives; `next` never calls it.
      */
    private def advanceIns(implicit mv: MethodVisitor): Unit =
      withName(0, Tagged.toTpe) { cursor =>
        withName(1, BackendType.Int32) { steps =>
          whileLoop(Condition.GT) {
            steps.load()
          } {
            cursor.load()
            INSTANCEOF(consTag.jvmName)
            ifConditionElse(Condition.NE) {
              cursor.load()
              CHECKCAST(consTag.jvmName)
              GETFIELD(consTag.IndexField(ChainIterator.TailIndex))
              CHECKCAST(Tagged.jvmName)
              cursor.store()
            } {
              throwWithMessage(JvmName.IndexOutOfBoundsException, "index past the end of the list")
            }
            steps.load()
            pushInt(-1)
            IADD()
            steps.store()
          }
          cursor.load()
          ARETURN()
        }
      }

    def HasNextMethod: InstanceMethod = InstanceMethod(this.jvmName, "hasNext", mkDescriptor()(BackendType.Bool))

    /** `[] --> return boolean` */
    private def hasNextIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(CursorField)
      INSTANCEOF(consTag.jvmName)
      xReturn(BackendType.Bool)
    }

    def NextMethod: InstanceMethod = InstanceMethod(this.jvmName, "next", mkDescriptor()(BackendType.Object))

    /** `[] --> return Object` */
    private def nextIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(CursorField)
      INSTANCEOF(consTag.jvmName)
      ifCondition(Condition.EQ) {
        throwWithMessage(JvmName.NoSuchElementException, "no elements remain")
      }
      // index++
      thisLoad()
      thisLoad()
      GETFIELD(IndexField)
      pushInt(1)
      IADD()
      PUTFIELD(IndexField)
      // cursor = cursor.tail, keeping the element it held
      withName(1, consTag.toTpe) { node =>
        thisLoad()
        GETFIELD(CursorField)
        CHECKCAST(consTag.jvmName)
        node.store()
        thisLoad()
        node.load()
        GETFIELD(consTag.IndexField(ChainIterator.TailIndex))
        CHECKCAST(Tagged.jvmName)
        PUTFIELD(CursorField)
        node.load()
        GETFIELD(consTag.IndexField(ChainIterator.HeadIndex))
        element.emit(SourceLocation.Unknown, 2)
        ARETURN()
      }
    }

    def HasPreviousMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "hasPrevious", mkDescriptor()(BackendType.Bool))

    /** `[] --> return boolean` */
    private def hasPreviousIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(IndexField)
      ifConditionElse(Condition.GT)(pushBool(true))(pushBool(false))
      xReturn(BackendType.Bool)
    }

    def PreviousMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "previous", mkDescriptor()(BackendType.Object))

    /**
      * `[] --> return Object`
      *
      * Steps back by re-walking from the head, since the chain runs one way only.
      */
    private def previousIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(IndexField)
      ifCondition(Condition.LE) {
        throwWithMessage(JvmName.NoSuchElementException, "no elements precede the cursor")
      }
      // index--
      thisLoad()
      thisLoad()
      GETFIELD(IndexField)
      pushInt(-1)
      IADD()
      PUTFIELD(IndexField)
      // cursor = advance(head, index)
      thisLoad()
      thisLoad()
      GETFIELD(HeadField)
      thisLoad()
      GETFIELD(IndexField)
      INVOKESTATIC(AdvanceMethod)
      PUTFIELD(CursorField)
      thisLoad()
      GETFIELD(CursorField)
      CHECKCAST(consTag.jvmName)
      GETFIELD(consTag.IndexField(ChainIterator.HeadIndex))
      element.emit(SourceLocation.Unknown, 1)
      ARETURN()
    }

    def NextIndexMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "nextIndex", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def nextIndexIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(IndexField)
      xReturn(BackendType.Int32)
    }

    def PreviousIndexMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "previousIndex", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def previousIndexIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(IndexField)
      pushInt(-1)
      IADD()
      xReturn(BackendType.Int32)
    }

    def RemoveMethod: InstanceMethod = InstanceMethod(this.jvmName, "remove", mkDescriptor()(VoidableType.Void))

    def SetMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "set", mkDescriptor(BackendType.Object)(VoidableType.Void))

    def AddMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "add", mkDescriptor(BackendType.Object)(VoidableType.Void))

    /** `[] --> throw`, for the three mutators there is nothing to write through to. */
    private def refuse(implicit mv: MethodVisitor): Unit =
      throwUnsupportedOperationException("Flix lists are immutable")
  }

  /**
    * A Flix `Vector` presented to Java as an unmodifiable `java.util.List`, without copying it.
    *
    * A `Vector` value already *is* a Java array (`Array.toVector` casts one, after one defensive
    * copy, rather than wrapping it), so unlike every other collection view this one is
    * index-addressable in O(1) -- there is no chain or tree to walk. It therefore extends
    * `java.util.AbstractList` rather than `AbstractSequentialList`: with `get(int)` and `size()`
    * given, `AbstractList` derives `iterator()`, `listIterator(int)` (bounds-checked), `contains`,
    * `equals`, `hashCode` and every mutator (which it inherits as throwing, since none is
    * overridden) with no further code here -- the smallest of the four collection views for
    * exactly the reason its underlying structure is the simplest.
    *
    * An out-of-range `get` is not checked separately: reading past the array's own bounds already
    * throws `ArrayIndexOutOfBoundsException`, a subtype of the `IndexOutOfBoundsException`
    * `java.util.List.get` promises, so the JVM's own bounds check is the whole implementation.
    *
    * The plan is the *erased* one -- `Identity(Object)`, or the boxing of a primitive -- exactly as
    * on every other view, so that every `Vector` whose elements are references shares one class.
    * The constructor's own parameter is therefore the erased array type, e.g. `Object[]`; the
    * concrete array a caller's `Vector[String]` actually is widens to it with no cast, the same way
    * passing a `String` where `Object` is expected needs none.
    */
  case class VectorView(element: ExportPlan) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.AbstractList)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(ArrayField, IsPrivate, IsFinal, NotVolatile)
      cm.mkMethod(Nil, GetMethod, IsPublic, IsFinal, getIns(root, _))
      cm.mkMethod(Nil, SizeMethod, IsPublic, IsFinal, sizeIns(_))

      cm.closeClassMaker()
    }

    private def ArrayField: InstanceField = InstanceField(this.jvmName, "elements", BackendType.Array(element.flixType))

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(BackendType.Array(element.flixType)))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, BackendType.Array(element.flixType)) { arr =>
        thisLoad()
        INVOKESPECIAL(JvmName.AbstractList, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        thisLoad()
        arr.load()
        PUTFIELD(ArrayField)
        RETURN()
      }

    def GetMethod: InstanceMethod = InstanceMethod(this.jvmName, "get", mkDescriptor(BackendType.Int32)(BackendType.Object))

    /** `[] --> return Object`, converting the element the same way every other view's read does. */
    private def getIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(1, BackendType.Int32) { index =>
        thisLoad()
        GETFIELD(ArrayField)
        index.load()
        xArrayLoad(element.flixType)
        element.emit(SourceLocation.Unknown, 2)
        ARETURN()
      }

    def SizeMethod: InstanceMethod = InstanceMethod(this.jvmName, "size", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def sizeIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ArrayField)
      ARRAYLENGTH()
      xReturn(BackendType.Int32)
    }
  }

  /**
    * A Flix `Chain` presented to Java as an unmodifiable `java.util.Collection`, without copying
    * it.
    *
    * Not `java.util.List`: a chain has no efficient indexed access any more than a `Set` or `Map`
    * does, so presenting it as `List` would advertise a positional-access contract the value
    * cannot honor any better than those two -- correctly presented as `Set`/`Map`, not `List`.
    * `AbstractCollection` needs only `iterator()` and `size()`, the same base contract
    * `TreeSetView` builds on.
    *
    * `Chain`'s own `Empty | One(t) | Chain(l, r)` shape is a genuine binary tree, distinct from
    * both precedents this backend already has: `List`'s cons chain is linear, and `Set`/`Map`'s
    * red-black tree carries a value at every internal node and is balanced by construction. A
    * `Chain` is neither -- only `One` leaves carry a value, `Chain` nodes carry none, and nothing
    * in the type stops a directly-constructed, arbitrarily unbalanced or degenerate value (the
    * doc comment on `Chain`'s own cases warns they "should not be used directly", which is a
    * convention, not an enforced restriction). The walk below is therefore a new algorithm rather
    * than a reuse of [[TreeIterator]]'s left-spine push, and iterative rather than recursive for
    * the same reason [[ListView]]'s own count is: a chain can be as deep as it has elements, so
    * recursion could overflow a stack a Flix-level traversal would not.
    *
    * Unlike `Set`/`Map`, this *is* the type being exported directly -- `sym` in `ExportPlan.of` is
    * `Chain`'s own `EnumSym` -- so unlike [[TreeIterator.nodeTag]], which has to state its shape
    * because the tree it walks is erased away before this code runs, the ordinals and tag shapes
    * here are read from `root.enums(sym)` at plan-construction time, the same way [[AsSealedEnum]]
    * reads a case's own ordinal rather than assuming a declaration order.
    */
  case class ExportedChainView(element: ExportPlan, emptyOrdinal: Int, oneOrdinal: Int, oneTag: Tag, chainTag: Tag) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.AbstractCollection)

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(ChainField, IsPrivate, IsFinal, NotVolatile)
      cm.mkField(SizeField, IsPrivate, NotFinal, NotVolatile)
      cm.mkMethod(Nil, IteratorMethod, IsPublic, IsFinal, iteratorIns(root, _))
      cm.mkMethod(Nil, SizeMethod, IsPublic, IsFinal, sizeIns(_))
      cm.mkMethod(Nil, IsEmptyMethod, IsPublic, IsFinal, isEmptyIns(_))
      cm.mkMethod(Nil, CountMethod, IsPrivate, IsFinal, countIns(root, _))

      cm.closeClassMaker()
    }

    /** The iterator this view hands out. Derived rather than stored, so the two cannot disagree. */
    def iteratorType: ExportedChainIterator = ExportedChainIterator(element, emptyOrdinal, oneOrdinal, oneTag, chainTag)

    private def ChainField: InstanceField = InstanceField(this.jvmName, "chain", Tagged.toTpe)

    /** The cached size, or `-1` before it has been computed. See [[TreeSetView.SizeField]]. */
    private def SizeField: InstanceField = InstanceField(this.jvmName, "size", BackendType.Int32)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe))

    /** `[] --> return` */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { chain =>
        thisLoad()
        INVOKESPECIAL(JvmName.AbstractCollection, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        thisLoad()
        chain.load()
        PUTFIELD(ChainField)
        thisLoad()
        pushInt(-1)
        PUTFIELD(SizeField)
        RETURN()
      }

    def IteratorMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "iterator", mkDescriptor()(BackendObjType.Native(JvmName.Iterator).toTpe))

    /** `[] --> return Iterator` */
    private def iteratorIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      NEW(iteratorType.jvmName)
      DUP()
      thisLoad()
      GETFIELD(ChainField)
      INVOKESPECIAL(iteratorType.Constructor)
      ARETURN()
    }

    def SizeMethod: InstanceMethod = InstanceMethod(this.jvmName, "size", mkDescriptor()(BackendType.Int32))

    /** `[] --> return int` */
    private def sizeIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(SizeField)
      ifCondition(Condition.LT) {
        thisLoad()
        thisLoad()
        INVOKESPECIAL(this.jvmName, CountMethod.name, CountMethod.d)
        PUTFIELD(SizeField)
      }
      thisLoad()
      GETFIELD(SizeField)
      xReturn(BackendType.Int32)
    }

    def IsEmptyMethod: InstanceMethod = InstanceMethod(this.jvmName, "isEmpty", mkDescriptor()(BackendType.Bool))

    /** `[] --> return boolean`, from the root's own ordinal alone, in O(1). */
    private def isEmptyIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ChainField)
      GETFIELD(Tagged.OrdinalField)
      pushInt(emptyOrdinal)
      ifConditionElse(Condition.ICMPEQ)(pushBool(true))(pushBool(false))
      xReturn(BackendType.Bool)
    }

    private def CountMethod: InstanceMethod = InstanceMethod(this.jvmName, "count", mkDescriptor()(BackendType.Int32))

    /**
      * `[] --> return int`
      *
      * Counted by running a fresh iterator to exhaustion rather than by a second, independent walk
      * of the tree: the traversal below is the one place this class earns its keep over copying
      * `TreeSetView`'s own recursive count, and giving `size` its own separate version of the same
      * walk would be a second chance for the two to disagree about what an "element" is.
      */
    private def countIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(1, BackendObjType.Native(JvmName.Iterator).toTpe) { it =>
        withName(2, BackendType.Int32) { count =>
          NEW(iteratorType.jvmName)
          DUP()
          thisLoad()
          GETFIELD(ChainField)
          INVOKESPECIAL(iteratorType.Constructor)
          it.store()
          pushInt(0)
          count.store()
          whileLoop(Condition.NE) {
            it.load()
            INVOKEINTERFACE(ClassConstants.Iterator.HasNextMethod)
          } {
            it.load()
            INVOKEINTERFACE(ClassConstants.Iterator.NextMethod)
            POP()
            count.load()
            pushInt(1)
            IADD()
            count.store()
          }
          count.load()
          xReturn(BackendType.Int32)
        }
      }
  }

  /**
    * The walk behind an [[ExportedChainView]].
    *
    * `stack` holds raw, not-yet-classified subtrees -- unlike [[TreeIterator]]'s stack, which
    * holds only nodes already known to contribute a value, because a `Chain` node contributes
    * nothing and an `Empty` one may hide arbitrarily more structure beneath it (see the class
    * comment on [[ExportedChainView]]). `normalize` is what brings the top of the stack to a
    * "leaf or exhausted" state before every read; `hasNext` and `next` both start by calling it,
    * which is one redundant call on the common path (the top is already a leaf) rather than two
    * divergent ideas of when the stack is ready.
    *
    * Exhaustion is not checked in `next` for the same reason [[TreeIterator]]'s is not: after
    * `normalize`, `stack.pop()` either returns the one-case value that is there or throws
    * `NoSuchElementException` on an empty deque, which is exactly what `Iterator.next` must throw.
    */
  case class ExportedChainIterator(element: ExportPlan, emptyOrdinal: Int, oneOrdinal: Int, oneTag: Tag, chainTag: Tag) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, interfaces = List(JvmName.Iterator))

      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(StackField, IsPrivate, IsFinal, NotVolatile)
      cm.mkMethod(Nil, HasNextMethod, IsPublic, IsFinal, hasNextIns(_))
      cm.mkMethod(Nil, NextMethod, IsPublic, IsFinal, nextIns(root, _))
      cm.mkMethod(Nil, NormalizeMethod, IsPrivate, IsFinal, normalizeIns(_))

      cm.closeClassMaker()
    }

    private def StackField: InstanceField =
      InstanceField(this.jvmName, "stack", BackendObjType.Native(JvmName.ArrayDeque).toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Tagged.toTpe))

    /** `[] --> return`, seeding the stack with the root alone. */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withName(1, Tagged.toTpe) { chain =>
        thisLoad()
        INVOKESPECIAL(ClassConstants.Object.Constructor)
        thisLoad()
        NEW(JvmName.ArrayDeque)
        DUP()
        INVOKESPECIAL(JvmName.ArrayDeque, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
        PUTFIELD(StackField)
        thisLoad()
        GETFIELD(StackField)
        chain.load()
        INVOKEVIRTUAL(JvmName.ArrayDeque, "push", mkDescriptor(BackendType.Object)(VoidableType.Void))
        RETURN()
      }

    def HasNextMethod: InstanceMethod = InstanceMethod(this.jvmName, "hasNext", mkDescriptor()(BackendType.Bool))

    /** `[] --> return boolean` */
    private def hasNextIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      INVOKESPECIAL(this.jvmName, NormalizeMethod.name, NormalizeMethod.d)
      thisLoad()
      GETFIELD(StackField)
      INVOKEVIRTUAL(JvmName.ArrayDeque, "isEmpty", mkDescriptor()(BackendType.Bool))
      ifConditionElse(Condition.EQ)(pushBool(true))(pushBool(false))
      xReturn(BackendType.Bool)
    }

    def NextMethod: InstanceMethod = InstanceMethod(this.jvmName, "next", mkDescriptor()(BackendType.Object))

    /** `[] --> return Object` */
    private def nextIns(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      thisLoad()
      INVOKESPECIAL(this.jvmName, NormalizeMethod.name, NormalizeMethod.d)
      thisLoad()
      GETFIELD(StackField)
      INVOKEVIRTUAL(JvmName.ArrayDeque, "pop", mkDescriptor()(BackendType.Object))
      CHECKCAST(oneTag.jvmName)
      GETFIELD(oneTag.IndexField(0))
      element.emit(SourceLocation.Unknown, 1)
      ARETURN()
    }

    private def NormalizeMethod: InstanceMethod = InstanceMethod(this.jvmName, "normalize", mkDescriptor()(VoidableType.Void))

    /**
      * `[] --> return`
      *
      * Repeatedly pops the top of the stack and reacts to what it is: an `Empty` is discarded, a
      * `Chain` is replaced by its two children (right pushed first, so left is popped next), and a
      * `One` -- the only case with a value to yield -- is pushed straight back and the loop stops.
      * A stack that started empty stays empty and the loop never runs its body, which is what
      * makes an exhausted iterator's `hasNext`/`next` behave correctly with no separate check.
      */
    private def normalizeIns(implicit mv: MethodVisitor): Unit = {
      whileLoop(Condition.NE) {
        thisLoad()
        GETFIELD(StackField)
        INVOKEVIRTUAL(JvmName.ArrayDeque, "isEmpty", mkDescriptor()(BackendType.Bool))
        ifConditionElse(Condition.NE) {
          // The stack is empty: nothing left to normalize, stop.
          pushBool(false)
        } {
          thisLoad()
          GETFIELD(StackField)
          INVOKEVIRTUAL(JvmName.ArrayDeque, "pop", mkDescriptor()(BackendType.Object))
          CHECKCAST(Tagged.jvmName)
          storeWithName(1, Tagged.toTpe) { node =>
            node.load()
            GETFIELD(Tagged.OrdinalField)
            pushInt(oneOrdinal)
            ifConditionElse(Condition.ICMPEQ) {
              // A leaf: push it back so `next` can read it, and stop.
              thisLoad()
              GETFIELD(StackField)
              node.load()
              INVOKEVIRTUAL(JvmName.ArrayDeque, "push", mkDescriptor(BackendType.Object)(VoidableType.Void))
              pushBool(false)
            } {
              node.load()
              GETFIELD(Tagged.OrdinalField)
              pushInt(emptyOrdinal)
              ifConditionElse(Condition.ICMPEQ) {
                // Already discarded by the pop above; keep looking.
                pushBool(true)
              } {
                // A branch: push what it owes, right first so left is popped next.
                thisLoad()
                GETFIELD(StackField)
                node.load()
                CHECKCAST(chainTag.jvmName)
                GETFIELD(chainTag.IndexField(1))
                CHECKCAST(Tagged.jvmName)
                INVOKEVIRTUAL(JvmName.ArrayDeque, "push", mkDescriptor(BackendType.Object)(VoidableType.Void))
                thisLoad()
                GETFIELD(StackField)
                node.load()
                CHECKCAST(chainTag.jvmName)
                GETFIELD(chainTag.IndexField(0))
                CHECKCAST(Tagged.jvmName)
                INVOKEVIRTUAL(JvmName.ArrayDeque, "push", mkDescriptor(BackendType.Object)(VoidableType.Void))
                pushBool(true)
              }
            }
          }
        }
      } {
        // The test above does all the work; there is nothing left to run per iteration.
      }
      RETURN()
    }
  }

  /**
    * A Flix tuple presented to Java as a generic record, one class per arity.
    *
    * The backend's own [[Tuple]] cannot be handed over: it is named after its *erased* element
    * types, so `(Int32, String)` is `Tuple$Int32$Obj` -- a name that says `Obj` where the caller
    * needs `String`, that changes shape when the element types do, and that lives in the package
    * the backend is free to rename. This class instead varies only in arity and takes the element
    * types as type parameters, so `(Int32, String)` and `(Bool, Regex)` are both `Tuple2`, told
    * apart by the shim's signature exactly as `Set[String]` and `Set[Regex]` are.
    *
    * That is also why every component is `Object`: a type parameter erases to `Object`, so a
    * primitive element is boxed on the way in by the element's own plan. A `Tuple2<Integer, String>`
    * is the only form Java can name.
    *
    * It is a *record* rather than a final class with getters. `equals`, `hashCode` and `toString`
    * are then three `invokedynamic` instructions derived from the components -- so they cannot
    * disagree with the fields -- instead of three hand-written method bodies. A tuple is value
    * data, and identity semantics on it would be a defect a Java caller could not work around.
    * Being a real record also lets Java 21 deconstruct it: `case Tuple2(var a, var b)`.
    */
  case class ExportTuple(arity: Int) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkClass(this.jvmName, IsFinal, superClass = JvmName.Record, signature = Some(classSignature))

      // The fields and the record components are separate declarations of the same thing: the
      // former is the storage, the latter is what makes this a record class at all.
      for (i <- indices) {
        cm.mkField(ComponentField(i), IsPrivate, IsFinal, NotVolatile, Some(typeVariable(i)))
        cm.mkRecordComponent(ComponentField(i), Some(typeVariable(i)))
      }
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_), Some(constructorSignature))
      for (i <- indices) {
        cm.mkMethod(Nil, AccessorMethod(i), IsPublic, NotFinal, accessorIns(i)(_), Some(accessorSignature(i)))
      }
      // Final, as javac makes them: a record's three derived methods are not meant to be overridden,
      // and nothing can extend this class to try.
      cm.mkMethod(Nil, ToStringMethod, IsPublic, IsFinal, derivedIns("toString", Nil, BackendType.String)(_))
      cm.mkMethod(Nil, HashCodeMethod, IsPublic, IsFinal, derivedIns("hashCode", Nil, BackendType.Int32)(_))
      cm.mkMethod(Nil, EqualsMethod, IsPublic, IsFinal, derivedIns("equals", List(BackendType.Object), BackendType.Bool)(_))

      cm.closeClassMaker()
    }

    /** The component indices, 1-based, because the accessors are named `_1` upwards. */
    private def indices: Range = 1 to arity

    /** The name of the type parameter of component `i`, e.g. `T1`. */
    private def typeParameter(i: Int): String = s"T$i"

    /** The type parameter of component `i` as it appears in a signature, e.g. `TT1;`. */
    private def typeVariable(i: Int): String = s"T${typeParameter(i)};"

    /** `<T1:Ljava/lang/Object;T2:Ljava/lang/Object;>Ljava/lang/Record;` */
    private def classSignature: String = {
      val params = indices.map(i => s"${typeParameter(i)}:${BackendType.Object.toDescriptor}").mkString
      s"<$params>${JvmName.Record.toTpe.toDescriptor}"
    }

    /** `(TT1;TT2;)V` */
    private def constructorSignature: String = s"(${indices.map(typeVariable).mkString})V"

    /** `()TT1;` */
    private def accessorSignature(i: Int): String = s"()${typeVariable(i)}"

    /**
      * The storage of component `i`.
      *
      * Named as the component is, because a record's accessor must be named after its component and
      * this is the name the bootstrap is handed a getter for.
      */
    def ComponentField(i: Int): InstanceField =
      InstanceField(this.jvmName, s"_$i", BackendType.Object)

    def AccessorMethod(i: Int): InstanceMethod =
      InstanceMethod(this.jvmName, s"_$i", mkDescriptor()(BackendType.Object))

    def Constructor: ConstructorMethod =
      ConstructorMethod(this.jvmName, List.fill(arity)(BackendType.Object))

    private def ToStringMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "toString", mkDescriptor()(BackendType.String))

    private def HashCodeMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "hashCode", mkDescriptor()(BackendType.Int32))

    private def EqualsMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "equals", mkDescriptor(BackendType.Object)(BackendType.Bool))

    /** `[] --> return`, storing each argument in its component. */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withNames(1, List.fill(arity)(BackendType.Object)) {
        case (_, variables) =>
          thisLoad()
          // `java.lang.Record`, not `java.lang.Object`: a record's superclass is what makes the
          // `Record` attribute meaningful, and its constructor is the one that must be called.
          INVOKESPECIAL(ConstructorMethod(JvmName.Record, Nil))
          for ((variable, i) <- variables.zip(indices)) {
            thisLoad()
            variable.load()
            PUTFIELD(ComponentField(i))
          }
          RETURN()
      }

    /** `[] --> [component i]` */
    private def accessorIns(i: Int)(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ComponentField(i))
      ARETURN()
    }

    /**
      * `[] --> [result]` for one of the three methods the runtime derives from the components.
      *
      * The bootstrap receives this class, its component names joined by `;`, and a getter handle
      * per component, and returns an implementation of the method named `name`. The call passes
      * `this` -- and, for `equals`, the other object -- so its descriptor is the method's with the
      * receiver made explicit, which is why it is built here rather than taken from the declaration.
      */
    private def derivedIns(name: String, arguments: List[BackendType], result: BackendType)(implicit mv: MethodVisitor): Unit = {
      val components = indices.map(i => ComponentField(i).name).mkString(";")
      val getters = indices.map(i => mkGetFieldHandle(ComponentField(i)).handle)
      thisLoad()
      for ((argument, slot) <- arguments.zipWithIndex) xLoad(argument, slot + 1)
      mv.visitInvokeDynamicInstruction(
        name,
        mkDescriptor((this.toTpe :: arguments) *)(result),
        mkStaticHandle(ClassConstants.ObjectMethods.BootstrapMethod),
        (this.toTpe.toAsmType :: components :: getters.toList) *
      )
      xReturn(result)
    }
  }

  /**
    * A Flix enum whose cases all carry no data, presented to Java as a real `java.lang.Enum`.
    *
    * The Flix representation is one singleton [[NullaryTag]] class per case, distinguished by the
    * `ordinal` field they inherit from [[Tagged]]. Those classes are named `Color$Red` in
    * `dev.flix.gen` and are exactly what J0 keeps private, so what crosses is a separate class
    * generated for the boundary.
    *
    * Unlike [[ExportTuple]], which varies only in arity, this one is a *user* type with a name, so
    * it is named beside its namespace as J1 requires: `enum Color` in `mod Acme.Api` is the class
    * `Acme.Api$Color` in the package `Acme`, sitting next to the `Acme.Api` facade rather than
    * inside a package named after it.
    *
    * The constants keep their Flix names -- `Red`, not `RED`. Java convention would prefer the
    * latter, but converting needs a heuristic that is lossy (`IPv4` becomes `IPV4`) and can make
    * two distinct cases collide, whereas the identity mapping cannot. Exported def names are
    * already used verbatim for the same reason.
    *
    * Only data-free enums are handled here. A case carrying a value has no constant to be, and
    * needs the sealed-interface-and-record shape instead.
    */
  case class ExportEnum(sym: Symbol.EnumSym) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val names = constantNames(root)
      val cm = ClassMaker.mkEnumClass(this.jvmName, Some(classSignature))

      names.foreach(name => cm.mkEnumConstant(ConstantField(name)))
      cm.mkSyntheticField(ValuesField, IsPrivate, IsFinal)
      // Private, as javac makes it: an enum's constants are exactly those the class initializer
      // creates, and a reachable constructor would let a caller make a value that is none of them.
      cm.mkConstructor(Constructor, IsPrivate, constructorIns(_))
      cm.mkStaticMethod(ValuesMethod, IsPublic, NotFinal, valuesIns(_))
      cm.mkStaticMethod(ValueOfMethod, IsPublic, NotFinal, valueOfIns(_))
      cm.mkStaticConstructor(StaticConstructorMethod(this.jvmName), staticConstructorIns(names)(_))

      cm.closeClassMaker()
    }

    /** The names of the cases, in ordinal order, which is the order the constants are created in. */
    private def constantNames(root: JvmAst.Root): List[String] =
      root.enums(sym).cases.keys.toList.sortBy(_.ordinal).map(_.name)

    /** `Ljava/lang/Enum<LAcme/Api$Color;>;` -- an enum is always `Enum` of itself. */
    private def classSignature: String =
      s"L${JvmName.Enum.toInternalName}<${this.toTpe.toDescriptor}>;"

    def ConstantField(name: String): StaticField = StaticField(this.jvmName, name, this.toTpe)

    /** Holds the constants in ordinal order, so `values()` need not rebuild them. */
    private def ValuesField: StaticField =
      StaticField(this.jvmName, "$VALUES", BackendType.Array(this.toTpe))

    private def Constructor: ConstructorMethod =
      ConstructorMethod(this.jvmName, List(BackendType.String, BackendType.Int32))

    private def ValuesMethod: StaticMethod =
      StaticMethod(this.jvmName, "values", mkDescriptor()(BackendType.Array(this.toTpe)))

    private def ValueOfMethod: StaticMethod =
      StaticMethod(this.jvmName, "valueOf", mkDescriptor(BackendType.String)(this.toTpe))

    /** `[] --> return`, passing the name and ordinal on to `java.lang.Enum`. */
    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      ALOAD(1)
      ILOAD(2)
      INVOKESPECIAL(ClassConstants.Enum.Constructor)
      RETURN()
    }

    /**
      * `[] --> [constants]`
      *
      * A copy, because the array is mutable and shared: handing out `$VALUES` itself would let one
      * caller overwrite what every later caller sees. This is what javac emits for the same reason.
      */
    private def valuesIns(implicit mv: MethodVisitor): Unit = {
      // An array type is its own descriptor where a class name is expected, and it is also the
      // owner of `clone`: `Object.clone` is protected, so invoking it there would not pass access
      // control. The public override lives on the array class, which is what javac targets too.
      val arrayType = BackendType.Array(this.toTpe).toDescriptor
      GETSTATIC(ValuesField)
      mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, arrayType, "clone", mkDescriptor()(BackendType.Object).toDescriptor, false)
      mv.visitTypeInstructionDirect(Opcodes.CHECKCAST, arrayType)
      ARETURN()
    }

    /** `[] --> [constant]`, delegating the name lookup and the error message to `java.lang.Enum`. */
    private def valueOfIns(implicit mv: MethodVisitor): Unit = {
      mv.visitLoadConstantInstruction(this.toTpe.toAsmType)
      ALOAD(0)
      INVOKESTATIC(ClassConstants.Enum.ValueOfMethod)
      CHECKCAST(this.jvmName)
      ARETURN()
    }

    /** `[] --> return`, creating each constant and then the array that holds them. */
    private def staticConstructorIns(names: List[String])(implicit mv: MethodVisitor): Unit = {
      for ((name, ordinal) <- names.zipWithIndex) {
        NEW(this.jvmName)
        DUP()
        pushString(name)
        pushInt(ordinal)
        INVOKESPECIAL(Constructor)
        PUTSTATIC(ConstantField(name))
      }
      pushInt(names.length)
      ANEWARRAY(this.jvmName)
      for ((name, ordinal) <- names.zipWithIndex) {
        DUP()
        pushInt(ordinal)
        GETSTATIC(ConstantField(name))
        xArrayStore(this.toTpe)
      }
      PUTSTATIC(ValuesField)
      RETURN()
    }
  }

  /**
    * A Flix enum with at least one data-carrying case, presented to Java as a sealed interface --
    * one generated record per case, see [[ExportCaseRecord]].
    *
    * Nothing about it is keyed on a representation. Like [[ExportEnum]], and unlike [[ExportTuple]],
    * it is a *user* type with a name, so J1 names it beside its namespace: `enum Shape` in
    * `mod Acme.Api` is the interface `Acme.Api$Shape`, sitting next to the `Acme.Api` facade.
    *
    * It declares no methods. A caller reaches a case's own accessors by pattern-matching down to
    * a specific record, so there is no contract every case needs to share -- this is a marker
    * interface, sealed only so a Java `switch` over it can be exhaustive without a `default`
    * branch, which is the entire reason to seal it.
    *
    * A pure-nullary enum keeps its existing, unrelated form -- a real `java.lang.Enum`, built by
    * [[ExportEnum]] -- rather than crossing through here as a sealed interface of zero-component
    * records. Two representations for the shape every case-free enum has always had would be the
    * drift J4 exists to prevent; this class exists only for the shape no plan could build before.
    */
  case class ExportSealedEnum(sym: Symbol.EnumSym) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkInterface(this.jvmName)
      caseSyms.foreach(c => cm.mkPermittedSubclass(ExportCaseRecord.jvmNameOf(c)))
      cm.closeClassMaker()
    }

    /** The symbols of the cases of `sym`, in ordinal order. */
    private def caseSyms(implicit root: JvmAst.Root): List[Symbol.CaseSym] =
      root.enums(sym).cases.keys.toList.sortBy(_.ordinal)
  }

  /**
    * A data-carrying case of an exported enum, presented to Java as a record implementing the
    * enum's sealed interface.
    *
    * Unlike [[ExportTuple]] this is not shared: a case is a user-declared shape with a name of its
    * own, so like [[ExportEnum]] it is named beside its namespace -- nested one level further,
    * under its enum's own name -- rather than kept in `dev.flix.runtime` under one class per arity.
    *
    * Unlike [[ExportTuple]] its components are neither boxed nor generic: nothing about this class
    * is ever shared across two different element-type instantiations -- one case has exactly one
    * shape -- so there is no erasure pressure forcing every component to `Object`. A component
    * keeps its element's own declared Java type, e.g. `Circle(int, int)`, not
    * `Circle(Integer, Integer)`. A nullary case inside a mixed enum is a zero-component record,
    * `record Square() implements Shape {}` -- Java permits this, and the bootstrap this class
    * shares with [[ExportTuple]] is defined for an empty component list, so nothing here needs to
    * special-case it.
    */
  case class ExportCaseRecord(caseSym: Symbol.CaseSym, elements: List[ExportPlan]) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkRecordClass(this.jvmName, interfaces = List(ExportSealedEnum(caseSym.enumSym).jvmName))

      for (i <- indices) {
        cm.mkField(ComponentField(i), IsPrivate, IsFinal, NotVolatile, componentSignature(i))
        cm.mkRecordComponent(ComponentField(i), componentSignature(i))
      }
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      for (i <- indices) {
        cm.mkMethod(Nil, AccessorMethod(i), IsPublic, NotFinal, accessorIns(i)(_), componentSignature(i).map(_ => accessorSignature(i)))
      }
      // Final, as javac makes them: a record's three derived methods are not meant to be
      // overridden, and nothing can extend this class to try -- it is already `IsFinal` above.
      cm.mkMethod(Nil, ToStringMethod, IsPublic, IsFinal, derivedIns("toString", Nil, BackendType.String)(_))
      cm.mkMethod(Nil, HashCodeMethod, IsPublic, IsFinal, derivedIns("hashCode", Nil, BackendType.Int32)(_))
      cm.mkMethod(Nil, EqualsMethod, IsPublic, IsFinal, derivedIns("equals", List(BackendType.Object), BackendType.Bool)(_))

      cm.closeClassMaker()
    }

    /** The component indices, 1-based, matching `ExportTuple`'s own accessor convention. */
    private def indices: Range = 1 to elements.length

    /** The declared Java type of component `i`. */
    private def componentType(i: Int): BackendType = elements(i - 1).javaType

    /**
      * The generic signature of component `i`, when its own type has arguments to declare -- e.g.
      * an element that is itself a generic Java type such as `ArrayList[String]`. `None` for every
      * primitive and every plain reference type, exactly as at a def's own shim signature: a plan
      * whose type argument does not differ from its descriptor has nothing a signature would add.
      */
    private def componentSignature(i: Int): Option[String] = {
      val plan = elements(i - 1)
      Option.when(plan.typeArgument != plan.javaType.toDescriptor)(plan.typeArgument)
    }

    def ComponentField(i: Int): InstanceField =
      InstanceField(this.jvmName, s"_$i", componentType(i))

    def AccessorMethod(i: Int): InstanceMethod =
      InstanceMethod(this.jvmName, s"_$i", mkDescriptor()(componentType(i)))

    def Constructor: ConstructorMethod =
      ConstructorMethod(this.jvmName, indices.map(componentType).toList)

    private def ToStringMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "toString", mkDescriptor()(BackendType.String))

    private def HashCodeMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "hashCode", mkDescriptor()(BackendType.Int32))

    private def EqualsMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "equals", mkDescriptor(BackendType.Object)(BackendType.Bool))

    /** `()<signature of component i>` */
    private def accessorSignature(i: Int): String = s"()${componentSignature(i).get}"

    /** `[] --> return`, storing each argument in its component. */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withNames(1, indices.map(componentType).toList) {
        case (_, variables) =>
          thisLoad()
          // `java.lang.Record`, not `java.lang.Object`: a record's superclass is what makes the
          // `Record` attribute meaningful, and its constructor is the one that must be called.
          INVOKESPECIAL(ConstructorMethod(JvmName.Record, Nil))
          for ((variable, i) <- variables.zip(indices)) {
            thisLoad()
            variable.load()
            PUTFIELD(ComponentField(i))
          }
          RETURN()
      }

    /** `[] --> [component i]` */
    private def accessorIns(i: Int)(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ComponentField(i))
      xReturn(componentType(i))
    }

    /**
      * `[] --> [result]` for one of the three methods the runtime derives from the components.
      *
      * The same bootstrap [[ExportTuple]] uses -- see its own `derivedIns` for why this is an
      * `invokedynamic` rather than three hand-written method bodies.
      */
    private def derivedIns(name: String, arguments: List[BackendType], result: BackendType)(implicit mv: MethodVisitor): Unit = {
      val components = indices.map(i => ComponentField(i).name).mkString(";")
      val getters = indices.map(i => mkGetFieldHandle(ComponentField(i)).handle)
      thisLoad()
      for ((argument, slot) <- arguments.zipWithIndex) xLoad(argument, slot + 1)
      mv.visitInvokeDynamicInstruction(
        name,
        mkDescriptor((this.toTpe :: arguments) *)(result),
        mkStaticHandle(ClassConstants.ObjectMethods.BootstrapMethod),
        (this.toTpe.toAsmType :: components :: getters.toList) *
      )
      xReturn(result)
    }
  }

  object ExportCaseRecord {
    /**
      * The JVM name of the record generated for case `caseSym`.
      *
      * Naming never reads `elements` (see the `jvmName` dispatcher), so `Nil` here is a valid stand-in
      * whenever only the name is needed -- as [[ExportSealedEnum]] does to list its permitted
      * subclasses without building the full per-element plans of a case it may not even be
      * generating a plan for.
      */
    def jvmNameOf(caseSym: Symbol.CaseSym): JvmName = ExportCaseRecord(caseSym, Nil).jvmName
  }

  /**
    * A Flix structural record presented to Java as a generated record, one class per distinct
    * sorted `(label, type)` shape.
    *
    * Shared program-wide, like [[ExportTuple]] and unlike a data-carrying case's own
    * [[ExportCaseRecord]]: a record's identity is its shape rather than a user declaration site,
    * so two defs returning "the same" record type -- same labels, same field types, any
    * declaration order -- get one class. `fields` is expected sorted by label already (see
    * `ExportPlan.of`'s record arm), which is what makes that sharing land on one name rather than
    * one per declaration order, and what keeps this class generating and every call site's
    * constructor call agreeing on the same argument order.
    *
    * Components are concretely typed rather than boxed and generic, unlike `ExportTuple`'s: two
    * records sharing a label set but differing in even one field's type already get separate
    * classes, since the type participates in the sharing key below, so nothing is bought by boxing
    * that a concrete type does not already give -- unlike arity-only tuple sharing, where the same
    * class serves every element-type instantiation and boxing is the only way to declare it once.
    *
    * Accessors are named after their Flix label directly (`.age()`, not `_1`), since unlike a
    * tuple's position or a case's position, a record field has a name the programmer chose to keep.
    */
  case class ExportRecord(fields: List[(String, ExportPlan)]) extends ExportClass {

    def genByteCode()(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
      val cm = ClassMaker.mkRecordClass(this.jvmName)

      for ((label, plan) <- fields) {
        cm.mkField(ComponentField(label, plan), IsPrivate, IsFinal, NotVolatile, componentSignature(plan))
        cm.mkRecordComponent(ComponentField(label, plan), componentSignature(plan))
      }
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      for ((label, plan) <- fields) {
        cm.mkMethod(Nil, AccessorMethod(label, plan), IsPublic, NotFinal, accessorIns(label, plan)(_), componentSignature(plan).map(_ => accessorSignature(plan)))
      }
      cm.mkMethod(Nil, ToStringMethod, IsPublic, IsFinal, derivedIns("toString", Nil, BackendType.String)(_))
      cm.mkMethod(Nil, HashCodeMethod, IsPublic, IsFinal, derivedIns("hashCode", Nil, BackendType.Int32)(_))
      cm.mkMethod(Nil, EqualsMethod, IsPublic, IsFinal, derivedIns("equals", List(BackendType.Object), BackendType.Bool)(_))

      cm.closeClassMaker()
    }

    /**
      * The generic signature of a field's component, when its own type has arguments to declare
      * -- e.g. a field that is itself a generic Java type such as `ArrayList[String]`. `None` for
      * every primitive and every plain reference type, exactly as at a def's own shim signature.
      */
    private def componentSignature(plan: ExportPlan): Option[String] =
      Option.when(plan.typeArgument != plan.javaType.toDescriptor)(plan.typeArgument)

    def ComponentField(label: String, plan: ExportPlan): InstanceField =
      InstanceField(this.jvmName, label, plan.javaType)

    def AccessorMethod(label: String, plan: ExportPlan): InstanceMethod =
      InstanceMethod(this.jvmName, label, mkDescriptor()(plan.javaType))

    def Constructor: ConstructorMethod =
      ConstructorMethod(this.jvmName, fields.map(_._2.javaType))

    private def ToStringMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "toString", mkDescriptor()(BackendType.String))

    private def HashCodeMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "hashCode", mkDescriptor()(BackendType.Int32))

    private def EqualsMethod: InstanceMethod =
      InstanceMethod(this.jvmName, "equals", mkDescriptor(BackendType.Object)(BackendType.Bool))

    /** `()<signature of this field's component>` */
    private def accessorSignature(plan: ExportPlan): String = s"()${componentSignature(plan).get}"

    /** `[] --> return`, storing each argument in its component. */
    private def constructorIns(implicit mv: MethodVisitor): Unit =
      withNames(1, fields.map(_._2.javaType)) {
        case (_, variables) =>
          thisLoad()
          // `java.lang.Record`, not `java.lang.Object`: a record's superclass is what makes the
          // `Record` attribute meaningful, and its constructor is the one that must be called.
          INVOKESPECIAL(ConstructorMethod(JvmName.Record, Nil))
          for ((variable, (label, plan)) <- variables.zip(fields)) {
            thisLoad()
            variable.load()
            PUTFIELD(ComponentField(label, plan))
          }
          RETURN()
      }

    /** `[] --> [component]` */
    private def accessorIns(label: String, plan: ExportPlan)(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ComponentField(label, plan))
      xReturn(plan.javaType)
    }

    /**
      * `[] --> [result]` for one of the three methods the runtime derives from the components.
      *
      * The same bootstrap [[ExportTuple]] and [[ExportCaseRecord]] use -- see `ExportTuple`'s own
      * `derivedIns` for why this is an `invokedynamic` rather than three hand-written method
      * bodies.
      */
    private def derivedIns(name: String, arguments: List[BackendType], result: BackendType)(implicit mv: MethodVisitor): Unit = {
      val components = fields.map { case (label, plan) => ComponentField(label, plan).name }.mkString(";")
      val getters = fields.map { case (label, plan) => mkGetFieldHandle(ComponentField(label, plan)).handle }
      thisLoad()
      for ((argument, slot) <- arguments.zipWithIndex) xLoad(argument, slot + 1)
      mv.visitInvokeDynamicInstruction(
        name,
        mkDescriptor((this.toTpe :: arguments) *)(result),
        mkStaticHandle(ClassConstants.ObjectMethods.BootstrapMethod),
        (this.toTpe.toAsmType :: components :: getters.toList) *
      )
      xReturn(result)
    }
  }

  //
  // Java Types
  //

  case object Result extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName)
      cm.closeClassMaker()
    }

    /**
      * Expects a Result on the stack and leaves a non-Thunk Result.
      * [..., Result] --> [..., Suspension|Value]
      */
    def unwindThunk()(implicit mv: MethodVisitor): Unit = {
      whileLoop(Condition.NE) {
        DUP()
        INSTANCEOF(Thunk.jvmName)
      } {
        CHECKCAST(Thunk.jvmName)
        INVOKEINTERFACE(Thunk.InvokeMethod)
      }
    }

    /**
      * Expects a Result on the stack.
      * If the result is a Suspension, this will return a modified Suspension.
      * If the result in NOT a Suspension, this will leave it on the stack.
      * [..., Result] --> [..., Thunk|Value]
      * side effect: Will return a modified suspension if a suspension occurs
      */
    private def handleSuspension(pc: Int, newFrame: MethodVisitor => Unit, setPc: MethodVisitor => Unit)(implicit mv: MethodVisitor): Unit = {
      DUP()
      INSTANCEOF(Suspension.jvmName)
      ifCondition(Condition.NE) {
        DUP()
        CHECKCAST(Suspension.jvmName) // [..., s]
        // Add our new frame
        NEW(Suspension.jvmName)
        DUP()
        INVOKESPECIAL(Suspension.Constructor) // [..., s, s']
        SWAP() // [..., s', s]
        DUP2() // [..., s', s, s', s]
        GETFIELD(Suspension.EffSymField)
        PUTFIELD(Suspension.EffSymField) // [..., s', s]
        DUP2()
        GETFIELD(Suspension.EffOpField)
        PUTFIELD(Suspension.EffOpField) // [..., s', s]
        DUP2()
        GETFIELD(Suspension.ResumptionField)
        PUTFIELD(Suspension.ResumptionField) // [..., s', s]
        DUP2()
        GETFIELD(Suspension.PrefixField) // [..., s', s, s', s.prefix]
        // Make the new frame and push it
        newFrame(mv)
        DUP()
        pushInt(pc)
        setPc(mv)
        INVOKEINTERFACE(Frames.PushMethod) // [..., s', s, s', prefix']
        PUTFIELD(Suspension.PrefixField) // [..., s', s]
        POP() // [..., s']
        // Return the suspension up the stack
        xReturn(Suspension.toTpe)
      }
    }

    /**
      * Expects a Result on the stack and leaves a Value.
      * This might return if a Suspension is encountered.
      * [..., Result] --> [..., Value.value: tpe]
      * side effect: Will return any Suspension found
      */
    def unwindThunkToValue(pc: Int, newFrame: MethodVisitor => Unit, setPc: MethodVisitor => Unit)(implicit mv: MethodVisitor): Unit = {
      unwindThunk()
      handleSuspension(pc, newFrame, setPc)
      CHECKCAST(Value.jvmName) // Cannot fail
    }

    /**
      * Expects a Result on the stack and leaves something of the given tpe but erased.
      * Assumes that the result is control-pure, i.e. it is not a suspension and will never return a suspension through a thunk.
      * [..., Result] --> [..., Value.value: tpe]
      * side effect: crashes on suspensions
      */
    def unwindSuspensionFreeThunkToType(tpe: BackendType, errorHint: String, loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
      unwindThunk()
      crashIfSuspension(errorHint, loc)
      CHECKCAST(Value.jvmName) // Cannot fail
      GETFIELD(Value.fieldFromType(tpe))
      castIfNotPrim(tpe)
    }

    /**
      * Expects a Result on the stack and leaves a Value.
      * Assumes that the result is control-pure, i.e. it is not a suspension and will never return a suspension through a thunk.
      * [..., Result] --> [..., Value]
      * side effect: crashes on suspensions
      */
    def unwindSuspensionFreeThunk(errorHint: String, loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
      unwindThunk()
      crashIfSuspension(errorHint, loc)
      CHECKCAST(Value.jvmName)
    }

    /**
      * [..., Result] -> [..., Value|Thunk]
      * side effect: if the result is a suspension, a [[UnhandledEffectError]] is thrown.
      */
    def crashIfSuspension(errorHint: String, loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
      DUP()
      INSTANCEOF(Suspension.jvmName)
      ifCondition(Condition.NE) {
        CHECKCAST(Suspension.jvmName)
        NEW(UnhandledEffectError.jvmName)
        // [.., suspension, UEE] -> [.., suspension, UEE, UEE, suspension]
        DUP2()
        SWAP()
        pushString(errorHint)
        pushLoc(loc)
        // [.., suspension, UEE, UEE, suspension, info, rsl] -> [.., suspension, UEE]
        INVOKESPECIAL(UnhandledEffectError.Constructor)
        ATHROW()
      }
    }
  }

  case object Value extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(Result.jvmName))

      // The fields of all erased types, only one will be relevant
      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkField(BoolField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(CharField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(Int8Field, IsPublic, NotFinal, NotVolatile)
      cm.mkField(Int16Field, IsPublic, NotFinal, NotVolatile)
      cm.mkField(Int32Field, IsPublic, NotFinal, NotVolatile)
      cm.mkField(Int64Field, IsPublic, NotFinal, NotVolatile)
      cm.mkField(Float32Field, IsPublic, NotFinal, NotVolatile)
      cm.mkField(Float64Field, IsPublic, NotFinal, NotVolatile)
      cm.mkField(ObjectField, IsPublic, NotFinal, NotVolatile)

      // Cached singleton Value instances for Unit, true, and false
      cm.mkField(UnitField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(TrueField, IsPublic, IsFinal, NotVolatile)
      cm.mkField(FalseField, IsPublic, IsFinal, NotVolatile)
      cm.mkStaticConstructor(StaticConstructorMethod(this.jvmName), staticConstructorIns(_))

      cm.closeClassMaker()
    }

    private def staticConstructorIns(implicit mv: MethodVisitor): Unit = {
      // Value.UNIT = new Value(); Value.UNIT.o = Unit.INSTANCE
      NEW(this.jvmName)
      DUP()
      INVOKESPECIAL(Constructor)
      DUP()
      GETSTATIC(BackendObjType.Unit.SingletonField)
      PUTFIELD(ObjectField)
      PUTSTATIC(UnitField)
      // Value.TRUE = new Value(); Value.TRUE.b = true
      NEW(this.jvmName)
      DUP()
      INVOKESPECIAL(Constructor)
      DUP()
      ICONST_1()
      PUTFIELD(BoolField)
      PUTSTATIC(TrueField)
      // Value.FALSE = new Value(); Value.FALSE.b = false (default, but explicit)
      NEW(this.jvmName)
      DUP()
      INVOKESPECIAL(Constructor)
      PUTSTATIC(FalseField)
      RETURN()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    private def BoolField: InstanceField = InstanceField(this.jvmName, "b", BackendType.Bool)

    private def CharField: InstanceField = InstanceField(this.jvmName, "c", BackendType.Char)

    private def Int8Field: InstanceField = InstanceField(this.jvmName, "i8", BackendType.Int8)

    private def Int16Field: InstanceField = InstanceField(this.jvmName, "i16", BackendType.Int16)

    private def Int32Field: InstanceField = InstanceField(this.jvmName, "i32", BackendType.Int32)

    private def Int64Field: InstanceField = InstanceField(this.jvmName, "i64", BackendType.Int64)

    private def Float32Field: InstanceField = InstanceField(this.jvmName, "f32", BackendType.Float32)

    private def Float64Field: InstanceField = InstanceField(this.jvmName, "f64", BackendType.Float64)

    private def ObjectField: InstanceField = InstanceField(this.jvmName, "o", BackendType.Object)

    def UnitField: StaticField = StaticField(this.jvmName, "UNIT", this.toTpe)

    def TrueField: StaticField = StaticField(this.jvmName, "TRUE", this.toTpe)

    def FalseField: StaticField = StaticField(this.jvmName, "FALSE", this.toTpe)

    /**
      * Returns the field of Value corresponding to the given type
      */
    def fieldFromType(tpe: BackendType): InstanceField = {
      import BackendType.*
      tpe match {
        case Bool => BoolField
        case Char => CharField
        case Int8 => Int8Field
        case Int16 => Int16Field
        case Int32 => Int32Field
        case Int64 => Int64Field
        case Float32 => Float32Field
        case Float64 => Float64Field
        case Array(_) | BackendType.Reference(_) => ObjectField
      }
    }
  }

  /** Frame is really just java.util.Function<Value, Result> * */
  case object Frame extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName)

      cm.mkInterfaceMethod(ApplyMethod)
      cm.mkStaticInterfaceMethod(StaticApplyMethod, IsPublic, NotFinal, staticApplyIns(_))

      cm.closeClassMaker()
    }

    def ApplyMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "applyFrame", mkDescriptor(Value.toTpe)(Result.toTpe))

    def StaticApplyMethod: StaticInterfaceMethod = StaticInterfaceMethod(
      this.jvmName,
      "applyFrameStatic",
      mkDescriptor(Frame.toTpe, Value.toTpe)(Result.toTpe)
    )

    private def staticApplyIns(implicit mv: MethodVisitor): Unit = {
      withName(0, Frame.toTpe) { fun =>
        withName(1, Value.toTpe) { resumeArg =>
          fun.load()
          resumeArg.load()
          INVOKEINTERFACE(Frame.ApplyMethod)
          ARETURN()
        }
      }
    }
  }

  case object Thunk extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName, interfaces = List(Result.jvmName, JvmName.Runnable))

      cm.mkInterfaceMethod(InvokeMethod)
      cm.mkDefaultMethod(RunMethod, IsPublic, NotFinal, runIns(_))

      cm.closeClassMaker()
    }

    def InvokeMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "invoke", mkDescriptor()(Result.toTpe))

    private def RunMethod: DefaultMethod = DefaultMethod(this.jvmName, "run", mkDescriptor()(VoidableType.Void))

    private def runIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      Result.unwindSuspensionFreeThunk(s"in ${JvmName.Runnable.toBinaryName}", SourceLocation.Unknown)
      POP()
      RETURN()
    }
  }

  case object Suspension extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(Result.jvmName))

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkField(EffSymField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(EffOpField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(PrefixField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(ResumptionField, IsPublic, NotFinal, NotVolatile)

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def EffSymField: InstanceField = InstanceField(this.jvmName, "effSym", BackendType.String)

    def EffOpField: InstanceField = InstanceField(this.jvmName, "effOp", EffectCall.toTpe)

    def PrefixField: InstanceField = InstanceField(this.jvmName, "prefix", Frames.toTpe)

    def ResumptionField: InstanceField = InstanceField(this.jvmName, "resumption", Resumption.toTpe)

  }

  case object Frames extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName)

      cm.mkInterfaceMethod(PushMethod)
      cm.mkInterfaceMethod(ReverseOntoMethod)

      cm.closeClassMaker()
    }

    def PushMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "push", mkDescriptor(Frame.toTpe)(Frames.toTpe))

    def ReverseOntoMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "reverseOnto", mkDescriptor(Frames.toTpe)(Frames.toTpe))

    def pushImplementation(implicit mv: MethodVisitor): Unit = {
      withName(1, Frame.toTpe) { frame =>
        NEW(FramesCons.jvmName)
        DUP()
        INVOKESPECIAL(FramesCons.Constructor)
        DUP()
        frame.load()
        PUTFIELD(FramesCons.HeadField)
        DUP()
        thisLoad()
        PUTFIELD(FramesCons.TailField)
        xReturn(FramesCons.toTpe)
      }
    }
  }

  case object FramesCons extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(Frames.jvmName))

      cm.mkField(HeadField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(TailField, IsPublic, NotFinal, NotVolatile)
      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkMethod(Nil, PushMethod, IsPublic, IsFinal, Frames.pushImplementation(_))
      cm.mkMethod(Nil, Frames.ReverseOntoMethod.implementation(this.jvmName), IsPublic, IsFinal, reverseOntoIns(_))

      cm.closeClassMaker()
    }

    def HeadField: InstanceField = InstanceField(this.jvmName, "head", Frame.toTpe)

    def TailField: InstanceField = InstanceField(this.jvmName, "tail", Frames.toTpe)

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def PushMethod: InstanceMethod = Frames.PushMethod.implementation(this.jvmName)

    private def reverseOntoIns(implicit mv: MethodVisitor): Unit = {
      withName(1, Frames.toTpe) { rest =>
        thisLoad()
        GETFIELD(TailField)
        NEW(FramesCons.jvmName)
        DUP()
        INVOKESPECIAL(FramesCons.Constructor)
        DUP()
        thisLoad()
        GETFIELD(HeadField)
        PUTFIELD(HeadField)
        DUP()
        rest.load()
        PUTFIELD(TailField)
        INVOKEINTERFACE(Frames.ReverseOntoMethod)
        xReturn(Frames.toTpe)
      }
    }
  }

  case object FramesNil extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(Frames.jvmName))

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkMethod(Nil, PushMethod, IsPublic, IsFinal, Frames.pushImplementation(_))
      cm.mkMethod(Nil, Frames.ReverseOntoMethod.implementation(this.jvmName), IsPublic, IsFinal, reverseOntoIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def PushMethod: InstanceMethod = Frames.PushMethod.implementation(this.jvmName)

    private def reverseOntoIns(implicit mv: MethodVisitor): Unit = {
      withName(1, Frames.toTpe) { rest =>
        rest.load()
        xReturn(rest.tpe)
      }
    }
  }

  case object Resumption extends BackendObjType {
    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName)
      cm.mkInterfaceMethod(RewindMethod)
      cm.mkStaticInterfaceMethod(StaticRewindMethod, IsPublic, NotFinal, staticRewindIns(_))
      cm.closeClassMaker()
    }

    def RewindMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "rewind", mkDescriptor(Value.toTpe)(Result.toTpe))

    def StaticRewindMethod: StaticInterfaceMethod = StaticInterfaceMethod(this.jvmName, "staticRewind", mkDescriptor(Resumption.toTpe, Value.toTpe)(Result.toTpe))

    private def staticRewindIns(implicit mv: MethodVisitor): Unit = {
      withName(0, Resumption.toTpe) { resumption =>
        withName(1, Value.toTpe) { v =>
          resumption.load()
          v.load()
          INVOKEINTERFACE(Resumption.RewindMethod)
          ARETURN()
        }
      }
    }
  }

  case object ResumptionCons extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(Resumption.jvmName))

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))

      cm.mkField(SymField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(HandlerField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(FramesField, IsPublic, NotFinal, NotVolatile)
      cm.mkField(TailField, IsPublic, NotFinal, NotVolatile)

      cm.mkMethod(Nil, Resumption.RewindMethod.implementation(this.jvmName), IsPublic, IsFinal, rewindIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    def SymField: InstanceField = InstanceField(this.jvmName, "sym", BackendType.String)

    def HandlerField: InstanceField = InstanceField(this.jvmName, "handler", Handler.toTpe)

    def FramesField: InstanceField = InstanceField(this.jvmName, "frames", Frames.toTpe)

    def TailField: InstanceField = InstanceField(this.jvmName, "tail", Resumption.toTpe)

    private def rewindIns(implicit mv: MethodVisitor): Unit = {
      withName(1, Value.toTpe) { v =>
        thisLoad()
        GETFIELD(SymField)
        thisLoad()
        GETFIELD(HandlerField)
        thisLoad()
        GETFIELD(FramesField)
        // () -> tail.rewind(v)
        thisLoad()
        GETFIELD(TailField)
        v.load()
        mkStaticLambda(Thunk.InvokeMethod, Resumption.StaticRewindMethod, drop = 0)
        mkStaticLambda(Thunk.InvokeMethod, Handler.InstallHandlerMethod, drop = 0)
        xReturn(Thunk.toTpe)
      }
    }
  }

  case object ResumptionNil extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, interfaces = List(Resumption.jvmName))

      cm.mkConstructor(Constructor, IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
      cm.mkMethod(Nil, Resumption.RewindMethod.implementation(this.jvmName), IsPublic, IsFinal, rewindIns(_))

      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, Nil)

    private def rewindIns(implicit mv: MethodVisitor): Unit = {
      withName(1, Value.toTpe) { v =>
        v.load()
        xReturn(v.tpe)
      }
    }
  }

  case object Handler extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName)
      cm.mkStaticInterfaceMethod(InstallHandlerMethod, IsPublic, NotFinal, installHandlerIns(_))
      cm.closeClassMaker()
    }

    def InstallHandlerMethod: StaticInterfaceMethod = StaticInterfaceMethod(
      this.jvmName,
      "installHandler",
      mkDescriptor(BackendType.String, Handler.toTpe, Frames.toTpe, Thunk.toTpe)(Result.toTpe)
    )

    private def installHandlerIns(implicit mv: MethodVisitor): Unit = {
      withName(0, BackendType.String) { effSym =>
        withName(1, Handler.toTpe) { handler =>
          withName(2, Frames.toTpe) { frames =>
            withName(3, Thunk.toTpe) { thunk =>
              thunk.load()
              // Thunk|Value|Suspension
              Result.unwindThunk()
              // Value|Suspension
              // handle suspension
              DUP()
              INSTANCEOF(Suspension.jvmName)
              ifCondition(Condition.NE) {
                DUP()
                CHECKCAST(Suspension.jvmName)
                storeWithName(4, Suspension.toTpe) { s =>
                  NEW(ResumptionCons.jvmName)
                  DUP()
                  INVOKESPECIAL(ResumptionCons.Constructor)
                  DUP()
                  effSym.load()
                  PUTFIELD(ResumptionCons.SymField)
                  DUP()
                  handler.load()
                  PUTFIELD(ResumptionCons.HandlerField)
                  DUP()
                  s.load()
                  GETFIELD(Suspension.PrefixField)
                  frames.load()
                  INVOKEINTERFACE(Frames.ReverseOntoMethod)
                  PUTFIELD(ResumptionCons.FramesField)
                  DUP()
                  s.load()
                  GETFIELD(Suspension.ResumptionField)
                  PUTFIELD(ResumptionCons.TailField)
                  storeWithName(5, ResumptionCons.toTpe) { r =>
                    s.load()
                    GETFIELD(Suspension.EffSymField)
                    effSym.load()
                    INVOKEVIRTUAL(ClassConstants.Object.EqualsMethod)
                    ifCondition(Condition.NE) {
                      s.load()
                      GETFIELD(Suspension.EffOpField)
                      handler.load()
                      r.load()
                      INVOKEINTERFACE(EffectCall.ApplyMethod)
                      xReturn(Result.toTpe)
                    }
                    NEW(Suspension.jvmName)
                    DUP()
                    INVOKESPECIAL(Suspension.Constructor)
                    DUP()
                    s.load()
                    GETFIELD(Suspension.EffSymField)
                    PUTFIELD(Suspension.EffSymField)
                    DUP()
                    s.load()
                    GETFIELD(Suspension.EffOpField)
                    PUTFIELD(Suspension.EffOpField)
                    DUP()
                    NEW(FramesNil.jvmName)
                    DUP()
                    INVOKESPECIAL(FramesNil.Constructor)
                    PUTFIELD(Suspension.PrefixField)
                    DUP()
                    r.load()
                    PUTFIELD(Suspension.ResumptionField)
                    xReturn(Suspension.toTpe)
                  }
                }
              }

              // Value
              CHECKCAST(Value.jvmName)
              storeWithName(6, Value.toTpe) { res =>
                //
                // Case on frames
                // FramesNil
                frames.load()
                INSTANCEOF(FramesNil.jvmName)
                ifCondition(Condition.NE) {
                  res.load()
                  xReturn(Value.toTpe)
                }
                // FramesCons
                frames.load()
                CHECKCAST(FramesCons.jvmName)
                storeWithName(7, FramesCons.toTpe) { cons => {
                  effSym.load()
                  handler.load()
                  cons.load()
                  GETFIELD(FramesCons.TailField)
                  // thunk
                  cons.load()
                  GETFIELD(FramesCons.HeadField)
                  res.load()
                  mkStaticLambda(Thunk.InvokeMethod, Frame.StaticApplyMethod, drop = 0)
                  INVOKESTATIC(InstallHandlerMethod)
                  xReturn(Result.toTpe)
                }
                }
              }
            }
          }
        }
      }
    }
  }

  case object EffectCall extends BackendObjType {

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkInterface(this.jvmName)
      cm.mkInterfaceMethod(ApplyMethod)
      cm.closeClassMaker()
    }

    def ApplyMethod: InterfaceMethod = InterfaceMethod(this.jvmName, "apply", mkDescriptor(Handler.toTpe, Resumption.toTpe)(Result.toTpe))

  }

  case class ResumptionWrapper(tpe: BackendType) extends BackendObjType {

    // tpe -> Result
    private val superClass: AbstractArrow = AbstractArrow(List(tpe.toErased), BackendType.Object)

    def genByteCode()(implicit flix: Flix): Array[Byte] = {
      val cm = mkClass(this.jvmName, IsFinal, superClass.jvmName)
      cm.mkConstructor(Constructor, IsPublic, constructorIns(_))
      cm.mkField(ResumptionField, IsPrivate, IsFinal, NotVolatile)
      cm.mkMethod(Nil, InvokeMethod, IsPublic, NotFinal, invokeIns(_))
      cm.mkMethod(Nil, UniqueMethod, IsPublic, NotFinal, uniqueIns(_))
      cm.closeClassMaker()
    }

    def Constructor: ConstructorMethod = ConstructorMethod(this.jvmName, List(Resumption.toTpe))

    private def constructorIns(implicit mv: MethodVisitor): Unit = {
      withName(1, Resumption.toTpe) { resumption =>
        thisLoad()
        INVOKESPECIAL(superClass.jvmName, JvmName.ConstructorMethod, MethodDescriptor.NothingToVoid)
        thisLoad()
        resumption.load()
        PUTFIELD(ResumptionField)
        RETURN()
      }
    }

    def ResumptionField: InstanceField = InstanceField(this.jvmName, "resumption", Resumption.toTpe)

    def InvokeMethod: InstanceMethod = Thunk.InvokeMethod.implementation(this.jvmName)

    private def invokeIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      GETFIELD(ResumptionField)
      tpe.toErased match {
        case BackendType.Bool =>
          // Use cached Value.TRUE / Value.FALSE singletons
          thisLoad()
          mv.visitFieldInsn(Opcodes.GETFIELD, this.jvmName.toInternalName, "arg0", tpe.toErased.toDescriptor)
          val falseLabel = new Label()
          val doneLabel = new Label()
          mv.visitJumpInsn(Opcodes.IFEQ, falseLabel)
          GETSTATIC(Value.TrueField)
          mv.visitJumpInsn(Opcodes.GOTO, doneLabel)
          mv.visitLabel(falseLabel)
          GETSTATIC(Value.FalseField)
          mv.visitLabel(doneLabel)
        case _ =>
          NEW(Value.jvmName)
          DUP()
          INVOKESPECIAL(Value.Constructor)
          DUP()
          thisLoad()
          mv.visitFieldInsn(Opcodes.GETFIELD, this.jvmName.toInternalName, "arg0", tpe.toErased.toDescriptor)
          PUTFIELD(Value.fieldFromType(tpe.toErased))
      }
      INVOKEINTERFACE(Resumption.RewindMethod)
      xReturn(Result.toTpe)
    }

    private def UniqueMethod: InstanceMethod = InstanceMethod(this.jvmName, "getUniqueThreadClosure", mkDescriptor()(this.superClass.toTpe))

    private def uniqueIns(implicit mv: MethodVisitor): Unit = {
      thisLoad()
      ARETURN()
    }
  }
}
