package ca.uwaterloo.flix.language.phase.jvm

import java.io.{ByteArrayInputStream, DataInputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.Base64
import scala.collection.mutable.ListBuffer

/** Renders the versioned semantic key carried by an uncommon X type spelling. */
private[jvm] object JvmTypeKeyDemangler {
  private val maxKeyBytes = 1024 * 1024
  private val maxDepth = 128

  def demangle(encoded: String): Either[String, String] = try {
    if (encoded.length > maxKeyBytes * 2) throw new IllegalArgumentException
    val bytes = Base64.getDecoder.decode(encoded)
    if (bytes.length > maxKeyBytes) throw new IllegalArgumentException
    val input = new DataInputStream(new ByteArrayInputStream(bytes))
    if (input.readInt() != 1 || input.readInt() != 3) throw new IllegalArgumentException
    val parts = List.fill(3)(readPart(input))
    if (input.available() != 0 || parts.head != "flix-jvm-name" || parts(1) != "semantic-type-v1")
      throw new IllegalArgumentException
    Right(render(parse(parts(2)), 0))
  } catch {
    case _: IllegalArgumentException => Left("Malformed semantic type key.")
    case _: java.io.IOException => Left("Malformed semantic type key.")
    case _: StackOverflowError => Left("Semantic type key is too deeply nested.")
  }

  private def readPart(input: DataInputStream): String = {
    val length = input.readInt()
    if (length < 0 || length > input.available()) throw new IllegalArgumentException
    val data = new Array[Byte](length)
    input.readFully(data)
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    try decoder.decode(ByteBuffer.wrap(data)).toString
    catch { case _: java.nio.charset.CharacterCodingException => throw new IllegalArgumentException }
  }

  private case class Node(tag: String, fields: List[String])

  private def parse(value: String): Node = {
    if (value.length < 3 || value.head != '[' || value.last != ']') throw new IllegalArgumentException
    val parts = ListBuffer.empty[String]
    var offset = 1
    while (offset < value.length - 1) {
      val start = offset
      while (offset < value.length - 1 && value.charAt(offset).isDigit) offset += 1
      if (start == offset || offset >= value.length - 1 || value.charAt(offset) != ':') throw new IllegalArgumentException
      val length = try value.substring(start, offset).toInt
      catch { case _: NumberFormatException => throw new IllegalArgumentException }
      offset += 1
      if (length < 0 || length > value.length - 1 - offset) throw new IllegalArgumentException
      parts += value.substring(offset, offset + length)
      offset += length
    }
    if (offset != value.length - 1 || parts.isEmpty) throw new IllegalArgumentException
    Node(parts.head, parts.tail.toList)
  }

  private def child(value: String, depth: Int): String = {
    if (depth >= maxDepth) throw new IllegalArgumentException
    render(parse(value), depth + 1)
  }

  private def children(value: String, depth: Int): List[String] = {
    val node = parse(value)
    if (node.tag != "list") throw new IllegalArgumentException
    node.fields.map(child(_, depth))
  }

  private def label(tag: String): String = tag.split('-').map(_.capitalize).mkString

  private def render(node: Node, depth: Int): String = {
    if (depth >= maxDepth) throw new IllegalArgumentException
    val fields = node.fields
    (node.tag, fields) match {
      case ("constant", List(tpe)) => child(tpe, depth)
      case ("apply", List(head, arg)) => child(head, depth) + "[" + child(arg, depth) + "]"
      case ("assoc", List(sym, arg, kind)) =>
        child(sym, depth) + "[" + child(arg, depth) + "]: " + child(kind, depth)
      case ("jvm-to-type", List(tpe)) => "JvmToType[" + child(tpe, depth) + "]"
      case ("jvm-to-eff", List(tpe)) => "JvmToEff[" + child(tpe, depth) + "]"
      case ("set-operation", List(operator, operands)) =>
        child(operator, depth) + children(operands, depth).mkString("(", ", ", ")")
      case ("associated-type", List(owner, name)) => child(owner, depth) + "." + name
      case ("case", List(owner, name)) => child(owner, depth) + "." + name
      case ("enum" | "struct" | "restrictable-enum" | "trait" | "effect", List(namespace, name))
          if parse(namespace).tag == "list" =>
        (parse(namespace).fields :+ name).mkString(".")
      case ("region", List(sym)) => "Region[" + child(sym, depth) + "]"
      case ("case-set", List(owner, members)) =>
        "CaseSet[" + child(owner, depth) + children(members, depth).mkString("{", ", ", "}") + "]"
      case ("generated", List(origin)) => renderOrigin(origin)
      case ("list", items) => items.map(child(_, depth)).mkString("[", ", ", "]")
      case (tag, Nil) => label(tag)
      case (tag, args) =>
        label(tag) + args.map { arg =>
          if (arg.startsWith("[") && arg.endsWith("]") && scala.util.Try(parse(arg)).isSuccess) child(arg, depth)
          else arg
        }.mkString("(", ", ", ")")
    }
  }

  private def renderOrigin(encoded: String): String = {
    val bytes = Base64.getDecoder.decode(encoded)
    if (bytes.length > maxKeyBytes) throw new IllegalArgumentException
    val input = new DataInputStream(new ByteArrayInputStream(bytes))
    if (input.readInt() != 1) throw new IllegalArgumentException
    val count = input.readInt()
    if (count < 2 || count > 32) throw new IllegalArgumentException
    val parts = List.fill(count)(readPart(input))
    if (input.available() != 0 || parts.head != "flix-jvm-name") throw new IllegalArgumentException
    "generated(" + parts.tail.mkString(", ") + ")"
  }
}
