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
  * A sequence of statements or top-level elements separated by newlines.
  */
final case class SequencePiece(items: List[Piece]) extends Piece {

  override def format(writer: CodeWriter, state: State): Unit = {
    items.zipWithIndex.foreach { case (item, idx) =>
      writer.format(item)
      if (idx < items.length - 1) {
        writer.newline()
      }
    }
  }

  override def forEachChild(f: Piece => Unit): Unit = {
    items.foreach(f)
  }
}
