/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{Kind, RigidityEnv, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{EqualityConstraint, RegionScope}
import ca.uwaterloo.flix.language.ast.shared.SymUse.AssocTypeSymUse
import ca.uwaterloo.flix.language.phase.typer.ConstraintSolver2
import ca.uwaterloo.flix.language.phase.unification.{EqualityEnv, Substitution}
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}

/**
  * ADR 3's concrete boundary-type proof, not an export phase.
  *
  * Call only after successful type checking and instance validation. It uses the checked trait
  * and equality environments, so user instances determine the result just like library instances.
  * Concrete associated-type applications are internal queries: surface syntax remains unchanged.
  */
object BoundaryTypeElaborator {

  sealed trait Error
  case class MissingInstance(sym: Symbol.TraitSym, arg: Type) extends Error
  case class UnresolvedAssociatedType(sym: Symbol.AssocTypeSym, arg: Type) extends Error
  case class RecursiveAssociatedType(sym: Symbol.AssocTypeSym, arg: Type) extends Error
  case class RecursiveInstance(sym: Symbol.TraitSym, arg: Type) extends Error
  case class NonConcreteType(tpe: Type) extends Error
  case class UnsatisfiedEquality(constr: EqualityConstraint) extends Error
  case class UnexpectedKind(tpe: Type, expected: Kind) extends Error
  case class ReductionLimit(tpe: Type) extends Error

  /** Derives one concrete associated type (for example JavaResult.Out or JavaResult.Aef). */
  def elaborate(sym: Symbol.AssocTypeSym, arg: Type, root: TypedAst.Root)(implicit flix: Flix): Result[Type, Error] = {
    root.traits.get(sym.trt).flatMap(_.assocs.find(_.sym == sym)) match {
      case None => Err(UnresolvedAssociatedType(sym, arg))
      case Some(assoc) =>
        val query = Type.AssocType(AssocTypeSymUse(sym, arg.loc), arg, assoc.kind, arg.loc)
        new Context(root).visitType(query, Set.empty, Set.empty, MaxDepth)
    }
  }

  // A cycle may grow its argument on each step instead of repeating an identical query.
  // This prototype rejects that case deterministically rather than exhausting the JVM stack.
  private val MaxDepth: Int = 128

  private class Context(root: TypedAst.Root)(implicit flix: Flix) {
    private type AssocPath = Set[(Symbol.AssocTypeSym, Type)]
    private type InstancePath = Set[(Symbol.TraitSym, Type)]

    /** Normalizes every projection, including those nested in Java type arguments and effects. */
    def visitType(tpe: Type, assocs: AssocPath, instances: InstancePath, depth: Int): Result[Type, Error] = {
      if (depth <= 0) return Err(ReductionLimit(tpe))
      tpe match {
        case _: Type.Var => Err(NonConcreteType(tpe))
        case Type.Cst(TypeConstructor.Error(_, _), _) => Err(NonConcreteType(tpe))
        case _: Type.Cst => Ok(tpe)
        case Type.Alias(_, _, expanded, _) => visitType(expanded, assocs, instances, depth - 1)
        case Type.Apply(left, right, loc) =>
          for {
            t1 <- visitType(left, assocs, instances, depth - 1)
            t2 <- visitType(right, assocs, instances, depth - 1)
          } yield (t1, t2) match {
            case (Type.Apply(Type.Cst(TypeConstructor.Union, _), first, _), second) =>
              Type.mkUnion(first, second, loc)
            case _ => Type.Apply(t1, t2, loc)
          }
        case Type.AssocType(symUse, input, kind, _) =>
          for {
            arg <- visitType(input, assocs, instances, depth - 1)
            result <- reduceAssoc(symUse.sym, arg, kind, assocs, instances, depth - 1)
          } yield result
        case _: Type.JvmToType | _: Type.JvmToEff | _: Type.UnresolvedJvmType =>
          Err(NonConcreteType(tpe))
      }
    }

    private def reduceAssoc(sym: Symbol.AssocTypeSym, arg: Type, kind: Kind,
                            assocs: AssocPath, instances: InstancePath, depth: Int): Result[Type, Error] = {
      val key = (sym, arg)
      if (assocs.contains(key)) return Err(RecursiveAssociatedType(sym, arg))
      val next = assocs + key
      for {
        _ <- requireInstance(sym.trt, arg, next, instances, depth)
        result <- root.eqEnv.getAssocDef(sym, arg) match {
          case None => Err(UnresolvedAssociatedType(sym, arg))
          case Some(defn) =>
            matchHead(arg, defn.arg) match {
              case None => Err(UnresolvedAssociatedType(sym, arg))
              case Some(subst) => visitType(subst(defn.ret), next, instances, depth)
            }
        }
        checked <- if (result.kind == kind) Ok(result) else Err(UnexpectedKind(result, kind))
      } yield checked
    }

    /** Evidence is required even when an instance's associated type does not use its constraints. */
    private def requireInstance(sym: Symbol.TraitSym, arg: Type, assocs: AssocPath,
                                instances: InstancePath, depth: Int): Result[Unit, Error] = {
      if (depth <= 0) return Err(ReductionLimit(arg))
      val key = (sym, arg)
      if (instances.contains(key)) return Err(RecursiveInstance(sym, arg))
      root.traitEnv.getInstance(sym, arg) match {
        case None => Err(MissingInstance(sym, arg))
        case Some(inst) => matchHead(arg, inst.tpe) match {
          case None => Err(MissingInstance(sym, arg))
          case Some(subst) =>
            val next = instances + key
            for {
              _ <- Result.sequence(inst.tconstrs.map { constr =>
                for {
                  input <- visitType(subst(constr.arg), assocs, next, depth - 1)
                  _ <- requireInstance(constr.symUse.sym, input, assocs, next, depth - 1)
                } yield ()
              })
              _ <- Result.sequence(inst.econstrs.map { constr =>
                checkEquality(subst(constr), assocs, next, depth - 1)
              })
            } yield ()
        }
      }
    }

    private def checkEquality(constr: EqualityConstraint, assocs: AssocPath,
                              instances: InstancePath, depth: Int): Result[Unit, Error] = {
      val sym = constr.symUse.sym
      val kind = root.traits.get(sym.trt).flatMap(_.assocs.find(_.sym == sym)).map(_.kind)
      kind match {
        case None => Err(UnresolvedAssociatedType(sym, constr.tpe1))
        case Some(k) =>
          val projection = Type.AssocType(constr.symUse, constr.tpe1, k, constr.loc)
          for {
            left <- visitType(projection, assocs, instances, depth)
            right <- visitType(constr.tpe2, assocs, instances, depth)
            _ <- if (ConstraintSolver2.isEquivalent(left, right)(EqualityEnv.empty, flix)) Ok(())
                 else Err(UnsatisfiedEquality(constr))
          } yield ()
      }
    }

    /** Instance heads are checked surface types; no associated-type reduction is needed to match. */
    private def matchHead(arg: Type, head: Type): Option[Substitution] =
      ConstraintSolver2.fullyUnify(arg, head, RegionScope.Top, RigidityEnv.empty)(EqualityEnv.empty, flix)
  }
}
