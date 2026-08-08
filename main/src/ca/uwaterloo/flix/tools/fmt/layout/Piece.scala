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
 * This file is a port of `lib/src/piece/piece.dart` from dart_style:
 *
 *   Copyright (c) 2023, the Dart project authors.
 *   Licensed under a BSD-style license. See https://github.com/dart-lang/dart_style
 *
 * The design (Piece / State / Shape / constraint propagation) is theirs; the
 * Scala rendering and the Flix-specific deviations noted below are not.
 * ---------------------------------------------------------------------------
 */
package ca.uwaterloo.flix.tools.fmt.layout

import java.util.concurrent.atomic.AtomicInteger

/**
  * A node in the formatter's layout tree.
  *
  * Lowering walks a [[ca.uwaterloo.flix.language.ast.SyntaxTree.Tree]] and
  * produces a tree of `Piece`s. It roughly follows the syntax tree, but it
  * folds comments in and is shaped for line splitting rather than for
  * semantics. The output is then determined by choosing, for every piece, which
  * of its [[State]]s it takes.
  *
  * A piece has two lifecycle phases and they must not overlap:
  *
  *   1. '''Construction.''' Children are attached and [[pin]] / [[preventSplit]]
  *      may be called. Nothing may read [[containsHardNewline]],
  *      [[totalCharacters]] or [[statefulOffspring]] yet — they are cached on
  *      first access and would capture an incomplete tree.
  *   2. '''Solving.''' The piece is immutable. The solver reads the cached
  *      metrics freely and calls [[format]] many times with different states.
  *
  * Scala makes phase 1 easy to violate, because a `lazy val` looks like a plain
  * field. If you add a piece subclass, do not touch those three members from a
  * constructor.
  */
abstract class Piece {

  /**
    * A cheap, stable identifier, used for hashing and debug output.
    *
    * Piece equality is reference equality — two structurally identical pieces at
    * different places in the tree are different pieces — so `hashCode` must not
    * be derived from contents. It must also not be derived from
    * `System.identityHashCode`, which varies between JVM runs and would make
    * any hash-ordered traversal nondeterministic.
    *
    * Nothing in the solver may *order* by `id`. Ids are allocated in tree
    * construction order within a single run, but the counter is global, so
    * absolute values differ when files are formatted in parallel.
    */
  final val id: Int = Piece.freshId()

  final override def hashCode(): Int = id

  final override def equals(that: Any): Boolean = that match {
    case other: AnyRef => this eq other
    case _ => false
  }

  /**
    * Every way this piece can split, in increasing order of splitness.
    *
    * [[State.Unsplit]] is always implicitly available and is not listed here.
    * Each piece decides what its states mean. The list must be sorted so that
    * earlier states compare less than later ones — the solver explores in this
    * order and its termination argument depends on it.
    */
  def additionalStates: List[State] = Nil

  private var pinned: Option[State] = None

  /**
    * The state this piece has been forced into, if any.
    *
    * Used when the surrounding context makes one choice mandatory, so the
    * solver need never consider the others. Purely a search-space reduction
    * where the outcome is already determined, plus a way to express rules like
    * "nested `if`-expressions always split".
    */
  def pinnedState: Option[State] = pinned

  /**
    * Whether this piece or any descendant emits a newline no matter what state
    * it is in.
    *
    * In Flix this is true for exactly two things: a `//` line comment (which
    * swallows the rest of the line) and a multi-line string literal. Unlike Elm
    * or Haskell, no construct forces a break for layout reasons, because Flix
    * has no off-side rule.
    */
  lazy val containsHardNewline: Boolean = calculateContainsHardNewline

  protected def calculateContainsHardNewline: Boolean = {
    var any = false
    forEachChild(child => any |= child.containsHardNewline)
    any
  }

  /** The total number of content characters in this piece and its descendants. */
  lazy val totalCharacters: Int = calculateTotalCharacters

  protected def calculateTotalCharacters: Int = {
    var total = 0
    forEachChild(child => total += child.totalCharacters)
    total
  }

  /**
    * Constraints this piece imposes on other pieces when it is in `state`.
    *
    * Call `constrain(child, requiredState)` for each piece that must follow.
    * The solver applies these transitively, so a single binding can settle a
    * whole subtree.
    */
  def applyConstraints(state: State, constrain: (Piece, State) => Unit): Unit = ()

  /**
    * Which shapes `child` may take while this piece is in `state`, as a
    * [[ShapeSet]] bitmask.
    */
  def allowedChildShapes(state: State, child: Piece): Int = ShapeSet.All

  /**
    * Whether this piece '''always''' emits a newline in `state`.
    *
    * Must be conservative: return `false` if the piece merely *might* split, or
    * the solver will prune valid solutions.
    */
  def containsNewline(state: State): Boolean =
    state != State.Unsplit || containsHardNewline

  /** Writes this piece's output for `state`. Called once per solution attempt. */
  def format(writer: CodeWriter, state: State): Unit

  /** Invokes `f` on each direct child. */
  def forEachChild(f: Piece => Unit): Unit

  /**
    * The state this piece is guaranteed to end up in given `pageWidth`, if that
    * can be determined from [[containsHardNewline]] and [[totalCharacters]]
    * alone.
    *
    * Example: an infix chain whose operands total more than a page will always
    * split one operand per line. Deciding that up front prunes an enormous
    * amount of search, because for a large expression most of the outer pieces
    * are in this situation.
    *
    * '''This is purely an optimisation.''' Running the solver with this always
    * returning `None` must produce byte-identical output. Any subclass that
    * overrides it and gets it wrong introduces a bug that only shows up on
    * large inputs, so the fixture suite should cover both settings.
    */
  def fixedStateForPageWidth(pageWidth: Int): Option[State] = None

  /** The penalty this piece contributes when bound to `state`. */
  def stateCost(state: State): Int = state.cost

  /**
    * Forces this piece into `state`, and transitively pins whatever `state`
    * constrains.
    *
    * Idempotent by first-wins: a piece already pinned keeps its original state.
    * This matters because [[fixedStateForPageWidth]] may try to pin something
    * that lowering already pinned for a structural reason.
    */
  final def pin(state: State): Unit = {
    if (pinned.isEmpty) {
      pinned = Some(state)
      applyConstraints(state, (other, constrained) => other.pin(constrained))
    }
  }

  /** Pins this piece to whatever state prevents it from splitting. */
  def preventSplit(): Unit = pin(State.Unsplit)

  /**
    * This piece and all transitive descendants that have more than one state.
    *
    * Cached, because constraint propagation needs it repeatedly and walking the
    * full tree means stepping over the many stateless pieces (adjacency, text,
    * sequence glue) that make up most of the nodes.
    */
  lazy val statefulOffspring: List[Piece] = {
    val buf = List.newBuilder[Piece]
    def traverse(piece: Piece): Unit = {
      if (piece.additionalStates.nonEmpty) buf += piece
      piece.forEachChild(traverse)
    }
    traverse(this)
    buf.result()
  }

  /** The name used in debug output. */
  def debugName: String = getClass.getSimpleName.replace("Piece", "")

  override def toString: String = s"$debugName$id${pinned.getOrElse("")}"
}

object Piece {
  private val counter = new AtomicInteger(0)

  private def freshId(): Int = counter.incrementAndGet()
}

/**
  * One way a piece can be split.
  *
  * States are interpreted per piece: `State(1)` means whatever the piece that
  * declared it says it means. Two states are only ever compared when they
  * belong to the same piece, so equality and ordering are defined on `value`
  * alone and deliberately ignore `cost`. That keeps `equals` consistent with
  * `compare`, which the solver's total ordering depends on.
  */
final class State(val value: Int, val cost: Int) extends Ordered[State] {

  override def compare(that: State): Int = Integer.compare(value, that.value)

  override def equals(that: Any): Boolean = that match {
    case other: State => value == other.value
    case _ => false
  }

  override def hashCode(): Int = value

  override def toString: String = s"◦$value"
}

object State {
  def apply(value: Int, cost: Int = 1): State = new State(value, cost)

  /** No splitting. Free — it is the baseline every solution starts from. */
  val Unsplit: State = new State(0, 0)

  /**
    * The maximally split state.
    *
    * The value is arbitrary; it only has to exceed anything a piece uses for
    * its intermediate states.
    */
  val Split: State = new State(255, 1)
}

/**
  * The spatial shape of a formatted piece.
  *
  * Most style rules are expressible as "a newline in this child forces the
  * parent to split". Some are not: a `def` body may be a block spanning many
  * lines without forcing the signature to break, while an arbitrary multi-line
  * expression in that position should force it. Shapes let a parent say which
  * kind of multi-line-ness it tolerates.
  */
sealed trait Shape {
  def bit: Int
}

object Shape {

  /** Fits entirely on one line. */
  case object Inline extends Shape {
    val bit = 1
  }

  /**
    * A delimited, block-indented structure: a `{ ... }` body, a record literal,
    * a list, an argument list.
    */
  case object Block extends Shape {
    val bit = 2
  }

  /**
    * One leading line of "header", then further indented lines. For Flix this
    * is the shape of a `|>` pipeline and of a split `if`/`else`.
    */
  case object Headline extends Shape {
    val bit = 4
  }

  /** Multi-line with no particular shape. */
  case object Other extends Shape {
    val bit = 8
  }

  /**
    * The shape of a parent that contains children of shapes `a` and `b`.
    *
    * `Inline` is the identity: an inline child does not constrain the parent.
    * Anything else combined with anything else degrades to `Other`.
    */
  def merge(a: Shape, b: Shape): Shape = (a, b) match {
    case (Inline, _) => b
    case (_, Inline) => a
    case _ => Other
  }
}

/**
  * A set of [[Shape]]s, represented as an `Int` bitmask.
  *
  * This is on the hot path — [[Solution.bind]] queries it for every child of
  * every bound piece — so it is a bitmask rather than a `Set[Shape]`. Scala 2.13
  * has no opaque types, so these are plain `Int`s; the naming is the only thing
  * keeping them honest.
  */
object ShapeSet {
  final val Empty: Int = 0
  final val OnlyInline: Int = Shape.Inline.bit
  final val OnlyBlock: Int = Shape.Block.bit
  final val InlineOrBlock: Int = Shape.Inline.bit | Shape.Block.bit
  final val All: Int = Shape.Inline.bit | Shape.Block.bit | Shape.Headline.bit | Shape.Other.bit

  def contains(set: Int, shape: Shape): Boolean = (set & shape.bit) != 0

  def size(set: Int): Int = Integer.bitCount(set)

  /** Everything if `condition`, otherwise no newlines at all. */
  def anyIf(condition: Boolean): Int = if (condition) All else OnlyInline

  def toString(set: Int): String = {
    val names = List(Shape.Inline, Shape.Block, Shape.Headline, Shape.Other)
      .filter(contains(set, _))
      .map(_.toString)
    names.mkString("{", ", ", "}")
  }
}
