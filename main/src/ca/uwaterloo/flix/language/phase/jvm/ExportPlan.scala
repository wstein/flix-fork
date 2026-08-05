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
    */
  def emit(loc: SourceLocation)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit
}

object ExportPlan {

  /** A value that already has the Java type it is declared with. */
  case class Identity(javaType: BackendType) extends ExportPlan {
    def typeArgument: String = javaType.toDescriptor

    def emit(loc: SourceLocation)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = ()
  }

  /** A primitive that must be boxed, because the value it is being placed into holds references. */
  case class Boxed(primitive: BackendType, boxed: JvmName) extends ExportPlan {
    def javaType: BackendType = boxed.toTpe

    def typeArgument: String = boxed.toDescriptor

    def emit(loc: SourceLocation)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit =
      INVOKESTATIC(boxed, "valueOf", mkDescriptor(primitive)(boxed.toTpe))
  }

  /**
    * A Flix `Option` presented as a `java.util.Optional`.
    *
    * The ordinal of `None` and the tag class of `Some` are resolved when the plan is built rather
    * than assumed, because they are assigned per compilation.
    */
  case class AsOptional(element: ExportPlan, noneOrdinal: Int, someTag: BackendObjType.Tag) extends ExportPlan {
    def javaType: BackendType = BackendObjType.Native(JvmName.Optional).toTpe

    def typeArgument: String = s"L${JvmName.Optional.toInternalName}<${element.typeArgument}>;"

    def emit(loc: SourceLocation)(implicit root: JvmAst.Root, mv: MethodVisitor): Unit = {
      DUP()
      GETFIELD(BackendObjType.Tagged.OrdinalField)
      pushInt(noneOrdinal)
      ifConditionElse(Condition.ICMPEQ) {
        POP()
        INVOKESTATIC(JvmName.Optional, "empty", mkDescriptor()(javaType))
      } {
        CHECKCAST(someTag.jvmName)
        GETFIELD(someTag.IndexField(0))
        element.emit(loc)
        // `ofNullable` rather than `of`: a `Some` may hold a Java value that is itself null, and
        // `of` would turn that into a NullPointerException at the boundary.
        INVOKESTATIC(JvmName.Optional, "ofNullable", mkDescriptor(BackendType.Object)(javaType))
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
      case _ => None
    }

  /** Returns `true` if `sym` is the standard library's `Option`. */
  private def isOption(sym: ca.uwaterloo.flix.language.ast.Symbol.EnumSym): Boolean =
    sym.namespace.isEmpty && sym.text == "Option"

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
