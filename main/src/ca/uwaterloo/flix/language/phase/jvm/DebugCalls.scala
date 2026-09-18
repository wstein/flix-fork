package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.util.FileOps

import java.nio.file.Path

/** Direct source calls and the generated JVM definitions selected for them. */
object DebugCalls {
  val FileName: String = "debug-calls.json"
  val FormatVersion: Int = 2
  val DefaultMethodName: String = "staticApply"

  case class Call(source: String,
                  startLine: Int,
                  startCol: Int,
                  endLine: Int,
                  endCol: Int,
                  label: String,
                  className: String,
                  methodName: String)

  def write(path: Path, calls: Iterable[Call]): Unit = {
    val sources = calls.toList
      .groupBy(_.source)
      .toList
      .sortBy(_._1)
      .map { case (source, sourceCalls) =>
        val entries = sourceCalls
          .sortBy(c => (c.startLine, c.startCol, c.endLine, c.endCol, c.label, c.className, c.methodName))
          .map(callJson)
        s"""    ${JvmDebugJson.quote(source)}:[
           |${entries.mkString(",\n")}
           |    ]""".stripMargin
    }
    FileOps.writeString(path,
      s"""{
         |  "formatVersion":$FormatVersion,
         |  "sources":{
         |${sources.mkString(",\n")}
         |  }
         |}
         |""".stripMargin)
  }

  private def callJson(call: Call): String = {
    val method = if (call.methodName == DefaultMethodName) "" else
      s""",\"methodName\":${JvmDebugJson.quote(call.methodName)}"""
    s"""      {"range":[${call.startLine},${call.startCol},${call.endLine},${call.endCol}],"name":${JvmDebugJson.quote(call.label)},"target":{"className":${JvmDebugJson.quote(call.className)}$method}}"""
  }
}
