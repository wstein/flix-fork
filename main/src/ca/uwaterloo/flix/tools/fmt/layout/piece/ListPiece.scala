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
  * Delimited list structure `( ... )`, `[ ... ]`, `{ ... }`, `#{ ... }`.
  *
  * In Flix, split lists strictly suppress trailing separators because Flix parser
  * rules enforce `allowTrailing = false`.
  */
final case class ListPiece(leftBracket: String,
                           elements: List[Piece],
                           rightBracket: String,
                           separator: String = ",") extends Piece {

  override val additionalStates: List[State] = List(State.Split)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write(leftBracket)

    if (elements.isEmpty) {
      writer.write(rightBracket)
      return
    }

    if (state == State.Split) {
      writer.pushIndent(Indent.Block)
      writer.newline()
      elements.zipWithIndex.foreach { case (elem, idx) =>
        writer.format(elem)
        if (idx < elements.length - 1) {
          writer.write(separator)
          writer.newline()
        }
      }
      writer.popIndent()
      writer.newline()
    } else {
      elements.zipWithIndex.foreach { case (elem, idx) =>
        writer.format(elem)
        if (idx < elements.length - 1) {
          writer.write(separator)
          writer.space()
        }
      }
    }

    writer.write(rightBracket)
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    elements.foreach(f)
  }
}
