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
import org.scalatest.DoNotDiscover

import scala.annotation.tailrec

/**
  * Measures where the corpus's authors break lines in `let` and `if`/`else`,
  * so that D24 can be reopened on evidence rather than on argument.
  *
  * Run with `./mill flix.breakEvidenceReport`. Like [[FormatterDiffReport]] it is
  * `@DoNotDiscover` and asserts nothing: it is an instrument, and the numbers are
  * the deliverable.
  *
  * It answers the two questions D24 left open, and it exists because D24's own
  * account of its evidence is what makes them open:
  *
  *   - **`let`.** D24 reports 98.9% of bindings broken and then disowns the
  *     number: `Expr.LetMatch` spans the binding *and the whole remainder of its
  *     block*, so asking whether that node spans lines asks whether the block
  *     does. The bound expression has to be cut out of the node before anything
  *     can be counted. [[boundExpr]] does that, and [[BoundExpr]] carries the
  *     check that says whether the cut landed where it was meant to — a number
  *     here that is not verified is the exact mistake D24 records.
  *   - **`if`/`else`.** D24 measured one population, found it split 33/67, and
  *     concluded there is no knee. A split is also what two populations look like
  *     when they are added together, so this cross-tabulates the same sites
  *     against whether their branches are braced blocks.
  *
  * Spans are measured over *code* tokens. The parser folds a comment into
  * whichever node's `open` or `close` reaches it first, so a node's outermost
  * tokens can be a comment that says nothing about where the author broke the
  * code; the all-token span is reported alongside for `if`/`else` only, to
  * reconcile the population with D24's.
  */
@DoNotDiscover
class BreakEvidenceReport extends TestFormatterCommon {

  /** How many isolated bound expressions to print for a human to check. */
  private val Verifications = 5

  /** Token-count buckets, as lower bounds. Reported in this order. */
  private val Buckets: List[(String, Int)] =
    List(("1-5", 1), ("6-10", 6), ("11-20", 11), ("21-40", 21), ("40+", 41))

  test("measure where the corpus breaks `let` bindings and `if`/`else`") {
    val samples = ExampleSamples ++ StdlibSamples
    val trees = samples.flatMap { s =>
      try Some((s.path, s.original.tree))
      catch {
        case e: Throwable =>
          println(s"SKIPPED ${s.path}: ${e.getClass.getSimpleName}")
          None
      }
    }

    println()
    println("=" * 78)
    println(s"BREAK EVIDENCE REPORT   files=${trees.size}")
    println("=" * 78)

    reportLet(trees)
    reportIf(trees)
  }

  //////////////////////////////////////////////////////////////////////////////
  /// MEASUREMENT A — `let`                                                   ///
  //////////////////////////////////////////////////////////////////////////////

  /**
    * One `let` binding, with the bound expression cut out of it.
    *
    * `terminatorOk` is the isolation check. `let p = e; rest` parses as
    * `LetMatch(let, p, =, Expr(Statement(e, ;, rest)))`, so the bound expression
    * is the statement's first operand and the very next code token in the binding
    * must be the `;` that ends it. If some other token follows, the cut did not
    * land on the bound expression and the site is excluded rather than counted —
    * a site counted from a span that is not the bound expression is how D24's
    * 98.9% came about.
    */
  private case class BoundExpr(
    path: String,
    line: Int,
    form: String,
    kind: String,
    text: String,
    letText: String,
    tokens: Int,
    broken: Boolean,
    terminatorOk: Boolean
  )

  private def reportLet(trees: List[(String, SyntaxTree.Tree)]): Unit = {
    val lets = trees.flatMap { case (path, tree) =>
      collect(tree, TreeKind.Expr.LetMatch).flatMap(describeLet(path, _))
    }
    val fragments = trees.flatMap { case (path, tree) =>
      collect(tree, TreeKind.Expr.ForFragmentLet).flatMap(describeForLet(path, _))
    }

    println()
    println("-" * 78)
    println("A. `let` — the bound expression, isolated from the block it precedes")
    println("-" * 78)

    val (ok, bad) = lets.partition(_.terminatorOk)
    println()
    println(s"`let` bindings found (Expr.LetMatch):           ${lets.size}")
    println(s"  bound expression isolated and verified:       ${ok.size}")
    println(s"  isolation failed, excluded from every count:  ${bad.size}")
    println(s"comprehension `let` (Expr.ForFragmentLet):      ${fragments.size}")
    println()
    if (bad.isEmpty && lets.nonEmpty) {
      println("ISOLATION: verified. For every binding the code token following the")
      println("bound expression is the `;` that terminates it, so the measured span")
      println("ends at the binding and does not run on into the rest of the block.")
    } else if (lets.isEmpty) {
      println("ISOLATION: FAILED — no bindings found at all. Report nothing from this")
      println("section; the tree kind or the corpus access is wrong.")
    } else {
      val pct = bad.size * 100.0 / lets.size
      println(f"ISOLATION: PARTIAL — $pct%.1f%% of bindings did not end at a `;`.")
      println("Those are excluded. If this share is large the split below is not")
      println("trustworthy and should not be quoted.")
      bad.take(3).foreach(b => println(s"    e.g. ${b.path}:${b.line}  ${oneLine(b.letText, 90)}"))
    }

    println()
    println(s"--- $Verifications isolated spans, for a human to confirm the cut ---")
    verificationSample(ok).foreach { b =>
      println(s"  ${b.path}:${b.line}")
      println(s"    whole LetMatch node : ${oneLine(b.letText, 100)}")
      println(s"    isolated bound expr : ${oneLine(b.text, 100)}")
    }

    println()
    println("--- inline vs broken, over the isolated bound expression ---")
    printSplit("all `let` bindings", ok.map(_.broken))
    println()
    for (form <- ok.map(_.form).distinct.sorted) {
      printSplit(s"  $form", ok.filter(_.form == form).map(_.broken))
    }
    println()
    if (fragments.isEmpty) {
      println("comprehension `let` — no Expr.ForFragmentLet node exists in the corpus,")
      println("so the one `let` form that needs no isolation cannot serve as a control.")
    } else {
      printSplit("comprehension `let` (whole fragment)", fragments.map(_.broken))
    }

    println()
    println("--- bound-expression size vs the decision to break ---")
    printBuckets(ok.map(b => (b.tokens, b.broken)))

    println()
    println("--- what the broken bound expressions are ---")
    println("A `let` break rule can only claim the breaks that are the `let`'s to")
    println("make. A bound expression that is a `match` or a block breaks because of")
    println("the construct inside it, and D20 already decides that layout, so those")
    println("sites must be subtracted before this is read as evidence for a rule.")
    printByKind(ok.map(b => (b.kind, b.broken)))
  }

  /**
    * The bound expression of a `let`, or `None` if the node has no `=` followed by
    * a subtree — which the parser can produce for a binding it could not read.
    */
  private def boundExpr(node: SyntaxTree.Tree): Option[SyntaxTree.Tree] = {
    val kids = node.children.toVector
    val eq = kids.indexWhere {
      case t: Token => t.kind == TokenKind.Equal
      case _ => false
    }
    if (eq < 0) return None
    // The parser wraps `statement()`'s result in `Expr.Expr`, and the statement
    // itself in `Expr.Statement` whose first operand is the bound expression.
    // Without a `;` there is no `Statement`, so the whole right-hand side is it.
    firstSubtree(kids.drop(eq + 1)).map { rhs =>
      val inner = unwrap(rhs)
      if (inner.kind == TreeKind.Expr.Statement) firstSubtree(inner.children.toVector).getOrElse(rhs)
      else rhs
    }
  }

  private def describeLet(path: String, node: SyntaxTree.Tree): Option[BoundExpr] =
    for {
      rhs <- boundExpr(node)
      first <- firstCodeToken(rhs)
      last <- lastCodeToken(rhs)
    } yield {
      val following = codeTokens(node).find(_.startIndex >= last.endIndex)
      BoundExpr(
        path = path,
        line = first.start.lineOneIndexed,
        form = letForm(node),
        kind = shortName(unwrap(rhs).kind),
        text = TokenStream.sourceText(rhs),
        letText = TokenStream.sourceText(node),
        tokens = countCodeTokens(rhs),
        broken = first.start.lineOneIndexed != last.end.lineOneIndexed,
        terminatorOk = following.exists(_.kind == TokenKind.Semi)
      )
    }

  /**
    * A comprehension `let` — the `x = e` fragment of a `forM` — binds without a
    * continuation, so its node already *is* the binding and needs no cutting. It
    * would be the control for the isolation above, if the corpus contained one.
    */
  private def describeForLet(path: String, node: SyntaxTree.Tree): Option[BoundExpr] =
    for {
      first <- firstCodeToken(node)
      last <- lastCodeToken(node)
    } yield BoundExpr(
      path = path,
      line = first.start.lineOneIndexed,
      form = "forFragmentLet",
      kind = "ForFragmentLet",
      text = TokenStream.sourceText(node),
      letText = TokenStream.sourceText(node),
      tokens = countCodeTokens(node),
      broken = first.start.lineOneIndexed != last.end.lineOneIndexed,
      terminatorOk = true
    )

  /**
    * How the binding names its value.
    *
    * There is no `let*` in the surface syntax — monadic binding is `forM`, whose
    * `let` fragment is a separate tree kind — so the only variation `LetMatch`
    * carries is its pattern and an optional type ascription. The pattern's own
    * kind is reported rather than a two-way `simple`/`destructuring` verdict, so
    * that what was counted is legible from the table instead of resting on this
    * function's judgement.
    */
  private def letForm(node: SyntaxTree.Tree): String = {
    val kids = node.children.toVector
    val ascribed = kids.exists {
      case t: Token => t.kind == TokenKind.Colon
      case _ => false
    }
    val pattern = firstSubtree(kids).map(p => shortName(unwrap(p).kind)).getOrElse("none")
    val shape = f"pattern = $pattern%-10s"
    if (ascribed) s"$shape (type-ascribed)" else shape
  }

  /** A `TreeKind`'s case-object name, which is what the parser calls the construct. */
  private def shortName(kind: TreeKind): String =
    kind.toString.split('.').last.stripSuffix("$")

  //////////////////////////////////////////////////////////////////////////////
  /// MEASUREMENT B — `if`/`else`                                             ///
  //////////////////////////////////////////////////////////////////////////////

  /**
    * One `if`, classified by whether each branch is a `{ }` block.
    *
    * The hypothesis under test is that braced and unbraced `if`s are two
    * populations rather than one disagreement, so the classification has to come
    * from the tree: `Expr.Block` is what the parser produces for `{ ... }` and
    * only for it — `isBlockExpr` has already separated a block from a record
    * literal that happens to start with the same character.
    */
  private case class IfSite(
    path: String,
    line: Int,
    hasElse: Boolean,
    thenBraced: Boolean,
    elseBraced: Boolean,
    elseIsIf: Boolean,
    thenKind: String,
    elseKind: String,
    tokens: Int,
    broken: Boolean,
    brokenWithComments: Boolean
  )

  private def reportIf(trees: List[(String, SyntaxTree.Tree)]): Unit = {
    val sites = trees.flatMap { case (path, tree) =>
      collect(tree, TreeKind.Expr.IfThenElse).flatMap(describeIf(path, _))
    }

    println()
    println("-" * 78)
    println("B. `if`/`else` — is the 33/67 split one disagreement or two populations?")
    println("-" * 78)

    val withElse = sites.filter(_.hasElse)
    val noElse = sites.filterNot(_.hasElse)
    println()
    println("--- reconciliation with D24 (1,357 sites; 444 inline / 913 broken) ---")
    printSplit("all Expr.IfThenElse nodes", sites.map(_.broken))
    printSplit("  ... spans measured incl. comments", sites.map(_.brokenWithComments))
    printSplit("with an `else` branch", withElse.map(_.broken))
    printSplit("without an `else` branch", noElse.map(_.broken))

    println()
    println("--- cross-tabulation: branch shape vs the decision to break ---")
    println("(all sites with an `else`; row percentages)")
    println()
    println(f"${"branches"}%-24s ${"inline"}%8s ${"broken"}%8s ${"total"}%8s   inline%%  broken%%")
    val rows = List(
      ("both braced", (s: IfSite) => s.thenBraced && s.elseBraced),
      ("only then braced", (s: IfSite) => s.thenBraced && !s.elseBraced),
      ("only else braced", (s: IfSite) => !s.thenBraced && s.elseBraced),
      ("neither braced", (s: IfSite) => !s.thenBraced && !s.elseBraced)
    )
    for ((label, pred) <- rows) printRow(label, withElse.filter(pred).map(_.broken))
    printRow("TOTAL", withElse.map(_.broken))

    println()
    println("--- `else if` chains (an `if` whose else-branch is itself an `if`) ---")
    val chains = withElse.filter(_.elseIsIf)
    printSplit("chain parents", chains.map(_.broken))
    println()
    println("The same cross-tabulation with chain parents removed, since a chain's")
    println("else-branch is an `if` rather than a body and cannot be braced:")
    println()
    val flat = withElse.filterNot(_.elseIsIf)
    println(f"${"branches"}%-24s ${"inline"}%8s ${"broken"}%8s ${"total"}%8s   inline%%  broken%%")
    for ((label, pred) <- rows) printRow(label, flat.filter(pred).map(_.broken))
    printRow("TOTAL", flat.map(_.broken))

    println()
    println("--- would width discriminate where braces do not? ---")
    println("Token count vs inline/broken, for the `neither braced` row only:")
    printBuckets(withElse.filter(s => !s.thenBraced && !s.elseBraced).map(s => (s.tokens, s.broken)))
    println()
    println("The same with chain parents removed. An `else if` chain is one `if` per")
    println("link and its token count is the whole chain, so leaving them in loads the")
    println("largest bucket with sites the brace test has already accounted for:")
    printBuckets(flat.filter(s => !s.thenBraced && !s.elseBraced).map(s => (s.tokens, s.broken)))
    println()
    println("The same for `both braced`, as a contrast:")
    printBuckets(withElse.filter(s => s.thenBraced && s.elseBraced).map(s => (s.tokens, s.broken)))

    println()
    println("--- what the unbraced branches are, where the site is still broken ---")
    println("The same subtraction as for `let`: a branch that is itself a `match` or")
    println("another `if` breaks because of what is inside it, so those sites are not")
    println("evidence about `if` layout. Then-branch kind, `neither braced` row,")
    println("chain parents excluded:")
    printByKind(flat.filter(s => !s.thenBraced && !s.elseBraced).map(s => (s.thenKind, s.broken)))
    println()
    println("Else-branch kind, same population:")
    printByKind(flat.filter(s => !s.thenBraced && !s.elseBraced).map(s => (s.elseKind, s.broken)))
  }

  private def describeIf(path: String, node: SyntaxTree.Tree): Option[IfSite] = {
    val kids = node.children.toVector
    val parenR = kids.indexWhere {
      case t: Token => t.kind == TokenKind.ParenR
      case _ => false
    }
    val elseAt = kids.indexWhere {
      case t: Token => t.kind == TokenKind.KeywordElse
      case _ => false
    }
    if (parenR < 0) return None
    val thenEnd = if (elseAt < 0) kids.length else elseAt
    val thenBranch = firstSubtree(kids.slice(parenR + 1, thenEnd))
    val elseBranch = if (elseAt < 0) None else firstSubtree(kids.drop(elseAt + 1))

    for {
      first <- firstCodeToken(node)
      last <- lastCodeToken(node)
      allFirst <- firstToken(node)
      allLast <- lastToken(node)
    } yield IfSite(
      path = path,
      line = first.start.lineOneIndexed,
      hasElse = elseBranch.isDefined,
      thenBraced = thenBranch.exists(isBlock),
      elseBraced = elseBranch.exists(isBlock),
      elseIsIf = elseBranch.exists(b => unwrap(b).kind == TreeKind.Expr.IfThenElse),
      thenKind = thenBranch.map(b => shortName(unwrap(b).kind)).getOrElse("none"),
      elseKind = elseBranch.map(b => shortName(unwrap(b).kind)).getOrElse("none"),
      tokens = countCodeTokens(node),
      broken = first.start.lineOneIndexed != last.end.lineOneIndexed,
      brokenWithComments = allFirst.start.lineOneIndexed != allLast.end.lineOneIndexed
    )
  }

  /** Whether a branch is a `{ }` block rather than a bare expression. */
  private def isBlock(branch: SyntaxTree.Tree): Boolean =
    unwrap(branch).kind == TreeKind.Expr.Block

  //////////////////////////////////////////////////////////////////////////////
  /// REPORTING                                                               ///
  //////////////////////////////////////////////////////////////////////////////

  private def printSplit(label: String, decisions: List[Boolean]): Unit = {
    val total = decisions.size
    val broken = decisions.count(identity)
    val inline = total - broken
    if (total == 0) {
      println(f"$label%-40s ${0}%6d")
    } else {
      println(f"$label%-40s $total%6d   inline $inline%6d (${inline * 100.0 / total}%5.1f%%)   broken $broken%6d (${broken * 100.0 / total}%5.1f%%)")
    }
  }

  private def printRow(label: String, decisions: List[Boolean]): Unit = {
    val total = decisions.size
    val broken = decisions.count(identity)
    val inline = total - broken
    if (total == 0) println(f"$label%-24s $inline%8d $broken%8d $total%8d        -       -")
    else println(f"$label%-24s $inline%8d $broken%8d $total%8d   ${inline * 100.0 / total}%6.1f  ${broken * 100.0 / total}%6.1f")
  }

  /**
    * The size distribution crossed with the break decision.
    *
    * A knee shows up here as a bucket where the broken share jumps rather than
    * climbs; a smooth climb is what D23 already found for width across the corpus
    * as a whole, and is the shape that argues against a threshold rule.
    */
  private def printBuckets(observations: List[(Int, Boolean)]): Unit = {
    println()
    println(f"${"tokens"}%-10s ${"inline"}%8s ${"broken"}%8s ${"total"}%8s   broken%%")
    val bounds = Buckets.map(_._2)
    for (((label, lower), i) <- Buckets.zipWithIndex) {
      val upper = bounds.lift(i + 1).getOrElse(Int.MaxValue)
      val inBucket = observations.filter { case (n, _) => n >= lower && n < upper }
      val total = inBucket.size
      val broken = inBucket.count(_._2)
      if (total == 0) println(f"$label%-10s ${0}%8d ${0}%8d ${0}%8d        -")
      else println(f"$label%-10s ${total - broken}%8d $broken%8d $total%8d   ${broken * 100.0 / total}%6.1f")
    }
  }

  /**
    * The break decision broken down by construct, ranked by how many breaks each
    * construct accounts for.
    *
    * This is the subtraction that decides whether a proposed rule has anything
    * left to decide: a construct that already has a layout rule contributes
    * breaks that the proposed rule would not be causing.
    */
  private def printByKind(observations: List[(String, Boolean)]): Unit = {
    val total = observations.size
    val broken = observations.count(_._2)
    println()
    println(f"${"construct"}%-22s ${"inline"}%8s ${"broken"}%8s ${"total"}%8s   broken%%   share of all breaks")
    val groups = observations.groupBy(_._1).toList.sortBy { case (_, os) => -os.count(_._2) }
    for ((kind, os) <- groups.take(10) if os.count(_._2) > 0) {
      val b = os.count(_._2)
      println(f"$kind%-22s ${os.size - b}%8d $b%8d ${os.size}%8d   ${b * 100.0 / os.size}%6.1f   ${b * 100.0 / math.max(1, broken)}%6.1f%%")
    }
    val shown = groups.take(10).filter(_._2.count(_._2) > 0)
    val rest = broken - shown.map(_._2.count(_._2)).sum
    println(f"${"(all other constructs)"}%-22s ${"-"}%8s $rest%8d ${"-"}%8s        -   ${rest * 100.0 / math.max(1, broken)}%6.1f%%")
    println(f"${"TOTAL"}%-22s ${total - broken}%8d $broken%8d $total%8d   ${broken * 100.0 / math.max(1, total)}%6.1f   100.0%%")
  }

  /** Spread the verification samples across the corpus rather than taking the first few. */
  private def verificationSample(all: List[BoundExpr]): List[BoundExpr] =
    if (all.size <= Verifications) all
    else (0 until Verifications).toList.map(i => all(i * all.size / Verifications))

  private def oneLine(s: String, width: Int): String = {
    val flat = s.replace("\r", "").linesIterator.map(_.trim).mkString(" ⏎ ")
    if (flat.length <= width) flat else flat.take(width) + " ..."
  }

  //////////////////////////////////////////////////////////////////////////////
  /// TREE ACCESS                                                             ///
  //////////////////////////////////////////////////////////////////////////////

  /** Every node of `tree` with the given kind, in source order. */
  private def collect(tree: SyntaxTree.Tree, kind: TreeKind): List[SyntaxTree.Tree] = {
    val acc = List.newBuilder[SyntaxTree.Tree]
    def go(node: SyntaxTree.Tree): Unit = {
      if (node.kind == kind) acc += node
      node.children.foreach {
        case child: SyntaxTree.Tree => go(child)
        case _ => ()
      }
    }
    go(tree)
    acc.result()
  }

  /**
    * Strips the marker nodes the parser wraps around every expression and every
    * pattern, so that a subtree can be compared against the kind it actually is.
    *
    * `exprDelimited` closes each expression as `Expr.Expr` and `Pattern.pattern`
    * closes each pattern as `Pattern.Pattern`, so the kind that says what the node
    * *is* always sits one or more levels down. Comparing against the wrapper is
    * how a corpus of `let x = ...` reads as entirely destructuring.
    */
  @tailrec
  private def unwrap(node: SyntaxTree.Tree): SyntaxTree.Tree =
    if (node.kind != TreeKind.Expr.Expr && node.kind != TreeKind.Pattern.Pattern) node
    else subtrees(node.children.toVector) match {
      case single :: Nil => unwrap(single)
      case _ => node
    }

  /** The subtree children of `children`, ignoring the comment runs the parser folds in. */
  private def subtrees(children: Vector[SyntaxTree.Child]): List[SyntaxTree.Tree] =
    children.collect { case t: SyntaxTree.Tree if t.kind != TreeKind.CommentList => t }.toList

  private def firstSubtree(children: Vector[SyntaxTree.Child]): Option[SyntaxTree.Tree] =
    subtrees(children).headOption

  private def firstToken(node: SyntaxTree.Tree): Option[Token] =
    node.children.iterator.flatMap {
      case t: SyntaxTree.Tree => firstToken(t)
      case tok: Token => Some(tok)
      case _ => None
    }.nextOption()

  private def lastToken(node: SyntaxTree.Tree): Option[Token] =
    node.children.reverseIterator.flatMap {
      case t: SyntaxTree.Tree => lastToken(t)
      case tok: Token => Some(tok)
      case _ => None
    }.nextOption()

  private def firstCodeToken(node: SyntaxTree.Tree): Option[Token] =
    node.children.iterator.flatMap {
      case t: SyntaxTree.Tree => firstCodeToken(t)
      case tok: Token if !tok.kind.isComment => Some(tok)
      case _ => None
    }.nextOption()

  private def lastCodeToken(node: SyntaxTree.Tree): Option[Token] =
    node.children.reverseIterator.flatMap {
      case t: SyntaxTree.Tree => lastCodeToken(t)
      case tok: Token if !tok.kind.isComment => Some(tok)
      case _ => None
    }.nextOption()

  private def codeTokens(node: SyntaxTree.Tree): Vector[Token] =
    TokenStream.codeTokens(node)

  private def countCodeTokens(node: SyntaxTree.Tree): Int = {
    var n = 0
    node.children.foreach {
      case t: SyntaxTree.Tree => n += countCodeTokens(t)
      case tok: Token => if (!tok.kind.isComment) n += 1
      case _ => ()
    }
    n
  }
}
