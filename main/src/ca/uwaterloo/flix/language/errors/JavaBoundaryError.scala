/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.errors

import ca.uwaterloo.flix.language.{CompilationMessage, CompilationMessageKind}
import ca.uwaterloo.flix.language.ast.{SourceLocation, TypedAst}
import ca.uwaterloo.flix.util.Formatter

/** Shared text, CLI JSON, and LSP diagnostic for experimental API declarations. */
case class JavaBoundaryError(detail: String, loc: SourceLocation, override val locs: List[SourceLocation] = Nil) extends CompilationMessage {
  def kind: CompilationMessageKind = CompilationMessageKind.EntryPointError
  def code: ErrorCode = ErrorCode.E1400
  def summary: String = detail
  protected def message(formatter: Formatter)(implicit root: Option[TypedAst.Root]): String =
    s">> $detail\n\n${Highlighter.highlight(loc, "Java API declaration", formatter)}" +
      locs.map(site => s"\n\nConflicting declaration at ${site.format}:\n${Highlighter.highlight(site, "conflicting declaration", formatter)}").mkString
}
