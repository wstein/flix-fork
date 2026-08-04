/*
 * Copyright 2025 Din Jakupi
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
package ca.uwaterloo.flix.tools.fmt

import ca.uwaterloo.flix.language.ast.SyntaxTree

/**
  * Renders a [[SyntaxTree.Tree]] back to Flix source text.
  *
  * The printer emits every token of the tree, in order, and decides only the
  * whitespace between them. That restriction is the design, not a limitation of
  * the current state:
  *
  *   - No token can be lost, duplicated, or reordered, so a program's meaning
  *     survives formatting by construction rather than by a rule that might have
  *     a gap in it.
  *   - Comments are tokens, so a comment always keeps the same neighbours. A
  *     formatter built this way can change which line a comment sits on, but not
  *     which declaration it belongs to — which is the failure that would
  *     otherwise be silent, and that corrupts generated documentation.
  *   - Declaration order, `use` order, and record label order are untouched
  *     because there is no operation that could touch them.
  *
  * What remains is choosing each gap. [[Separators.Verbatim]] chooses the gap the
  * source already had, which reproduces the input exactly; a canonical policy
  * chooses gaps from the surrounding syntax instead. Layout rules are therefore
  * added one gap decision at a time, against a baseline that is already correct,
  * and a construct with no rule yet costs fidelity nothing.
  */
object PrettyPrinter {

  /**
    * A policy for the whitespace between two adjacent tokens.
    *
    * `left` is the token before the gap, or `None` at the start of the file;
    * `right` is the token after it, or `None` at the end. `original` is the
    * whitespace the source had there.
    */
  trait Separators {
    def between(
      left: Option[TokenStream.PrintableToken],
      right: Option[TokenStream.PrintableToken],
      original: String
    ): String
  }

  object Separators {

    /**
      * Keeps the whitespace the source already had.
      *
      * This reproduces the input byte for byte, which makes it the baseline every
      * layout rule is a departure from, and the fixed point that
      * `TestFormatterStability` checks.
      */
    object Verbatim extends Separators {
      override def between(
        left: Option[TokenStream.PrintableToken],
        right: Option[TokenStream.PrintableToken],
        original: String
      ): String = original
    }
  }

  /**
    * Renders `tree` as Flix source text, reproducing the input exactly.
    *
    * No layout rule is applied yet, so this is a round trip. It is wired up and
    * tested rather than left as a stub because the round trip is what establishes
    * that the tree can reproduce a program at all, and because it makes the whole
    * path — parse, print, apply, write — executable and observable before any
    * layout decision is argued about.
    */
  def format(tree: SyntaxTree.Tree): String =
    format(tree, Separators.Verbatim)

  /** Renders `tree` as Flix source text, choosing gaps with `separators`. */
  def format(tree: SyntaxTree.Tree, separators: Separators): String = {
    val printable = TokenStream.printableTokens(tree)
    if (printable.isEmpty) return ""

    val data = printable.head.token.src.data
    val sb = new StringBuilder

    // The text before the first token and after the last lies outside every
    // token's span, so it is bounded by `None` on one side.
    sb.append(separators.between(None, printable.headOption, whitespace(data, 0, printable.head.token.startIndex)))

    var previous: Option[TokenStream.PrintableToken] = None
    for (current <- printable) {
      previous.foreach { prev =>
        val gap = whitespace(data, prev.token.endIndex, current.token.startIndex)
        sb.append(separators.between(Some(prev), Some(current), gap))
      }
      sb.append(current.text)
      previous = Some(current)
    }

    val last = printable.last.token
    sb.append(separators.between(previous, None, whitespace(data, last.endIndex, data.length)))
    sb.toString()
  }

  /**
    * The whitespace of `data` between `from` and `until`.
    *
    * Non-whitespace characters in a gap belong to the token that follows and are
    * already part of its printable text (see [[TokenStream.printableTokens]]), so
    * dropping them here reassembles the source rather than duplicating them.
    */
  private def whitespace(data: Array[Char], from: Int, until: Int): String =
    data.slice(from, until).filter(_.isWhitespace).mkString
}
