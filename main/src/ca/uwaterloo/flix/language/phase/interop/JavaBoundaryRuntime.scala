/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.language.phase.jvm.JvmClass

import java.lang.constant.ClassDesc

/** Standalone, JDK-only support classes included in both bootstrap APIs and runnable artifacts. */
object JavaBoundaryRuntime {
  def classes: List[JvmClass] = List("OpaqueHandle", "OpaqueHandleBridge").map { name =>
    val path = s"/dev/flix/runtime/$name.class"
    val resource = Option(getClass.getResourceAsStream(path)).getOrElse(throw new IllegalStateException(s"Missing boundary runtime: $path"))
    try JvmClass(ClassDesc.of(s"dev.flix.runtime.$name"), resource.readAllBytes()) finally resource.close()
  }
}
