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
package ca.uwaterloo.flix.tools.fmt

import ca.uwaterloo.flix.language.ast.SyntaxTree.TreeKind
import ca.uwaterloo.flix.language.ast.{SyntaxTree, Token, TokenKind}

/**
  * Whitespace decided from the shape of the tree rather than from a pair of
  * adjacent tokens.
  *
  * [[Canonical]] can only see two tokens at a time, which is enough to decide
  * whether a space belongs between them and not enough for anything vertical.
  * Breaking a line depends on the construct the tokens sit in, indenting depends
  * on how deeply that construct nests, and aligning depends on every sibling in a
  * group. Those decisions are computed here, over the tree, and handed to the
  * printer as one directive per gap.
  *
  * The printer applies a directive where there is one and falls back to the
  * separator policy everywhere else, so this stays additive: a construct with no
  * rule keeps behaving exactly as it did.
  *
  * Only `match` is covered. That is not where the work stopped for lack of time
  * but where the evidence stops being decisive: the corpus contains 1,868 `match`
  * expressions and **not one** written inline, so "always break" needs no
  * threshold and contradicts nothing. The pipeline threshold, by contrast, splits
  * the corpus — 40% of two-stage pipelines are broken and 60% are not — and the
  * Datalog thresholds have never been measured at all. Guessing those would
  * reformat thousands of sites on a coin toss.
  */
object LayoutPlan {

  /** What the printer should put in a gap. */
  sealed trait Gap

  object Gap {

    /** No opinion: the separator policy decides. */
    case object Unspecified extends Gap

    /** A line break followed by `indent` spaces. */
    case class Break(indent: Int) extends Gap

    /** Exactly `spaces` spaces, used to line a column up. */
    case class Pad(spaces: Int) extends Gap
  }

  /**
    * A directive for the gap *before* each token of `tree`, indexed as
    * [[TokenStream.tokens]] indexes them.
    *
    * `policy` is consulted when measuring how wide an arm will be rendered, so
    * that alignment padding is computed against the spacing that will actually be
    * emitted rather than the spacing the source happens to have.
    */
  def plan(tree: SyntaxTree.Tree, policy: PrettyPrinter.Separators): Vector[Gap] = {
    val tokens = TokenStream.printableTokens(tree)
    val plan = Array.fill[Gap](tokens.length)(Gap.Unspecified)
    val ranges = new java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)]()
    index(tree, 0, ranges)

    ranges.forEach { (node, range) =>
      if (node.kind == TreeKind.Expr.Match) {
        planMatch(node, range, ranges, tokens, plan, policy)
      }
    }
    plan.toVector
  }

  /** Records the token range each node spans, and returns the index after `node`. */
  private def index(
    node: SyntaxTree.Tree,
    start: Int,
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)]
  ): Int = {
    var idx = start
    node.children.foreach {
      case child: SyntaxTree.Tree => idx = index(child, idx, ranges)
      case _: Token => idx += 1
      case _ => ()
    }
    ranges.put(node, (start, idx))
    idx
  }

  /**
    * Lays out one `match`: every arm on its own line, indented one unit from the
    * `match` keyword, with the closing brace back at the keyword's indentation.
    *
    * The base indentation is read from the line the `match` keyword sits on rather
    * than computed, because the code *around* the match has no layout rule yet and
    * so keeps whatever indentation it had. Indenting the arms against a computed
    * base would place them relative to a line that was never moved. When the
    * enclosing constructs gain rules, this base becomes computed too.
    */
  private def planMatch(
    node: SyntaxTree.Tree,
    range: (Int, Int),
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap],
    policy: PrettyPrinter.Separators
  ): Unit = {
    val (start, end) = range
    val arms = node.children.collect {
      case t: SyntaxTree.Tree if t.kind == TreeKind.Expr.MatchRuleFragment => t
    }.toList
    if (arms.isEmpty) return

    val base = indentOfLineContaining(tokens(start).token)
    val body = base + IndentUnit

    // Each arm starts a line, including the first, which breaks after the `{`.
    for (arm <- arms) {
      val (armStart, _) = ranges.get(arm)
      if (armStart > 0) plan(armStart) = Gap.Break(body)
    }

    // The closing brace returns to the keyword's own indentation. It is the last
    // token of the match, and only when the parser actually produced one — on a
    // malformed match there may be none, and inventing it would change the program.
    val lastIdx = end - 1
    if (lastIdx > 0 && lastIdx < tokens.length && tokens(lastIdx).token.kind == TokenKind.CurlyR) {
      plan(lastIdx) = Gap.Break(base)
    }

    alignArrows(arms, ranges, tokens, plan, policy)
  }

  /**
    * Pads each arm so the `=>` of every arm in a group starts at the same column.
    *
    * `docs/STYLE.md` requires this — *"Pattern matches should align `=>`"* — and
    * 2,047 arms in the corpus already do it. Producing the padding rather than
    * preserving whatever the author typed is what makes two files that differ only
    * in alignment format identically.
    *
    * A group is a run of arms with no blank line between them, following `gofmt`:
    * a blank line is the one signal an author has that two runs are separate
    * tables. An arm whose prefix is far wider than the narrowest in its group opts
    * out, taking a single space and not widening the target, so one long pattern
    * cannot push every other `=>` across the screen.
    *
    * An arm carrying a comment before its `=>` is left alone entirely: its width
    * is not a property of the code, and padding around a comment reads as noise.
    */
  private def alignArrows(
    arms: List[SyntaxTree.Tree],
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap],
    policy: PrettyPrinter.Separators
  ): Unit = {
    for (group <- groupArms(arms, ranges, tokens)) {
      val measured = group.flatMap { arm =>
        val (armStart, armEnd) = ranges.get(arm)
        arrowIndex(tokens, armStart, armEnd).flatMap { arrow =>
          val hasComment = (armStart until arrow).exists(i => tokens(i).token.kind.isComment)
          if (hasComment) None else Some((arrow, prefixWidth(tokens, armStart, arrow, policy)))
        }
      }
      if (measured.sizeIs > 1) {
        val narrowest = measured.map(_._2).min
        val participating = measured.filter(_._2 <= narrowest + OutlierSlack)
        if (participating.sizeIs > 1) {
          val target = participating.map(_._2).max
          for ((arrow, width) <- participating) {
            plan(arrow) = Gap.Pad(target - width + 1)
          }
        }
      }
    }
  }

  /** Splits `arms` into runs separated by a blank line in the source. */
  private def groupArms(
    arms: List[SyntaxTree.Tree],
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken]
  ): List[List[SyntaxTree.Tree]] = {
    val groups = scala.collection.mutable.ListBuffer.empty[List[SyntaxTree.Tree]]
    var current = scala.collection.mutable.ListBuffer.empty[SyntaxTree.Tree]
    for (arm <- arms) {
      val (armStart, _) = ranges.get(arm)
      if (current.nonEmpty && blankLineBefore(tokens, armStart)) {
        groups += current.toList
        current = scala.collection.mutable.ListBuffer.empty
      }
      current += arm
    }
    if (current.nonEmpty) groups += current.toList
    groups.toList
  }

  /** Returns `true` if a blank line separates token `i` from the one before it. */
  private def blankLineBefore(tokens: Vector[TokenStream.PrintableToken], i: Int): Boolean =
    if (i <= 0) false
    else {
      val data = tokens(i).token.src.data
      val gap = data.slice(tokens(i - 1).token.endIndex, tokens(i).token.startIndex)
      gap.count(_ == '\n') > 1
    }

  /** The index of the `=>` belonging to the arm spanning `[start, end)`. */
  private def arrowIndex(
    tokens: Vector[TokenStream.PrintableToken],
    start: Int,
    end: Int
  ): Option[Int] =
    (start until math.min(end, tokens.length))
      .find(i => tokens(i).token.kind == TokenKind.ArrowThickR)

  /**
    * The width the arm's text from `start` up to `arrow` will occupy once printed,
    * measured with the gaps the policy will actually choose.
    */
  private def prefixWidth(
    tokens: Vector[TokenStream.PrintableToken],
    start: Int,
    arrow: Int,
    policy: PrettyPrinter.Separators
  ): Int = {
    var width = 0
    for (i <- start until arrow) {
      if (i > start) {
        val data = tokens(i).token.src.data
        val original = data.slice(tokens(i - 1).token.endIndex, tokens(i).token.startIndex)
          .filter(_.isWhitespace).mkString
        width += policy.between(Some(tokens(i - 1)), Some(tokens(i)), original).length
      }
      width += tokens(i).text.length
    }
    width
  }

  /** The number of leading spaces on the line `token` starts on. */
  private def indentOfLineContaining(token: Token): Int = {
    val data = token.src.data
    val lineStart = data.lastIndexWhere(_ == '\n', token.startIndex - 1) + 1
    data.slice(lineStart, token.startIndex).takeWhile(_ == ' ').length
  }

  /** The indentation unit, per `docs/STYLE.md`: *"Indentation is 4 spaces."* */
  private val IndentUnit: Int = 4

  /**
    * How much wider than its group's narrowest arm a prefix may be and still take
    * part in alignment.
    *
    * A group is a table, and a table stops being readable when one row is far
    * wider than the rest: every other `=>` gets pushed across the screen to
    * accommodate a single long pattern. The value is a judgement rather than a
    * measurement, chosen so ordinary constructor patterns stay in one column while
    * a nested pattern several times their width steps out.
    */
  private val OutlierSlack: Int = 24
}
