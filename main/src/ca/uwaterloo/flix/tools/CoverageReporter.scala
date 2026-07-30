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
    val snapshot = Coverage.snapshot()
    val metadata = Coverage.getProbeMetadata

    // Filter out synthetic/unknown locations (use loc.isReal check during registration instead)
    // Organize ALL probes by file (including zero-hit)
    val coverageByFile: Map[String, List[(Int, Int, String)]] = metadata.toList.map {
      case (probeId, pm) => (pm.source, (probeId, pm.line, pm.kind.asString))
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
            case (line, probes) =>
              val branchCoverage = ListMap.from(probes.sortBy(_._3).map { case (probeId, _, kind) =>
                kind -> snapshot.contains(probeId)
              })
              ("line" -> line) ~ ("branches" -> branchCoverage)
          }

        ("path" -> path) ~
          ("functions" -> probes.filter(_._3 == "function").length) ~
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
}
