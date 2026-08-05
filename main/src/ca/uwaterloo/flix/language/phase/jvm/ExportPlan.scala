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

  /** The Java type the conversion produces. */
  def javaType: BackendType

  /**
    * The descriptor this plan contributes as a type argument.
    *
    * Type arguments are references, so a primitive appears boxed: the element of an
    * `Option[Int32]` is `Ljava/lang/Integer;`, not `I`.
    */
  def typeArgument: String

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
  def generatedClasses: List[BackendObjType.ExportView] = Nil
}

object ExportPlan {

  /** A value that already has the Java type it is declared with. */
  case class Identity(javaType: BackendType) extends ExportPlan {
    def flixType: BackendType = javaType

    def typeArgument: String = javaType.toDescriptor

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

    def javaType: BackendType = BackendObjType.Native(clazz).toTpe

    def typeArgument: String = s"L${clazz.toInternalName}<${targs.map(_.typeArgument).mkString}>;"

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = ()

    override def generatedClasses: List[BackendObjType.ExportView] = targs.flatMap(_.generatedClasses)
  }

  /** A primitive that must be boxed, because the value it is being placed into holds references. */
  case class Boxed(primitive: BackendType, boxed: JvmName) extends ExportPlan {
    def flixType: BackendType = primitive

    def javaType: BackendType = boxed.toTpe

    def typeArgument: String = boxed.toDescriptor

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

    def javaType: BackendType = BackendObjType.Native(JvmName.Optional).toTpe

    def typeArgument: String = s"L${JvmName.Optional.toInternalName}<${element.typeArgument}>;"

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

    override def generatedClasses: List[BackendObjType.ExportView] = element.generatedClasses
  }

  /**
    * A Flix `Set` presented as an unmodifiable `java.util.Set`, without copying it.
    *
    * Unlike [[AsList]] this converts nothing here: it unwraps the red-black tree and hands it to a
    * generated view that walks it on demand. `element` describes the element for the *signature*;
    * the conversion the view emits per element lives in the view class, which is keyed on the
    * erased element instead (see [[BackendObjType.SetView]]).
    *
    * The set value is a single-case tag holding the tree, so the tree is one field read away.
    */
  case class AsSet(element: ExportPlan, setTag: BackendObjType.Tag, view: BackendObjType.TreeSetView) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def javaType: BackendType = BackendObjType.Native(JvmName.JavaSet).toTpe

    def typeArgument: String = s"L${JvmName.JavaSet.toInternalName}<${element.typeArgument}>;"

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      emitTreeView(setTag, view.jvmName, view.Constructor, nextLocal)

    override def generatedClasses: List[BackendObjType.ExportView] =
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

    def javaType: BackendType = BackendObjType.Native(JvmName.JavaMap).toTpe

    def typeArgument: String =
      s"L${JvmName.JavaMap.toInternalName}<${key.typeArgument}${value.typeArgument}>;"

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      emitTreeView(mapTag, view.jvmName, view.Constructor, nextLocal)

    override def generatedClasses: List[BackendObjType.ExportView] =
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
    * A Flix `List` presented as an unmodifiable `java.util.List`.
    *
    * The copy is eager. A lazy view over the cons chain would allocate O(1) instead of O(n), but
    * it is a class with a published contract -- mutability, iteration, `size()` -- that has to be
    * settled before it ships rather than after, and for a primitive element it re-converts on
    * every traversal, which is worse than copying once. The copy is the conservative half of that
    * trade and can be replaced without the caller noticing, since what a caller is handed is a
    * `java.util.List` either way.
    *
    * Unmodifiable because a Flix list is immutable and a mutable copy would invite a caller to
    * write to something that looks like the Flix value and is not.
    */
  case class AsList(element: ExportPlan, nilOrdinal: Int, consTag: BackendObjType.Tag) extends ExportPlan {
    def flixType: BackendType = BackendObjType.Tagged.toTpe

    def javaType: BackendType = BackendObjType.Native(JvmName.JavaList).toTpe

    def typeArgument: String = s"L${JvmName.JavaList.toInternalName}<${element.typeArgument}>;"

    def emit(loc: SourceLocation, nextLocal: Int)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      val arrayList = BackendObjType.Native(JvmName.ArrayList).toTpe
      // The chain is walked with a cursor rather than on the stack: keeping both the accumulator
      // and the cursor as stack values needs the operand stack shuffled on every iteration, which
      // is easy to get subtly wrong and impossible to read afterwards.
      withName(nextLocal, BackendObjType.Tagged.toTpe) { cursor =>
        withName(nextLocal + 1, arrayList) { acc =>
          cursor.store()
          NEW(JvmName.ArrayList)
          DUP()
          INVOKESPECIAL(JvmName.ArrayList, JvmName.ConstructorMethod, mkDescriptor()(VoidableType.Void))
          acc.store()
          whileLoop(Condition.ICMPNE) {
            cursor.load()
            GETFIELD(BackendObjType.Tagged.OrdinalField)
            pushInt(nilOrdinal)
          } {
            acc.load()
            cursor.load()
            CHECKCAST(consTag.jvmName)
            GETFIELD(consTag.IndexField(0))
            element.emit(loc, nextLocal + 2)
            INVOKEVIRTUAL(JvmName.ArrayList, "add", mkDescriptor(BackendType.Object)(BackendType.Bool))
            POP()
            cursor.load()
            CHECKCAST(consTag.jvmName)
            GETFIELD(consTag.IndexField(1))
            CHECKCAST(BackendObjType.Tagged.jvmName)
            cursor.store()
          }
          acc.load()
          INVOKESTATIC(JvmName.Collections, "unmodifiableList", mkDescriptor(javaType)(javaType))
        }
      }
    }

    override def generatedClasses: List[BackendObjType.ExportView] = element.generatedClasses
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
        val (nilOrdinal, consTag) = listTags(erased)
        Some(AsList(elementPlan(element, consTag.elms.head), nilOrdinal, consTag))
      case SimpleType.Enum(sym, List(element)) if isStdEnum(sym, "Set") =>
        val erasedKey = BackendType.toErasedBackendType(element)
        val view = BackendObjType.TreeSetView(viewElementPlan(erasedKey), None)
        Some(AsSet(elementPlan(element, erasedKey), treeTag(erased, "Set"), view))
      case SimpleType.Enum(sym, List(k, v)) if isStdEnum(sym, "Map") =>
        val erasedKey = BackendType.toErasedBackendType(k)
        val erasedValue = BackendType.toErasedBackendType(v)
        val view = BackendObjType.MapView(viewElementPlan(erasedKey), viewElementPlan(erasedValue))
        Some(AsMap(elementPlan(k, erasedKey), elementPlan(v, erasedValue), treeTag(erased, "Map"), view))
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

  /** Returns the ordinal of `Nil` and the tag class of `Cons` for the specialized `List`. */
  private def listTags(erased: SimpleType)(implicit root: JvmAst.Root): (Int, BackendObjType.Tag) = {
    val sym = enumSymOf(erased, "List")
    (caseSymOf(sym, "Nil").ordinal, tagOf(sym, "Cons"))
  }

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
  def viewClassesOf(root: JvmAst.Root): List[BackendObjType.ExportView] = {
    implicit val r: JvmAst.Root = root
    root.defs.values.toList.flatMap(defn => ofDef(defn).toList.flatMap(_.generatedClasses)).distinct
  }
}
