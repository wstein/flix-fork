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
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.language.ast.shared.Source
import org.objectweb.asm.{Label, MethodVisitor}

/**
  * Records the `LineNumberTable` of one method.
  *
  * Two kinds of entry are dropped.
  *
  * A location belonging to another file is dropped because a class carries a single `SourceFile`
  * attribute, so every line it records is read as a line of `owner`. Recording line 50 of an
  * inlined standard library function would send a debugger to line 50 of the user's file, which
  * may hold unrelated code or may not exist at all. Code inlined from elsewhere instead stays
  * under the line already in effect, which is the call site it was inlined into.
  *
  * A location repeating the line already in effect is dropped because expressions nest, so several
  * frequently begin on the same line and at the same offset. Since an entry maps every offset from
  * its own up to the next entry, a suppressed entry would have mapped its offsets to the line they
  * already map to, so the table describes exactly the same mapping.
  *
  * @param owner the source of the class this method belongs to.
  */
class LineNumbers(owner: Source) {

  /** The line most recently recorded, or `-1` before anything has been. */
  private var current: Int = -1

  /** Records `loc` at the current position, unless it is foreign or already in effect. */
  def emit(loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
    if (loc.source != owner) {
      return
    }
    val line = loc.startLine
    if (line == current) {
      return
    }
    current = line
    val label = new Label()
    mv.visitLabel(label)
    mv.visitLineNumber(line, label)
  }

}
