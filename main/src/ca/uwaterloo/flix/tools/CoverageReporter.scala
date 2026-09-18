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
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.runtime.{CoverageProbe, CoverageProbeKind, CoverageSnapshot}
import org.json4s.JsonDSL.*
import org.json4s.JValue
import org.json4s.native.JsonMethods.{compact, render}

/** Deterministic serializers for one immutable coverage snapshot. */
object CoverageReporter {

  def renderJson(snapshot: CoverageSnapshot): String = compact(render(buildJson(snapshot)))

  def renderLcov(snapshot: CoverageSnapshot): String = {
    val sb = new StringBuilder
    probesByFile(snapshot).foreach { case (source, probes) =>
      sb.append("TN:\n")
      sb.append("SF:").append(source).append('\n')

      val functions = probes.filter(_.kind == CoverageProbeKind.Function).sortBy(p => (p.line, p.qualifiedName, p.id))
      functions.foreach(p => sb.append("FN:").append(p.line).append(',').append(p.qualifiedName).append('\n'))
      functions.foreach(p => sb.append("FNDA:").append(snapshot.count(p)).append(',').append(p.qualifiedName).append('\n'))
      sb.append("FNF:").append(functions.size).append('\n')
      sb.append("FNH:").append(functions.count(snapshot.count(_) > 0L)).append('\n')

      val lines = probes.filter(_.kind == CoverageProbeKind.Line).groupBy(_.line).toList.sortBy(_._1)
      lines.foreach { case (line, lineProbes) =>
        sb.append("DA:").append(line).append(',').append(lineProbes.map(snapshot.count).max).append('\n')
      }
      sb.append("LF:").append(lines.size).append('\n')
      sb.append("LH:").append(lines.count { case (_, ps) => ps.exists(snapshot.count(_) > 0L) }).append('\n')

      val branches = probes.filter(_.kind.isBranch).groupBy(_.line).toList.sortBy(_._1)
      var branchTotal = 0
      var branchHit = 0
      branches.foreach { case (line, lineProbes) =>
        lineProbes.sortBy(_.id).zipWithIndex.foreach { case (probe, index) =>
          branchTotal += 1
          val count = snapshot.count(probe)
          val taken = if (count > 0L) {
            branchHit += 1
            count.toString
          } else "-"
          sb.append("BRDA:").append(line).append(",0,").append(index).append(',').append(taken).append('\n')
        }
      }
      sb.append("BRF:").append(branchTotal).append('\n')
      sb.append("BRH:").append(branchHit).append('\n')
      sb.append("end_of_record\n")
    }
    sb.toString()
  }

  def formatSummary(snapshot: CoverageSnapshot): String = {
    val probes = snapshot.session.probes
    val functions = probes.filter(_.kind == CoverageProbeKind.Function)
    val lines = probes.filter(_.kind == CoverageProbeKind.Line)
    val branches = probes.filter(_.kind.isBranch)
    val context = List(
      Option.when(snapshot.partial)("partial"),
      Option.when(snapshot.testFilters.nonEmpty)(s"filtered by ${snapshot.testFilters.mkString(", ")}")
    ).flatten
    val qualifier = if (context.isEmpty) "" else context.mkString(" (", ", ", ")")
    s"Coverage$qualifier: Functions: ${percentage(functions, snapshot)}, " +
      s"Lines: ${percentage(lines, snapshot)}, Branches: ${percentage(branches, snapshot)}"
  }

  private def buildJson(snapshot: CoverageSnapshot): JValue = {
    val probes = snapshot.session.probes
    val functions = probes.filter(_.kind == CoverageProbeKind.Function)
    val lines = probes.filter(_.kind == CoverageProbeKind.Line)
    val branches = probes.filter(_.kind.isBranch)

    val files: List[JValue] = probesByFile(snapshot).map { case (source, fileProbes) =>
      val fileFunctions: List[JValue] = fileProbes.filter(_.kind == CoverageProbeKind.Function)
        .sortBy(p => (p.line, p.qualifiedName, p.id)).toList.map { p =>
          ("name" -> p.qualifiedName) ~ ("line" -> p.line) ~
            ("covered" -> (snapshot.count(p) > 0L)) ~ ("hitCount" -> snapshot.count(p))
        }
      val fileLines: List[JValue] = fileProbes.filter(_.kind == CoverageProbeKind.Line)
        .groupBy(_.line).toList.sortBy(_._1).map { case (line, ps) =>
          val count = ps.map(snapshot.count).max
          ("line" -> line) ~ ("covered" -> (count > 0L)) ~ ("hitCount" -> count)
        }
      val fileBranches: List[JValue] = fileProbes.filter(_.kind.isBranch).sortBy(p => (p.line, p.id)).toList.map { p =>
        ("line" -> p.line) ~ ("kind" -> p.kind.wireName) ~ ("function" -> p.qualifiedName) ~
          ("covered" -> (snapshot.count(p) > 0L)) ~ ("hitCount" -> snapshot.count(p))
      }
      ("path" -> source) ~ ("functions" -> fileFunctions) ~ ("lines" -> fileLines) ~ ("branches" -> fileBranches)
    }

    ("formatVersion" -> 1) ~
      ("partial" -> snapshot.partial) ~
      ("testFilters" -> snapshot.testFilters) ~
      ("summary" -> (
        ("functions" -> totals(functions, snapshot)) ~
          ("lines" -> totals(lines, snapshot)) ~
          ("branches" -> totals(branches, snapshot)))) ~
      ("files" -> files)
  }

  private def probesByFile(snapshot: CoverageSnapshot): List[(String, Vector[CoverageProbe])] =
    snapshot.session.probes.groupBy(_.source).toList.sortBy(_._1).map { case (source, probes) => source -> probes.sortBy(_.id) }

  private def totals(probes: Vector[CoverageProbe], snapshot: CoverageSnapshot): JValue =
    ("covered" -> probes.count(snapshot.count(_) > 0L)) ~ ("total" -> probes.size)

  private def percentage(probes: Vector[CoverageProbe], snapshot: CoverageSnapshot): String = {
    val covered = probes.count(snapshot.count(_) > 0L)
    val percent = if (probes.isEmpty) 0.0 else covered.toDouble * 100.0 / probes.size
    f"$percent%.1f%% ($covered/${probes.size})"
  }
}
