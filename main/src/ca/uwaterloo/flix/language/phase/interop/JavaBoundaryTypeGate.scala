/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
import ca.uwaterloo.flix.language.phase.unification.Substitution
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}

/** Check declared shapes and component ABIs before checking their generated conversion bodies. */
object JavaBoundaryTypeGate {
  def verify(contract: JavaBoundaryContract.Contract, root: TypedAst.Root)(implicit flix: Flix): Result[Unit, JavaBoundaryContract.Error] = {
    val products = contract.products.zipWithIndex.map { case (p, i) =>
      p -> Type.eraseAliases(root.defs(Symbol.mkDefnSym(s"${JavaBoundaryProducts.module(contract)}.out$i")).spec.fparams.head.tpe)
    }
    val nominals = contract.nominals.zipWithIndex.map { case (n, i) =>
      val owner = JavaBoundaryNominals.helperOwner(contract, i)
      n -> Type.eraseAliases(root.defs(Symbol.mkDefnSym(s"$owner.boundaryPayload")).spec.fparams.head.tpe)
    }
    val targets = products.map(_._2) ++ nominals.map(_._2)
    if (targets.distinct.size != targets.size)
      return Err(JavaBoundaryContract.Error("Declared Java representations have the same checked Flix payload type (including aliases).", contract.loc))
    for {
      _ <- Result.traverse(products) { case (product, tpe) =>
        if (product.tuple) tpe.baseType match {
          case Type.Cst(_: TypeConstructor.Tuple, _) => fields(product.components, tpe.typeArguments, product.loc, root)
          case _ => Err(JavaBoundaryContract.Error("A tuple declaration must target a concrete Flix tuple.", product.loc))
        } else tpe.baseType match {
          case Type.Cst(TypeConstructor.Record, _) =>
            val row = tpe.typeArguments.head
            val labels = recordFields(row)
            if (!closedRecordRow(row))
              Err(JavaBoundaryContract.Error("A record declaration must target a closed Flix record.", product.loc))
            else if (labels.size != product.components.size || labels.map(_._1).toSet != product.components.map(_.name).toSet)
              Err(JavaBoundaryContract.Error("Record components must name every checked Flix label exactly once.", product.loc))
            else fields(product.components, product.components.map(c => labels.toMap.apply(c.name)), product.loc, root)
          case _ => Err(JavaBoundaryContract.Error("A record declaration must target a closed Flix record.", product.loc))
        }
      }
      _ <- Result.traverse(nominals) { case (nominal, tpe) => tpe.baseType match {
        case Type.Cst(TypeConstructor.Enum(sym, _), _) =>
          val enumDecl = root.enums(sym)
          val actual = enumDecl.cases.values.map(c => c.sym.name -> c).toMap
          if (actual.keySet != nominal.variants.map(_.name).toSet)
            Err(JavaBoundaryContract.Error("Declared variants must cover every checked Flix enum case exactly once.", nominal.loc))
          else {
            val subst = Substitution(enumDecl.tparams.map(_.sym).zip(tpe.typeArguments).toMap)
            Result.traverse(nominal.variants)(v => fields(v.components, actual(v.name).tpes.map(subst.apply), nominal.loc, root)).map(_ => ())
          }
        case _ => Err(JavaBoundaryContract.Error("An enum or sealed declaration must target a concrete nominal Flix enum.", nominal.loc))
      }}
    } yield ()
  }

  @scala.annotation.tailrec
  private def closedRecordRow(row: Type): Boolean = Type.eraseAliases(row).baseType match {
    case Type.Cst(TypeConstructor.RecordRowExtend(_), _) => closedRecordRow(Type.eraseAliases(row).typeArguments(1))
    case Type.Cst(TypeConstructor.RecordRowEmpty, _) => true
    case _ => false
  }

  private def recordFields(row: Type): List[(String, Type)] = row.baseType match {
    case Type.Cst(TypeConstructor.RecordRowExtend(label), _) =>
      (label.name -> row.typeArguments.head) :: recordFields(row.typeArguments(1))
    case _ => Nil
  }

  /** Called only after verify succeeds: retain original field types for type-directed validation. */
  def argumentShapes(contract: JavaBoundaryContract.Contract, root: TypedAst.Root): Map[String, String] = {
    val products = contract.products.zipWithIndex.flatMap { case (product, i) =>
      val tpe = Type.eraseAliases(root.defs(Symbol.mkDefnSym(s"${JavaBoundaryProducts.module(contract)}.out$i")).spec.fparams.head.tpe)
      val types = if (product.tuple) tpe.typeArguments else {
        val labels = recordFields(tpe.typeArguments.head).toMap
        product.components.map(c => labels(c.name))
      }
      product.components.zip(types).map { case (c, t) => s"${product.className}.${c.name}" -> JavaBoundaryApi.argumentShape(t) }
    }
    val nominals = contract.nominals.zipWithIndex.flatMap { case (nominal, i) =>
      val owner = JavaBoundaryNominals.helperOwner(contract, i)
      val tpe = Type.eraseAliases(root.defs(Symbol.mkDefnSym(s"$owner.boundaryPayload")).spec.fparams.head.tpe)
      val TypeConstructor.Enum(sym, _) = tpe.typeConstructor.get: @unchecked
      val decl = root.enums(sym)
      val subst = Substitution(decl.tparams.map(_.sym).zip(tpe.typeArguments).toMap)
      nominal.variants.flatMap { v =>
        val actual = decl.cases.values.find(_.sym.name == v.name).get.tpes.map(subst.apply)
        v.components.zip(actual).map { case (c, t) => s"${nominal.className}.${v.name}.${c.name}" -> JavaBoundaryApi.argumentShape(t) }
      }
    }
    (products ++ nominals).toMap
  }

  private def fields(expected: List[JavaBoundaryContract.Component], actual: List[Type], loc: SourceLocation, root: TypedAst.Root)
                    (implicit flix: Flix): Result[Unit, JavaBoundaryContract.Error] = {
    if (expected.size != actual.size)
      return Err(JavaBoundaryContract.Error("Declared component count differs from the checked Flix payload.", loc))
    Result.traverse(expected.zip(actual)) { case (component, tpe) =>
      Result.traverse(List("JavaResult" -> "Out", "JavaArgument" -> "In")) { case (traitName, assoc) =>
        // Primitive components stay primitive in products, exactly as at a top-level boundary position.
        val selected = if (component.tpe.desc.isPrimitive) JavaBoundaryApi.boundaryType(tpe).mapErr(_.message)
        else {
          val trt = Symbol.mkTraitSym(s"Java.Boundary.$traitName")
          BoundaryTypeElaborator.elaborate(new Symbol.AssocTypeSym(trt, assoc, loc), tpe, root)
            .mapErr(_.toString).flatMap(t => JavaBoundaryApi.boundaryType(t).mapErr(_.message))
        }
        selected.mapErr(message => JavaBoundaryContract.Error(s"Component '${component.name}' $assoc: $message", loc))
          .flatMap(found => if (found == component.tpe) Ok(()) else
            Err(JavaBoundaryContract.Error(s"Component '${component.name}' $assoc: expected ${component.tpe.signature}, actual ${found.signature}.", loc)))
      }.map(_ => ())
    }.map(_ => ())
  }

}
