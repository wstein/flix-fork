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
import ca.uwaterloo.flix.language.ast.{JvmAst, SimpleType, SourceLocation}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.IsFinal
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Visibility.IsPublic
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.{ConstructorMethod, InstanceField, StaticMethod}
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.MethodTypeDescs.mkDescriptor
import ca.uwaterloo.flix.language.phase.jvm.{ArgumentPlan, ClassConstants, ClassMaker, ExportPlan, ExportSignature, GenFunAndClosureClasses, JvmNames, Mangle, TypeDescs}
import ca.uwaterloo.flix.util.InternalCompilerException
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
    mkShims(cm, ns, defs)
    cm.closeClassMaker()
  }

  /**
    * Adds the shim methods of `defs` to `cm`, which must be making the class [[desc]]`(ns)`.
    *
    * `GenExportedEnum` calls this too: an exported enum's Java class or interface is named like its
    * companion module's namespace class, so it is the type that carries that module's shims.
    */
  def mkShims(cm: ClassMaker, ns: List[String], defs: List[JvmAst.Def])(implicit root: JvmAst.Root, flix: Flix): Unit =
    for (defn <- defs) {
      cm match {
        case c: ClassMaker.InstanceClassMaker =>
          c.mkStaticMethod(ShimMethod(ns, defn), IsPublic, IsFinal, shimIns(defn)(_, root, flix), methodSignature(defn))
        // A sealed interface generated for a data-carrying enum.
        case i: ClassMaker.InterfaceMaker =>
          i.mkStaticMethod(ShimMethod(ns, defn), shimIns(defn)(_, root, flix), methodSignature(defn))
        case _: ClassMaker.AbstractClassMaker =>
          throw InternalCompilerException(s"Unexpected abstract namespace class for '$ns'", SourceLocation.Unknown)
      }
    }

  private def Constructor(ns: List[String]): ConstructorMethod = ConstructorMethod(desc(ns), Nil)

  /** The formal parameters as a Java caller sees them: a nullary export's lone `Unit` parameter is dropped. */
  private def callerParams(defn: JvmAst.Def): List[JvmAst.OffsetFormalParam] =
    if (!defn.ann.isExport) defn.fparams
    else defn.fparams match {
      case List(fp) if fp.tpe == SimpleType.Unit => Nil
      case fps => fps
    }

  /**
    * The signatures of the parameters a Java caller passes: of each declared type for an export,
    * which may be converted, and of the historical erased type otherwise.
    */
  private def callerSignatures(defn: JvmAst.Def): List[ExportSignature] = {
    val declared = defn.exportedParamTypes.filter(_ => defn.ann.isExport).getOrElse(defn.fparams.map(_.tpe))
    val erased = defn.fparams.map(fp => ExportSignature.Exact(TypeDescs.toErasedClassDesc(fp.tpe)))
    val all = if (!defn.ann.isExport) erased else declared.zip(erased).map {
      case (tpe, fallback) => ExportPlan.signatureOf(tpe).getOrElse(fallback)
    }
    all.take(callerParams(defn).length)
  }

  def ShimMethod(ns: List[String], defn: JvmAst.Def)(implicit flix: Flix): StaticMethod = {
    val erasedArgs = callerSignatures(defn).map(_.javaType)
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
    val params = callerParams(defn)
    val facadeParamTypes = callerSignatures(defn).map(_.javaType)
    val fieldTypes = defn.fparams.map(fp => TypeDescs.toErasedClassDesc(fp.tpe))
    // Present only for an export; each converts one Java argument to the Flix value it stands for.
    val argumentPlans = ArgumentPlan.ofDef(defn)
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
          argumentPlans.foreach(plans => plans(index).emit(nextLocal))
          PUTFIELD(InstanceField(defnDesc, s"arg$index", fieldTypes(index)))
        }
        if (params.isEmpty && defn.fparams.nonEmpty) {
          // A dropped nullary `Unit` parameter: the shim supplies the singleton itself.
          DUP()
          GETSTATIC(GenUnit.SingletonField)
          PUTFIELD(InstanceField(defnDesc, "arg0", fieldTypes.head))
        }
        GenResult.unwindSuspensionFreeThunkToType(flixResult, s"in shim method of ${defn.sym}", defn.loc)
        resultPlan.foreach(_.emit(nextLocal))
        xReturn(javaResult)
    }
  }

  /** Returns the generic method signature when an exported result or parameter carries type arguments. */
  private def methodSignature(defn: JvmAst.Def): Option[String] = {
    if (!defn.ann.isExport) None
    else defn.exportedReturnType.flatMap(ExportPlan.signatureOf).flatMap { result =>
      val params = callerSignatures(defn)
      val needsSignature = result.typeArgument != result.javaType.descriptorString() || params.exists(p => p.typeArgument != p.javaType.descriptorString())
      if (!needsSignature) None
      else {
        Some(s"(${params.map(_.typeArgument).mkString})${result.typeArgument}")
      }
    }
  }

}
