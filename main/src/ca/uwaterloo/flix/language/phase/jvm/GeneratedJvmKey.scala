package ca.uwaterloo.flix.language.phase.jvm

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets

final case class GeneratedJvmKey(family: String, fields: List[String]) {

  private[jvm] def bytes: Array[Byte] = {
    val buffer = new ByteArrayOutputStream()
    val output = new DataOutputStream(buffer)
    output.writeInt(1)
    val parts = "flix-jvm-name" :: family :: fields
    output.writeInt(parts.length)
    parts.foreach { part =>
      val encoded = part.getBytes(StandardCharsets.UTF_8)
      output.writeInt(encoded.length)
      output.write(encoded)
    }
    output.flush()
    buffer.toByteArray
  }
}
