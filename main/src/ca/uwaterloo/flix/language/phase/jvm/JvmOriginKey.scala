package ca.uwaterloo.flix.language.phase.jvm

import java.security.MessageDigest
import java.util.Base64

object JvmOriginKey {
  def compose(family: String, parents: List[GeneratedJvmKey], fields: List[String] = Nil): GeneratedJvmKey = {
    val ancestry = parents.map(key => Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(key.bytes)))
    GeneratedJvmKey(family, parents.size.toString :: ancestry ::: fields)
  }
}
