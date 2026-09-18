/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.util.FileOps
import org.json4s.{JArray, JInt, JObject, JString}
import org.json4s.native.JsonMethods

import java.nio.file.Path

/** The Flix types of debugger-visible bindings, keyed by emitted class and method. */
object DebugScopes {
  val FileName: String = "debug-scopes.json"
  val FormatVersion: Int = 2

  case class Binding(name: String, tpe: String)

  case class Scope(methods: Map[String, List[Binding]])

  def write(path: Path, definitions: Map[String, Map[String, List[JvmLexicalOrigins.Binding]]]): Unit = {
    val classes = definitions.toList.sortBy(_._1).map { case (clazz, methods) =>
      val renderedMethods = methods.toList.sortBy(_._1).map { case (method, bindings) =>
        val unambiguous = bindings.groupBy(_.name).values.collect {
          case sameName if sameName.map(_.tpe).distinct.sizeIs == 1 => sameName.minBy(_.identity)
        }.toList
        val renderedBindings = unambiguous.sortBy(_.identity).map { binding =>
          s"{\"name\":${JvmDebugJson.quote(binding.name)},\"type\":${JvmDebugJson.quote(binding.tpe)}}"
        }
        s"      ${JvmDebugJson.quote(method)}: [${renderedBindings.mkString(",")}]"
      }
      s"    ${JvmDebugJson.quote(clazz)}: {\n${renderedMethods.mkString(",\n")}\n    }"
    }
    FileOps.writeString(path,
      s"{\n  \"formatVersion\":$FormatVersion,\n  \"classes\":{\n${classes.mkString(",\n")}\n  }\n}\n")
  }

  /** Reads the exact format written above; malformed and future formats fail closed. */
  def read(text: String): Map[String, Scope] = try {
    JsonMethods.parse(text) match {
      case JObject(root) =>
        val version = root.collectFirst { case ("formatVersion", JInt(n)) => n.toInt }
        val classes = root.collectFirst { case ("classes", JObject(values)) => values }
        if (!version.contains(FormatVersion)) Map.empty
        else classes.toList.flatten.collect { case (className, JObject(methods)) =>
          val parsedMethods = methods.collect { case (methodName, JArray(values)) =>
            val bindings = values.collect { case JObject(fields) =>
              val name = fields.collectFirst { case ("name", JString(value)) => value }
              val tpe = fields.collectFirst { case ("type", JString(value)) => value }
              (name, tpe) match {
                case (Some(n), Some(t)) => Some(Binding(n, t))
                case _ => None
              }
            }.flatten
            methodName -> bindings
          }.toMap
          className -> Scope(parsedMethods)
        }.toMap
      case _ => Map.empty
    }
  } catch {
    case _: Exception => Map.empty
  }
}
