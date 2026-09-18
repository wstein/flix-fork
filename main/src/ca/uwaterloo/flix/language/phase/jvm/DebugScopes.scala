/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.util.FileOps

import java.nio.file.Path

/** The Flix types of debugger-visible bindings, keyed by emitted class and method. */
object DebugScopes {
  val FileName: String = "debug-scopes.json"
  val FormatVersion: Int = 2

  def write(path: Path, definitions: Map[String, Map[String, List[JvmLexicalOrigins.Binding]]]): Unit = {
    val classes = definitions.toList.sortBy(_._1).map { case (clazz, methods) =>
      val renderedMethods = methods.toList.sortBy(_._1).map { case (method, bindings) =>
        val renderedBindings = bindings.sortBy(_.identity).map { binding =>
          s"{\"name\":${JvmDebugJson.quote(binding.name)},\"type\":${JvmDebugJson.quote(binding.tpe)}}"
        }
        s"      ${JvmDebugJson.quote(method)}: [${renderedBindings.mkString(",")}]"
      }
      s"    ${JvmDebugJson.quote(clazz)}: {\n${renderedMethods.mkString(",\n")}\n    }"
    }
    FileOps.writeString(path,
      s"{\n  \"formatVersion\":$FormatVersion,\n  \"classes\":{\n${classes.mkString(",\n")}\n  }\n}\n")
  }
}
