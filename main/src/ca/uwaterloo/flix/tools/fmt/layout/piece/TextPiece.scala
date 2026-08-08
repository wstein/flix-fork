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

import ca.uwaterloo.flix.tools.fmt.layout.{CodeWriter, Piece, State}

/**
  * A layout piece representing literal text, optionally followed by a space.
  */
final case class TextPiece(text: String, spaceAfter: Boolean = false) extends Piece {

  override protected def calculateContainsHardNewline: Boolean = text.contains('\n')

  override protected def calculateTotalCharacters: Int = text.length + (if (spaceAfter) 1 else 0)

  override def format(writer: CodeWriter, state: State): Unit = {
    writer.write(text)
    if (spaceAfter) writer.space()
  }

  override def forEachChild(f: Piece => Unit): Unit = ()
}
