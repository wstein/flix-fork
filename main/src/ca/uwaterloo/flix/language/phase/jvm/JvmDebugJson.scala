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

/**
  * The one JSON-string escaper shared by the hand-rolled debug sidecars ([[DebugIndex]],
  * [[DebugScopes]]), which write deterministic, sorted JSON directly rather than through a
  * general-purpose library.
  */
private[jvm] object JvmDebugJson {

  /** Returns `s` as a quoted JSON string literal. */
  def quote(s: String): String = {
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    for (c <- s) c match {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"')
    sb.toString
  }

}
