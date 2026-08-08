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

import scala.collection.mutable

/**
  * Memoises the solution for a subtree keyed by the piece, the state it is
  * pinned to, and the indentation it is being solved at.
  *
  * Two different parent solutions frequently need the same subtree laid out
  * identically; without this the same work is redone once per candidate.
  */
final class SolutionCache {
  private val cache = mutable.Map.empty[(Piece, Option[State], Int, Int), Solution]

  def find(ctx: SolveContext, piece: Piece, state: Option[State]): Solution = {
    val key = (piece, state, ctx.leadingIndent, ctx.subsequentIndent)
    cache.getOrElseUpdate(key, {
      val subCtx = ctx.copy(root = piece)
      Solver.solve(subCtx, state)
    })
  }

  def clear(): Unit = cache.clear()
}
