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

package ca.uwaterloo.flix.language.phase.jvm.classes

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.JvmAst
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.IsFinal
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Visibility.IsPublic
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.{ConstructorMethod, InstanceField, StaticMethod}
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.MethodTypeDescs.mkDescriptor
import ca.uwaterloo.flix.language.phase.jvm.{ClassConstants, ClassMaker, ExportPlan, GenFunAndClosureClasses, JvmNames, Mangle, TypeDescs}
import org.objectweb.asm.MethodVisitor

import java.lang.constant.ClassDesc

/**
  * The namespace class of a Flix module, which holds the shim methods of the module's
  * entry points and tests.
  */
object GenNamespace {

  def desc(ns: List[String]): ClassDesc =
    Mangle.namespaceFacadeDesc(ns)

  def genByteCode(ns: List[String], defs: List[JvmAst.Def])(implicit root: JvmAst.Root, flix: Flix): Array[Byte] = {
    val cm = ClassMaker.mkClass(desc(ns), IsFinal)

    cm.mkConstructor(Constructor(ns), IsPublic, nullarySuperConstructor(ClassConstants.Object.Constructor)(_))

    for (defn <- defs) {
      cm.mkStaticMethod(ShimMethod(ns, defn), IsPublic, IsFinal, shimIns(defn)(_, root, flix), methodSignature(defn))
    }

    cm.closeClassMaker()
  }

  private def Constructor(ns: List[String]): ConstructorMethod = ConstructorMethod(desc(ns), Nil)

  def ShimMethod(ns: List[String], defn: JvmAst.Def)(implicit flix: Flix): StaticMethod = {
    val erasedArgs = defn.fparams.map(_.tpe).map(boundaryType(defn.ann.isExport, _))
    val erasedResult =
      if (defn.ann.isExport) defn.exportedReturnType.flatMap(ExportPlan.signatureOf).map(_.javaType).getOrElse(TypeDescs.toErasedClassDesc(defn.unboxedType.tpe))
      else TypeDescs.toErasedClassDesc(defn.unboxedType.tpe)
    // Exported names are checked in Safety, so no mangling is needed.
    val defnName = JvmNames.defnName(defn.sym)
    val name = if (defn.ann.isExport) defn.sym.text else "m_" + Mangle.mangle(defnName)
    StaticMethod(desc(ns), name, mkDescriptor(erasedArgs *)(erasedResult))
  }

  private def shimIns(defn: JvmAst.Def)(implicit mv: MethodVisitor, root: JvmAst.Root, flix: Flix): Unit = {
    val defnDesc = GenFunAndClosureClasses.defnDesc(defn.sym)
    val facadeParamTypes = defn.fparams.map(fp => boundaryType(defn.ann.isExport, fp.tpe))
    val fieldTypes = defn.fparams.map(fp => TypeDescs.toErasedClassDesc(fp.tpe))
    withNames(0, facadeParamTypes) {
      case (nextLocal, args) =>
        val resultPlan = ExportPlan.ofDef(defn)
        val flixResult = resultPlan.map(_.flixType).getOrElse(TypeDescs.toErasedClassDesc(defn.unboxedType.tpe))
        val javaResult = resultPlan.map(_.javaType).getOrElse(flixResult)
        NEW(defnDesc)
        DUP()
        INVOKESPECIAL(ConstructorMethod(defnDesc, Nil))
        for ((arg, index) <- args.zipWithIndex) {
          DUP()
          arg.load()
          PUTFIELD(InstanceField(defnDesc, s"arg$index", fieldTypes(index)))
        }
        GenResult.unwindSuspensionFreeThunkToType(flixResult, s"in shim method of ${defn.sym}", defn.loc)
        resultPlan.foreach(_.emit(nextLocal))
        xReturn(javaResult)
    }
  }

  /** Returns the caller-facing type for an export and the historical erased type otherwise. */
  private def boundaryType(isExport: Boolean, tpe: ca.uwaterloo.flix.language.ast.SimpleType): ClassDesc =
    if (isExport) ExportPlan.exact(tpe).map(_.javaType).getOrElse(TypeDescs.toErasedClassDesc(tpe))
    else TypeDescs.toErasedClassDesc(tpe)

  /** Returns the generic method signature when the exported result carries type arguments. */
  private def methodSignature(defn: JvmAst.Def): Option[String] = {
    if (!defn.ann.isExport) None
    else defn.exportedReturnType.flatMap(ExportPlan.signatureOf).flatMap { result =>
      if (result.typeArgument == result.javaType.descriptorString()) None
      else {
        val params = defn.fparams.map(fp => boundaryType(isExport = true, fp.tpe).descriptorString()).mkString
        Some(s"($params)${result.typeArgument}")
      }
    }
  }

}
