/*
 * Copyright 2021 Jonathan Lindegaard Starup
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.jvm.classes

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{JvmAst, SourceLocation}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.IsFinal
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Visibility.IsPublic
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.{ConstructorMethod, StaticMethod}
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.{ClassConstants, ClassMaker, GenFunAndClosureClasses, JvmNames, Mangle, MethodTypeDescs}
import ca.uwaterloo.flix.util.InternalCompilerException
import org.objectweb.asm.MethodVisitor

import java.lang.constant.ClassDesc

/**
  * The namespace class of a Flix module, which holds a `static void` shim method for each test
  * in the module. A namespace class is only generated for namespaces that contain tests.
  */
object GenNamespace {

  def desc(ns: List[String]): ClassDesc =
    Mangle.namespaceFacadeDesc(ns)

  def genByteCode(ns: List[String], defs: List[JvmAst.Def])(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
    val cm = ClassMaker.mkClass(desc(ns), IsFinal)

    cm.mkConstructor(Constructor(ns), IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))
    mkShims(cm, ns, defs)
    cm.closeClassMaker()
  }

  /** Adds the shim methods of `defs` to the namespace class. */
  def mkShims(cm: ClassMaker, ns: List[String], defs: List[JvmAst.Def])(implicit root: JvmAst.Root, flix: Flix): Unit =
    for (defn <- defs) {
      cm match {
        case c: ClassMaker.InstanceClassMaker =>
          c.mkStaticMethod(ShimMethod(ns, defn), IsPublic, IsFinal, shimIns(defn)(_, root, flix))
        case _ =>
          throw InternalCompilerException(s"Unexpected namespace class for '$ns'", SourceLocation.Unknown)
      }
    }

  private def Constructor(ns: List[String]): ConstructorMethod = ConstructorMethod(desc(ns), Nil)

  def ShimMethod(ns: List[String], defn: JvmAst.Def)(implicit flix: Flix): StaticMethod = {
    val defnName = JvmNames.defnName(defn.sym)
    val name = "m_" + Mangle.mangle(defnName)
    StaticMethod(desc(ns), name, MethodTypeDescs.NothingToVoid)
  }

  private def shimIns(defn: JvmAst.Def)(implicit mv: MethodVisitor, root: JvmAst.Root, flix: Flix): Unit = {
    GenFunAndClosureClasses.runUnitDef(defn.sym, s"in shim method of ${defn.sym}")
    RETURN()
  }

}
