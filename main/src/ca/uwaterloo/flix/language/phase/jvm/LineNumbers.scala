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
  * Only a repeat of the line currently in effect is dropped. Since an entry maps every offset from
  * its own up to the next entry, a suppressed entry would have mapped its offsets to the line they
  * already map to, so the resulting table describes exactly the same mapping.
  *
  * A *different* line arriving at an offset that already has an entry is dropped too, and that one
  * is not cosmetic. An entry is keyed by its offset, so a second entry at the same offset does not
  * add a mapping -- it replaces the first, and the replaced line becomes invisible to a debugger.
  * `locationsOfLine` returns nothing for it and no breakpoint can bind to it.
  *
  * This happens whenever a single-expression function is inlined into a call site in the same
  * file: the call site's line and the inlined body's line are emitted at one instruction. Observed
  * in `flix-lab`, where `println(helloFromKotlin())` on line 102 and `KotlinGreeter.greeting()` on
  * line 59 both landed on bytecode offset 208 -- and line 102 could not take a breakpoint, while
  * its neighbours could. Cross-file inlining escapes this because [[Smap]] allocates the foreign
  * line a synthetic number of its own; same-file inlining gets the line unchanged, so the two
  * collide.
  *
  * The first entry wins, which is the call site. That is the line the programmer wrote and the one
  * they set a breakpoint on; the inlined body is reachable by stepping into it.
  *
  * One entry must *not* win that way, and it is the reason [[emitHeader]] exists rather than every
  * caller using [[emit]]. A method opens by recording its own declaration line, before a single
  * instruction has been written -- so that entry sits at offset 0, and the body's first statement,
  * which also begins at offset 0, lost to it under the rule above. The effect was that the first
  * statement of *every* function was missing from the table and could never take a breakpoint,
  * while the declaration line above it and every later statement could. Measured with
  * `ReferenceType.locationsOfLine` against a live VM, which is the only authority on what a
  * debugger can bind to.
  *
  * A declaration entry is therefore provisional: it is written only once it is known that the body
  * did not claim its offset. That keeps the call-site rule intact for every other collision -- the
  * inlining case is between two body expressions and never involves the declaration -- while
  * letting the statement the programmer actually clicks on take the offset it shares with a header
  * that describes no code of its own.
  *
  * @param smap resolves each location to the synthetic line `smap` assigns it, so that code
  *             inlined from another file still maps back to its true (file, line) pair rather
  *             than being misattributed to this method's own file.
  */
class LineNumbers(smap: Smap) {

  /** The line most recently recorded, or `-1` before anything has been. */
  private var current: Int = -1

  /** The bytecode offset the most recent entry was recorded at, or `-1` before any. */
  private var currentOffset: Int = -1

  /** A declaration entry recorded but not yet written -- see [[emitHeader]]. */
  private var pending: Option[(Int, Label, Int)] = None

  /**
    * Records the enclosing method's declaration line, to be written only if the body leaves it a
    * bytecode offset of its own.
    *
    * The label is placed now, because that is what fixes the offset the entry would describe, but
    * the entry itself waits: the very next thing written is the body, and if its first statement
    * begins at this same offset then only one of the two can exist. The statement wins, because it
    * is the one a debugger is asked to stop on. A declaration line describes no instruction the
    * body does not already describe.
    *
    * When the body does move first -- it loads parameters, say -- the offsets differ, both entries
    * are real, and the declaration is written by the first [[emit]]. When the body records nothing
    * at all, as without `--Xdebug`, [[finish]] writes it and the method reports its declaration
    * line exactly as before.
    */
  def emitHeader(loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
    val line = smap.register(loc)
    val label = new Label()
    mv.visitLabel(label)
    pending = Some((line, label, offsetOf(label)))
  }

  /**
    * Records `loc` at the current position, unless an entry is already in effect there.
    *
    * The label is visited before the decision because that is what resolves its offset: until the
    * writer has placed it, there is no way to ask where "here" is. Visiting a label that is never
    * given a line number is harmless -- it contributes nothing to the class file.
    */
  def emit(loc: SourceLocation)(implicit mv: MethodVisitor): Unit = {
    val line = smap.register(loc)
    val label = new Label()
    mv.visitLabel(label)
    val offset = offsetOf(label)
    resolvePending(Some(offset))
    if (line == current) {
      return
    }
    if (offset >= 0 && offset == currentOffset) {
      return
    }
    write(line, label, offset)
  }

  /**
    * Writes a declaration entry that no statement displaced.
    *
    * Called once the method's body is complete. Without it a method whose body records no line at
    * all -- every method when `--Xdebug` is off -- would carry no `LineNumberTable` entry
    * whatsoever, where before it carried its declaration line.
    */
  def finish()(implicit mv: MethodVisitor): Unit = resolvePending(None)

  /**
    * Settles the provisional declaration entry against the offset the body is about to occupy, or
    * against `None` when there is no body entry left to come.
    *
    * Dropped only on an exact, *known* offset match. An unresolved offset (`-1`) is not evidence of
    * a collision, so the entry is kept -- the same "emit rather than guess" trade [[offsetOf]]
    * makes.
    */
  private def resolvePending(bodyOffset: Option[Int])(implicit mv: MethodVisitor): Unit = {
    pending.foreach { case (line, label, offset) =>
      pending = None
      val displaced = offset >= 0 && bodyOffset.exists(_ == offset)
      if (!displaced) {
        write(line, label, offset)
      }
    }
  }

  private def write(line: Int, label: Label, offset: Int)(implicit mv: MethodVisitor): Unit = {
    current = line
    currentOffset = offset
    mv.visitLineNumber(line, label)
  }

  /**
    * The resolved offset of `label`, or `-1` if this visitor does not resolve labels eagerly.
    *
    * Returning `-1` restores the previous behaviour rather than failing: a visitor that cannot say
    * where a label sits leaves us unable to detect a collision, and emitting the entry is the
    * lesser harm.
    */
  private def offsetOf(label: Label): Int =
    try label.getOffset
    catch { case _: IllegalStateException => -1 }

}
