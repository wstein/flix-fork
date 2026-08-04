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

import org.scalatest.DoNotDiscover

/**
  * Reports what `flix format --canonical` would do to the corpus, ranked by how
  * much of each file it rewrites.
  *
  * Run with `./mill flix.formatterDiffReport`. It is `@DoNotDiscover` because it
  * asserts nothing and takes a minute: it is an instrument, not a test.
  *
  * It exists because the automated properties cannot see this class of defect and
  * repeatedly did not. Fidelity, idempotence and comment-anchor checks establish
  * that formatting destroys nothing; they are all satisfied by output that is
  * consistently, reproducibly wrong. Every layout defect found in this subsystem
  * so far — a whole file drifting one level right, comments indented into the
  * previous declaration's body, predicate arities spaced out like division,
  * inline records padded into columns, continuations stair-stepping four columns
  * per line — passed all three and was caught by reading this ranking.
  *
  * So: run it after any change to `LayoutPlan` or `Canonical`, and read the top
  * of the list. A construct that is being reformatted wholesale shows up as a
  * file whose every line changes, and the two worst offenders in the history of
  * this subsystem were both visible in the first three entries.
  */
@DoNotDiscover
class FormatterDiffReport extends TestFormatterCommon {

  /** How many entries to show, and how many changed lines to sample from each. */
  private val Worst = 15
  private val Sample = 4

  test("report what canonical formatting would change across the corpus") {
    val samples = ExampleSamples ++ StdlibSamples
    val rows = samples.flatMap { s =>
      try {
        val after = PrettyPrinter.format(s.original.tree, Canonical)
        Some(Row(s.path, s.content, after))
      } catch {
        case e: Throwable =>
          println(s"SKIPPED ${s.path}: ${e.getClass.getSimpleName}")
          None
      }
    }

    val changed = rows.filter(_.changedLines > 0)
    println()
    println(s"files=${rows.size}  changed=${changed.size}  unchanged=${rows.size - changed.size}")
    println(s"totalChangedLines=${rows.map(_.changedLines).sum}  totalLines=${rows.map(_.totalLines).sum}")
    println()
    println(s"=== $Worst files by proportion of lines rewritten ===")
    for (row <- rows.sortBy(r => -r.proportion).take(Worst)) {
      println(f"${row.path}%-54s ${row.changedLines}%5d / ${row.totalLines}%5d  ${row.proportion * 100}%5.1f%%")
      row.samples(Sample).foreach { case (before, after) =>
        println(s"    - $before")
        println(s"    + $after")
      }
    }
    println()
    println("A file rewritten almost entirely usually means a rule is firing where")
    println("it should not, rather than that the file was badly formatted.")
  }

  /** One corpus file, before and after formatting. */
  private case class Row(path: String, before: String, after: String) {

    private val beforeLines: Vector[String] = before.linesIterator.toVector

    private val afterLines: Vector[String] = after.linesIterator.toVector

    /**
      * The number of lines that differ.
      *
      * When the two differ in length the comparison is no longer line-for-line, so
      * this reports the longer of the two rather than pretending to diff them. The
      * report ranks candidates for a human to read; it is not a diff algorithm.
      */
    val changedLines: Int =
      if (beforeLines.length != afterLines.length) math.max(beforeLines.length, afterLines.length)
      else beforeLines.zip(afterLines).count { case (x, y) => x != y }

    val totalLines: Int = beforeLines.length

    def proportion: Double = changedLines.toDouble / math.max(1, totalLines)

    /** Up to `n` differing line pairs, for eyeballing what the change looks like. */
    def samples(n: Int): Vector[(String, String)] =
      beforeLines.zip(afterLines).filter { case (x, y) => x != y }.take(n)
  }
}
