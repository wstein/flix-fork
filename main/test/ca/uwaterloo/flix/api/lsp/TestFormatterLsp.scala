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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.tools.fmt.{Canonical, PrettyPrinter, TestFormatterCommon}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/**
  * Tests for the file and offset handling in [[FormatterLsp]].
  *
  * These cover the write path rather than any layout rule: encoding, line
  * offsets, path matching, and the decision of whether to write at all. They are
  * the part of the formatter that can lose data, and they run regardless of what
  * the printer does.
  */
class TestFormatterLsp extends AnyFunSuite {

  private implicit val flix: Flix = new Flix()

  /** Runs `f` on a temporary file containing `content`, deleting it afterwards. */
  private def withTempFile(content: String)(f: Path => Unit): Unit = {
    val path = Files.createTempFile("flix-fmt-", ".flix")
    try {
      Files.write(path, content.getBytes(flix.defaultCharset))
      f(path)
    } finally {
      Files.deleteIfExists(path)
      ()
    }
  }

  /** A [[TextEdit]] replacing the whole of a single-line document with `text`. */
  private def wholeLineEdit(lineLength: Int, text: String): TextEdit =
    TextEdit(Range(Position(1, 1), Position(1, lineLength + 1)), text)

  test("computeLineOffsets: LF line endings") {
    val offsets = FormatterLsp.computeLineOffsets("a\nbb\nccc")
    assert(offsets(0) == 0)
    assert(offsets(1) == 2)
    assert(offsets(2) == 5)
  }

  test("computeLineOffsets: CRLF line endings index the character after the break") {
    // Splitting on "\n" leaves the "\r" attached to the end of the preceding line,
    // so it is counted in that line's length and the offsets stay correct. This is
    // asserted rather than assumed because the opposite is easy to believe.
    val src = "a\r\nbb\r\nccc"
    val offsets = FormatterLsp.computeLineOffsets(src)
    assert(src.charAt(offsets(1)) == 'b')
    assert(src.substring(offsets(2)) == "ccc")
  }

  test("computeLineOffsets: empty source has one line starting at zero") {
    val offsets = FormatterLsp.computeLineOffsets("")
    assert(offsets(0) == 0)
  }

  test("computeLineOffsets: a trailing newline opens a final empty line") {
    val src = "a\n"
    val offsets = FormatterLsp.computeLineOffsets(src)
    assert(offsets(1) == src.length)
  }

  test("applyTextEditsToString: positions are one-indexed") {
    val src = "abcdef"
    val edit = TextEdit(Range(Position(1, 2), Position(1, 4)), "XY")
    assert(FormatterLsp.applyTextEditsToString(src, edit :: Nil) == "aXYdef")
  }

  test("applyTextEditsToString: no edits leaves the source untouched") {
    val src = "def f(): Int32 = 42\n"
    assert(FormatterLsp.applyTextEditsToString(src, Nil) == src)
  }

  test("applyTextEditsToString: multiple edits do not disturb each other's positions") {
    val src = "one two three"
    val edits = List(
      TextEdit(Range(Position(1, 1), Position(1, 4)), "1"),
      TextEdit(Range(Position(1, 9), Position(1, 14)), "3")
    )
    assert(FormatterLsp.applyTextEditsToString(src, edits) == "1 two 3")
  }

  test("normalizePathText: equivalent spellings of a path compare equal") {
    assert(FormatterLsp.normalizePathText("./src/Main.flix") ==
      FormatterLsp.normalizePathText("src/Main.flix"))
    assert(FormatterLsp.normalizePathText("src/../src/Main.flix") ==
      FormatterLsp.normalizePathText("src/Main.flix"))
  }

  test("normalizePathText: distinct paths stay distinct") {
    assert(FormatterLsp.normalizePathText("src/Main.flix") !=
      FormatterLsp.normalizePathText("test/Main.flix"))
  }

  test("applyTextEditsToFile: a run producing no edits does not write the file") {
    withTempFile("def f(): Int32 = 42\n") { path =>
      val before = Files.getLastModifiedTime(path)
      val wrote = FormatterLsp.applyTextEditsToFile(path, Nil)
      assert(!wrote, "a no-op format must not rewrite the file")
      assert(Files.getLastModifiedTime(path) == before)
    }
  }

  test("applyTextEditsToFile: an edit producing identical text does not write the file") {
    val content = "abc"
    withTempFile(content) { path =>
      val identity = wholeLineEdit(content.length, content)
      assert(!FormatterLsp.applyTextEditsToFile(path, identity :: Nil))
    }
  }

  test("applyTextEditsToFile: a real edit is written") {
    withTempFile("abc") { path =>
      assert(FormatterLsp.applyTextEditsToFile(path, wholeLineEdit(3, "xyz") :: Nil))
      assert(Files.readString(path) == "xyz")
    }
  }

  test("applyTextEditsToFile: non-ASCII content survives a round trip") {
    // The file is decoded and re-encoded with the same charset. Reading with one
    // and writing with another would mangle these characters silently.
    val content = "/// π ≤ ∞ — 日本語\ndef f(): Int32 = 1"
    withTempFile(content) { path =>
      val edit = TextEdit(Range(Position(2, 1), Position(2, 4)), "def")
      val _ = FormatterLsp.applyTextEditsToFile(path, edit :: Nil)
      assert(new String(Files.readAllBytes(path), flix.defaultCharset) == content)
    }
  }

  test("minimalEdits: identical text needs no edit at all") {
    assert(FormatterLsp.minimalEdits("def f(): Int32 = 1\n", "def f(): Int32 = 1\n").isEmpty)
  }

  test("minimalEdits: a change on one line touches only that line") {
    val before = "line one\nline  two\nline three\n"
    val after = "line one\nline two\nline three\n"
    val edits = FormatterLsp.minimalEdits(before, after)
    assert(edits.sizeIs == 1)
    val range = edits.head.range
    assert(range.start.line == 2, s"starts on line 2, got ${range.start}")
    assert(range.end.line == 2, s"ends on line 2, got ${range.end}")
  }

  test("minimalEdits: an insertion is an empty range, not a replacement") {
    val edits = FormatterLsp.minimalEdits("ab", "axb")
    assert(edits.sizeIs == 1)
    val edit = edits.head
    assert(edit.newText == "x", s"inserts only the new text, got '${edit.newText}'")
    assert(edit.range.start == edit.range.end, s"empty range, got ${edit.range}")
  }

  test("minimalEdits: a deletion has empty new text") {
    val edits = FormatterLsp.minimalEdits("axb", "ab")
    assert(edits.sizeIs == 1)
    assert(edits.head.newText.isEmpty, s"deletes, got '${edits.head.newText}'")
  }

  test("minimalEdits: a shared prefix and suffix that overlap do not run backwards") {
    // "aaa" -> "aa" shares "aa" at both ends of a two-character string. Counting
    // both would produce a range whose end precedes its start.
    for ((before, after) <- List(("aaa", "aa"), ("aa", "aaa"), ("", "x"), ("x", ""))) {
      val edits = FormatterLsp.minimalEdits(before, after)
      for (edit <- edits) {
        val s = edit.range.start
        val e = edit.range.end
        val ordered = s.line < e.line || (s.line == e.line && s.character <= e.character)
        assert(ordered, s"'$before' -> '$after' produced a backwards range ${edit.range}")
      }
      assert(FormatterLsp.applyTextEditsToString(before, edits) == after,
        s"'$before' -> '$after' did not round-trip")
    }
  }

  test("minimalEdits: applying them reproduces the target exactly") {
    // The property that matters: whatever region the edit picks, applying it has
    // to yield the formatted text. An edit that is minimal but wrong is worse
    // than a whole-document replacement.
    val cases = List(
      ("def f(): Int32 = 1\n", "def g(): Int32 = 2\n"),
      ("a\nb\nc\n", "a\nB\nc\n"),
      ("", "def f(): Int32 = 1\n"),
      ("def f(): Int32 = 1\n", ""),
      ("mod M {\nf\n}\n", "mod M {\n    f\n}\n"),
      ("trailing\n\n\n", "trailing\n")
    )
    for ((before, after) <- cases) {
      val edits = FormatterLsp.minimalEdits(before, after)
      assert(FormatterLsp.applyTextEditsToString(before, edits) == after,
        s"round trip failed for '$before' -> '$after'")
    }
  }

  test("minimalEdits: applying them reproduces canonical formatting across the corpus") {
    // The end-to-end guarantee an editor depends on. Whatever region the edit
    // picks, applying it to the buffer has to produce exactly what the formatter
    // would have written; a minimal edit that is subtly wrong corrupts the file
    // rather than merely formatting it badly.
    for (sample <- TestFormatterCommon.ExampleSamples) {
      val formatted = PrettyPrinter.format(sample.original.tree, Canonical)
      val edits = FormatterLsp.minimalEdits(sample.content, formatted)
      assert(FormatterLsp.applyTextEditsToString(sample.content, edits) == formatted,
        s"applying the edits did not reproduce the formatting of ${sample.path}")
      if (sample.content == formatted) {
        assert(edits.isEmpty, s"an already-formatted file should need no edit: ${sample.path}")
      }
    }
  }

  test("applyTextEditsToFile: a missing file is rejected") {
    val missing = Path.of("does-not-exist-", "Main.flix")
    assertThrows[IllegalArgumentException] {
      FormatterLsp.applyTextEditsToFile(missing, Nil)
    }
  }
}
