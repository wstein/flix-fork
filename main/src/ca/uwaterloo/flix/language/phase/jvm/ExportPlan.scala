/*
 * Copyright 2026 Werner Stein
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

import ca.uwaterloo.flix.language.ast.{JvmAst, SimpleType, SourceLocation, Symbol}
import ca.uwaterloo.flix.language.phase.jvm.BytecodeInstructions.*
import ca.uwaterloo.flix.language.phase.jvm.JvmName.MethodDescriptor.mkDescriptor
import ca.uwaterloo.flix.util.InternalCompilerException
import org.objectweb.asm.MethodVisitor

/**
  * How the value of an exported def is converted into the Java value its shim method returns.
  *
  * A plan is a tree rather than a flag so that conversions compose: the element of a converted
  * container is described by a plan of its own. Everything the boundary needs is derived from the
  * one structure -- the Java type, the type argument it contributes to a generic signature, and the
  * instructions that perform the conversion -- so the descriptor a caller compiles against and the
  * bytecode it ends up calling cannot drift apart.
  *
  * The naming half is an [[ExportSignature]], which a plan derives rather than holds. It is
  * separable because it needs nothing from the compilation in progress, and it is separate because
  * a build tool describing the boundary before codegen has run cannot build a plan at all. See
  * that file for why the split is where it is.
  *
  * A type that needs no conversion has no plan. Those already have an exact Java representation and
  * are passed through untouched, which is why [[Identity]] appears only inside another plan.
  */
sealed trait ExportPlan {

  /**
    * The type the conversion consumes, i.e. the representation the value has on the stack when
    * [[emit]] runs.
    *
    * Not every plan starts from a tag. A converted `Option` does, but a Java type that is merely
    * being described starts as itself, and reading it as a tag would emit a cast that fails
    * verification.
    */
  def flixType: BackendType

  /**
    * How the produced value is named to a Java caller.
    *
    * Derived, never held: a plan that carried its signature could be constructed with one that
    * contradicts the tags it emits against, and the drift this structure exists to prevent would
    * be reachable through its own constructor.
    */
  def signature: ExportSignature

  /** The Java type the conversion produces. */
  final def javaType: BackendType = signature.javaType

  /**
    * The descriptor this plan contributes as a type argument.
    *
    * Type arguments are references, so a primitive appears boxed: the element of an
    * `Option[Int32]` is `Ljava/lang/Integer;`, not `I`.
    */
  final def typeArgument: String = signature.typeArgument

  /**
    * Emits the conversion.
    *
    * `[..., flix value] --> [..., java value]`
    *
    * `nextLocal` is the first local variable slot the enclosing method is not already using.
    * Walking a data structure needs somewhere to keep its cursor, and a shim's own parameters own
    * the slots below it.
    */
  def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit

  /**
    * The classes this plan needs generated, including those of any nested plan.
    *
    * A conversion that hands back a view rather than a copy needs a class to be that view. These
    * are the only generated classes keyed on a plan rather than on a type in `root.types`, which is
    * why `CodeGen` has to collect them from the exported defs.
    */
  def generatedClasses: List[BackendObjType.ExportClass] = Nil
}

object ExportPlan {

  /** A value that already has the Java type it is declared with. */
  case class Identity(tpe: BackendType) extends ExportPlan {
    def flixType: BackendType = tpe

    def signature: ExportSignature = ExportSignature.Exact(tpe)

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = ()
  }

  /**
    * A Java class that was applied to type arguments, presented with them.
    *
    * Nothing is converted -- an `ArrayList[String]` already *is* a `java.util.ArrayList`. The plan
    * exists only to say what the arguments are, because the descriptor cannot, and because the
    * declared type is the last place they survive.
    */
  case class GenericNative(clazz: JvmName, targs: List[ExportPlan]) extends ExportPlan {
    def flixType: BackendType = javaType

    def signature: ExportSignature = ExportSignature.Applied(clazz, targs.map(_.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = ()

    override def generatedClasses: List[BackendObjType.ExportClass] = targs.flatMap(_.generatedClasses)
  }

  /** A primitive that must be boxed, because the value it is being placed into holds references. */
  case class Boxed(primitive: BackendType, boxed: JvmName) extends ExportPlan {
    def flixType: BackendType = primitive

    def signature: ExportSignature = ExportSignature.Boxed(primitive, boxed)

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      INVOKESTATIC(boxed, "valueOf", mkDescriptor(primitive)(boxed.toTpe))
  }

  /**
    * A Flix `Option` presented as a `java.util.Optional`.
    *
    * The ordinal of `None` and the tag class of `Some` are resolved when the plan is built rather
    * than assumed, because they are assigned per compilation.
    */
  case class AsOptional(element: ExportPlan, noneOrdinal: Int, someTag: BackendObjType.Tag) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature = ExportSignature.Applied(JvmName.Optional, List(element.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      DUP()
      GETFIELD(BackendObjType.Tagged.OrdinalField)
      pushInt(noneOrdinal)
      ifConditionElse(Condition.ICMPEQ) {
        POP()
        INVOKESTATIC(JvmName.Optional, "empty", mkDescriptor()(javaType))
      } {
        CHECKCAST(someTag.jvmName)
        GETFIELD(someTag.IndexField(0))
        element.emit(loc, nextLocal)
        // `ofNullable` rather than `of`: a `Some` may hold a Java value that is itself null, and
        // `of` would turn that into a NullPointerException at the boundary.
        INVOKESTATIC(JvmName.Optional, "ofNullable", mkDescriptor(BackendType.Object)(javaType))
      }
    }

    override def generatedClasses: List[BackendObjType.ExportClass] = element.generatedClasses
  }

  /**
    * A Flix `Set` presented as an unmodifiable `java.util.Set`, without copying it.
    *
    * Unlike [[AsList]] this converts nothing here: it unwraps the red-black tree and hands it to a
    * generated view that walks it on demand. `element` describes the element for the *signature*;
    * the conversion the view emits per element lives in the view class, which is keyed on the
    * erased element instead (see [[BackendObjType.TreeSetView]]).
    *
    * The set value is a single-case tag holding the tree, so the tree is one field read away.
    */
  case class AsSet(element: ExportPlan, setTag: BackendObjType.Tag, view: BackendObjType.TreeSetView) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature = ExportSignature.Applied(JvmName.JavaSet, List(element.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      emitTreeView(setTag, view.jvmName, view.Constructor, nextLocal)

    override def generatedClasses: List[BackendObjType.ExportClass] =
      view :: view.iteratorType :: element.generatedClasses
  }

  /**
    * A Flix `Map` presented as an unmodifiable `java.util.Map`, without copying it.
    *
    * The same shape as [[AsSet]] -- one tag holding a red-black tree -- and the same view over it,
    * differing only in what each node contributes: an entry rather than a key.
    */
  case class AsMap(key: ExportPlan, value: ExportPlan, mapTag: BackendObjType.Tag, view: BackendObjType.MapView) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature =
      ExportSignature.Applied(JvmName.JavaMap, List(key.signature, value.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      emitTreeView(mapTag, view.jvmName, view.Constructor, nextLocal)

    override def generatedClasses: List[BackendObjType.ExportClass] =
      view :: view.entrySetType :: view.entrySetType.iteratorType ::
        (key.generatedClasses ::: value.generatedClasses)
  }

  /**
    * Unwraps the red-black tree of a tree-backed collection and hands it to its view.
    *
    * `[..., tagged collection] --> [..., view]`
    *
    * The tree goes through a local because `new` has to be on the stack below its argument, and
    * the argument is what is already there.
    */
  private def emitTreeView(containerTag: BackendObjType.Tag, view: JvmName, constructor: ClassMaker.ConstructorMethod, nextLocal: Int)(implicit mv: MethodVisitor): Unit =
    withName(nextLocal, BackendObjType.Tagged.toTpe) { tree =>
      CHECKCAST(containerTag.jvmName)
      GETFIELD(containerTag.IndexField(0))
      CHECKCAST(BackendObjType.Tagged.jvmName)
      tree.store()
      NEW(view)
      DUP()
      tree.load()
      INVOKESPECIAL(constructor)
    }

  /**
    * A Flix `List` presented as an unmodifiable `java.util.List`, without copying it.
    *
    * This was an eager copy into an `ArrayList` until the views existed. Both forms hand a caller
    * the same `java.util.List`, which is what made the copy safe to ship first and safe to replace
    * now -- no signature changes. Only one is kept: two conversions for one type is exactly the
    * drift J4 exists to prevent, and the copy's remaining advantage (a primitive element boxed
    * once rather than once per traversal) is not worth a second code path.
    *
    * Unlike a `Set` or a `Map`, a `List` value *is* the chain -- there is no wrapper tag to
    * unwrap -- so the view is constructed straight from it.
    */
  case class AsList(element: ExportPlan, view: BackendObjType.ListView) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature = ExportSignature.Applied(JvmName.JavaList, List(element.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(nextLocal, BackendObjType.Tagged.toTpe) { chain =>
        chain.store()
        NEW(view.jvmName)
        DUP()
        chain.load()
        INVOKESPECIAL(view.Constructor)
      }

    override def generatedClasses: List[BackendObjType.ExportClass] =
      view :: view.iteratorType :: element.generatedClasses
  }

  /**
    * A Flix `Vector` presented as an unmodifiable `java.util.List`, without copying it.
    *
    * Unlike [[AsList]] this is not a `Tagged` conversion: a `Vector` value already *is* a Java
    * array (`Array.toVector` casts one after a defensive copy, rather than wrapping it in a tag),
    * so `arrayType` -- the type the value genuinely has on the stack -- is concrete, not the shared
    * `Tagged` every other converted enum uses. `element`, by contrast, is the *erased* plan used
    * only for the signature, exactly as at every other view: the conversion `view` itself performs
    * per element is described separately, keyed on the erased type so every reference element
    * shares one view class. See [[BackendObjType.VectorView]].
    */
  case class AsVector(element: ExportPlan, arrayType: BackendType, view: BackendObjType.VectorView) extends ExportPlan {
    def flixType: BackendType = arrayType

    def signature: ExportSignature = ExportSignature.Applied(JvmName.JavaList, List(element.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(nextLocal, arrayType) { arr =>
        arr.store()
        NEW(view.jvmName)
        DUP()
        arr.load()
        INVOKESPECIAL(view.Constructor)
      }

    override def generatedClasses: List[BackendObjType.ExportClass] =
      view :: element.generatedClasses
  }

  /**
    * A Flix `Chain` presented as an unmodifiable `java.util.Collection`, without copying it.
    *
    * `Chain`'s value directly *is* one of its three cases -- there is no wrapper tag to unwrap, the
    * same shape [[AsList]] has and unlike [[AsSet]]/[[AsMap]]'s tree-behind-a-tag. See
    * [[BackendObjType.ExportedChainView]] for why `Collection` rather than `List`, and for the walk.
    */
  case class AsChain(element: ExportPlan, view: BackendObjType.ExportedChainView) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature = ExportSignature.Applied(JvmName.JavaCollection, List(element.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(nextLocal, BackendObjType.Tagged.toTpe) { chain =>
        chain.store()
        NEW(view.jvmName)
        DUP()
        chain.load()
        INVOKESPECIAL(view.Constructor)
      }

    override def generatedClasses: List[BackendObjType.ExportClass] =
      view :: view.iteratorType :: element.generatedClasses
  }

  /**
    * A Flix structural record presented as a generated `dev.flix.runtime` record.
    *
    * Its identity is its shape -- `EntryPoints.checkExportedTypeVariables` already guarantees a
    * closed row by the time any record type reaches export at all, so every field is statically
    * known here, the same guarantee a tuple's fixed arity gives [[AsTuple]]. Unlike a tuple's
    * record, components are named after their Flix labels rather than by position (a record field
    * has a name the programmer chose; a tuple field never did), and unlike a tuple's record,
    * components are concretely typed rather than boxed and generic (see
    * [[BackendObjType.ExportRecord]] for why the two records differ there).
    */
  case class AsRecord(fields: List[AsRecord.Field], record: BackendObjType.ExportRecord) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Record.toTpe

    def signature: ExportSignature = ExportSignature.Exact(record.toTpe)

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      withName(nextLocal, BackendObjType.Record.toTpe) { rec =>
        rec.store()
        NEW(record.jvmName)
        DUP()
        for (field <- fields) {
          val internal = BackendObjType.RecordExtend(field.erasedType)
          rec.load()
          pushString(field.label)
          INVOKEINTERFACE(BackendObjType.Record.LookupFieldMethod)
          CHECKCAST(internal.jvmName)
          GETFIELD(internal.ValueField)
          field.plan.emit(loc, nextLocal + rec.tpe.stackSlots)
          castIfNotPrim(field.plan.javaType)
        }
        INVOKESPECIAL(record.Constructor)
      }

    override def generatedClasses: List[BackendObjType.ExportClass] =
      record :: fields.flatMap(_.plan.generatedClasses)
  }

  object AsRecord {
    /**
      * One field of a record conversion.
      *
      * `erasedType` is what the internal `RecordExtend` node this field is read off of is keyed
      * on -- reference-typed values are erased to `Object` there, the same as a data-carrying
      * case's `Tag` -- so it names the class to `CHECKCAST` to and the field to read, independent
      * of `plan`, which describes what the *declared* type is and how it is presented to Java.
      */
    case class Field(label: String, erasedType: BackendType, plan: ExportPlan)
  }

  /**
    * A Flix tuple presented as a `dev.flix.runtime.TupleN` record.
    *
    * A copy rather than a view, which is the one place this differs from every other container
    * here. The reasons a view wins elsewhere -- not walking a structure the caller may not read all
    * of, not boxing what it never asks for -- do not apply to a fixed, small number of already
    * materialized fields, and a view would instead re-run each element's conversion on every
    * access. See [[BackendObjType.ExportTuple]] for why the target is a record rather than the
    * backend's own tuple class.
    *
    * `flixTuple` is the backend's representation, rebuilt here from the erased element types. It is
    * not read from the AST because there is nothing to read: a tuple has no declaration of its own,
    * so the class the backend generated for it is determined entirely by that erasure.
    */
  case class AsTuple(elements: List[ExportPlan], flixTuple: BackendObjType.Tuple, tuple: BackendObjType.ExportTuple) extends ExportPlan {
    def flixType: BackendType = flixTuple.toTpe

    def signature: ExportSignature =
      ExportSignature.Applied(tuple.jvmName, elements.map(_.signature))

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      // The tuple goes through a local for the same reason a view's tree does: `new` has to be on
      // the stack below its arguments, and the value they are read from is what is already there.
      withName(nextLocal, flixTuple.toTpe) { value =>
        value.store()
        NEW(tuple.jvmName)
        DUP()
        for ((element, i) <- elements.zipWithIndex) {
          value.load()
          GETFIELD(flixTuple.IndexField(i))
          element.emit(loc, nextLocal + value.tpe.stackSlots)
        }
        INVOKESPECIAL(tuple.Constructor)
      }

    override def generatedClasses: List[BackendObjType.ExportClass] =
      tuple :: elements.flatMap(_.generatedClasses)
  }

  /**
    * A Flix enum whose cases all carry no data, presented as a real Java enum.
    *
    * The value is one of a fixed set of singletons distinguished by the `ordinal` they inherit
    * from `Tagged`, and the generated enum's constants are created in that same order, so the
    * conversion is a switch from one to the other.
    *
    * By ordinal rather than by `INSTANCEOF` on each tag class, which is what an `Option` uses.
    * That is not an inconsistency: `Option` asks a two-way question -- is this the case that
    * carries a value -- where one type test is simpler than reading a field and comparing it.
    * Here the question is which of `n` cases this is, and a dense switch answers it in one step
    * where a chain of type tests would take `n`.
    */
  case class AsEnum(enum0: BackendObjType.ExportEnum, constants: List[String]) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature = ExportSignature.Exact(enum0.toTpe)

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      GETFIELD(BackendObjType.Tagged.OrdinalField)
      tableSwitch(constants.map(name => (mv: MethodVisitor) => GETSTATIC(enum0.ConstantField(name))(mv))) { implicit mv =>
        // Unreachable: the ordinals of an enum are exactly `0` until the number of its cases, and
        // this switch has a branch for each. It is written rather than omitted because a switch
        // must have a default, and throwing says which invariant broke if one ever does.
        throwWithMessage(JvmName.IllegalStateException, s"Unknown ordinal for '${enum0.jvmName.toBinaryName}'")
      }
    }

    override def generatedClasses: List[BackendObjType.ExportClass] = List(enum0)
  }

  /**
    * A Flix enum with at least one data-carrying case, presented as a sealed interface with one
    * generated record per case.
    *
    * `cases` is in ordinal order, matching the order [[AsEnum.constants]] uses for the same
    * reason: the value's `ordinal` is what a `tableSwitch` dispatches on.
    *
    * A pure-nullary enum never reaches here -- [[isNullaryEnum]] claims it first, in [[of]]'s own
    * match order, so this exists only for the shape that solver could not build.
    */
  case class AsSealedEnum(enum0: BackendObjType.ExportSealedEnum, cases: List[AsSealedEnum.Case]) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def signature: ExportSignature = ExportSignature.Exact(enum0.toTpe)

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      // Duplicated because the ordinal is read from one copy while the other is kept around for
      // whichever branch needs to read the value's own fields.
      DUP()
      storeWithName(nextLocal, BackendObjType.Tagged.toTpe) { tagged =>
        GETFIELD(BackendObjType.Tagged.OrdinalField)
        tableSwitch(cases.map(c => (mv: MethodVisitor) => emitCase(loc, c, tagged, nextLocal + tagged.tpe.stackSlots)(root, mv))) { implicit mv =>
          // Unreachable for the same reason it is in `AsEnum`: every ordinal the value can have has
          // a branch, and a switch must still have a default.
          throwWithMessage(JvmName.IllegalStateException, s"Unknown ordinal for '${enum0.jvmName.toBinaryName}'")
        }
      }
    }

    /**
      * `[..., tagged value] --> [..., record]` for one case.
      *
      * A nullary case reads nothing -- there is nothing on its record to fill in -- and a
      * data-carrying one reads each element the same way [[AsTuple.emit]] reads a tuple's: off a
      * local holding the checked-cast internal value, one field at a time.
      */
    private def emitCase(loc: SourceLocation, c: AsSealedEnum.Case, tagged: Variable, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      c.tag match {
        case None =>
          NEW(c.record.jvmName)
          DUP()
          INVOKESPECIAL(c.record.Constructor)
        case Some(tag) =>
          tagged.load()
          CHECKCAST(tag.jvmName)
          storeWithName(nextLocal, tag.toTpe) { value =>
            NEW(c.record.jvmName)
            DUP()
            for ((element, i) <- c.elements.zipWithIndex) {
              value.load()
              GETFIELD(tag.IndexField(i))
              element.emit(loc, nextLocal + value.tpe.stackSlots)
              castToDeclaredType(element)
            }
            INVOKESPECIAL(c.record.Constructor)
          }
      }

    /**
      * Narrows a converted element back to its declared type, when it needs narrowing.
      *
      * A reference-typed element is read off a field the internal `Tag` erases to `Object` (see
      * `BackendType.toErasedBackendType`), and every plan [[directPlan]] builds converts
      * nothing -- unlike a tuple's component, a case record's component keeps its own concrete
      * type rather than `Object`, so what a tuple never needed, this does.
      */
    private def castToDeclaredType(element: ExportPlan)(implicit mv: MethodVisitor): Unit = element.javaType match {
      case BackendType.Reference(ref) if ref.jvmName != JvmName.Object => CHECKCAST(ref.jvmName)
      case _ => ()
    }

    override def generatedClasses: List[BackendObjType.ExportClass] =
      enum0 :: cases.flatMap(c => c.record :: c.elements.flatMap(_.generatedClasses))
  }

  object AsSealedEnum {
    /**
      * One case of a sealed-enum conversion.
      *
      * `tag` is `None` for a nullary case -- there is nothing to read, and its record has no
      * components -- and the internal `Tag` class to read `elements` off of otherwise.
      */
    case class Case(tag: Option[BackendObjType.Tag], elements: List[ExportPlan], record: BackendObjType.ExportCaseRecord)
  }

  /**
    * Returns how `declared` is converted, or `None` if it needs no conversion.
    *
    * `declared` is the type as the programmer wrote it. The erased type cannot be used: it has
    * already specialized `Option[String]` into an `Option$…` that no longer says what it holds.
    */
  def of(declared: SimpleType, erased: SimpleType)(implicit root: JvmAst.Root): Option[ExportPlan] =
    declared match {
      case SimpleType.Enum(sym, List(element)) if isOption(sym) =>
        val (noneOrdinal, someTag) = optionTags(erased)
        Some(AsOptional(elementPlan(element, someTag.elms.head), noneOrdinal, someTag))
      case SimpleType.Enum(sym, List(element)) if isList(sym) =>
        val erasedElement = BackendType.toErasedBackendType(element)
        val view = BackendObjType.ListView(viewElementPlan(erasedElement))
        Some(AsList(elementPlan(element, erasedElement), view))
      case SimpleType.Enum(sym, List(element)) if isStdEnum(sym, "Set") =>
        val erasedKey = BackendType.toErasedBackendType(element)
        val view = BackendObjType.TreeSetView(viewElementPlan(erasedKey), None)
        Some(AsSet(elementPlan(element, erasedKey), treeTag(erased, "Set"), view))
      case SimpleType.Enum(sym, List(k, v)) if isStdEnum(sym, "Map") =>
        val erasedKey = BackendType.toErasedBackendType(k)
        val erasedValue = BackendType.toErasedBackendType(v)
        val view = BackendObjType.MapView(viewElementPlan(erasedKey), viewElementPlan(erasedValue))
        Some(AsMap(elementPlan(k, erasedKey), elementPlan(v, erasedValue), treeTag(erased, "Map"), view))
      case SimpleType.Enum(sym, List(element)) if isStdEnum(sym, "Chain") =>
        // `sym` (from `declared`) is not read here, the same way `treeTag` does not read one: a
        // generic stdlib enum is specialized per erasure class, so only `erased`'s own symbol is
        // guaranteed to be a live key in `root.enums` -- `declared`'s may be the unspecialized
        // symbol that was never retained on its own once monomorphization ran.
        val erasedElement = BackendType.toErasedBackendType(element)
        val chainSym = enumSymOf(erased, "Chain")
        val emptyOrdinal = caseSymOf(chainSym, "Empty").ordinal
        val oneOrdinal = caseSymOf(chainSym, "One").ordinal
        val view = BackendObjType.ExportedChainView(viewElementPlan(erasedElement), emptyOrdinal, oneOrdinal, tagOf(chainSym, "One"), tagOf(chainSym, "Chain"))
        Some(AsChain(elementPlan(element, erasedElement), view))
      // Unguarded: by the time `SimpleType` exists, `Simplifier` has already erased `Vector[t]` and
      // `Array[t, r]` to the identical `SimpleType.Array(t)`, so this solver cannot itself tell them
      // apart. Soundness rests entirely on `EntryPoints`'s gate, which can (it still sees the
      // pre-erasure `Type`, where `Vector` and `Array` are distinct constructors) and does: no def
      // with a declared `Array[t, r]` return type reaches this function at all. See `Test.
      // ExportVector` in `TestEntryPoints` for the regression this invariant depends on staying true.
      case SimpleType.Array(elm) =>
        val erasedElement = BackendType.toErasedBackendType(elm)
        val arrayType = BackendType.Array(BackendType.toBackendType(elm))
        val view = BackendObjType.VectorView(viewElementPlan(erasedElement))
        Some(AsVector(elementPlan(elm, erasedElement), arrayType, view))
      case SimpleType.Enum(sym, Nil) if isNullaryEnum(sym) =>
        Some(AsEnum(BackendObjType.ExportEnum(sym), constantNames(sym)))
      case SimpleType.Enum(sym, Nil) if isSealedEnum(sym) =>
        Some(AsSealedEnum(BackendObjType.ExportSealedEnum(sym), sealedEnumCases(sym)))
      case SimpleType.Tuple(elms) =>
        // The erased element types are both what the backend named its tuple class after and what
        // decides whether each element needs boxing, so one list serves both.
        val erasedElms = elms.map(BackendType.toErasedBackendType)
        val plans = elms.zip(erasedElms).map { case (elm, erasedElm) => elementPlan(elm, erasedElm) }
        Some(AsTuple(plans, BackendObjType.Tuple(erasedElms), BackendObjType.ExportTuple(elms.length)))
      case SimpleType.RecordExtend(_, _, _) | SimpleType.RecordEmpty =>
        // Sorted by label: two defs returning `{ age = 1, name = "x" }` and `{ name = "x", age = 1
        // }` are the same Flix row type and must share one generated class, in one field order --
        // the order this list is built in is also the order `AsRecord.emit` populates the
        // constructor with, so sorting here is what keeps every call site agreeing with it.
        val fields = recordFields(declared).sortBy(_._1).map { case (label, tpe) =>
          AsRecord.Field(label, BackendType.toErasedBackendType(tpe), directPlan(tpe))
        }
        val record = BackendObjType.ExportRecord(fields.map(f => (f.label, f.plan)))
        Some(AsRecord(fields, record))
      case SimpleType.Native(clazz, targs) if targs.nonEmpty =>
        // Every argument must be describable, and `EntryPoints.isExportableType` has already
        // ensured it: a Flix type argument such as `ArrayList[SomeEnum]` is rejected there,
        // because it would otherwise hand a caller a generated class name. Failing here is
        // therefore a compiler bug -- the gate and this solver disagreeing about what may cross,
        // which is the divergence J16 exists to make loud rather than silent.
        val plans = traverse(targs)(typeArgumentPlan).getOrElse(
          throw InternalCompilerException(s"Exported Java type has an undescribable argument: '$declared'", SourceLocation.Unknown))
        Some(GenericNative(JvmName.ofClass(clazz), plans))
      case _ => None
    }

  /**
    * Returns how a parameter of type `declared` is described, or `None` if it needs no description.
    *
    * Only descriptions, never conversions. A shim converts its *result* and passes its parameters
    * straight into the closure it builds, so a plan that emitted instructions here would declare a
    * signature the bytecode does not honour. [[GenericNative]] is the only case that qualifies:
    * it says what the type arguments are and emits nothing.
    *
    * This is why an `Option` or a `List` parameter is not handled here rather than merely
    * unimplemented -- both need a conversion, and `EntryPoints` rejects them in this position.
    */
  def ofParameter(declared: SimpleType): Option[ExportPlan] = declared match {
    case SimpleType.Native(clazz, targs) if targs.nonEmpty =>
      traverse(targs)(typeArgumentPlan).map(GenericNative(JvmName.ofClass(clazz), _))
    case _ => None
  }

  /**
    * Returns how a value of type `declared` is described as a type argument, if it can be.
    *
    * Unlike [[elementPlan]] there is no erased type to consult, because nothing is converted: this
    * only ever produces the text of a signature.
    */
  private def typeArgumentPlan(declared: SimpleType): Option[ExportPlan] =
    boxedPlan(BackendType.toErasedBackendType(declared)).orElse(nonPrimitiveTypeArgumentPlan(declared))

  /** The part of [[typeArgumentPlan]] that applies once boxing has been ruled out. */
  private def nonPrimitiveTypeArgumentPlan(declared: SimpleType): Option[ExportPlan] = declared match {
    case SimpleType.String => Some(Identity(BackendType.String))
    case SimpleType.BigInt => Some(Identity(BackendObjType.Native(JvmName.BigInteger).toTpe))
    case SimpleType.BigDecimal => Some(Identity(BackendObjType.Native(JvmName.BigDecimal).toTpe))
    case SimpleType.Regex => Some(Identity(BackendObjType.Native(JvmName.Regex).toTpe))
    // A type variable reaches the boundary as `Object`, which is also what it erases to.
    case SimpleType.AnyType => Some(Identity(BackendType.Object))
    case SimpleType.Native(clazz, Nil) => Some(Identity(BackendObjType.Native(JvmName.ofClass(clazz)).toTpe))
    case SimpleType.Native(clazz, inner) =>
      traverse(inner)(typeArgumentPlan).map(GenericNative(JvmName.ofClass(clazz), _))
    case _ => None
  }

  /** Returns the plans for every element of `xs`, or `None` if any of them has none. */
  private def traverse(xs: List[SimpleType])(f: SimpleType => Option[ExportPlan]): Option[List[ExportPlan]] =
    xs.foldRight(Option(List.empty[ExportPlan])) {
      case (x, acc) => for (plans <- acc; plan <- f(x)) yield plan :: plans
    }

  /**
    * Returns `true` if every case of `sym` carries no data.
    *
    * Such an enum has a constant to be on the Java side; one whose cases carry values does not,
    * and is rejected by `EntryPoints` until the sealed-interface shape exists. Asked of the
    * backend's own view of the enum, so it cannot disagree with what the conversion will read.
    */
  private def isNullaryEnum(sym: Symbol.EnumSym)(implicit root: JvmAst.Root): Boolean =
    root.enums.get(sym).exists(e => e.cases.nonEmpty && e.cases.values.forall(_.tpes.isEmpty))

  /** Returns the names of the cases of `sym`, in ordinal order. */
  private def constantNames(sym: Symbol.EnumSym)(implicit root: JvmAst.Root): List[String] =
    root.enums(sym).cases.keys.toList.sortBy(_.ordinal).map(_.name)

  /**
    * Returns `true` if `sym` has at least one case.
    *
    * The only condition this solver's own view of the enum needs to re-check for the
    * sealed-interface conversion: every other condition (no type parameters, namespace depth, and
    * each case's own elements) is `EntryPoints.isExportableEnum`'s, and this arm in [[of]] runs
    * only for a def the front end has already accepted (J17).
    */
  private def isSealedEnum(sym: Symbol.EnumSym)(implicit root: JvmAst.Root): Boolean =
    root.enums.get(sym).exists(_.cases.nonEmpty)

  /** Returns the per-case description of `sym`'s cases, in ordinal order, for [[AsSealedEnum]]. */
  private def sealedEnumCases(sym: Symbol.EnumSym)(implicit root: JvmAst.Root): List[AsSealedEnum.Case] =
    root.enums(sym).cases.values.toList.sortBy(_.sym.ordinal).map { c =>
      val elements = c.tpes.map(directPlan)
      val tag = if (c.tpes.isEmpty) None else Some(BackendObjType.Tag(c.tpes.map(BackendType.toErasedBackendType)))
      AsSealedEnum.Case(tag, elements, BackendObjType.ExportCaseRecord(c.sym, elements))
    }

  /**
    * Returns the labels and field types of `tpe`, in declaration order.
    *
    * `tpe` is closed by construction: `EntryPoints.checkExportedTypeVariables` rejects any def
    * whose boundary types have a leftover row variable before `ExportPlan.of` ever runs, so this
    * always reaches `RecordEmpty` rather than an open row.
    */
  private def recordFields(tpe: SimpleType): List[(String, SimpleType)] = tpe match {
    case SimpleType.RecordExtend(label, value, rest) => (label, value) :: recordFields(rest)
    case SimpleType.RecordEmpty => Nil
    case other => throw InternalCompilerException(s"Exported record has an unexpected row: '$other'", SourceLocation.Unknown)
  }

  /**
    * Returns the plan for a value that needs no conversion, only possibly a signature.
    *
    * Nothing here is converted, in the sense [[ofParameter]] already uses for a shim's own
    * parameters: the value is copied straight out of wherever it is erased to `Object` -- a data-
    * carrying case's internal `Tag` field, or a record's internal `RecordExtend` field -- and the
    * caller is what casts the copy back to its declared type afterward (`castIfNotPrim`). Only a
    * generic Java type has something to say beyond its own identity, exactly as at a parameter --
    * which is also why this never boxes a primitive: unlike a container's element, neither a case
    * record's component nor a structural record's field is shared across element-type
    * instantiations, so nothing forces either into a reference slot.
    */
  private def directPlan(declared: SimpleType)(implicit root: JvmAst.Root): ExportPlan = declared match {
    case SimpleType.Native(clazz, targs) if targs.nonEmpty =>
      val plans = traverse(targs)(typeArgumentPlan).getOrElse(
        throw InternalCompilerException(s"Exported value has an undescribable argument: '$declared'", SourceLocation.Unknown))
      GenericNative(JvmName.ofClass(clazz), plans)
    case _ => Identity(BackendType.toBackendType(declared))
  }

  /** Returns `true` if `sym` is the standard library's `Option`. */
  private def isOption(sym: Symbol.EnumSym): Boolean = isStdEnum(sym, "Option")

  /** Returns `true` if `sym` is the standard library's `List`. */
  private def isList(sym: Symbol.EnumSym): Boolean = isStdEnum(sym, "List")

  /**
    * Returns `true` if `sym` is the standard library's enum named `name`.
    *
    * By symbol rather than by name alone, so a user-defined `Foo.Set` stays an ordinary enum. This
    * has to agree with `EntryPoints`, which decides the same question on the front-end type.
    */
  private def isStdEnum(sym: Symbol.EnumSym, name: String): Boolean =
    sym.namespace.isEmpty && sym.text == name

  /**
    * Returns the tag class of the single case of `Set` or `Map`, which holds the red-black tree.
    *
    * Only this much is read from the AST. The tree's own enum cannot be: the eraser rewrites this
    * case's field type to `Object`, so nothing here still says that what the collection holds is a
    * tree. The shape the view walks is stated in [[BackendObjType.TreeIterator]] instead.
    */
  private def treeTag(erased: SimpleType, name: String)(implicit root: JvmAst.Root): BackendObjType.Tag =
    tagOf(enumSymOf(erased, name), name)

  /** Returns the enum symbol of `tpe`, which describes a `what` that reaches the boundary. */
  private def enumSymOf(tpe: SimpleType, what: String): Symbol.EnumSym = tpe match {
    case SimpleType.Enum(sym, _) => sym
    case other => throw InternalCompilerException(s"Exported $what is not an enum: '$other'", SourceLocation.Unknown)
  }

  /** Returns the symbol of the case of `sym` named `name`. */
  private def caseSymOf(sym: Symbol.EnumSym, name: String)(implicit root: JvmAst.Root): Symbol.CaseSym =
    root.enums(sym).cases.keys.find(_.name == name).getOrElse(
      throw InternalCompilerException(s"Exported '${sym.text}' has no $name case: '$sym'", SourceLocation.Unknown))

  /** Returns the tag class of the case of `sym` named `name`, which must carry at least one field. */
  private def tagOf(sym: Symbol.EnumSym, name: String)(implicit root: JvmAst.Root): BackendObjType.Tag =
    BackendObjType.Tag(root.enums(sym).cases(caseSymOf(sym, name)).tpes.map(BackendType.toErasedBackendType))

  /**
    * Returns the plan for a value held inside a converted container.
    *
    * `erased` is the type the field actually has, which is what decides whether boxing is needed;
    * `declared` is only consulted once nested conversions exist.
    */
  private def elementPlan(declared: SimpleType, erased: BackendType)(implicit root: JvmAst.Root): ExportPlan =
    // Where there is no boxing the field is erased to `Object`, so the declared type is what a
    // caller should see.
    boxedPlan(erased).getOrElse(Identity(BackendType.toBackendType(declared)))

  /** The wrapper each primitive is boxed into. */
  private val Wrappers: Map[BackendType, JvmName] = Map(
    BackendType.Bool -> JvmName.Boolean,
    BackendType.Char -> JvmName.Character,
    BackendType.Int8 -> JvmName.Byte,
    BackendType.Int16 -> JvmName.Short,
    BackendType.Int32 -> JvmName.Integer,
    BackendType.Int64 -> JvmName.Long,
    BackendType.Float32 -> JvmName.Float,
    BackendType.Float64 -> JvmName.Double,
  )

  /** Returns the plan that boxes `erased`, or `None` if it is already a reference. */
  private def boxedPlan(erased: BackendType): Option[Boxed] =
    Wrappers.get(erased).map(Boxed(erased, _))

  /** Returns the ordinal of `None` and the tag class of `Some` for the specialized `Option`. */
  private def optionTags(erased: SimpleType)(implicit root: JvmAst.Root): (Int, BackendObjType.Tag) = {
    val sym = enumSymOf(erased, "Option")
    (caseSymOf(sym, "None").ordinal, tagOf(sym, "Some"))
  }

  /**
    * Returns the plan a generated view emits for each of its elements.
    *
    * Keyed on the erased element rather than the declared one, so that every `Set` whose elements
    * are references shares a single view class. What a caller is told the elements are is the
    * *signature*, which the plan tree carries separately.
    */
  private def viewElementPlan(erased: BackendType): ExportPlan =
    boxedPlan(erased).getOrElse(Identity(BackendType.Object))

  /**
    * Returns how the result of `defn` is converted for Java, if it needs converting.
    *
    * The one place a def's plan is built. The shim reads it for its descriptor, its signature and
    * its instructions, and `CodeGen` reads it for the classes it needs generated -- so a view that
    * a shim returns is always a view that was emitted.
    */
  def ofDef(defn: JvmAst.Def)(implicit root: JvmAst.Root): Option[ExportPlan] =
    if (!defn.ann.isExport) None
    else defn.exportedReturnType.flatMap(of(_, defn.unboxedType.tpe))

  /** Returns every class the export plans of `root` need generated. */
  def viewClassesOf(root: JvmAst.Root): List[BackendObjType.ExportClass] = {
    implicit val r: JvmAst.Root = root
    root.defs.values.toList.flatMap(defn => ofDef(defn).toList.flatMap(_.generatedClasses)).distinct
  }
}
