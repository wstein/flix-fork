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

import ca.uwaterloo.flix.runtime.{Coverage, ProbeKind, ProbeMetadata}
import org.json4s.{JObject, JValue}
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{compact, render}

import java.nio.file.{Files, Path}
import scala.collection.immutable.ListMap

/**
  * Generates coverage reports from collected coverage data.
  *
  * All public methods accept an explicit [[Coverage.Session]] and take exactly one
  * immutable snapshot via [[Coverage.Session.reportSnapshot()]].  This ensures that
  * the JSON, LCOV, and summary artifacts are always consistent with each other and
  * are never polluted by a concurrently executing compilation.
  */
object CoverageReporter {

  /**
    * Generate and write a JSON coverage report for the given session.
    *
    * @param session    the coverage session to report on.
    * @param outputPath the path to write the report to.
    */
  def writeJsonReport(session: Coverage.Session, outputPath: Path): Unit = {
    val parentDir = outputPath.getParent
    if (parentDir != null && !Files.exists(parentDir)) {
      Files.createDirectories(parentDir)
    }

    // Single immutable snapshot — the same snapshot drives all artifacts.
    val (metadata, snapshot) = session.reportSnapshot()
    val jsonString = compact(render(buildJson(metadata, snapshot)))
    Files.write(outputPath, jsonString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }

  /**
    * Generate and write an LCOV tracefile report (`.info`) for the given session.
    *
    * @param session    the coverage session to report on.
    * @param outputPath the path to write the LCOV report to.
    */
  def writeLcovReport(session: Coverage.Session, outputPath: Path): Unit = {
    val parentDir = outputPath.getParent
    if (parentDir != null && !Files.exists(parentDir)) {
      Files.createDirectories(parentDir)
    }

    val (metadata, snapshot) = session.reportSnapshot()
    val content = buildLcov(metadata, snapshot)
    Files.write(outputPath, content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }

  /**
    * Return a formatted terminal summary string for the given session.
    *
    * @param session the coverage session to summarize.
    */
  def formatSummary(session: Coverage.Session): String = {
    val (metadata, snapshot) = session.reportSnapshot()
    buildSummary(metadata, snapshot)
  }

  // ─── Private helpers ───────────────────────────────────────────────────────

  private def buildJson(metadata: Map[Int, ProbeMetadata], snapshot: Map[Int, Long]): JValue = {
    val coverageByFile: Map[String, List[(Int, Int, String, String)]] = metadata.toList.map {
      case (probeId, pm) => (pm.source, (probeId, pm.line, pm.kind.asString, pm.qualifiedName))
    }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

    val functionProbes = metadata.values.count(_.kind.asString == "function")
    val lineProbes     = metadata.values.count(_.kind.asString == "line")
    val branchProbes   = metadata.values.count(_.kind.asString.startsWith("branch"))

    val functionCovered = metadata.count { case (id, pm) => pm.kind.asString == "function" && snapshot.contains(id) }
    val lineCovered     = metadata.count { case (id, pm) => pm.kind.asString == "line"     && snapshot.contains(id) }
    val branchCovered   = metadata.count { case (id, pm) => pm.kind.asString.startsWith("branch") && snapshot.contains(id) }

    val files: List[JValue] = coverageByFile.toList.sortBy(_._1).map {
      case (path, probes) =>
        val lines: Map[String, Boolean] = ListMap.from(probes
          .filter(_._3 == "line")
          .groupBy(_._2)
          .toList
          .sortBy(_._1)
          .map { case (line, lps) =>
            val covered = lps.map(_._1).exists(snapshot.contains)
            line.toString -> covered
          })

        val branches: List[JValue] = probes
          .filter(_._3.startsWith("branch"))
          .groupBy(_._2)
          .toList
          .sortBy(_._1)
          .map {
            case (line, lineProbes) =>
              val branchCoverage: List[JValue] = lineProbes.sortBy(_._1).map {
                case (probeId, _, kind, qualifiedName) =>
                  ("id"       -> probeId) ~
                  ("kind"     -> kind) ~
                  ("covered"  -> snapshot.contains(probeId)) ~
                  ("function" -> qualifiedName)
              }
              ("line" -> line) ~ ("branches" -> branchCoverage)
          }

        val functionsList: List[JValue] = probes
          .filter(_._3 == "function")
          .sortBy(_._1)
          .map { case (probeId, line, _, qualifiedName) =>
            ("qualifiedName" -> qualifiedName) ~
            ("source"        -> path) ~
            ("line"          -> line) ~
            ("covered"       -> snapshot.contains(probeId)) ~
            ("hitCount"      -> snapshot.getOrElse(probeId, 0L))
          }

        ("path"           -> path) ~
        ("functionsCount" -> probes.filter(_._3 == "function").length) ~
        ("functions"      -> functionsList) ~
        ("lines"          -> lines) ~
        ("branches"       -> branches)
    }

    ("summary" ->
      ("functions" -> (("covered" -> functionCovered) ~ ("total" -> functionProbes))) ~
      ("lines"     -> (("covered" -> lineCovered)     ~ ("total" -> lineProbes))) ~
      ("branches"  -> (("covered" -> branchCovered)   ~ ("total" -> branchProbes)))
    ) ~
    ("files" -> files)
  }

  private def buildLcov(metadata: Map[Int, ProbeMetadata], snapshot: Map[Int, Long]): String = {
    val coverageByFile = metadata.toList.map {
      case (probeId, pm) => (pm.source, (probeId, pm.line, pm.kind, pm.qualifiedName))
    }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

    val sb = new java.lang.StringBuilder()

    coverageByFile.toList.sortBy(_._1).foreach { case (sourcePath, probes) =>
      sb.append("TN:\n")
      sb.append(s"SF:$sourcePath\n")

      val funcProbes    = probes.filter(_._3 == ProbeKind.Function)
      val funcHitCount  = funcProbes.count { case (id, _, _, _) => snapshot.contains(id) }

      funcProbes.sortBy(_._2).foreach { case (_, line, _, name) => sb.append(s"FN:$line,$name\n") }
      funcProbes.sortBy(_._2).foreach { case (id, _, _, name)   => sb.append(s"FNDA:${snapshot.getOrElse(id, 0L)},$name\n") }
      sb.append(s"FNF:${funcProbes.size}\n")
      sb.append(s"FNH:$funcHitCount\n")

      val lineProbesByLine = probes.filter(_._3 == ProbeKind.Line).groupBy(_._2)
      var lineHitCount = 0
      lineProbesByLine.toList.sortBy(_._1).foreach { case (line, lps) =>
        val hits = lps.map(_._1).map(id => snapshot.getOrElse(id, 0L)).foldLeft(0L)(_ max _)
        if (hits > 0) lineHitCount += 1
        sb.append(s"DA:$line,$hits\n")
      }
      sb.append(s"LF:${lineProbesByLine.size}\n")
      sb.append(s"LH:$lineHitCount\n")

      val branchProbesByLine = probes.filter(_._3.asString.startsWith("branch")).groupBy(_._2)
      var branchTotalCount   = 0
      var branchHitCount     = 0
      branchProbesByLine.toList.sortBy(_._1).foreach { case (line, branchProbes) =>
        branchProbes.sortBy(_._1).zipWithIndex.foreach { case ((id, _, _, _), idx) =>
          branchTotalCount += 1
          val hitStr = snapshot.get(id) match {
            case Some(h) => branchHitCount += 1; h.toString
            case None    => "-"
          }
          sb.append(s"BRDA:$line,0,$idx,$hitStr\n")
        }
      }
      sb.append(s"BRF:$branchTotalCount\n")
      sb.append(s"BRH:$branchHitCount\n")

      sb.append("end_of_record\n")
    }

    sb.toString
  }

  private def buildSummary(metadata: Map[Int, ProbeMetadata], snapshot: Map[Int, Long]): String = {
    val totalFunctions    = metadata.values.count(_.kind == ProbeKind.Function)
    val totalLines        = metadata.values.count(_.kind == ProbeKind.Line)
    val totalBranches     = metadata.values.count(_.kind.asString.startsWith("branch"))
    val coveredFunctions  = metadata.count { case (id, pm) => pm.kind == ProbeKind.Function && snapshot.contains(id) }
    val coveredLines      = metadata.count { case (id, pm) => pm.kind == ProbeKind.Line     && snapshot.contains(id) }
    val coveredBranches   = metadata.count { case (id, pm) => pm.kind.asString.startsWith("branch") && snapshot.contains(id) }

    def pct(covered: Int, total: Int): String =
      if (total == 0) "0.0%" else f"${(covered.toDouble / total) * 100.0}%.1f%%"

    s"Coverage: Functions: ${pct(coveredFunctions, totalFunctions)} ($coveredFunctions/$totalFunctions), " +
    s"Lines: ${pct(coveredLines, totalLines)} ($coveredLines/$totalLines), " +
    s"Branches: ${pct(coveredBranches, totalBranches)} ($coveredBranches/$totalBranches)"
  }
}
