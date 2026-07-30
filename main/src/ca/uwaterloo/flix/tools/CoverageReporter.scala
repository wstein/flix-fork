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

import java.nio.file.Path

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
    // Get the snapshot of coverage data and metadata
    val snapshot = Coverage.snapshot()
    val metadata = Coverage.getProbeMetadata

    // Organize coverage by file
    val coverageByFile: Map[String, List[(Int, Int, String)]] = snapshot.toList.flatMap {
      case (probeId, count) =>
        metadata.get(probeId).map {
          case (source, line, kind) => (source, (probeId, line, kind))
        }
    }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

    // Calculate summary statistics
    val functionProbes = metadata.values.count(_._3 == "function")
    val lineProbes = metadata.values.count(_._3 == "line")
    val branchProbes = metadata.values.count(_._3.startsWith("branch"))

    val functionCovered = snapshot.count {
      case (probeId, _) =>
        metadata.get(probeId).exists(_._3 == "function")
    }
    val lineCovered = snapshot.count {
      case (probeId, _) =>
        metadata.get(probeId).exists(_._3 == "line")
    }
    val branchCovered = snapshot.count {
      case (probeId, _) =>
        metadata.get(probeId).exists(_._3.startsWith("branch"))
    }

    // Build the JSON report
    val files: List[JValue] = coverageByFile.toList.map {
      case (path, probes) =>
        val lines: Map[String, Boolean] = probes.groupBy(_._2).map { case (line, probes) =>
          val covered = probes.map(_._1).exists(snapshot.contains)
          line.toString -> covered
        }

        val branches: List[JValue] = probes
          .filter(_._3.startsWith("branch"))
          .groupBy(_._2)
          .toList
          .map {
            case (line, probes) =>
              val branchCoverage = probes.map { case (probeId, _, kind) =>
                kind -> snapshot.contains(probeId)
              }.toMap
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

    // Write the report to file
    val jsonString = compact(render(report))
    java.nio.file.Files.write(outputPath, jsonString.getBytes)
  }
}
