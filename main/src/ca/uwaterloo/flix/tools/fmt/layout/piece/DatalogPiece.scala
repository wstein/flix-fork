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
  * A single Datalog rule / constraint: `head :- body.`.
  */
final case class DatalogRulePiece(head: Piece, body: Option[Piece]) extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.format(head)
    body match {
      case Some(b) =>
        writer.write(" :- ")
        if (state == State.Split) {
          writer.pushIndent(Indent.Block)
          writer.newline()
          writer.format(b)
          writer.write(".")
          writer.popIndent()
        } else {
          writer.format(b)
          writer.write(".")
        }
      case None =>
        writer.write(".")
    }
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    f(head)
    body.foreach(f)
  }
}

/**
  * Embedded Datalog constraint set `#{ ... }` or `#( ... )`.
  */
final case class DatalogConstraintSetPiece(prefix: String, rules: List[Piece]) extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write(prefix)
    writer.write("{")

    if (rules.nonEmpty) {
      if (state == State.Split) {
        writer.pushIndent(Indent.Block)
        writer.newline()
        rules.foreach { rule =>
          writer.format(rule)
          writer.newline()
        }
        writer.popIndent()
      } else {
        writer.space()
        rules.foreach { rule =>
          writer.format(rule)
          writer.space()
        }
      }
    }

    writer.write("}")
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    rules.foreach(f)
  }
}
