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
  * Binary operator expressions, pipelines (`|>`), or infix chains.
  */
final case class InfixPiece(left: Piece, operator: String, right: Piece) extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.format(left)
    writer.space()
    writer.write(operator)

    if (state == State.Split) {
      writer.pushIndent(Indent.Infix)
      writer.newline()
      writer.format(right)
      writer.popIndent()
    } else {
      writer.space()
      writer.format(right)
    }
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    f(left)
    f(right)
  }
}
