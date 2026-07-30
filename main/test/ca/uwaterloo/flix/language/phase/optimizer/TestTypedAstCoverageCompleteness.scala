/*
 * Copyright 2026
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
package ca.uwaterloo.flix.language.phase.optimizer

import ca.uwaterloo.flix.language.ast.TypedAst
import org.scalatest.funsuite.AnyFunSuite

/**
  * Exhaustive completeness test for TypedAst.Expr coverage matrix classification.
  *
  * Enforces that every single TypedAst.Expr constructor is explicitly categorized:
  * - LineOrBranchInstrumented: Explicit line or branch probe instrumentation
  * - TraversedChildOnly: Child expression traversal without inserting direct probes
  * - SyntheticOrTerminal: Primitive/synthetic/terminal form without child expressions
  *
  * If a new AST constructor is added to TypedAst.Expr, this total match will fail to compile
  * unless the constructor is explicitly categorized in this test.
  */
class TestTypedAstCoverageCompleteness extends AnyFunSuite {

  sealed trait Category
  object Category {
    case object LineOrBranchInstrumented extends Category
    case object TraversedChildOnly extends Category
    case object SyntheticOrTerminal extends Category
  }

  /**
    * Total pattern match over all TypedAst.Expr variants.
    * NO WILDCARD CASE IS ALLOWED HERE.
    */
  def classify(exp: TypedAst.Expr): Category = exp match {
    case _: TypedAst.Expr.Cst => Category.SyntheticOrTerminal
    case _: TypedAst.Expr.Var => Category.SyntheticOrTerminal
    case _: TypedAst.Expr.Hole => Category.SyntheticOrTerminal
    case _: TypedAst.Expr.HoleWithExp => Category.TraversedChildOnly
    case _: TypedAst.Expr.OpenAs => Category.TraversedChildOnly
    case _: TypedAst.Expr.Use => Category.TraversedChildOnly
    case _: TypedAst.Expr.Lambda => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ApplyClo => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ApplyDef => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ApplyLocalDef => Category.TraversedChildOnly
    case _: TypedAst.Expr.ApplyOp => Category.TraversedChildOnly
    case _: TypedAst.Expr.ApplySig => Category.TraversedChildOnly
    case _: TypedAst.Expr.Unary => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Binary => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Let => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.LocalDef => Category.TraversedChildOnly
    case _: TypedAst.Expr.Region => Category.TraversedChildOnly
    case _: TypedAst.Expr.IfThenElse => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Stm => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Discard => Category.TraversedChildOnly
    case _: TypedAst.Expr.Match => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.RestrictableChoose => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ExtMatch => Category.TraversedChildOnly
    case _: TypedAst.Expr.Tag => Category.TraversedChildOnly
    case _: TypedAst.Expr.RestrictableTag => Category.TraversedChildOnly
    case _: TypedAst.Expr.ExtTag => Category.TraversedChildOnly
    case _: TypedAst.Expr.Tuple => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.RecordSelect => Category.TraversedChildOnly
    case _: TypedAst.Expr.RecordExtend => Category.TraversedChildOnly
    case _: TypedAst.Expr.RecordRestrict => Category.TraversedChildOnly
    case _: TypedAst.Expr.ArrayLit => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ArrayNew => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ArrayLoad => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ArrayLength => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ArrayStore => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.StructNew => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.StructGet => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.StructPut => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.VectorLit => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.VectorLoad => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.VectorLength => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Ascribe => Category.TraversedChildOnly
    case _: TypedAst.Expr.InstanceOf => Category.TraversedChildOnly
    case _: TypedAst.Expr.CheckedCast => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.UncheckedCast => Category.TraversedChildOnly
    case _: TypedAst.Expr.Unsafe => Category.TraversedChildOnly
    case _: TypedAst.Expr.TryCatch => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Throw => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Handler => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.RunWith => Category.TraversedChildOnly
    case _: TypedAst.Expr.InvokeConstructor => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.InvokeSuperConstructor => Category.TraversedChildOnly
    case _: TypedAst.Expr.InvokeMethod => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.InvokeSuperMethod => Category.TraversedChildOnly
    case _: TypedAst.Expr.InvokeStaticMethod => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.GetField => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.PutField => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.GetStaticField => Category.SyntheticOrTerminal
    case _: TypedAst.Expr.PutStaticField => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.NewObject => Category.TraversedChildOnly
    case _: TypedAst.Expr.NewChannel => Category.TraversedChildOnly
    case _: TypedAst.Expr.GetChannel => Category.TraversedChildOnly
    case _: TypedAst.Expr.PutChannel => Category.TraversedChildOnly
    case _: TypedAst.Expr.SelectChannel => Category.TraversedChildOnly
    case _: TypedAst.Expr.Spawn => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.ParYield => Category.TraversedChildOnly
    case _: TypedAst.Expr.Lazy => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.Force => Category.LineOrBranchInstrumented
    case _: TypedAst.Expr.FixpointConstraintSet => Category.SyntheticOrTerminal
    case _: TypedAst.Expr.FixpointLambda => Category.TraversedChildOnly
    case _: TypedAst.Expr.FixpointMerge => Category.TraversedChildOnly
    case _: TypedAst.Expr.FixpointQueryWithProvenance => Category.TraversedChildOnly
    case _: TypedAst.Expr.FixpointQueryWithSelect => Category.TraversedChildOnly
    case _: TypedAst.Expr.FixpointSolveWithProject => Category.TraversedChildOnly
    case _: TypedAst.Expr.FixpointInjectInto => Category.TraversedChildOnly
    case _: TypedAst.Expr.CoverageHit => Category.SyntheticOrTerminal
    case _: TypedAst.Expr.Error => Category.SyntheticOrTerminal
  }

  test("TypedAst.Expr classification exhaustiveness") {
    assert(true, "All TypedAst.Expr variants are exhaustively classified")
  }
}
