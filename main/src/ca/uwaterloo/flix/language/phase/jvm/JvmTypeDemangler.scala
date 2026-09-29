package ca.uwaterloo.flix.language.phase.jvm

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/** Parses the un-compacted, human-facing part of a generated JVM class name. */
object JvmTypeDemangler {
  def demangle(value: String): Either[String, String] = {
    val simple = value.stripSuffix(".class").split('/').last
    if (simple.contains("$$$$")) return Left("The middle of this class name was compacted and cannot be recovered.")
    if (simple.startsWith("Case$")) {
      val parts = simple.split("\\$").toList
      return if (parts.length >= 4) Right(parts(1) + "[" + parts.slice(2, parts.length - 1).mkString(", ") + "]." + parts.last)
      else Left("Not a mangled case class name.")
    }
    val prefix = if (simple.startsWith("Def$")) "Def$" else if (simple.startsWith("Clo$")) "Clo$" else ""
    if (prefix.isEmpty) return Left("Expected a Def$, Clo$, or Case$ class name.")
    val marker = simple.indexOf("$I", prefix.length)
    if (marker < 0) return Left("This class has no mangled type arguments.")
    val owner = simple.substring(prefix.length, marker)
    val parser = new Parser(simple.substring(marker + 1))
    parser.arguments() match {
      case Left(error) => Left(error)
      case Right(args) =>
        val tail = parser.remaining
        if (tail.nonEmpty && !tail.startsWith("$")) Left("Unexpected trailing mangled-name input.")
        else Right(owner + args.mkString("(", ", ", ")") + (if (tail.isEmpty) "" else " / " + tail.drop(1)))
    }
  }

  private class Parser(input: String) {
    private val data = input.getBytes(StandardCharsets.UTF_8)
    private var offset = 0
    private val substitutions = mutable.ArrayBuffer.empty[String]

    def remaining: String = new String(data, offset, data.length - offset, StandardCharsets.UTF_8)

    def arguments(): Either[String, List[String]] = try {
      Right(list())
    } catch {
      case _: IllegalArgumentException => Left("Malformed mangled type arguments.")
      case _: IndexOutOfBoundsException => Left("Truncated mangled type arguments.")
    }

    private def current: Char = {
      if (offset >= data.length) throw new IndexOutOfBoundsException
      data(offset).toChar
    }

    private def take(expected: Char): Unit = {
      if (current != expected) throw new IllegalArgumentException
      offset += 1
    }

    private def number(): Int = {
      val start = offset
      while (offset < data.length && data(offset) >= '0' && data(offset) <= '9') offset += 1
      if (start == offset) throw new IllegalArgumentException
      new String(data, start, offset - start, StandardCharsets.US_ASCII).toInt
    }

    private def framed(): String = {
      val length = number()
      if (length < 0 || offset + length > data.length) throw new IllegalArgumentException
      val end = offset + length
      val decoded = new ByteArrayOutputStream()
      while (offset < end) {
        if (data(offset) == '$') {
          if (offset + 2 >= end) throw new IllegalArgumentException
          val digits = new String(data, offset + 1, 2, StandardCharsets.US_ASCII)
          decoded.write(Integer.parseInt(digits, 16))
          offset += 3
        } else {
          decoded.write(data(offset) & 0xff)
          offset += 1
        }
      }
      val result = new String(decoded.toByteArray, StandardCharsets.UTF_8)
      result
    }

    private def list(): List[String] = {
      take('I')
      val result = mutable.ListBuffer.empty[String]
      while (current != 'E') result += tpe()
      offset += 1
      result.toList
    }

    private def tpe(): String = {
      if (current == 'S') {
        offset += 1
        val start = offset
        while (current != '_') offset += 1
        val index = if (start == offset) 0 else Integer.parseInt(new String(data, start, offset - start, StandardCharsets.US_ASCII), 36) + 1
        offset += 1
        return substitutions(index)
      }
      val base = current match {
        case c if c >= '0' && c <= '9' => framed()
        case 'N' => qualified()
        case 'F' | 'W' | 'T' | 'R' | 'L' =>
          val kind = current
          offset += 1
          val arity = number()
          take('_')
          Map('F' -> "Arrow", 'W' -> "ArrowWithoutEffect", 'T' -> "Tuple", 'R' -> "Relation", 'L' -> "Lattice")(kind) + arity
        case 'Q' =>
          offset += 1
          "effect " + (if (current == 'N') qualified() else framed())
        case 'O' =>
          offset += 1
          framed()
        case 'B' | 'H' =>
          val kind = if (current == 'B') "RecordRow" else "SchemaRow"
          offset += 1
          take('I')
          val fields = mutable.ListBuffer.empty[String]
          while (current == 'Y') {
            offset += 1
            val label = framed()
            fields += label + ": " + tpe()
          }
          take('Z')
          val tail = tpe()
          take('E')
          kind + fields.mkString("{", ", ", " | " + tail + "}")
        case 'J' | 'G' | 'X' =>
          val kind = current
          offset += 1
          if (kind == 'J' && current == 'N') "Java(" + qualified() + ")"
          else {
            val encoded = framed()
            if (kind == 'J') "Java(" + unhex(encoded) + ")" else if (kind == 'G') "generated(" + encoded + ")" else "opaque(" + encoded + ")"
          }
        case 'K' =>
          offset += 1
          val kind = framed()
          take('X')
          framed()
          kind
        case _ => throw new IllegalArgumentException
      }
      val rendered = if (offset < data.length && current == 'I') base + list().mkString("[", ", ", "]") else base
      substitutions += rendered
      rendered
    }

    private def unhex(value: String): String = {
      if (value.length % 2 != 0) throw new IllegalArgumentException
      val decoded = value.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      new String(decoded, StandardCharsets.UTF_8)
    }

    private def qualified(): String = {
      take('N')
      val names = mutable.ListBuffer.empty[String]
      while (current != 'E') names += framed()
      offset += 1
      names.mkString(".")
    }
  }
}
