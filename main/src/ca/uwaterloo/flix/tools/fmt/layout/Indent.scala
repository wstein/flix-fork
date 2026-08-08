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
  * A unit of indentation, named after why it is being applied.
  *
  * The names matter more than the numbers: they are how the style is
  * documented, and how a reviewer can tell whether an indent is deliberate.
  * Flix settled on four spaces, so most of these are 4 and the enumeration
  * exists to keep the *reasons* distinguishable if that ever changes.
  */
sealed abstract class Indent(val spaces: Int)

object Indent {
  case object None extends Indent(0)

  /** Continuation of an expression, e.g. after `=`. */
  case object Expression extends Indent(4)

  /** Contents of a `{ ... }` block or a delimited literal. */
  case object Block extends Indent(4)

  /** Operands of an infix chain after the first. */
  case object Infix extends Indent(4)

  /** Inside `(`, `[` or `<` when the delimiter itself supplies the alignment. */
  case object Grouping extends Indent(0)
}
