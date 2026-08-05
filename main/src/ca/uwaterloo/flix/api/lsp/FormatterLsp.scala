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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.SyntaxTree
import ca.uwaterloo.flix.tools.fmt.{PrettyPrinter, TokenStream}
import ca.uwaterloo.flix.util.Result

import java.nio.file.{Files, InvalidPathException, Path, Paths}

/**
  * The FormatterLsp object provides functionality to format Flix source files using the [[PrettyPrinter]].
  */
object FormatterLsp {

  /**
    * Formats the files at the given source paths using [[PrettyPrinter]].
    * A file that is already formatted is left alone.
    *
    * @param root        the syntax tree root
    * @param sourcePaths the list of source file paths
    * @param separators  the layout policy deciding the gap between tokens
    */
  def formatFiles(
    root: SyntaxTree.Root,
    sourcePaths: List[Path],
    separators: PrettyPrinter.Separators
  )(implicit flix: Flix): Unit = {
    sourcePaths.foreach { path =>
      findTreeBasedOnUri(root, path.toString).foreach { tree =>
        val _ = applyTextEditsToFile(path, treeToTextEdits(tree, separators))
      }
    }
  }

  /**
    * Formats the given syntax tree for the given URI using [[PrettyPrinter]].
    *
    * @param root       the syntax tree root
    * @param uri        the file URI
    * @param separators the layout policy deciding the gap between tokens
    * @return the edits turning the document into its formatted form, empty if none are needed
    */
  def format(
    root: SyntaxTree.Root,
    uri: String,
    separators: PrettyPrinter.Separators = PrettyPrinter.Separators.Verbatim
  ): List[TextEdit] =
    findTreeBasedOnUri(root, uri).map(treeToTextEdits(_, separators)).getOrElse(Nil)

  /**
    * Formats `tree` and returns the edits that turn its document into the result.
    *
    * The edit covers only the part that actually changed. Replacing the whole
    * document would be simpler and is what this did first, but an editor applies
    * an edit literally: a full-buffer replacement collapses undo granularity,
    * moves the caret, and resets folding, even when formatting changed one line.
    * A document that is already formatted yields no edits at all.
    */
  private def treeToTextEdits(
    tree: SyntaxTree.Tree,
    separators: PrettyPrinter.Separators
  ): List[TextEdit] = {
    val formatted = PrettyPrinter.format(tree, separators)
    documentText(tree) match {
      case None => Nil
      case Some(original) => minimalEdits(original, formatted)
    }
  }

  /**
    * The edits that turn `before` into `after`, as one replacement of the region
    * that differs.
    *
    * The region is found by trimming the common prefix and suffix, so an edit that
    * reindents one line touches one line. It is a single contiguous replacement
    * rather than a hunk per change: when a file has changes far apart, the edit
    * spans the gap between them. That is a deliberate limit — it keeps this
    * obviously correct, and the case it costs (scattered changes) is the case
    * where a large edit was going to happen anyway, while the case it wins
    * (someone edits a line and saves) is the common one.
    */
  private[lsp] def minimalEdits(before: String, after: String): List[TextEdit] = {
    if (before == after) return Nil

    val maxCommon = math.min(before.length, after.length)
    var prefix = 0
    while (prefix < maxCommon && before.charAt(prefix) == after.charAt(prefix)) prefix += 1

    // The prefix and suffix may not overlap, or the replaced region would run
    // backwards: "aaa" -> "aa" shares "aa" at both ends of a two-character string.
    var suffix = 0
    while (
      suffix < maxCommon - prefix &&
        before.charAt(before.length - 1 - suffix) == after.charAt(after.length - 1 - suffix)
    ) suffix += 1

    val range = Range(
      offsetToPosition(before, prefix),
      offsetToPosition(before, before.length - suffix)
    )
    List(TextEdit(range, after.substring(prefix, after.length - suffix)))
  }

  /**
    * The one-indexed line and character of `offset` in `text`, as LSP counts them
    * in this codebase (see [[Position]], whose fields are one-indexed).
    */
  private def offsetToPosition(text: String, offset: Int): Position = {
    var line = 1
    var lineStart = 0
    var i = 0
    while (i < offset) {
      if (text.charAt(i) == '\n') {
        line += 1
        lineStart = i + 1
      }
      i += 1
    }
    Position(line, offset - lineStart + 1)
  }

  /** The full text of the source `tree` was parsed from. */
  private def documentText(tree: SyntaxTree.Tree): Option[String] =
    TokenStream.tokens(tree).headOption.map(_.src.data.mkString)

  /**
    * Applies the given text edits to the file at the specified path.
    *
    * The file is decoded and re-encoded with the same charset, so that formatting
    * a file never transcodes it as a side effect. A file whose edited content
    * equals its current content is left alone rather than rewritten with identical
    * bytes, which keeps a run that changes nothing from touching every timestamp
    * in the project.
    *
    * @param path  the file path
    * @param edits the list of text edits to apply
    * @return true if the file was rewritten, false if it was already up to date
    */
  private[lsp] def applyTextEditsToFile(path: Path, edits: List[TextEdit])(implicit flix: Flix): Boolean = {
    isValidPath(path) match {
      case Result.Err(e: Throwable) => throw e
      case Result.Ok(()) =>
        val charset = flix.defaultCharset
        val src = new String(Files.readAllBytes(path), charset)
        val updated = applyTextEditsToString(src, edits)
        if (updated == src) {
          false
        } else {
          Files.write(path, updated.getBytes(charset))
          true
        }
    }
  }

  /**
    * Applies the given text edits to the source string.
    * Edits are applied in reverse order to maintain correct positions.
    *
    * @param src   the source string
    * @param edits the list of text edits to apply
    * @return the updated source string after applying the edits
    */
  private[lsp] def applyTextEditsToString(src: String, edits: List[TextEdit]): String = {
    val sortedEdits = edits.sortBy(e => (e.range.start.line, e.range.start.character)).reverse
    val sb = new StringBuilder(src)
    val lineOffsets = computeLineOffsets(src)

    for (edit <- sortedEdits) {
      val start =
        lineOffsets(edit.range.start.line - 1) + (edit.range.start.character - 1)

      val endLineIdx = math.min(edit.range.end.line - 1, lineOffsets.length - 1)
      val end = math.min(
        lineOffsets(endLineIdx) + (edit.range.end.character - 1),
        sb.length()
      )

      sb.replace(start, end, edit.newText)
    }

    sb.toString()
  }

  /**
    * Computes the starting offsets of each line in the source string.
    *
    * @param src the source string
    * @return an array of line starting offsets
    */
  private[lsp] def computeLineOffsets(src: String): Array[Int] = {
    val lines = src.split("\n", -1)
    val offsets = new Array[Int](lines.length + 1)
    var currentOffset = 0
    for (i <- lines.indices) {
      offsets(i) = currentOffset
      currentOffset += lines(i).length + 1
    }
    offsets(lines.length) = currentOffset
    offsets
  }

  /**
    * Finds the syntax tree corresponding to the given URI.
    *
    * Sources are keyed by the path text they were added under, which need not be
    * spelled the way the caller spells it: `./src/Main.flix` and `src/Main.flix`
    * name the same file. Both sides are normalised before comparison, since a
    * mismatch here is silent — the file is simply skipped and no diagnostic says why.
    *
    * @param root the syntax tree root
    * @param uri  the file path of the syntax tree
    * @return an option containing the syntax tree if found
    */
  private[lsp] def findTreeBasedOnUri(root: SyntaxTree.Root, uri: String): Option[SyntaxTree.Tree] = {
    val target = normalizePathText(uri)
    root.units.collectFirst {
      case (source, tree) if normalizePathText(source.toString) == target => tree
    }
  }

  /**
    * Normalises path text for comparison, removing redundant `.` and `..` segments.
    *
    * Falls back to the original text for names that are not valid paths, since a
    * source may be a virtual file whose name is not a path at all.
    */
  private[lsp] def normalizePathText(text: String): String =
    try Paths.get(text).normalize().toString
    catch { case _: InvalidPathException => text }

  /**
    * Validate that the given path can exist, is a regular file and is readable.
    *
    * @param path the file path
    * @return a result indicating success or an illegal argument exception
    */
  private def isValidPath(path: Path): Result[Unit, IllegalArgumentException] = {
    if (path == null) {
      return Result.Err(new IllegalArgumentException(s"'p' must be non-null."))
    }
    val pNorm = path.normalize()
    if (!Files.exists(pNorm)) {
      return Result.Err(new IllegalArgumentException(s"'$pNorm' must be a file."))
    }
    if (!Files.isRegularFile(pNorm)) {
      return Result.Err(new IllegalArgumentException(s"'$pNorm' must be a regular file."))
    }
    if (!Files.isReadable(pNorm)) {
      return Result.Err(new IllegalArgumentException(s"'$pNorm' must be a readable file."))
    }
    Result.Ok(())
  }
}
