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
  * `if (condition) thenBranch [else elseBranch]`.
  */
final case class IfPiece(condition: Piece,
                         thenBranch: Piece,
                         elseBranch: Option[Piece],
                         isBraced: Boolean = false) extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  if (isBraced) {
    pin(State.Split)
  }

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write("if (")
    writer.format(condition)
    writer.write(") ")

    if (state == State.Split) {
      writer.format(thenBranch)
      elseBranch.foreach { el =>
        writer.space()
        writer.write("else ")
        writer.format(el)
      }
    } else {
      writer.format(thenBranch)
      elseBranch.foreach { el =>
        writer.space()
        writer.write("else ")
        writer.format(el)
      }
    }
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    f(condition)
    f(thenBranch)
    elseBranch.foreach(f)
  }
}
