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
package ca.uwaterloo.flix.api.bsp

import ca.uwaterloo.flix.api.lsp
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.util.Formatter
import ch.epfl.scala.bsp4j.*
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods

import scala.jdk.CollectionConverters.*

/**
  * Turns what the compiler said into what a client shows, and remembers what it was told.
  *
  * ==Why a conversion and not a cast==
  *
  * BSP's diagnostic is structurally LSP's, which is why `CliContract` was careful to make ranges
  * zero-based: they cross a BSP hop untranslated. But `ch.epfl.scala.bsp4j.Diagnostic` is a
  * different JVM type from `org.eclipse.lsp4j.Diagnostic`, so the existing `toLsp4j` cannot be
  * reused and the fields are copied across one at a time.
  *
  * ==Why a ledger==
  *
  * Publishing a diagnostic is easy; making one go away is the part that is usually wrong. A client
  * keeps showing what it was last told about a file, so a file that *stops* having errors needs an
  * explicit empty report -- otherwise the marker stays until the editor is restarted, which is the
  * single most common complaint about build servers. [[DiagnosticLedger]] remembers which documents
  * were reported on so the next compile can clear the ones that no longer apply.
  */
object BspDiagnostics {

  /** What the `source` field of every diagnostic says, so a client can group them. */
  private val Source: String = "flix"

  /**
    * Returns `message` as a protocol diagnostic.
    *
    * ==Why it goes through `toLsp4j`==
    *
    * `lsp.Position` is **one**-indexed: Flix counts lines from one internally, and only `toJSON` and
    * `toLsp4j` subtract to reach the zero-indexed positions LSP and BSP both define. Reading
    * `range.start.line` directly therefore reports every diagnostic one line below where it belongs,
    * which is exactly the mistake this used to make. Converting through `toLsp4j` borrows the
    * subtraction rather than repeating it, so there stays one place where it could be wrong.
    */
  def diagnosticOf(message: CompilationMessage, root: Option[TypedAst.Root]): Diagnostic = {
    val converted = lsp.Diagnostic.from(message, root)
    val zeroBased = converted.toLsp4j.getRange
    val range = new Range(
      new Position(zeroBased.getStart.getLine, zeroBased.getStart.getCharacter),
      new Position(zeroBased.getEnd.getLine, zeroBased.getEnd.getCharacter))

    val diagnostic = new Diagnostic(range, message.summary)
    // Always an error: `CompilationMessage` carries no severity, and `Flix.check` returns nothing
    // else. Reporting a warning here would be inventing one.
    diagnostic.setSeverity(DiagnosticSeverity.ERROR)
    // The stable identifier, `E2136`, and not the category. `lsp.Diagnostic.code` is the *kind* --
    // "Resolution Error" -- which reads well in a problem list and is useless to key on, since
    // hundreds of distinct errors share it. A client suppressing or escalating one specific error
    // needs the code, and `CliContract` draws the same distinction for the same reason.
    diagnostic.setCode(message.code.toString)
    diagnostic.setSource(Source)
    diagnostic.setDataKind(Source)
    // The rendered message, without terminal escapes: `lsp.Diagnostic.from` renders with the ANSI
    // formatter, which an editor strips and anything else pastes verbatim into a report.
    diagnostic.setData(JsonMethods.render(
      ("kind" -> converted.code) ~
        ("fullMessage" -> message.messageWithLoc(Formatter.NoFormatter)(root))))
    diagnostic
  }

  /**
    * Returns one report per document, for the documents `messages` are about.
    *
    * Grouped by URI rather than by `Source`, because the URI is what a client keys on and two
    * sources can map to one document.
    */
  def reportsFor(target: BuildTargetIdentifier,
                 messages: List[CompilationMessage],
                 root: Option[TypedAst.Root]): List[PublishDiagnosticsParams] =
    messages
      .groupBy(m => BspUri.ofSource(m.loc.source))
      .toList
      .sortBy(_._1)
      .map { case (uri, forDocument) =>
        report(target, uri, forDocument.map(diagnosticOf(_, root)))
      }

  /** Returns an empty report for `uri`, which is how a client is told to clear its markers. */
  def clearFor(target: BuildTargetIdentifier, uri: String): PublishDiagnosticsParams =
    report(target, uri, Nil)

  /**
    * `reset = true` on every report, always.
    *
    * It tells the client to replace what it holds for the document rather than add to it. Appending
    * would double every diagnostic on the second compile.
    */
  private def report(target: BuildTargetIdentifier,
                     uri: String,
                     diagnostics: List[Diagnostic]): PublishDiagnosticsParams =
    new PublishDiagnosticsParams(new TextDocumentIdentifier(uri), target, diagnostics.asJava, true)
}

/**
  * Which documents the client has been told about.
  *
  * A client shows what it was last told and nothing else clears it, so the set of documents reported
  * on has to be remembered between compiles: the ones that no longer have diagnostics need an
  * explicit empty report. Without this a fixed error stays on screen, which is the failure a build
  * server is most often blamed for.
  *
  * Not thread-safe on its own; the session serialises compiles.
  */
class DiagnosticLedger {

  /** The documents that carried diagnostics after the last compile. */
  private var reported: Set[String] = Set.empty

  /**
    * Returns everything to publish for a compile that produced `reports`, in order.
    *
    * The empty reports come first so a client that renders as it reads clears before it draws.
    *
    * @param reachedEverySource whether the compiler got far enough to speak for every document it
    *                           spoke for last time. When it did not, nothing is cleared: a document
    *                           left unmentioned by a failed compile has not been shown to be clean,
    *                           and clearing it would hide a real error until the next success.
    */
  def publishFor(reports: List[PublishDiagnosticsParams],
                 target: BuildTargetIdentifier,
                 reachedEverySource: Boolean): List[PublishDiagnosticsParams] = {
    val now = reports.map(_.getTextDocument.getUri).toSet
    val cleared =
      if (reachedEverySource) (reported -- now).toList.sorted.map(BspDiagnostics.clearFor(target, _))
      else Nil

    reported = if (reachedEverySource) now else reported ++ now
    cleared ::: reports
  }

  /** Forgets everything, for a reload that makes the previous report meaningless. */
  def forget(): Unit = reported = Set.empty
}
