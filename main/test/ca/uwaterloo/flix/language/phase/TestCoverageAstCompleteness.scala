/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 */
package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.language.ast.TypedAst
import org.scalatest.funsuite.AnyFunSuite

/** Compile-time tripwire: adding a TypedAst expression requires an explicit coverage decision. */
class TestCoverageAstCompleteness extends AnyFunSuite {

  sealed trait Category
  case object Leaf extends Category
  case object Recursive extends Category
  case object Synthetic extends Category

  // Deliberately no wildcard: this match must change with TypedAst.Expr.
  def classify(exp: TypedAst.Expr): Category = exp match {
    case _: TypedAst.Expr.Cst | _: TypedAst.Expr.Var | _: TypedAst.Expr.Hole |
         _: TypedAst.Expr.GetStaticField | _: TypedAst.Expr.FixpointConstraintSet |
         _: TypedAst.Expr.Error => Leaf
    case _: TypedAst.Expr.CoverageHit => Synthetic
    case _: TypedAst.Expr.HoleWithExp | _: TypedAst.Expr.OpenAs | _: TypedAst.Expr.Use |
         _: TypedAst.Expr.Lambda | _: TypedAst.Expr.ApplyClo | _: TypedAst.Expr.ApplyDef |
         _: TypedAst.Expr.ApplyLocalDef | _: TypedAst.Expr.ApplyOp | _: TypedAst.Expr.ApplySig |
         _: TypedAst.Expr.Unary | _: TypedAst.Expr.Binary | _: TypedAst.Expr.Let |
         _: TypedAst.Expr.LocalDef | _: TypedAst.Expr.Region | _: TypedAst.Expr.IfThenElse |
         _: TypedAst.Expr.Stm | _: TypedAst.Expr.Discard | _: TypedAst.Expr.Match |
         _: TypedAst.Expr.RestrictableChoose | _: TypedAst.Expr.ExtMatch | _: TypedAst.Expr.Tag |
         _: TypedAst.Expr.RestrictableTag | _: TypedAst.Expr.ExtTag | _: TypedAst.Expr.Tuple |
         _: TypedAst.Expr.RecordSelect | _: TypedAst.Expr.RecordExtend | _: TypedAst.Expr.RecordRestrict |
         _: TypedAst.Expr.ArrayLit | _: TypedAst.Expr.ArrayNew | _: TypedAst.Expr.ArrayLoad |
         _: TypedAst.Expr.ArrayLength | _: TypedAst.Expr.ArrayStore | _: TypedAst.Expr.StructNew |
         _: TypedAst.Expr.StructGet | _: TypedAst.Expr.StructPut | _: TypedAst.Expr.VectorLit |
         _: TypedAst.Expr.VectorLoad | _: TypedAst.Expr.VectorLength | _: TypedAst.Expr.Ascribe |
         _: TypedAst.Expr.InstanceOf | _: TypedAst.Expr.CheckedCast | _: TypedAst.Expr.UncheckedCast |
         _: TypedAst.Expr.Unsafe | _: TypedAst.Expr.TryCatch | _: TypedAst.Expr.Throw |
         _: TypedAst.Expr.Handler | _: TypedAst.Expr.RunWith | _: TypedAst.Expr.InvokeConstructor |
         _: TypedAst.Expr.InvokeSuperConstructor | _: TypedAst.Expr.InvokeMethod |
         _: TypedAst.Expr.InvokeSuperMethod | _: TypedAst.Expr.InvokeStaticMethod |
         _: TypedAst.Expr.GetField | _: TypedAst.Expr.PutField | _: TypedAst.Expr.PutStaticField |
         _: TypedAst.Expr.NewObject | _: TypedAst.Expr.NewChannel | _: TypedAst.Expr.GetChannel |
         _: TypedAst.Expr.PutChannel | _: TypedAst.Expr.SelectChannel | _: TypedAst.Expr.Spawn |
         _: TypedAst.Expr.ParYield | _: TypedAst.Expr.Lazy | _: TypedAst.Expr.Force |
         _: TypedAst.Expr.FixpointLambda | _: TypedAst.Expr.FixpointMerge |
         _: TypedAst.Expr.FixpointQueryWithProvenance | _: TypedAst.Expr.FixpointQueryWithSelect |
         _: TypedAst.Expr.FixpointSolveWithProject | _: TypedAst.Expr.FixpointInjectInto => Recursive
  }

  test("every TypedAst expression has an explicit coverage classification") {
    assert(true)
  }
}
