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
 *
 * ---------------------------------------------------------------------------
 * Ported from `lib/src/back_end/solver.dart` in dart_style:
 *
 *   Copyright (c) 2023, the Dart project authors.
 *   Licensed under a BSD-style license. See https://github.com/dart-lang/dart_style
 * ---------------------------------------------------------------------------
 */
package ca.uwaterloo.flix.tools.fmt.layout

import scala.collection.mutable

/**
  * Chooses states for the pieces in a tree so as to minimise overflow first and
  * splitting cost second.
  *
  * The problem is combinatorial in the number of pieces and exponential in
  * their states, so brute force is out. Four things make it tractable:
  *
  *   - The initial solution splits nothing, so the cheapest candidates are
  *     tried first.
  *   - Candidates are explored in cost order, so the first one that fits is
  *     provably optimal and the search stops there.
  *   - Only pieces on the first problematic line are expanded.
  *   - Sufficiently isolated subtrees are hoisted out, solved by their own
  *     `Solver`, and memoised across candidates.
  */
object Solver {

  /**
    * The cap on dequeues before the solver gives up and returns the least-bad
    * candidate found so far.
    *
    * The cap does not compromise determinism: the queue ordering is total (see
    * [[Solution.compare]]), so the sequence of dequeues is a function of the
    * input alone, and truncating it at a fixed count is too. It does mean the
    * result on pathological input is merely good rather than optimal, which is
    * the intended trade.
    */
  private val MaxAttempts = 10000

  /**
    * Solves `ctx.root`, optionally with the root pinned to `rootState`.
    *
    * Returns the best solution found. It is always non-null; on a fully
    * constrained tree that is the initial solution unchanged.
    */
  def solve(ctx: SolveContext, rootState: Option[State] = None): Solution = {
    val queue = new mutable.PriorityQueue[Solution]()(Solution.minHeapOrdering)

    val initial = Solution.initial(ctx, rootState)
    queue.enqueue(initial)

    var best = initial
    var attempts = 0
    var done = false

    while (queue.nonEmpty && attempts < MaxAttempts && !done) {
      val solution = queue.dequeue()
      attempts += 1

      if (solution.isValid) {
        if (solution.overflow == 0) {
          // Candidates arrive in cost order, so this is the optimum.
          best = solution
          done = true
        } else if (!best.isValid || solution.overflow < best.overflow) {
          best = solution
        }
      }

      if (!done) queue ++= solution.expand(ctx)
    }

    best
  }
}
