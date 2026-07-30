/*
 * Copyright 2024
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
package ca.uwaterloo.flix.runtime

/**
  * Sealed trait for coverage probe kinds.
  *
  * Each probe type represents a distinct source location tracked during coverage analysis:
  * - Function: Entry point of a compiled function
  * - Line: Executable statement or expression within a function
  * - BranchTrue: True branch of an if-expression
  * - BranchFalse: False branch of an if-expression
  * - BranchRule: Selected body of a match or restrictable choose rule
  */
sealed trait ProbeKind {
  /**
    * Return the string representation for JSON serialization.
    */
  def asString: String = this match {
    case ProbeKind.Function => "function"
    case ProbeKind.Line => "line"
    case ProbeKind.BranchTrue => "branch-true"
    case ProbeKind.BranchFalse => "branch-false"
    case ProbeKind.BranchRule => "branch-rule"
  }
}

object ProbeKind {
  case object Function extends ProbeKind
  case object Line extends ProbeKind
  case object BranchTrue extends ProbeKind
  case object BranchFalse extends ProbeKind
  case object BranchRule extends ProbeKind

  /**
    * Parse a string into a ProbeKind.
    * Used during deserialization or when reading from configuration.
    */
  def fromString(s: String): Option[ProbeKind] = s match {
    case "function" => Some(Function)
    case "line" => Some(Line)
    case "branch-true" => Some(BranchTrue)
    case "branch-false" => Some(BranchFalse)
    case "branch-rule" => Some(BranchRule)
    case _ => None
  }
}
