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
package ca.uwaterloo.flix.tools.fmt

import ca.uwaterloo.flix.language.ast.SyntaxTree
import ca.uwaterloo.flix.language.ast.SyntaxTree.{Tree, TreeKind}
import ca.uwaterloo.flix.tools.fmt.layout.Piece
import ca.uwaterloo.flix.tools.fmt.layout.piece._

/**
  * Translates a [[SyntaxTree.Tree]] into a solver-based [[Piece]] tree.
  */
object Lowering {

  /**
    * Translates `tree` into a layout [[Piece]].
    */
  def lower(tree: Tree): Piece = {
    tree.kind match {
      case TreeKind.ArgumentList | TreeKind.ParameterList | TreeKind.TypeParameterList =>
        lowerList(tree, "(", ")")

      case TreeKind.Type.Tuple | TreeKind.Expr.Tuple =>
        lowerList(tree, "(", ")")

      case TreeKind.Expr.LiteralList =>
        lowerList(tree, "List#{", "}")

      case TreeKind.Expr.LiteralVector =>
        lowerList(tree, "Vector#{", "}")

      case TreeKind.Expr.LiteralSet =>
        lowerList(tree, "Set#{", "}")

      case TreeKind.Expr.LiteralMap =>
        lowerList(tree, "Map#{", "}")

      case TreeKind.Expr.Match | TreeKind.Expr.ExtMatch =>
        lowerMatch(tree)

      case TreeKind.Expr.IfThenElse =>
        lowerIf(tree)

      case TreeKind.Expr.LetMatch =>
        lowerLet(tree)

      case TreeKind.Expr.FixpointConstraintSet =>
        lowerDatalogSet(tree)

      case TreeKind.Expr.FixpointConstraint =>
        lowerDatalogRule(tree)

      case TreeKind.Expr.Binary =>
        lowerBinary(tree)

      case _ =>
        lowerFallback(tree)
    }
  }

  private def lowerList(tree: Tree, left: String, right: String): Piece = {
    val elements = tree.children.collect {
      case subTree: Tree => lower(subTree)
    }.toList
    ListPiece(left, elements, right)
  }

  private def lowerMatch(tree: Tree): Piece = {
    val subTrees = tree.children.collect { case t: Tree => t }.toList
    val subject = subTrees.headOption.map(lower).getOrElse(TextPiece("_"))
    val armTrees = subTrees.drop(1)

    val arms = armTrees.map { armTree =>
      val armSub = armTree.children.collect { case t: Tree => t }.toList
      val pat = armSub.headOption.map(lower).getOrElse(TextPiece("_"))
      val body = armSub.lastOption.map(lower).getOrElse(TextPiece("()"))
      MatchArmPiece(pat, None, body)
    }

    MatchPiece(subject, arms)
  }

  private def lowerIf(tree: Tree): Piece = {
    val subTrees = tree.children.collect { case t: Tree => t }.toList
    val cond = subTrees.headOption.map(lower).getOrElse(TextPiece("true"))
    val thenB = subTrees.lift(1).map(lower).getOrElse(TextPiece("()"))
    val elseB = subTrees.lift(2).map(lower)

    val isBraced = thenB.isInstanceOf[ListPiece] || elseB.exists(_.isInstanceOf[ListPiece])
    IfPiece(cond, thenB, elseB, isBraced)
  }

  private def lowerLet(tree: Tree): Piece = {
    val subTrees = tree.children.collect { case t: Tree => t }.toList
    val name = subTrees.headOption.map(lower).getOrElse(TextPiece("_"))
    val value = subTrees.lift(1).map(lower).getOrElse(TextPiece("()"))
    val body = subTrees.lift(2).map(lower)
    LetPiece(name, value, body)
  }

  private def lowerDatalogSet(tree: Tree): Piece = {
    val rules = tree.children.collect { case t: Tree => lower(t) }.toList
    DatalogConstraintSetPiece("#", rules)
  }

  private def lowerDatalogRule(tree: Tree): Piece = {
    val subTrees = tree.children.collect { case t: Tree => t }.toList
    val head = subTrees.headOption.map(lower).getOrElse(TextPiece("_"))
    val body = subTrees.lift(1).map(lower)
    DatalogRulePiece(head, body)
  }

  private def lowerBinary(tree: Tree): Piece = {
    val subTrees = tree.children.collect { case t: Tree => t }.toList
    val left = subTrees.headOption.map(lower).getOrElse(TextPiece("0"))
    val right = subTrees.lift(1).map(lower).getOrElse(TextPiece("0"))
    InfixPiece(left, "+", right)
  }

  private def lowerFallback(tree: Tree): Piece = {
    val pieces = tree.children.flatMap {
      case subTree: Tree => Some(lower(subTree))
      case tok: ca.uwaterloo.flix.language.ast.Token => Some(TextPiece(tok.text))
      case _ => None
    }.toList
    AdjacentPiece(pieces)
  }
}
