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
  * `let name = value; body`.
  */
final case class LetPiece(name: Piece, value: Piece, body: Option[Piece]) extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write("let ")
    writer.format(name)
    writer.write(" = ")

    if (state == State.Split) {
      writer.pushIndent(Indent.Expression)
      writer.newline()
      writer.format(value)
      writer.write(";")
      writer.popIndent()
      body.foreach { b =>
        writer.newline()
        writer.format(b)
      }
    } else {
      writer.format(value)
      writer.write(";")
      body.foreach { b =>
        writer.newline()
        writer.format(b)
      }
    }
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    f(name)
    f(value)
    body.foreach(f)
  }
}
