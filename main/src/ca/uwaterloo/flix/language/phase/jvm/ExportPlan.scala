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

import ca.uwaterloo.flix.language.ast.{JvmAst, SimpleType, SourceLocation}
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
  private def typeArgumentPlan(declared: SimpleType): Option[ExportPlan] = declared match {
    case SimpleType.Bool => Some(Boxed(BackendType.Bool, JvmName.Boolean))
    case SimpleType.Char => Some(Boxed(BackendType.Char, JvmName.Character))
    case SimpleType.Int8 => Some(Boxed(BackendType.Int8, JvmName.Byte))
    case SimpleType.Int16 => Some(Boxed(BackendType.Int16, JvmName.Short))
    case SimpleType.Int32 => Some(Boxed(BackendType.Int32, JvmName.Integer))
    case SimpleType.Int64 => Some(Boxed(BackendType.Int64, JvmName.Long))
    case SimpleType.Float32 => Some(Boxed(BackendType.Float32, JvmName.Float))
    case SimpleType.Float64 => Some(Boxed(BackendType.Float64, JvmName.Double))
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
  private def isOption(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Option"

  /** Returns `true` if `sym` is the standard library's `List`. */
  private def isList(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "List"

  /** Returns the ordinal of `Nil` and the tag class of `Cons` for the specialized `List`. */
  private def listTags(erased: SimpleType)(implicit root: JvmAst.Root): (Int, BackendObjType.Tag) = {
    val sym = erased match {
      case SimpleType.Enum(s, _) => s
      case other => throw InternalCompilerException(s"Exported List is not an enum: '$other'", SourceLocation.Unknown)
    }
    val cases = root.enums(sym).cases
    def caseNamed(name: String) = cases.keys.find(_.name == name).getOrElse(
      throw InternalCompilerException(s"Exported List has no $name case: '$sym'", SourceLocation.Unknown))
    val cons = caseNamed("Cons")
    (caseNamed("Nil").ordinal, BackendObjType.Tag(cases(cons).tpes.map(BackendType.toErasedBackendType)))
  }

  /**
    * Returns the plan for a value held inside a converted container.
    *
    * `erased` is the type the field actually has, which is what decides whether boxing is needed;
    * `declared` is only consulted once nested conversions exist.
    */
  private def elementPlan(declared: SimpleType, erased: BackendType)(implicit root: JvmAst.Root): ExportPlan =
    erased match {
      case BackendType.Bool => Boxed(erased, JvmName.Boolean)
      case BackendType.Char => Boxed(erased, JvmName.Character)
      case BackendType.Int8 => Boxed(erased, JvmName.Byte)
      case BackendType.Int16 => Boxed(erased, JvmName.Short)
      case BackendType.Int32 => Boxed(erased, JvmName.Integer)
      case BackendType.Int64 => Boxed(erased, JvmName.Long)
      case BackendType.Float32 => Boxed(erased, JvmName.Float)
      case BackendType.Float64 => Boxed(erased, JvmName.Double)
      // The field is erased to `Object`, so the declared type is what a caller should see.
      case _ => Identity(BackendType.toBackendType(declared))
    }

  /** Returns the ordinal of `None` and the tag class of `Some` for the specialized `Option`. */
  private def optionTags(erased: SimpleType)(implicit root: JvmAst.Root): (Int, BackendObjType.Tag) = {
    val sym = erased match {
      case SimpleType.Enum(s, _) => s
      case other => throw InternalCompilerException(s"Exported Option is not an enum: '$other'", SourceLocation.Unknown)
    }
    val cases = root.enums(sym).cases
    def caseNamed(name: String) = cases.keys.find(_.name == name).getOrElse(
      throw InternalCompilerException(s"Exported Option has no $name case: '$sym'", SourceLocation.Unknown))
    val some = caseNamed("Some")
    (caseNamed("None").ordinal, BackendObjType.Tag(cases(some).tpes.map(BackendType.toErasedBackendType)))
  }
}
