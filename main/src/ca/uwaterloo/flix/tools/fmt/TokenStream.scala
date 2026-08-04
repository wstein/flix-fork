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

import ca.uwaterloo.flix.language.ast.{SyntaxTree, Token, TokenKind}
import ca.uwaterloo.flix.language.ast.SyntaxTree.TreeKind
import ca.uwaterloo.flix.util.InternalCompilerException

/**
  * The tokens of a [[SyntaxTree.Tree]] in source order, and the position of each
  * comment relative to them.
  *
  * A formatter is allowed to move a comment's *characters* — it decides which
  * line and column they land on — but not its *place in the program*. Nothing
  * else in the formatter test suite can tell the two apart:
  *
  *   - Comparing weeded ASTs cannot: comments are not in the weeded AST.
  *   - Comparing output bytes cannot, once output legitimately differs from input.
  *   - Comparing syntax trees structurally cannot, if comment tokens are
  *     normalised away to make the comparison meaningful across layouts.
  *
  * So a comment's place is recorded directly, as an *anchor*: the number of
  * non-comment tokens that precede it. Ordinals rather than source positions,
  * because formatting changes every position by construction, while the
  * non-comment token sequence is exactly what formatting must leave alone.
  */
object TokenStream {

  /**
    * A comment together with its place in the non-comment token sequence.
    *
    * `ordinal` is how many non-comment tokens precede the comment, so the comment
    * sits in the gap between non-comment tokens `ordinal - 1` and `ordinal`.
    * Several comments in the same gap share an ordinal and are ordered among
    * themselves by their position in the containing sequence.
    *
    * `before` and `after` are the kinds of the tokens bounding that gap. They add
    * nothing an ordinal comparison does not already catch; they are recorded so
    * that a failure reads as "moved from after `KeywordDef` to after `Semi`"
    * rather than as two integers.
    *
    * @param kind    the kind of comment: line, block, or doc
    * @param text    the exact text of the comment
    * @param ordinal the number of non-comment tokens preceding the comment
    * @param before  the kind of the nearest preceding non-comment token, if any
    * @param after   the kind of the nearest following non-comment token, if any
    */
  case class CommentAnchor(
    kind: TokenKind,
    text: String,
    ordinal: Int,
    before: Option[TokenKind],
    after: Option[TokenKind]
  )

  /**
    * Every token of `tree`, in source order.
    *
    * The parser puts comments in the tree — [[ca.uwaterloo.flix.language.phase.Parser2]]
    * gathers runs of them into `TreeKind.CommentList` nodes — so they appear here
    * alongside the code tokens. Whitespace does not: the lexer emits no token for
    * it, and the space between two tokens is recoverable only from the source.
    */
  def tokens(tree: SyntaxTree.Tree): Vector[Token] = {
    val acc = Vector.newBuilder[Token]
    var worklist: List[SyntaxTree.Child] = List(tree)
    while (worklist.nonEmpty) {
      worklist match {
        case (t: SyntaxTree.Tree) :: rest =>
          // Prepending the children keeps the traversal depth-first and left-to-right,
          // which is source order. An explicit worklist rather than recursion, since
          // expression chains nest as deeply as the source is long.
          worklist = t.children.foldRight(rest)(_ :: _)
        case (token: Token) :: rest =>
          acc += token
          worklist = rest
        case other :: _ =>
          throw InternalCompilerException(s"Unexpected syntax tree child: $other", tree.loc)
        case Nil =>
          throw InternalCompilerException("Unreachable: worklist is non-empty", tree.loc)
      }
    }
    acc.result()
  }

  /** The tokens of `tree` that are not comments, in source order. */
  def codeTokens(tree: SyntaxTree.Tree): Vector[Token] =
    tokens(tree).filterNot(_.kind.isComment)

  /**
    * A token together with the text a printer must emit for it.
    *
    * These differ, and the difference is a trap. `Token.text` is the slice between
    * the token's own indices, and the lexer deliberately leaves some characters
    * outside those indices: `Lexer.acceptEscapedName` resets the token start past
    * the `$` of an escaped name, so the `$` of `$run` or `x.$and(y)` belongs to no
    * token at all. A printer that concatenated `Token.text` would emit `def run`,
    * renaming a definition to a keyword and changing the program.
    *
    * @param token the token
    * @param text  the text to emit, including anything the lexer left outside it
    */
  case class PrintableToken(token: Token, text: String)

  /**
    * The tokens of `tree` paired with the text each must be printed as.
    *
    * Any non-whitespace character sitting between two tokens is attributed to the
    * following token, which is where the `$` of an escaped name belongs. Emitting
    * these texts in order therefore reproduces every non-whitespace character of
    * the source, which is the property `TestTokenStream` asserts corpus-wide.
    *
    * `tree` must be a whole compilation unit rather than a subtree: the text
    * preceding a token is attributed by scanning back to the previous token, and
    * for the first token that scan starts at the beginning of the source. Use
    * [[sourceText]] to reproduce a subtree.
    */
  def printableTokens(tree: SyntaxTree.Tree): Vector[PrintableToken] = {
    val ts = tokens(tree)
    if (ts.isEmpty) return Vector.empty
    val data = ts.head.src.data
    var cursor = 0
    ts.map { token =>
      val prefix = data.slice(cursor, token.startIndex).filterNot(_.isWhitespace).mkString
      cursor = token.endIndex
      PrintableToken(token, prefix + token.text)
    }
  }

  /**
    * For each token of `tree`, in the order [[tokens]] returns them, whether it
    * belongs to a region the formatter must not lay out.
    *
    * A region is quarantined when it is a declaration whose subtree contains a
    * parse error. The formatter reproduces those tokens' spacing exactly and
    * formats every other declaration in the file normally, so a developer
    * mid-edit gets formatting on everything except the thing under their cursor.
    *
    * The granularity is the declaration because that is the unit the formatter
    * already treats as independent: editing one declaration must not change how
    * another is laid out. If that holds, a broken declaration can be passed
    * through untouched while its siblings are formatted, and idempotence follows
    * for free — each declaration is either formatted and stable, or untouched and
    * trivially stable.
    *
    * A parse error outside any declaration quarantines only the error node
    * itself, since there is no enclosing declaration to fall back to.
    *
    * Note what this deliberately does not do: it takes declaration boundaries
    * from the parser rather than guessing them from indentation or by scanning
    * for a keyword at column zero. A boundary guessed wrongly would produce
    * exactly the destructive, unpredictable rewrite that quarantining exists to
    * prevent.
    */
  def quarantined(tree: SyntaxTree.Tree): Vector[Boolean] = {
    val acc = Vector.newBuilder[Boolean]
    mark(tree, inherited = false, acc)
    acc.result()
  }

  /** Marks every token under `node`, quarantining declarations that contain a parse error. */
  private def mark(
    node: SyntaxTree.Tree,
    inherited: Boolean,
    acc: scala.collection.mutable.Builder[Boolean, Vector[Boolean]]
  ): Unit = {
    val broken = inherited || isErrorTree(node.kind) ||
      (node.kind.isInstanceOf[TreeKind.Decl] && containsError(node))
    node.children.foreach {
      case child: SyntaxTree.Tree => mark(child, broken, acc)
      case _: Token => acc += broken
      case other => throw InternalCompilerException(s"Unexpected syntax tree child: $other", node.loc)
    }
  }

  /** Returns `true` if `kind` is the parser's error node. */
  private def isErrorTree(kind: TreeKind): Boolean = kind match {
    case TreeKind.ErrorTree(_) => true
    case _ => false
  }

  /** Returns `true` if `node` or any node beneath it is a parse error. */
  private def containsError(node: SyntaxTree.Tree): Boolean =
    isErrorTree(node.kind) || node.children.exists {
      case child: SyntaxTree.Tree => containsError(child)
      case _ => false
    }

  /**
    * The exact source text spanned by `tree`, from the start of its first token
    * to the end of its last.
    *
    * The tree's tokens are contiguous in the source — consecutive tokens are
    * separated only by whitespace, which is asserted corpus-wide by
    * `TestTokenStream` — so the span can simply be sliced rather than
    * reassembled. That makes this an exact copy of the original text, comments,
    * spacing and all.
    *
    * This is what a printer emits for a construct it has no layout rule for. A
    * rule that has not been written yet then costs fidelity nothing: the output
    * is the input, and the printer stays total while its coverage grows.
    */
  def sourceText(tree: SyntaxTree.Tree): String = {
    val ts = tokens(tree)
    if (ts.isEmpty) {
      ""
    } else {
      val data = ts.head.src.data
      data.slice(ts.head.startIndex, ts.last.endIndex).mkString
    }
  }

  /**
    * The comments of `tree`, each with its place in the non-comment token sequence.
    *
    * Comparing this between a source and its formatted output is what detects a
    * comment that has been dropped, duplicated, reordered, or moved across a
    * token — the failures that are otherwise silent.
    */
  def commentAnchors(tree: SyntaxTree.Tree): Vector[CommentAnchor] = {
    val all = tokens(tree)
    val acc = Vector.newBuilder[CommentAnchor]
    var ordinal = 0
    var previous: Option[TokenKind] = None
    for (token <- all) {
      if (token.kind.isComment) {
        // The following non-comment token is not known yet, so the anchor is
        // completed once the scan reaches it.
        acc += CommentAnchor(token.kind, token.text, ordinal, previous, None)
      } else {
        ordinal += 1
        previous = Some(token.kind)
      }
    }
    val pending = acc.result()
    val followers = all.filterNot(_.kind.isComment)
    pending.map(a => a.copy(after = followers.lift(a.ordinal).map(_.kind)))
  }
}
