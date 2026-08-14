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
  * Execution strategy for Fixpoint3 Datalog programs.
  */
sealed trait DatalogExecution

object DatalogExecution {
  /**
    * Concurrent execution using virtual threads and parallel B+ tree traversals.
    */
  case object Parallel extends DatalogExecution

  /**
    * Sequential execution with the RAM interpreter's own parallelism compiled away.
    *
    * Note: This is not a guarantee that the compiled program is free of concurrency primitives.
    * The B+ tree still locks its indexes, and the `Map` operations used to build and marshal them
    * are `@ParallelWhenPure` and may still spawn threads. See `docs/datalog-execution-mode.md`.
    */
  case object Sequential extends DatalogExecution
}
