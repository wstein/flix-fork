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
      case TreeKind.ArgumentList | TreeKind.ParameterList =>
        lowerList(tree, "(", ")")
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

  private def lowerFallback(tree: Tree): Piece = {
    val pieces = tree.children.flatMap {
      case subTree: Tree => Some(lower(subTree))
      case tok: ca.uwaterloo.flix.language.ast.Token => Some(TextPiece(tok.text))
      case _ => None
    }.toList
    AdjacentPiece(pieces)
  }
}
