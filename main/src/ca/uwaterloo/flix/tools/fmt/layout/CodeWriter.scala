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
package ca.uwaterloo.flix.tools.fmt.layout

import scala.collection.mutable

/**
  * Renders a layout [[Piece]] tree under a specific [[Solution]] binding.
  *
  * Handles four responsibilities in a single rendering pass:
  *   1. Formats pieces into output text spans.
  *   2. Tracks horizontal column position and records overflow past `ctx.pageWidth`.
  *   3. Computes each piece's [[Shape]] bottom-up and enforces parent constraints via [[Solution.invalidate]].
  *   4. Identifies expandable stateful pieces on the first problematic line for search progression.
  *
  * @param ctx      the active solve context
  * @param solution the solution candidate being evaluated
  */
final class CodeWriter(ctx: SolveContext, solution: Solution) {

  private val sb = new StringBuilder()
  private val indentStack = mutable.Stack[Int]()

  private var currentLineIndex: Int = 0
  private var currentColumn: Int = ctx.leadingIndent
  private var lineStartColumn: Int = ctx.leadingIndent
  private var pendingIndentSpaces: Int = ctx.leadingIndent

  private var problematicLineIndex: Option[Int] = None
  private val piecesOnCurrentLine = mutable.LinkedHashSet[Piece]()
  private val expandPieces = mutable.LinkedHashSet[Piece]()

  /** Returns current total indentation level in spaces. */
  private def currentIndentLevel: Int = ctx.subsequentIndent + indentStack.sum

  /** Writes literal text to the output buffer. */
  def write(text: String, soft: Boolean = false): Unit = {
    if (text.isEmpty) return

    emitPendingIndentIfNeeded()

    val lines = text.split("\n", -1)
    if (lines.length == 1) {
      sb.append(text)
      currentColumn += text.length
      checkLineOverflow()
    } else {
      var i = 0
      while (i < lines.length) {
        if (i > 0) {
          sb.append("\n")
          currentLineIndex += 1
          currentColumn = 0
          lineStartColumn = 0
          piecesOnCurrentLine.clear()
        }
        val line = lines(i)
        if (line.nonEmpty) {
          sb.append(line)
          currentColumn += line.length
          checkLineOverflow()
        }
        i += 1
      }
    }
  }

  /** Writes a single space. */
  def space(): Unit = {
    write(" ")
  }

  /**
    * Writes a newline if `condition` is true, or a single space if `condition` is false (and `space` is true).
    */
  def splitIf(condition: Boolean, space: Boolean = true, blank: Boolean = false): Unit = {
    if (condition) {
      newline(blank = blank)
    } else if (space) {
      this.space()
    }
  }

  /** Emits a newline and sets pending indentation for the next line. */
  def newline(blank: Boolean = false, flushLeft: Boolean = false): Unit = {
    checkLineOverflow()
    if (blank) {
      sb.append("\n\n")
      currentLineIndex += 2
    } else {
      sb.append("\n")
      currentLineIndex += 1
    }

    val indent = if (flushLeft) 0 else currentIndentLevel
    currentColumn = 0
    lineStartColumn = indent
    pendingIndentSpaces = indent
    piecesOnCurrentLine.clear()
  }

  /** Pushes an indentation increment onto the stack. */
  def pushIndent(indent: Indent): Unit = {
    indentStack.push(indent.spaces)
  }

  /** Pops an indentation increment from the stack. */
  def popIndent(): Unit = {
    if (indentStack.nonEmpty) {
      indentStack.pop()
    }
  }

  /**
    * Formats `piece` under the state assigned by `solution`.
    */
  def format(piece: Piece, separate: Boolean = false): Unit = {
    val state = solution.pieceState(piece)
    val isExpandable = piece.additionalStates.nonEmpty && !solution.isBound(piece)

    if (isExpandable) {
      piecesOnCurrentLine += piece
    }

    if (separate) {
      val subtreeSolution = ctx.cache.find(ctx, piece, piece.pinnedState)
      solution.mergeSubtree(subtreeSolution)
      write(subtreeSolution.code.toText)
      return
    }

    val startLine = currentLineIndex
    val startCol = currentColumn

    piece.format(this, state)

    val endLine = currentLineIndex
    val endCol = currentColumn

    val shape: Shape = if (startLine == endLine) {
      Shape.Inline
    } else if (endLine > startLine && startCol > lineStartColumn) {
      Shape.Block
    } else if (endLine > startLine) {
      Shape.Headline
    } else {
      Shape.Other
    }

    piece.forEachChild { child =>
      val allowed = piece.allowedChildShapes(state, child)
      if (!ShapeSet.contains(allowed, shape)) {
        solution.invalidate(piece)
        markProblematicLine()
      }
    }
  }

  /** Finishes formatting and returns rendered output and expandable pieces. */
  def finish(): (GroupCode, List[Piece]) = {
    checkLineOverflow()
    (new GroupCode(sb.toString()), expandPieces.toList)
  }

  private def emitPendingIndentIfNeeded(): Unit = {
    if (pendingIndentSpaces > 0) {
      sb.append(" " * pendingIndentSpaces)
      currentColumn += pendingIndentSpaces
      pendingIndentSpaces = 0
    }
  }

  private def checkLineOverflow(): Unit = {
    if (currentColumn > ctx.pageWidth) {
      solution.addOverflow(currentColumn - ctx.pageWidth)
      markProblematicLine()
    }
  }

  private def markProblematicLine(): Unit = {
    if (problematicLineIndex.isEmpty || problematicLineIndex.contains(currentLineIndex)) {
      problematicLineIndex = Some(currentLineIndex)
      expandPieces ++= piecesOnCurrentLine
    }
  }
}
