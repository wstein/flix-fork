package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.util.InternalCompilerException

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.CharBuffer
import java.nio.charset.{CharacterCodingException, StandardCharsets}

final case class GeneratedJvmKey(family: String, fields: List[String]) {

  private[jvm] def bytes: Array[Byte] = {
    val buffer = new ByteArrayOutputStream()
    val output = new DataOutputStream(buffer)
    output.writeInt(1)
    val parts = "flix-jvm-name" :: family :: fields
    output.writeInt(parts.length)
    parts.foreach { part =>
      val encoded = try {
        StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(part))
      } catch {
        case _: CharacterCodingException =>
          throw InternalCompilerException("Malformed Unicode in JVM naming provenance.", SourceLocation.Unknown)
      }
      val data = new Array[Byte](encoded.remaining())
      encoded.get(data)
      output.writeInt(data.length)
      output.write(data)
    }
    output.flush()
    buffer.toByteArray
  }
}
