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

/**
  * The Java face of a value that crosses the export boundary: what a caller compiles against.
  *
  * This is the half of [[ExportPlan]] that depends on nothing but the type. A plan additionally
  * knows how to *produce* the value, and that needs state from the compilation in progress -- the
  * ordinal of `None`, the tag class of `Cons` -- because those are assigned per compilation. The
  * declaration does not: a Flix `Option[String]` is `java.util.Optional<java.lang.String>` in every
  * compilation there has ever been.
  *
  * Separating them is what lets something other than the backend describe the boundary. A build
  * tool generating Java stubs for a facade needs exactly these two projections and cannot obtain a
  * [[ExportPlan]] at all, because at the point it runs there is no compiled enum to read tags from.
  * Without this split it would have to reimplement the mapping, and a second implementation of a
  * boundary is a second answer to what the boundary is.
  *
  * There are three shapes here where [[ExportPlan]] has five cases. That is not a simplification:
  * `Option`, `List` and an applied Java class differ entirely in what they *do* and not at all in
  * what they are *called*, so they were already the same shape wearing three names.
  */
sealed trait ExportSignature {

  /** The Java type, as it appears in a method descriptor. */
  def javaType: BackendType

  /**
    * The descriptor this contributes as a type argument.
    *
    * Type arguments are references, so a primitive appears boxed: the element of an `Option[Int32]`
    * is `Ljava/lang/Integer;`, not `I`.
    */
  def typeArgument: String
}

object ExportSignature {

  /** A type that is already exactly what a Java caller sees, with no arguments to report. */
  case class Exact(javaType: BackendType) extends ExportSignature {
    def typeArgument: String = javaType.toDescriptor
  }

  /** A primitive appearing where a reference is required, and therefore named by its box. */
  case class Boxed(primitive: BackendType, boxed: JvmName) extends ExportSignature {
    def javaType: BackendType = boxed.toTpe

    def typeArgument: String = boxed.toDescriptor
  }

  /**
    * A Java class presented with type arguments.
    *
    * The descriptor cannot carry the arguments -- it erases them -- so they survive only here and
    * in the generic signature this produces.
    */
  case class Applied(clazz: JvmName, targs: List[ExportSignature]) extends ExportSignature {
    def javaType: BackendType = BackendObjType.Native(clazz).toTpe

    def typeArgument: String =
      if (targs.isEmpty)
        // An unapplied class has no argument list, and `L…<>;` is not a signature. No caller
        // builds this today, since a bare class is an `Exact`, but the empty case has a correct
        // answer and it costs nothing to give it rather than to emit something unparseable.
        javaType.toDescriptor
      else
        s"L${clazz.toInternalName}<${targs.map(_.typeArgument).mkString}>;"
  }
}
