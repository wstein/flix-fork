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
  * What is decided here: indentation of every line, `match` layout and `=>`
  * alignment, struct-field alignment, pipeline breaking, and one Datalog
  * constraint per line.
  *
  * Indentation is the rule with the widest blast radius and the one most easily
  * got subtly wrong; see [[indent]] and [[bodyBegins]] for the two mistakes that
  * a corpus diff caught and that the property tests could not, since both produce
  * output that is wrong but perfectly consistent and idempotent.
  */
object LayoutPlan {

  /** What the printer should put in a gap. */
  sealed trait Gap

  object Gap {

    /** No opinion: the separator policy decides. */
    case object Unspecified extends Gap

    /**
      * Requests that a new item begin here — a declaration, a match arm, a
      * Datalog constraint. How far it is indented is decided by [[indent]].
      */
    case object StartLine extends Gap

    /**
      * Requests that the line continue on the next one — a broken pipeline, a
      * wrapped expression. A continuation is indented one unit past the line that
      * began the item, rather than being treated as a new item at that level.
      */
    case object ContinueLine extends Gap

    /** A line break followed by `indent` spaces, as resolved by [[indent]]. */
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
      } else if (node.kind == TreeKind.Decl.Struct) {
        alignStructFields(node, ranges, tokens, plan, policy)
      } else if (node.kind == TreeKind.Expr.FixpointConstraintSet) {
        planConstraintSet(node, range, ranges, tokens, plan)
      } else if (node.kind == TreeKind.Expr.Binary) {
        planPipeline(node, range, ranges, tokens, plan)
      } else if (node.kind == TreeKind.PredicateAndArity) {
        tightenArity(range, plan)
      } else if (node.kind == TreeKind.Type.Record) {
        alignRecordFields(node, ranges, tokens, plan, policy)
      }
    }
    indent(tokens, ranges, plan)
    plan.toVector
  }

  /**
    * Re-indents every line, one unit per enclosing construct that the line is
    * nested inside.
    *
    * Indentation is *relative to the line the enclosing construct starts on*,
    * never to raw tree depth. The two differ whenever constructs share a line: in
    * `def f(): Int32 = match o {` the arms are nested inside both the definition
    * and the match, but only one line has been opened, so they are indented once.
    * Counting ancestors would indent them twice and drift further with every
    * construct that fits on one line.
    *
    * So each line's indentation is its innermost enclosing construct's line
    * indentation plus one unit. Tokens are visited in order and the indentation
    * computed for each line is remembered, so by the time a line is reached the
    * line it hangs off has already been decided. A closing delimiter is the
    * exception: it returns to the indentation of the construct it closes rather
    * than being indented past it.
    */
  private def indent(
    tokens: Vector[TokenStream.PrintableToken],
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    plan: Array[Gap]
  ): Unit = {
    val openers = indentingAncestors(tokens, ranges)
    val lineIndent = Array.fill(tokens.length)(0)
    // The indentation of the line that began the item currently being written. A
    // continuation hangs one unit off this, not off whatever line precedes it, so
    // a chain of continuations does not stair-step further right with each line.
    var itemIndent = 0

    for (i <- tokens.indices) {
      val startsLine = i > 0 && (plan(i) match {
        case Gap.StartLine | Gap.ContinueLine => true
        case _ => breaksLine(tokens, i)
      })
      if (i == 0) {
        lineIndent(i) = 0
      } else if (!startsLine) {
        lineIndent(i) = lineIndent(i - 1)
      } else if (continues(plan, i)) {
        lineIndent(i) = itemIndent + IndentUnit
        plan(i) = Gap.Break(lineIndent(i))
      } else {
        // A closing delimiter ends the construct it belongs to, so it returns to
        // the indentation of the line that construct opened on. Everything else is
        // one unit inside its innermost enclosing construct.
        val innermost = openers(i).headOption
        lineIndent(i) =
          if (isClosingDelimiter(tokens(i).token.kind)) innermost.map(lineIndent).getOrElse(0)
          else innermost.map(a => lineIndent(a) + IndentUnit).getOrElse(0)
        itemIndent = lineIndent(i)
        plan(i) = Gap.Break(lineIndent(i))
      }
    }
  }

  /**
    * Returns `true` if the line beginning at token `i` continues the previous one
    * rather than starting a new item.
    *
    * Wrapping an expression across lines is not the same as starting a new
    * statement, and indenting both the same way loses the distinction: a broken
    * pipeline would sit at the same column as the `let` it belongs to. A
    * continuation is therefore indented one unit further in.
    *
    * Only a construct rule can say so. Inferring it from the preceding token was
    * tried and was badly wrong: declarations do not end in `;`, so a run of `use`
    * lines read as one long continuation and stair-stepped four columns further
    * right with each line. A line the plan says nothing about keeps being treated
    * as an item, which is the conservative reading.
    */
  private def continues(plan: Array[Gap], i: Int): Boolean = plan(i) == Gap.ContinueLine

  /**
    * For each token, the start indices of the constructs enclosing it, innermost
    * first.
    *
    * A construct counts when it can hold a line of its own: any declaration, and
    * any node that opens a bracket. A node is not counted for the token that opens
    * it, since that token sits on the enclosing line rather than inside.
    */
  private def indentingAncestors(
    tokens: Vector[TokenStream.PrintableToken],
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)]
  ): Array[List[Int]] = {
    val size = tokens.length
    val result = Array.fill(size)(List.empty[Int])
    val spans = scala.collection.mutable.ListBuffer.empty[(Int, Int, Int)]
    ranges.forEach { (node, range) =>
      val (start, end) = range
      bodyBegins(node, start).foreach { opensAt =>
        // Trailing comments are not part of the construct for layout purposes.
        // `Parser2.close` folds a comment that follows a declaration into it, so a
        // comment introducing the *next* declaration ends up as the previous one's
        // last token and would be indented as if it were inside its body.
        var stop = end
        while (stop > start && tokens(stop - 1).token.kind.isComment) stop -= 1
        if (stop - start > 1) spans += ((start, opensAt, stop))
      }
    }
    // Innermost first: a construct whose body opens later is nested more deeply.
    val ordered = spans.toList.sortBy { case (_, opensAt, end) => (-opensAt, end) }
    for (i <- 0 until size) {
      result(i) = ordered.collect { case (start, opensAt, end) if opensAt < i && i < end => start }
    }
    result
  }

  /**
    * The index after which `node` indents, if it indents at all.
    *
    * A construct indents its *body*, not its own header, and the two are easy to
    * confuse because a node starts earlier than it looks: the parser folds a
    * declaration's doc comment and modifiers into it, so `node.start` is the first
    * `///` line rather than the `def`. Indenting everything after `node.start`
    * therefore pushes the construct's own header in by one level, and since that
    * happens at every level of nesting the whole file drifts right — which is
    * exactly what a corpus file showed.
    *
    * The body begins at the construct's opening bracket, or for a declaration with
    * no bracketed body, at the `=` introducing it. Returns `None` for nodes that
    * hold no lines of their own.
    */
  private def bodyBegins(node: SyntaxTree.Tree, start: Int): Option[Int] = {
    var idx = start
    var bracket = -1
    var equals = -1
    var arrow = -1
    node.children.foreach {
      case t: Token =>
        if (bracket < 0 && opensBracket(t.kind)) bracket = idx
        if (equals < 0 && t.kind == TokenKind.Equal) equals = idx
        if (arrow < 0 && t.kind == TokenKind.ArrowThickR) arrow = idx
        idx += 1
      case t: SyntaxTree.Tree => idx += subtreeSize(t)
      case _ => idx += 1
    }
    if (bracket >= 0) Some(bracket)
    else if (equals >= 0 && node.kind.isInstanceOf[TreeKind.Decl]) Some(equals)
    else if (arrow >= 0 && node.kind == TreeKind.Expr.MatchRuleFragment) Some(arrow)
    else None
  }

  /** The number of tokens beneath `node`. */
  private def subtreeSize(node: SyntaxTree.Tree): Int =
    node.children.foldLeft(0) {
      case (acc, t: SyntaxTree.Tree) => acc + subtreeSize(t)
      case (acc, _) => acc + 1
    }

  /** Returns `true` if `kind` opens a bracket whose contents may be indented. */
  private def opensBracket(kind: TokenKind): Boolean = kind match {
    case TokenKind.CurlyL => true
    case TokenKind.BracketL => true
    case TokenKind.ParenL => true
    case TokenKind.HashCurlyL => true
    case _ => false
  }

  /** Returns `true` if `kind` closes a bracket. */
  private def isClosingDelimiter(kind: TokenKind): Boolean = kind match {
    case TokenKind.CurlyR => true
    case TokenKind.BracketR => true
    case TokenKind.ParenR => true
    case _ => false
  }

  /** Returns `true` if the source already put token `i` on a new line. */
  private def breaksLine(tokens: Vector[TokenStream.PrintableToken], i: Int): Boolean = {
    val data = tokens(i).token.src.data
    data.slice(tokens(i - 1).token.endIndex, tokens(i).token.startIndex).exists(_ == '\n')
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
    val (_, end) = range
    val arms = node.children.collect {
      case t: SyntaxTree.Tree if t.kind == TreeKind.Expr.MatchRuleFragment => t
    }.toList
    if (arms.isEmpty) return

    // Each arm starts a line, including the first, which breaks after the `{`.
    // Only *that* a line starts is decided here; `indent` decides how far in.
    for (arm <- arms) {
      val (armStart, _) = ranges.get(arm)
      if (armStart > 0) plan(armStart) = Gap.StartLine
    }

    // The closing brace goes on its own line, and only when the parser actually
    // produced one — on a malformed match there may be none, and inventing it
    // would change the program.
    val lastIdx = end - 1
    if (lastIdx > 0 && lastIdx < tokens.length && tokens(lastIdx).token.kind == TokenKind.CurlyR) {
      plan(lastIdx) = Gap.StartLine
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
    val items = arms.flatMap { arm =>
      val (armStart, armEnd) = ranges.get(arm)
      // The padding goes immediately before the `=>`.
      pivot(tokens, armStart, armEnd, _ == TokenKind.ArrowThickR).map(Alignable(armStart, _))
    }
    alignColumn(items, tokens, plan, policy)
  }

  /**
    * Puts each Datalog constraint of a set on its own line.
    *
    * A constraint is a sentence terminated by `.`, and the corpus and the
    * principles paper both write one per line:
    *
    *     let rules = #{
    *         Path(x, y) :- Edge(x, y).
    *         Path(x, z) :- Path(x, y), Edge(y, z).
    *     };
    *
    * The body atoms of a clause stay on the head's line. The design document
    * proposes breaking a body of two or more atoms, but its own worked example —
    * taken from the paper — writes exactly that inline, and the corpus agrees. The
    * threshold was offered "by analogy, not measurement"; the measurement, such as
    * it is, contradicts it.
    *
    * A set holding a single constraint is left as the author wrote it, so the
    * short inline form `#{ A(123). }` survives.
    */
  private def planConstraintSet(
    node: SyntaxTree.Tree,
    range: (Int, Int),
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap]
  ): Unit = {
    val constraints = node.children.collect {
      case t: SyntaxTree.Tree if t.kind == TreeKind.Expr.FixpointConstraint => t
    }.toList
    if (constraints.sizeIs < 2) return
    for (constraint <- constraints) {
      val (start, _) = ranges.get(constraint)
      if (start > 0) plan(start) = Gap.StartLine
    }
    // The closing brace goes on its own line, as it does for a `match`, and only
    // when the parser produced one.
    val lastIdx = range._2 - 1
    if (lastIdx > 0 && lastIdx < tokens.length && tokens(lastIdx).token.kind == TokenKind.CurlyR) {
      plan(lastIdx) = Gap.StartLine
    }
  }

  /**
    * Breaks a pipeline of two or more stages, one `|>` per line.
    *
    * A single-stage pipeline is a function call wearing pipeline syntax and the
    * corpus never breaks one: 493 occurrences, not one broken. Beyond that the
    * corpus splits — 40% of two-stage pipelines are broken, 71% of three-stage —
    * so a canonical rule has to pick. Breaking at two is the smaller error and the
    * better reading of intent: a pipeline exists to make data flow visible
    * top-to-bottom, which is why the language puts the subject last.
    *
    * The operator leads its continuation line, so the chain reads down the left
    * margin rather than trailing off the right.
    */
  private def planPipeline(
    node: SyntaxTree.Tree,
    range: (Int, Int),
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap]
  ): Unit = {
    val (start, end) = range
    if (!isPipeOperator(node)) {
      // Not a pipeline, but still a binary chain. If the author already broke the
      // line before an operator, that line continues the expression rather than
      // starting a new item, and indenting it as an item flattens `::` chains and
      // the like against the statement they belong to. The break itself is left
      // to the author; only its indentation is decided.
      operatorIndex(node, start).foreach { op =>
        if (op > 0 && op < tokens.length && breaksLine(tokens, op)) plan(op) = Gap.ContinueLine
      }
      return
    }
    // `a |> b |> c` nests to the left, so only the outermost node plans the chain;
    // an inner one would break the same operators again from a shorter span.
    val enclosedByPipeline = ranges.entrySet().stream().anyMatch { entry =>
      val (otherStart, otherEnd) = entry.getValue
      (entry.getKey ne node) && isPipeOperator(entry.getKey) &&
        otherStart <= start && end <= otherEnd
    }
    if (enclosedByPipeline) return

    val stages = (start until math.min(end, tokens.length)).filter(isPipeToken(tokens, _))
    if (stages.sizeIs < 2) return
    for (i <- stages if i > 0) plan(i) = Gap.ContinueLine
  }

  /** The token index of `node`'s operator, if it has one. */
  private def operatorIndex(node: SyntaxTree.Tree, start: Int): Option[Int] = {
    var idx = start
    var found = -1
    node.children.foreach {
      case t: SyntaxTree.Tree =>
        if (found < 0 && t.kind == TreeKind.Operator) found = idx
        idx += subtreeSize(t)
      case _ => idx += 1
    }
    if (found >= 0) Some(found) else None
  }

  /** Returns `true` if `node` is a binary application of `|>`. */
  private def isPipeOperator(node: SyntaxTree.Tree): Boolean =
    node.kind == TreeKind.Expr.Binary && node.children.exists {
      case t: SyntaxTree.Tree if t.kind == TreeKind.Operator =>
        t.children.exists {
          case tok: Token => tok.text == "|>"
          case _ => false
        }
      case _ => false
    }

  /** Returns `true` if token `i` is the `|>` operator. */
  private def isPipeToken(tokens: Vector[TokenStream.PrintableToken], i: Int): Boolean =
    tokens(i).text == "|>"

  /**
    * Pads each field of a struct so the types line up.
    *
    * The corpus writes struct fields as a table, with the padding after the colon:
    *
    *     struct Stack[a, r] {
    *         rc:       Region[r],
    *         mut size: Int32,
    *         mut arr:  Array[a, r]
    *     }
    *
    * Same machinery as the match arms, and for the same reason: a canonical
    * formatter has to *derive* the padding, or two files differing only in it
    * format differently.
    */
  private def alignStructFields(
    node: SyntaxTree.Tree,
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap],
    policy: PrettyPrinter.Separators
  ): Unit = {
    val items = node.children.collect {
      case t: SyntaxTree.Tree if t.kind == TreeKind.StructField => t
    }.toList.flatMap { field =>
      val (fieldStart, fieldEnd) = ranges.get(field)
      // The padding goes after the colon, so the aligned column is the type.
      pivot(tokens, fieldStart, fieldEnd, _ == TokenKind.Colon)
        .map(colon => Alignable(fieldStart, colon + 1))
    }
    alignColumn(items, tokens, plan, policy)
  }

  /**
    * Closes up the `/` of a `Predicate/Arity`.
    *
    * `inject links into Link/2` names a predicate and its arity; the slash is part
    * of the name rather than division. Nothing distinguishes it from `a / b` in a
    * pair of adjacent tokens — both are a name, a slash and a number — so the
    * separator policy spaces it out and only the tree can say otherwise.
    */
  private def tightenArity(range: (Int, Int), plan: Array[Gap]): Unit = {
    val (start, end) = range
    for (i <- math.max(start + 1, 1) until end) plan(i) = Gap.Pad(0)
  }

  /**
    * Pads the fields of a record type so their `=` line up.
    *
    * Record type aliases are written as tables throughout the corpus, in the same
    * spirit as struct fields:
    *
    *     type alias Program = {
    *         classes         = Vector[String],
    *         finalClasses    = Vector[String],
    *         classImplements = Vector[(String, String)]
    *     }
    */
  private def alignRecordFields(
    node: SyntaxTree.Tree,
    ranges: java.util.IdentityHashMap[SyntaxTree.Tree, (Int, Int)],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap],
    policy: PrettyPrinter.Separators
  ): Unit = {
    val items = node.children.collect {
      case t: SyntaxTree.Tree if t.kind == TreeKind.Type.RecordFieldFragment => t
    }.toList.flatMap { field =>
      val (fieldStart, fieldEnd) = ranges.get(field)
      pivot(tokens, fieldStart, fieldEnd, _ == TokenKind.Equal).map(Alignable(fieldStart, _))
    }
    alignColumn(items, tokens, plan, policy)
  }

  /**
    * An item taking part in a column: its first token, and the gap to pad.
    *
    * The width measured for it is everything from `start` up to `gap`, so putting
    * `gap` before the `=>` of a match arm aligns the arrows, and putting it after
    * the colon of a struct field aligns the types.
    */
  private case class Alignable(start: Int, gap: Int)

  /**
    * Pads a set of items so that each one's gap starts at the same column.
    *
    * A group is a run with no blank line between its members, following `gofmt`: a
    * blank line is the one signal an author has that two runs are separate tables.
    * An item far wider than the narrowest in its group opts out, taking a single
    * space and not widening the target, so one long row cannot push the whole
    * column across the screen. An item carrying a comment before its gap is left
    * alone, since its width is not a property of the code.
    */
  private def alignColumn(
    items: List[Alignable],
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap],
    policy: PrettyPrinter.Separators
  ): Unit = {
    // A column only exists down a page. Fields written inline — `{a = 1, b = 2}` —
    // share a line, and padding them apart lines nothing up; it just inserts gaps
    // in the middle of an expression.
    val onOwnLine = items.filter(it => it.start > 0 && startsItsOwnLine(tokens, plan, it.start))
    for (group <- groupByBlankLine(onOwnLine, tokens)) {
      val measured = group
        .filterNot(it => (it.start until it.gap).exists(i => tokens(i).token.kind.isComment))
        .map(it => (it.gap, prefixWidth(tokens, it.start, it.gap, policy)))
      if (measured.sizeIs > 1) {
        val narrowest = measured.map(_._2).min
        val participating = measured.filter(_._2 <= narrowest + OutlierSlack)
        if (participating.sizeIs > 1) {
          val target = participating.map(_._2).max
          for ((gap, width) <- participating) {
            plan(gap) = Gap.Pad(target - width + 1)
          }
        }
      }
    }
  }

  /** The first token in `[start, end)` whose kind satisfies `p`. */
  private def pivot(
    tokens: Vector[TokenStream.PrintableToken],
    start: Int,
    end: Int,
    p: TokenKind => Boolean
  ): Option[Int] =
    (start until math.min(end, tokens.length)).find(i => p(tokens(i).token.kind))

  /**
    * Returns `true` if token `i` will begin a line, either because a rule asked
    * for it or because the source already broke there.
    */
  private def startsItsOwnLine(
    tokens: Vector[TokenStream.PrintableToken],
    plan: Array[Gap],
    i: Int
  ): Boolean = plan(i) == Gap.StartLine || breaksLine(tokens, i)

  /** Splits `items` into runs separated by a blank line in the source. */
  private def groupByBlankLine(
    items: List[Alignable],
    tokens: Vector[TokenStream.PrintableToken]
  ): List[List[Alignable]] = {
    val groups = scala.collection.mutable.ListBuffer.empty[List[Alignable]]
    var current = scala.collection.mutable.ListBuffer.empty[Alignable]
    for (item <- items) {
      if (current.nonEmpty && blankLineBefore(tokens, item.start)) {
        groups += current.toList
        current = scala.collection.mutable.ListBuffer.empty
      }
      current += item
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
