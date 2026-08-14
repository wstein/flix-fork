/*
 * Copyright 2026 Magnus Madsen, Werner Stein
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

package ca.uwaterloo.flix.util

/**
  * Whether a part of the standard library is compiled with or without its parallelism.
  *
  * Each such part is guarded by a library function returning a constant, which the
  * [[ca.uwaterloo.flix.language.phase.LibraryOptions]] phase rewrites. Selecting [[Sequential]]
  * makes the guard `false`, after which the optimizer folds the guarded branch away and the tree
  * shaker removes everything it kept alive.
  */
sealed trait ExecutionMode

object ExecutionMode {
  /**
    * Concurrent execution using virtual threads.
    */
  case object Parallel extends ExecutionMode

  /**
    * Sequential execution with the guarded parallelism compiled away.
    *
    * Note: This is never a guarantee that the compiled program is free of concurrency primitives.
    * See `docs/datalog-execution-mode.md` for what each option does and does not remove.
    */
  case object Sequential extends ExecutionMode
}
