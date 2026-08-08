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

import ca.uwaterloo.flix.language.ast.SyntaxTree.{Tree, TreeKind}
import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.tools.fmt.layout.piece._
import org.scalatest.funsuite.AnyFunSuite

class TestLowering extends AnyFunSuite {

  test("Lowering.01 — ArgumentList lowers to ListPiece") {
    val childTree1 = Tree(TreeKind.Argument, Array.empty, SourceLocation.Unknown)
    val childTree2 = Tree(TreeKind.Argument, Array.empty, SourceLocation.Unknown)
    val argListTree = Tree(TreeKind.ArgumentList, Array(childTree1, childTree2), SourceLocation.Unknown)

    val piece = Lowering.lower(argListTree)
    assert(piece.isInstanceOf[ListPiece])
    val listPiece = piece.asInstanceOf[ListPiece]
    assert(listPiece.leftBracket == "(")
    assert(listPiece.rightBracket == ")")
    assert(listPiece.elements.length == 2)
  }

  test("Lowering.02 — Unhandled Tree lowers to AdjacentPiece") {
    val tree = Tree(TreeKind.Case, Array.empty, SourceLocation.Unknown)
    val piece = Lowering.lower(tree)
    assert(piece.isInstanceOf[AdjacentPiece])
  }
}
