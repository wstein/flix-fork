/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.StaticMethod
import ca.uwaterloo.flix.util.InternalCompilerException

import java.lang.constant.MethodTypeDesc

/** Runtime classes present only in a coverage build, loaded from the compiler's class path. */
object CoverageRuntime {
  private val ClassName = "dev.flix.runtime.Coverage"
  val Desc = ClassDescs.ofBinaryName(ClassName).get
  val HitMethod: StaticMethod = StaticMethod(Desc, "hit", MethodTypeDesc.ofDescriptor("(JI)V"))

  def classes: List[JvmClass] = {
    val resource = "/" + ClassName.replace('.', '/') + ".class"
    val stream = classOf[dev.flix.runtime.Coverage].getResourceAsStream(resource)
    if (stream == null) {
      throw InternalCompilerException(s"Missing coverage runtime class: $resource", SourceLocation.Unknown)
    }
    val bytes = try stream.readAllBytes() finally stream.close()
    List(JvmClass(Desc, bytes))
  }
}
