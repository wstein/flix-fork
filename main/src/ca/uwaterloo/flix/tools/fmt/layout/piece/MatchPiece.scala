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
package ca.uwaterloo.flix.tools.fmt.layout.piece

import ca.uwaterloo.flix.tools.fmt.layout._

/**
  * A single arm in a match expression: `case pattern [if guard] => body`.
  */
final case class MatchArmPiece(pattern: Piece, guard: Option[Piece], body: Piece) extends Piece {

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write("case ")
    writer.format(pattern)
    guard.foreach { g =>
      writer.space()
      writer.write("if ")
      writer.format(g)
    }
    writer.write(" => ")
    writer.format(body)
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    f(pattern)
    guard.foreach(f)
    f(body)
  }
}

/**
  * `match subject { case ... => ... }`.
  */
final case class MatchPiece(subject: Piece, arms: List[MatchArmPiece]) extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write("match ")
    writer.format(subject)
    writer.space()
    writer.write("{")

    if (arms.nonEmpty) {
      if (state == State.Split) {
        writer.pushIndent(Indent.Block)
        writer.newline()
        arms.zipWithIndex.foreach { case (arm, idx) =>
          writer.format(arm)
          if (idx < arms.length - 1) {
            writer.newline()
          }
        }
        writer.popIndent()
        writer.newline()
      } else {
        writer.space()
        arms.zipWithIndex.foreach { case (arm, idx) =>
          writer.format(arm)
          if (idx < arms.length - 1) {
            writer.space()
          }
        }
        writer.space()
      }
    }

    writer.write("}")
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    f(subject)
    arms.foreach(f)
  }
}
