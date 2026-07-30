/*
 * Copyright 2024
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

import ca.uwaterloo.flix.runtime.Coverage
import org.json4s.{JObject, JValue}
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{compact, render}

import java.nio.file.{Files, Path}
import scala.collection.immutable.ListMap

/**
  * Generates coverage reports from collected coverage data.
  */
object CoverageReporter {

  /**
    * Generate and write a JSON coverage report.
    *
    * @param outputPath the path to write the report to.
    */
  def writeJsonReport(outputPath: Path): Unit = {
    // Create parent directories if needed
    val parentDir = outputPath.getParent
    if (parentDir != null && !Files.exists(parentDir)) {
      Files.createDirectories(parentDir)
    }

    // Get the snapshot of coverage data and ALL registered metadata
    val (metadata, snapshot) = Coverage.reportSnapshot()

    // Filter out synthetic/unknown locations (use loc.isReal check during registration instead)
    // Organize ALL probes by file (including zero-hit)
    val coverageByFile: Map[String, List[(Int, Int, String, String)]] = metadata.toList.map {
      case (probeId, pm) => (pm.source, (probeId, pm.line, pm.kind.asString, pm.qualifiedName))
    }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

    // Calculate summary statistics from all probes
    val functionProbes = metadata.values.count(_.kind.asString == "function")
    val lineProbes = metadata.values.count(_.kind.asString == "line")
    val branchProbes = metadata.values.count(_.kind.asString.startsWith("branch"))

    val functionCovered = metadata.count {
      case (probeId, pm) =>
        pm.kind.asString == "function" && snapshot.contains(probeId)
    }
    val lineCovered = metadata.count {
      case (probeId, pm) =>
        pm.kind.asString == "line" && snapshot.contains(probeId)
    }
    val branchCovered = metadata.count {
      case (probeId, pm) =>
        pm.kind.asString.startsWith("branch") && snapshot.contains(probeId)
    }

    // Build the JSON report with deterministic, sorted output
    val files: List[JValue] = coverageByFile.toList.sortBy(_._1).map {
      case (path, probes) =>
        // Line status computed from ProbeKind.Line probes ONLY
        val lines: Map[String, Boolean] = ListMap.from(probes
          .filter(_._3 == "line") // Only ProbeKind.Line probes determine line coverage
          .groupBy(_._2)
          .toList
          .sortBy(_._1)
          .map { case (line, lineProbes) =>
              val covered = lineProbes.map(_._1).exists(snapshot.contains)
            line.toString -> covered
          })

        val branches: List[JValue] = probes
          .filter(_._3.startsWith("branch"))
          .groupBy(_._2)
          .toList
          .sortBy(_._1)
          .map {
            case (line, lineProbes) =>
              // A source line can have multiple probes of the same kind, e.g. two
              // match rules written on one line. Use a list of probe records rather
              // than a map keyed by kind so every compiled branch remains visible.
              val branchCoverage: List[JValue] = lineProbes.sortBy(_._1).map {
                case (probeId, _, kind, qualifiedName) =>
                  ("id" -> probeId) ~
                    ("kind" -> kind) ~
                    ("covered" -> snapshot.contains(probeId)) ~
                    ("function" -> qualifiedName)
              }
              ("line" -> line) ~ ("branches" -> branchCoverage)
          }

        val functionsList: List[JValue] = probes
          .filter(_._3 == "function")
          .sortBy(_._1)
          .map { case (probeId, line, _, qualifiedName) =>
            ("qualifiedName" -> qualifiedName) ~
              ("source" -> path) ~
              ("line" -> line) ~
              ("covered" -> snapshot.contains(probeId)) ~
              ("hitCount" -> snapshot.getOrElse(probeId, 0L))
          }

        ("path" -> path) ~
          ("functionsCount" -> probes.filter(_._3 == "function").length) ~
          ("functions" -> functionsList) ~
          ("lines" -> lines) ~
          ("branches" -> branches)
    }

    val report: JValue =
      ("summary" -> (
        ("functions" -> (("covered" -> functionCovered) ~ ("total" -> functionProbes))) ~
        ("lines" -> (("covered" -> lineCovered) ~ ("total" -> lineProbes))) ~
        ("branches" -> (("covered" -> branchCovered) ~ ("total" -> branchProbes)))
      )) ~
      ("files" -> files)

    // Write the report to file with explicit UTF-8 encoding
    val jsonString = compact(render(report))
    Files.write(outputPath, jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }

  /**
    * Generate and write an LCOV tracefile report (`.info`).
    *
    * @param outputPath the path to write the LCOV report to.
    */
  def writeLcovReport(outputPath: Path): Unit = {
    val parentDir = outputPath.getParent
    if (parentDir != null && !Files.exists(parentDir)) {
      Files.createDirectories(parentDir)
    }

    val (metadata, snapshot) = Coverage.reportSnapshot()

    val coverageByFile = metadata.toList.map {
      case (probeId, pm) => (pm.source, (probeId, pm.line, pm.kind, pm.qualifiedName))
    }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

    val sb = new java.lang.StringBuilder()

    coverageByFile.toList.sortBy(_._1).foreach { case (sourcePath, probes) =>
      sb.append("TN:\n")
      sb.append(s"SF:$sourcePath\n")

      // Functions
      val funcProbes = probes.filter(_._3 == ca.uwaterloo.flix.runtime.ProbeKind.Function)
      val funcHitCount = funcProbes.count { case (id, _, _, _) => snapshot.contains(id) }

      funcProbes.sortBy(_._2).foreach { case (_, line, _, name) =>
        sb.append(s"FN:$line,$name\n")
      }
      funcProbes.sortBy(_._2).foreach { case (id, _, _, name) =>
        val hits = snapshot.getOrElse(id, 0L)
        sb.append(s"FNDA:$hits,$name\n")
      }
      sb.append(s"FNF:${funcProbes.size}\n")
      sb.append(s"FNH:$funcHitCount\n")

      // Lines
      val lineProbesByLine = probes.filter(_._3 == ca.uwaterloo.flix.runtime.ProbeKind.Line).groupBy(_._2)
      var lineHitCount = 0

      lineProbesByLine.toList.sortBy(_._1).foreach { case (line, lineProbes) =>
        val hits = lineProbes.map(_._1).map(id => snapshot.getOrElse(id, 0L)).foldLeft(0L)(_ max _)
        if (hits > 0) lineHitCount += 1
        sb.append(s"DA:$line,$hits\n")
      }
      sb.append(s"LF:${lineProbesByLine.size}\n")
      sb.append(s"LH:$lineHitCount\n")

      // Branches
      val branchProbesByLine = probes.filter(_._3.asString.startsWith("branch")).groupBy(_._2)
      var branchTotalCount = 0
      var branchHitCount = 0

      branchProbesByLine.toList.sortBy(_._1).foreach { case (line, branchProbes) =>
        branchProbes.sortBy(_._1).zipWithIndex.foreach { case ((id, _, _, _), idx) =>
          branchTotalCount += 1
          val hits = snapshot.get(id)
          val hitStr = hits match {
            case Some(h) =>
              branchHitCount += 1
              h.toString
            case None => "-"
          }
          sb.append(s"BRDA:$line,0,$idx,$hitStr\n")
        }
      }
      sb.append(s"BRF:$branchTotalCount\n")
      sb.append(s"BRH:$branchHitCount\n")

      sb.append("end_of_record\n")
    }

    Files.write(outputPath, sb.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }

  /**
    * Return a formatted terminal summary string for collected coverage data.
    */
  def formatSummary(): String = {
    val (metadata, snapshot) = Coverage.reportSnapshot()
    val totalFunctions = metadata.values.count(_.kind == ca.uwaterloo.flix.runtime.ProbeKind.Function)
    val totalLines = metadata.values.count(_.kind == ca.uwaterloo.flix.runtime.ProbeKind.Line)
    val totalBranches = metadata.values.count(_.kind.asString.startsWith("branch"))

    val coveredFunctions = metadata.count { case (id, pm) => pm.kind == ca.uwaterloo.flix.runtime.ProbeKind.Function && snapshot.contains(id) }
    val coveredLines = metadata.count { case (id, pm) => pm.kind == ca.uwaterloo.flix.runtime.ProbeKind.Line && snapshot.contains(id) }
    val coveredBranches = metadata.count { case (id, pm) => pm.kind.asString.startsWith("branch") && snapshot.contains(id) }

    def pct(covered: Int, total: Int): String = if (total == 0) "0.0%" else f"${(covered.toDouble / total) * 100.0}%.1f%%"

    s"Coverage: Functions: ${pct(coveredFunctions, totalFunctions)} ($coveredFunctions/$totalFunctions), " +
      s"Lines: ${pct(coveredLines, totalLines)} ($coveredLines/$totalLines), " +
      s"Branches: ${pct(coveredBranches, totalBranches)} ($coveredBranches/$totalBranches)"
  }
}
