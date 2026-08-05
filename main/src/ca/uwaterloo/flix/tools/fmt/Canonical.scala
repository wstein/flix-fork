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

import ca.uwaterloo.flix.language.ast.TokenKind
import ca.uwaterloo.flix.tools.fmt.TokenStream.PrintableToken

/**
  * The canonical separator policy: one spacing per pair of adjacent tokens,
  * chosen from the tokens alone.
  *
  * Choosing from the tokens alone is what makes the result canonical. Two files
  * that differ only in spacing parse to the same tokens and so format
  * identically, which is the property that removes spacing churn from diffs. It
  * is also why no rule here may consult the source's own layout.
  *
  * The policy is deliberately horizontal. A gap that already spans a line is left
  * as it is, so indentation, blank lines, and where a construct breaks are
  * untouched. Those are vertical decisions, they need the surrounding syntax
  * rather than two adjacent tokens, and they are not settled — see
  * `docs/FORMATTER-DECISIONS.md`. Restricting the policy this way means every
  * rule below can be justified from the token pair in front of it.
  *
  * Two spacings are not style at all and are reproduced exactly:
  *
  *   - Around `->`. `a->b` lexes as [[TokenKind.ArrowThinRTight]], struct field
  *     access; `a -> b` lexes as [[TokenKind.ArrowThinRWhitespace]], the function
  *     arrow. Adding a space re-lexes the program into a different one.
  *   - Around `.`. A dot followed by whitespace is [[TokenKind.DotWhiteSpace]],
  *     which terminates a Datalog constraint, and a dot *preceded* by whitespace
  *     is a lexer error rather than a token.
  *
  * String literals are likewise never touched, including the interior of an
  * interpolation, where the whitespace belongs to the program's data.
  */
object Canonical extends PrettyPrinter.Separators {

  /** The canonical mode lays code out vertically as well as horizontally. */
  override def usesLayoutPlan: Boolean = true

  override def between(
    left: Option[PrintableToken],
    right: Option[PrintableToken],
    original: String
  ): String = (left, right) match {
    case (Some(l), Some(r)) =>
      val lk = l.token.kind
      val rk = r.token.kind
      if (isSpacingSensitive(lk) || isSpacingSensitive(rk)) original
      else if (signsLiteral(lk, rk)) original
      else if (original.contains('\n')) original
      else if (spaced(lk, rk)) " "
      else ""

    // The gaps before the first token and after the last are the file's leading
    // and trailing whitespace. Trailing newlines are a vertical concern.
    case _ => original
  }

  /**
    * Returns `true` if `left` is a minus sign attached to the numeric literal
    * `right`, where the spacing decides whether the literal is negative.
    *
    * Separating them is not cosmetic. `-9223372036854775808i64` is `Int64`'s least
    * value and is only representable as a *negative* literal, so `- 9223…i64` is
    * out of range and does not compile. Nothing distinguishes that case from
    * ordinary subtraction in a pair of adjacent tokens, so the source's own
    * spacing is kept for all of them: `-1` stays `-1` and `x - 1` stays `x - 1`.
    */
  private def signsLiteral(left: TokenKind, right: TokenKind): Boolean =
    left == TokenKind.Minus && numericLiteral(right)

  /**
    * Returns `true` if `kind` is a numeric literal.
    *
    * Every width and suffix, not just the unsuffixed forms: a literal written
    * `123i64` or `1.5f32` lexes to its own kind, and the least value of each
    * signed width is the case that makes this matter.
    */
  private[fmt] def numericLiteral(kind: TokenKind): Boolean = kind match {
    case TokenKind.LiteralInt => true
    case TokenKind.LiteralInt8 => true
    case TokenKind.LiteralInt16 => true
    case TokenKind.LiteralInt32 => true
    case TokenKind.LiteralInt64 => true
    case TokenKind.LiteralBigInt => true
    case TokenKind.LiteralFloat => true
    case TokenKind.LiteralFloat32 => true
    case TokenKind.LiteralFloat64 => true
    case TokenKind.LiteralBigDecimal => true
    case _ => false
  }

  /**
    * Returns `true` if the spacing next to `kind` changes how the program lexes
    * or what it means, and must therefore be reproduced rather than chosen.
    */
  private def isSpacingSensitive(kind: TokenKind): Boolean = kind match {
    // Whitespace selects between the struct arrow and the function arrow.
    case TokenKind.ArrowThinRTight => true
    case TokenKind.ArrowThinRWhitespace => true
    // A trailing space makes a dot a Datalog terminator; a leading one is an error.
    case TokenKind.Dot => true
    case TokenKind.DotWhiteSpace => true
    // Whitespace selects between the `@` of a region and an annotation: `@` followed
    // immediately by a name char lexes as one Annotation token, so closing up the
    // space in `new S @ rc` turns two tokens into one and changes the program.
    case TokenKind.At => true
    case TokenKind.Annotation => true
    // A backtick is not symmetric — `x `Int32.mod` 2` is spaced outside the ticks
    // and tight inside — and which tick is which cannot be told from one adjacent
    // pair of tokens.
    case TokenKind.Tick => true
    // Inside an interpolation the spacing sits within a string literal.
    case TokenKind.LiteralStringInterpolationL => true
    case TokenKind.LiteralStringInterpolationR => true
    // Braces open blocks, records, record types, Datalog values and handler
    // bodies, and the corpus spaces them differently in each. Nothing here can
    // tell those apart from two adjacent tokens, and guessing would reformat
    // every brace in the language on no evidence.
    case TokenKind.CurlyL => true
    case TokenKind.CurlyR => true
    case _ => false
  }

  /** Returns `true` if a single space belongs between adjacent tokens of these kinds. */
  private def spaced(left: TokenKind, right: TokenKind): Boolean = {
    if (opensGroup(left)) return false
    if (closesGroup(right)) return false
    if (leadsPunctuation(right)) return false
    if (bindsTightly(left) || bindsTightly(right)) return false
    if (appliesTo(left, right)) return false
    true
  }

  /** Returns `true` if `kind` opens a bracket that its content follows immediately. */
  private def opensGroup(kind: TokenKind): Boolean = kind match {
    case TokenKind.ParenL => true
    case TokenKind.BracketL => true
    case TokenKind.HashParenL => true
    case _ => false
  }

  /** Returns `true` if `kind` closes a bracket that its content precedes immediately. */
  private def closesGroup(kind: TokenKind): Boolean = kind match {
    case TokenKind.ParenR => true
    case TokenKind.BracketR => true
    case _ => false
  }

  /** Returns `true` if `kind` is punctuation that attaches to what precedes it. */
  private def leadsPunctuation(kind: TokenKind): Boolean = kind match {
    case TokenKind.Comma => true
    case TokenKind.Semi => true
    case TokenKind.Colon => true
    case _ => false
  }

  /**
    * Returns `true` if `kind` binds tightly to the token on either side of it.
    *
    * Only record selection qualifies. Tightness here is symmetric, and the
    * collection-literal heads — `Set#{`, `Map#{` and the rest — are not: they are
    * single tokens that need a space from whatever precedes them. Treating them as
    * tight turned `else Set#{ }` into `elseSet#{ }`, welding a keyword to a name,
    * and would have turned `= #{` into `=#{`, which lexes as an operator.
    */
  private def bindsTightly(kind: TokenKind): Boolean = kind match {
    // Record selection: `p1#x`.
    case TokenKind.Hash => true
    case _ => false
  }

  /**
    * Returns `true` if `right` opens an argument or type-argument list applied to
    * `left`, as in `f(x)` and `List[a]`.
    *
    * A bracket following a name is application and binds tightly; the same bracket
    * following a keyword is grouping and does not, which is why `if (c)` keeps its
    * space while `println(x)` does not.
    */
  private def appliesTo(left: TokenKind, right: TokenKind): Boolean =
    (right == TokenKind.ParenL || right == TokenKind.BracketL) && namelike(left)

  /** Returns `true` if `kind` can end a name or a completed expression. */
  private def namelike(kind: TokenKind): Boolean = kind match {
    case TokenKind.NameLowercase => true
    case TokenKind.NameUppercase => true
    case TokenKind.NameMath => true
    case TokenKind.ParenR => true
    case TokenKind.BracketR => true
    case _ => false
  }
}
