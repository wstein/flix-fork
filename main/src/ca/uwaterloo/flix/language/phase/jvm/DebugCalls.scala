package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.util.FileOps

import java.nio.file.Path

/** Direct source calls and the generated JVM definitions selected for them. */
object DebugCalls {
  val FileName: String = "debug-calls.json"
  val FormatVersion: Int = 1

  case class Call(source: String,
                  startLine: Int,
                  startCol: Int,
                  endLine: Int,
                  endCol: Int,
                  label: String,
                  className: String,
                  methodName: String)

  def write(path: Path, calls: Iterable[Call]): Unit = {
    val entries = calls.toList.sortBy(c => (c.source, c.startLine, c.startCol, c.endLine, c.endCol, c.label)).map { call =>
      s"""    {"source":${JvmDebugJson.quote(call.source)},"startLine":${call.startLine},"startCol":${call.startCol},"endLine":${call.endLine},"endCol":${call.endCol},"label":${JvmDebugJson.quote(call.label)},"className":${JvmDebugJson.quote(call.className)},"methodName":${JvmDebugJson.quote(call.methodName)}}"""
    }
    FileOps.writeString(path,
      s"""{
         |  "formatVersion":$FormatVersion,
         |  "calls":[
         |${entries.mkString(",\n")}
         |  ]
         |}
         |""".stripMargin)
  }
}
