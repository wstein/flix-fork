/*
 * Copyright 2026 Flix Authors
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
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.TypedAst.Root
import ca.uwaterloo.flix.language.phase.{Documentor, SvgDocumentor}
import ca.uwaterloo.flix.tools.pkg.PackageModules

/**
  * The structural diagram of a trait or module, as an SVG.
  *
  * Both servers answer this question -- the VS Code protocol as `getDiagram`, standard LSP as the
  * `flix.showDiagram` command -- so the lookup lives here rather than in either of them. Two copies
  * would drift, and the way they would drift is invisible: a client asking the other server would
  * get a different answer for the same symbol.
  */
object DiagramProvider {

  /** What a request for one item's diagram can produce. */
  sealed trait Result

  object Result {
    /** The item has a diagram. */
    case class Svg(svg: String) extends Result

    /** The item exists, but has no supertrait or submodule relationships to draw. */
    case class NoDiagram(itemName: String) extends Result

    /** No trait or module of that name is in the current compilation. */
    case class Unknown(itemName: String) extends Result
  }

  /**
    * Diagrams for one compilation, generated on first use.
    *
    * Generating every diagram walks the whole module tree, which is far too much to repeat per
    * hover, so it is done once and reused. The cache remembers *which* root it was built from and
    * rebuilds when it is handed a different one, so it cannot serve results from a stale
    * compilation -- and no caller has to remember to invalidate it, which is the kind of obligation
    * that is honoured on one code path and forgotten on the other.
    */
  final class Cache {
    private var cachedRoot: Root = TypedAst.empty
    private var diagrams: Map[String, String] = Map.empty
    private var knownNames: Set[String] = Set.empty

    private def refresh(root: Root)(implicit flix: Flix): Unit = {
      // Reference equality: a compilation produces a new root, and comparing these structurally
      // would cost more than the regeneration it is meant to avoid.
      if (!(cachedRoot eq root)) {
        val moduleTree = Documentor.build(root, PackageModules.All)
        diagrams = SvgDocumentor.generateAll(moduleTree)
        knownNames = SvgDocumentor.allItemNames(moduleTree)
        cachedRoot = root
      }
    }

    private[provider] def lookup(itemName: String, root: Root)(implicit flix: Flix): Result = {
      if (root == TypedAst.empty) return Result.Unknown(itemName)
      refresh(root)
      diagrams.get(s"$itemName.svg") match {
        case Some(svg) => Result.Svg(svg)
        case None if knownNames.contains(itemName) => Result.NoDiagram(itemName)
        case None => Result.Unknown(itemName)
      }
    }
  }

  /** The diagram for `itemName`, generating this compilation's diagrams if not already done. */
  def getDiagram(itemName: String, cache: Cache)(implicit root: Root, flix: Flix): Result =
    cache.lookup(itemName, root)

  /** The message a client should show for a result that carries no diagram. */
  def messageFor(result: Result): String = result match {
    case Result.Svg(_) => ""
    case Result.NoDiagram(name) =>
      s"'$name' exists but has no structural diagram (no supertrait or submodule relationships)."
    case Result.Unknown(name) =>
      s"Unknown item '$name': no such trait or module found in the current compilation."
  }
}
