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
package ca.uwaterloo.flix.tools.fmt.layout

/**
  * The rendered output of a formatted piece tree.
  *
  * Wrapped around the rendered text string to support tree-based composition
  * and future source map / selection tracking.
  */
final class GroupCode(val text: String) {
  def toText: String = text

  def length: Int = text.length

  def isEmpty: Boolean = text.isEmpty

  override def toString: String = text
}

object GroupCode {
  val Empty: GroupCode = new GroupCode("")
}
