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
 * This file is a port of `lib/src/back_end/solution.dart` from dart_style:
 *
 *   Copyright (c) 2023, the Dart project authors.
 *   Licensed under a BSD-style license. See https://github.com/dart-lang/dart_style
 * ---------------------------------------------------------------------------
 */
package ca.uwaterloo.flix.tools.fmt.layout

/**
  * Everything the solver needs to lay out one piece tree.
  *
  * dart_style threads these five values separately through `Solver`,
  * `Solution` and `CodeWriter`; bundling them removes about forty parameters
  * from the port without changing any behaviour.
  *
  * @param cache            memoised solutions for separately solved subtrees
  * @param root             the piece tree being solved
  * @param pageWidth        the column budget
  * @param leadingIndent    indentation of the first line
  * @param subsequentIndent indentation of every line after the first
  */
final case class SolveContext(cache: SolutionCache,
                              root: Piece,
                              pageWidth: Int,
                              leadingIndent: Int,
                              subsequentIndent: Int)

/**
  * One candidate set of formatting choices.
  *
  * A solution binds some subset of the pieces in the tree to states. Pieces it
  * does not bind are treated as [[State.Unsplit]] unless they are pinned. Given
  * those bindings a [[CodeWriter]] renders the tree, which yields the output,
  * the overflow, and the list of pieces worth expanding next.
  *
  * Solutions are only ever refined in the direction of more splitting, so cost
  * increases monotonically along any path through the search. That is what lets
  * the solver stop at the first zero-overflow solution it dequeues.
  */
final class Solution private(private var boundStates: Solution.StateNode,
                             private var costOfOwnBindings: Int,
                             private var allowedStates: Map[Piece, List[State]]) extends Ordered[Solution] {

  /** Penalty from subtrees that were solved separately and merged in. */
  private var subtreeCost: Int = 0

  private var validFlag: Boolean = true

  /**
    * True once this solution violates a constraint outright.
    *
    * Distinct from `!isValid`: a dead end means every solution derivable from
    * this one is also invalid, so the whole branch can be discarded rather than
    * merely ranked last.
    */
  private var deadEndFlag: Boolean = false

  private var overflowChars: Int = 0

  private var renderedCode: GroupCode = null

  private var piecesToExpand: List[Piece] = Nil

  /** Total penalty, including separately solved subtrees. */
  def cost: Int = costOfOwnBindings + subtreeCost

  def overflow: Int = overflowChars

  /**
    * False if this solution puts a newline where some piece forbids one.
    *
    * An invalid solution ranks below every valid one regardless of cost or
    * overflow.
    */
  def isValid: Boolean = validFlag

  def isDeadEnd: Boolean = deadEndFlag

  def code: GroupCode = renderedCode

  /**
    * The unbound pieces this solution should expand next, or `Nil` if it is a
    * dead end or a winner.
    *
    * [[CodeWriter]] computes this: it is the set of stateful pieces appearing
    * on the '''first''' line that either overflows or carries a forbidden
    * newline. Restricting expansion to one line is the single most important
    * pruning rule in the solver — splitting an earlier piece usually reflows
    * everything after it anyway, so exploring later lines first is wasted work.
    */
  def expandPieces: List[Piece] = piecesToExpand

  /** The state `piece` is pinned or bound to, defaulting to [[State.Unsplit]]. */
  def pieceState(piece: Piece): State = pieceStateIfBound(piece).getOrElse(State.Unsplit)

  /**
    * The state `piece` is pinned or bound to, if any.
    *
    * Walks the binding list linearly. That is fine and is what dart_style does:
    * the number of bound pieces in a solution is small, and a linear walk over
    * a shared-tail list beats allocating a map per solution.
    */
  def pieceStateIfBound(piece: Piece): Option[State] = {
    piece.pinnedState match {
      case some@Some(_) => some
      case None =>
        var node = boundStates
        while (node != null) {
          if (node.piece eq piece) return Some(node.state)
          node = node.parent
        }
        None
    }
  }

  def isBound(piece: Piece): Boolean = pieceStateIfBound(piece).isDefined

  /** Called by [[CodeWriter]] as it discovers characters past the page width. */
  def addOverflow(n: Int): Unit = overflowChars += n

  /** Folds a separately solved subtree's cost and overflow into this solution. */
  def mergeSubtree(subtree: Solution): Unit = {
    overflowChars += subtree.overflowChars
    subtreeCost += subtree.cost
  }

  /**
    * Marks this solution as putting a newline where `piece` forbids one.
    *
    * A pinned piece is exempt: it has no alternative state to move to, so
    * invalidating would discard solutions that are in fact the only ones
    * available.
    */
  def invalidate(piece: Piece): Unit = {
    if (piece.pinnedState.isEmpty) validFlag = false
  }

  /**
    * Derives refined solutions by binding each expandable piece to each state
    * it may still take.
    */
  def expand(ctx: SolveContext): List[Solution] = {
    if (piecesToExpand.isEmpty) return Nil

    val expandable = piecesToExpand.toVector
    val result = List.newBuilder[Solution]

    var i = 0
    while (i < expandable.length) {
      val piece = expandable(i)
      val states = allowedStates.getOrElse(piece, piece.additionalStates)

      states.foreach { state =>
        // Immutable maps share structure, so unlike the Dart original there is
        // nothing to copy here.
        val expanded = new Solution(boundStates, costOfOwnBindings, allowedStates)

        // Earlier expandable pieces have already been explored in their split
        // states by previous iterations, so bind them unsplit here to avoid
        // generating the same combination twice.
        var ok = true
        var j = 0
        while (ok && j < i) {
          expanded.bind(expandable(j), State.Unsplit)
          if (expanded.deadEndFlag) ok = false
          j += 1
        }

        if (ok) {
          expanded.bind(piece, state)
          if (!expanded.deadEndFlag) {
            expanded.render(ctx)
            // Some newline violations only surface during rendering — hard
            // newlines from comments and multi-line strings, mainly.
            if (!expanded.deadEndFlag) result += expanded
          }
        }
      }
      i += 1
    }

    result.result()
  }

  /**
    * Orders solutions best-first: cost, then overflow, then bound states.
    *
    * Formulates a strict total order across solutions so priority queue search
    * behavior is completely deterministic across runs.
    */
  override def compare(that: Solution): Int = {
    if (cost != that.cost) return Integer.compare(cost, that.cost)
    if (overflow != that.overflow) return Integer.compare(overflow, that.overflow)

    // Collect bound states from both solutions in insertion order (oldest first).
    var listA: List[(Piece, State)] = Nil
    var nodeA = boundStates
    while (nodeA != null) {
      listA = (nodeA.piece, nodeA.state) :: listA
      nodeA = nodeA.parent
    }

    var listB: List[(Piece, State)] = Nil
    var nodeB = that.boundStates
    while (nodeB != null) {
      listB = (nodeB.piece, nodeB.state) :: listB
      nodeB = nodeB.parent
    }

    if (listA.length != listB.length) return Integer.compare(listA.length, listB.length)

    var i = 0
    while (i < listA.length) {
      val (pieceA, stateA) = listA(i)
      val (pieceB, stateB) = listB(i)
      if (pieceA ne pieceB) return Integer.compare(pieceA.id, pieceB.id)
      if (stateA != stateB) return stateA.compare(stateB)
      i += 1
    }

    0
  }

  /** Renders this solution, computing its output, overflow and expand set. */
  private def render(ctx: SolveContext): Unit = {
    val writer = new CodeWriter(ctx, this)
    writer.format(ctx.root)
    val (rendered, expand) = writer.finish()
    renderedCode = rendered
    piecesToExpand = expand
  }

  /**
    * Binds `piece` to `state`, then applies whatever that binding constrains,
    * recursively.
    *
    * Marks the solution a dead end on conflict.
    */
  private def bind(piece: Piece, state: State): Unit = {
    if (deadEndFlag) return

    pieceStateIfBound(piece) match {
      case None =>
        costOfOwnBindings += piece.stateCost(state)
        boundStates = new Solution.StateNode(piece, state, boundStates)

        piece.applyConstraints(state, bind)

        if (!deadEndFlag) {
          piece.forEachChild { child =>
            if (!deadEndFlag) {
              val shapes = piece.allowedChildShapes(state, child)
              if (shapes == ShapeSet.OnlyInline) constrainOffspring(child)
            }
          }
        }

      case Some(existing) if existing != state =>
        deadEndFlag = true
        validFlag = false

      case _ => // Already bound to the same state.
    }
  }

  /**
    * Eliminates, throughout `piece`'s subtree, every state that would emit a
    * newline, because `piece`'s parent forbids newlines here.
    *
    * Where that leaves exactly one survivor the piece is bound outright; where
    * it leaves none the solution is a dead end; otherwise the survivors are
    * recorded so [[expand]] does not bother generating the others.
    */
  private def constrainOffspring(piece: Piece): Unit = {
    val offspring = piece.statefulOffspring
    var remaining = offspring

    while (remaining.nonEmpty && !deadEndFlag) {
      val child = remaining.head
      remaining = remaining.tail

      pieceStateIfBound(child) match {
        case Some(bound) =>
          if (child.containsNewline(bound)) {
            deadEndFlag = true
            validFlag = false
          }

        case None if !allowedStates.contains(child) =>
          val unsplitAllowed = !child.containsNewline(State.Unsplit)
          val all = child.additionalStates
          val survivors = all.filterNot(child.containsNewline)

          if (survivors.isEmpty && !unsplitAllowed) {
            deadEndFlag = true
            validFlag = false
          } else if (survivors.isEmpty) {
            bind(child, State.Unsplit)
          } else if (survivors.lengthCompare(1) == 0 && !unsplitAllowed) {
            bind(child, survivors.head)
          } else if (survivors.lengthCompare(all.length) < 0) {
            allowedStates = allowedStates.updated(child, survivors)
          }

        case _ => // Already constrained.
      }
    }
  }

  override def toString: String = {
    val flags = List(
      Some(s"$$${cost}"),
      if (overflow > 0) Some(s"($overflow over)") else None,
      if (!validFlag) Some("(invalid)") else None
    ).flatten
    flags.mkString(" ")
  }
}

object Solution {

  /**
    * A binding in a shared-tail linked list.
    *
    * Deriving a solution copies the map in O(1) by sharing the tail, which is
    * the whole reason this is a list and not a `Map`.
    */
  private final class StateNode(val piece: Piece, val state: State, val parent: StateNode)

  /** Creates the initial, fully unsplit solution for `ctx.root`. */
  def initial(ctx: SolveContext, rootState: Option[State] = None): Solution = {
    val solution = new Solution(null, 0, Map.empty)
    rootState.foreach(state => solution.bind(ctx.root, state))
    solution.render(ctx)
    solution
  }

  /**
    * Ordering for the solver's priority queue.
    *
    * '''Reversed on purpose.''' `scala.collection.mutable.PriorityQueue` is a
    * max-heap and `dequeue` returns the greatest element, whereas the solver
    * needs the least. Handing it the natural ordering would explore the worst
    * solutions first: the search would still terminate, but only by exhausting
    * the attempt cap, and the output would be garbage.
    */
  implicit val minHeapOrdering: Ordering[Solution] =
    (a: Solution, b: Solution) => b.compare(a)
}
