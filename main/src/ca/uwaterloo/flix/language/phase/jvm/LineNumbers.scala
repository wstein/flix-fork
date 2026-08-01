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
import org.objectweb.asm.{Label, MethodVisitor}

/**
  * Records the `LineNumberTable` of one method, dropping a line that repeats the one already
  * in effect.
  *
  * Expressions nest, so several of them frequently begin at the same source line and at the same
  * bytecode offset -- a `Stm` whose first element is a `Let` whose bound expression is an
  * `ApplyDef` reports the same line three times over. Recording each one is harmless but
  * wasteful, and it makes the table hard to read when checking what a debugger will do.
  *
  * @param smap resolves each location to the synthetic line `smap` assigns it, so that code
  *             inlined from another file still maps back to its true (file, line) pair rather
  *             than being misattributed to this method's own file.
  */
class LineNumbers(smap: Smap) {

  /** The line most recently recorded, or `-1` before anything has been. */
  private var current: Int = -1

  /** Records `loc` at the current position, unless it repeats the line already in effect. */
  def emit(loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
    val line = smap.register(loc)
    if (line == current) {
      return
    }
    current = line
    val label = new Label()
    mv.visitLabel(label)
    mv.visitLineNumber(line, label)
  }

}
