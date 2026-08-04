/*
 * Copyright 2026 Flix Authors
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
package ca.uwaterloo.flix.language.errors

import ca.uwaterloo.flix.language.ast.{SourceLocation, TokenKind}
import ca.uwaterloo.flix.util.Formatter
import org.scalatest.funsuite.AnyFunSuite

/**
  * How a parse error reads to a client that only gets the summary.
  *
  * `summary` is not an abbreviation of `message` -- it is the whole of what an editor shows.
  * `Diagnostic.from` carries it verbatim into `textDocument/publishDiagnostics`, so a token named
  * wrongly here is named wrongly in every LSP client, while the terminal, which renders `message`,
  * looks right. That asymmetry is why this went unnoticed: `>> Expected <name> before '('` in a
  * build log, `Expected <name> before ParenL.` in the IDE, from one error.
  */
class TestParseErrorSummary extends AnyFunSuite {

  private def summaryOf(expected: ParseError.NamedTokenSet, actual: TokenKind): String =
    ParseError.UnexpectedToken(expected, Some(actual), loc = SourceLocation.Unknown).summary

  test("the token found is named as it is written, not as it is spelled in the compiler") {
    assert(summaryOf(ParseError.NamedTokenSet.Parameter, TokenKind.ParenL).contains("'('"))
  }

  test("no summary leaks a TokenKind's Scala name") {
    // The defect in one line: `s"before $a"` where every other message says `a.display`.
    val summary = summaryOf(ParseError.NamedTokenSet.Parameter, TokenKind.ParenL)
    assert(!summary.contains("ParenL"), s"internal token name reached the client: $summary")
  }

  test("the summary and the terminal message name the same token") {
    // The two are written separately, which is how they came to disagree. Neither is the authority
    // on its own; agreeing is what makes either trustworthy.
    val actual = TokenKind.CurlyR
    val summary = summaryOf(ParseError.NamedTokenSet.Expression, actual)
    val message = ParseError
      .UnexpectedToken(ParseError.NamedTokenSet.Expression, Some(actual), loc = SourceLocation.Unknown)
      .message(Formatter.NoFormatter)(None)
    assert(summary.contains(actual.display), s"summary: $summary")
    assert(message.contains(actual.display), s"message: $message")
  }

  test("an error with no token found says nothing about one") {
    assertResult("Expected <expression>.")(
      ParseError.UnexpectedToken(ParseError.NamedTokenSet.Expression, None, loc = SourceLocation.Unknown).summary
    )
  }
}
