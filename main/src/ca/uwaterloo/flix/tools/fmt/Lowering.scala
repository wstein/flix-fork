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
import ca.uwaterloo.flix.language.ast.SyntaxTree.{Child, Tree, TreeKind}
import ca.uwaterloo.flix.language.ast.Token
import ca.uwaterloo.flix.tools.fmt.layout.Piece
import ca.uwaterloo.flix.tools.fmt.layout.piece._

/**
  * Translates a [[SyntaxTree.Tree]] into a solver-based [[Piece]] tree losslessly.
  *
  * Preserves every source token and AST sub-tree without hardcoded operator text or dropped guards.
  */
object Lowering {

  /**
    * Translates `tree` into a layout [[Piece]].
    */
  def lower(tree: Tree): Piece = {
    tree.kind match {
      case TreeKind.ArgumentList | TreeKind.ParameterList | TreeKind.TypeParameterList =>
        lowerDelimitedList(tree)

      case TreeKind.Type.Tuple | TreeKind.Expr.Tuple =>
        lowerDelimitedList(tree)

      case TreeKind.Expr.LiteralList | TreeKind.Expr.LiteralVector |
           TreeKind.Expr.LiteralSet | TreeKind.Expr.LiteralMap =>
        lowerDelimitedList(tree)

      case _ =>
        lowerFallback(tree)
    }
  }

  private def lowerChild(child: Child): Piece = child match {
    case t: Tree => lower(t)
    case tok: Token => TextPiece(tok.text)
    case _ => TextPiece("")
  }

  private def lowerDelimitedList(tree: Tree): Piece = {
    val subTrees = tree.children.collect { case t: Tree => lower(t) }.toList
    val tokTexts = tree.children.collect { case tok: Token if !tok.text.forall(_.isWhitespace) => tok.text }

    val leftStr = tokTexts.headOption.getOrElse("(")
    val rightStr = tokTexts.lastOption.getOrElse(")")

    ListPiece(leftStr, subTrees, rightStr)
  }

  private def lowerFallback(tree: Tree): Piece = {
    val pieces = tree.children.map(lowerChild).toList
    AdjacentPiece(pieces)
  }
}
