/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.util.InternalCompilerException
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.StaticMethod

import java.lang.constant.MethodTypeDesc

/** Runtime classes present only in a debug build, loaded from the compiler's own class path. */
object DebugEvalRuntime {
  val HostDesc = ClassDescs.ofBinaryName("dev.flix.runtime.DebugEvalHost").get
  val InstallMethod: StaticMethod = StaticMethod(HostDesc, "install", MethodTypeDesc.ofDescriptor("()V"))

  private val ClassNames = List(
    "dev.flix.runtime.DebugEvalHost",
    "dev.flix.runtime.DebugEvalHost$ArtifactLoader",
    "dev.flix.runtime.DebugEvalException",
  )

  def classes: List[JvmClass] = ClassNames.map { name =>
    val resource = "/" + name.replace('.', '/') + ".class"
    val stream = classOf[dev.flix.runtime.DebugEvalHost].getResourceAsStream(resource)
    if (stream == null) {
      throw InternalCompilerException(s"Missing debug-evaluation runtime class: $resource", SourceLocation.Unknown)
    }
    val bytes = try stream.readAllBytes() finally stream.close()
    JvmClass(ClassDescs.ofBinaryName(name).get, bytes)
  }
}
