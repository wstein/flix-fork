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
package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input, SecurityContext}
import ca.uwaterloo.flix.language.ast.{ChangeSet, DesugaredAst, NamedAst, SymId, SyntaxTree, WeededAst}
import ca.uwaterloo.flix.language.errors.ResolutionError
import ca.uwaterloo.flix.util.{Options, Validation}
import ca.uwaterloo.flix.TestUtils
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Paths
import java.util.concurrent.ForkJoinPool

/**
  * Characterization fixtures for docs/adr/0001-source-identity-vs-generated-name-identity.md.
  *
  * These pin what the compiler does *today*, not what it should do: an instance member's id is
  * derived from `(instance, member name)`, so two duplicate declarations of one member mint an
  * equal [[ca.uwaterloo.flix.language.ast.Symbol.DefnSym]] before either has been validated.
  * When the ADR's follow-up lands, the assertions marked GAP below are the ones to invert, and
  * the ignored test at the end is the invariant to enable.
  *
  * The front end is driven directly rather than through `Flix.check()` because the gap is only
  * observable in `NamedAst`: `Resolver.checkDuplicateInstanceDefs` filters later duplicates out,
  * so nothing downstream of it holds two declarations to compare.
  */
class TestSourceIdentity extends AnyFunSuite with TestUtils {

  /** Runs the front end up to and including [[Namer]] and returns the named root. */
  private def namedRootOf(source: String, opts: Options = Options.TestWithLibNix): NamedAst.Root = {
    implicit val flix: Flix = new Flix().setOptions(opts)
    // The phases below run on the fork-join pool, which only `Flix.check()` sets up.
    flix.threadPool = new ForkJoinPool(1)
    try {
      val input = Input.VirtualFile(Paths.get("Test.flix"), source, SecurityContext.Unrestricted)
      val (readRoot, _) = Reader.run(List(input), AvailableClasses.empty)
      val (tokens, _) = Lexer.run(readRoot, Map.empty, ChangeSet.Everything)
      val (cst, _) = Parser2.run(tokens, SyntaxTree.empty, ChangeSet.Everything)
      val (weeded, _) = Weeder2.run(readRoot, None, cst, WeededAst.empty, ChangeSet.Everything)
      val weededRoot = weeded match {
        case Validation.Success(r) => r
        case Validation.Failure(errs) => fail(s"Weeder rejected the fixture: ${errs.toList}")
      }
      val desugared = Desugar.run(weededRoot, DesugaredAst.empty, ChangeSet.Everything)
      val (named, _) = Namer.run(desugared)
      named
    } finally {
      flix.threadPool.shutdown()
    }
  }

  /** Returns every instance member declaration in `root`, in declaration order. */
  private def instanceDefs(root: NamedAst.Root): List[NamedAst.Declaration.Def] =
    root.instances.values.flatMap(_.values).flatten.flatMap(_.defs).toList

  /**
    * Returns every top-level def declaration named `name` in `root`, read from the compilation
    * units rather than from `root.symbols`: the symbol table keeps only the first declaration of
    * a duplicated name, so the second is not observable there.
    */
  private def topLevelDefs(root: NamedAst.Root, name: String): List[NamedAst.Declaration.Def] =
    root.units.values.flatMap(_.decls).collect {
      case d: NamedAst.Declaration.Def if d.sym.text == name => d
    }.toList

  private val DuplicateInstanceMember: String =
    """
      |enum Color { case Red }
      |
      |instance Eq[Color] {
      |    pub def eq(x: Color, y: Color): Bool = true
      |    pub def eq(x: Color, y: Color): Bool = false
      |}
      |""".stripMargin

  test("two duplicate instance members are two declarations") {
    val defs = instanceDefs(namedRootOf(DuplicateInstanceMember))
    assert(defs.length == 2)
    // They are genuinely distinct declarations: different source locations, different bodies.
    assert(defs.head.loc != defs(1).loc)
  }

  test("GAP: two duplicate instance members carry one symbol in NamedAst") {
    val defs = instanceDefs(namedRootOf(DuplicateInstanceMember))
    assert(defs.head.sym == defs(1).sym)
    // A consumer that keys pre-validation declarations by symbol therefore sees one, not two.
    assert(defs.groupBy(_.sym).size == 1)
    assert(defs.map(_.sym).distinct.length == 1)
  }

  test("GAP: --Xstable-name-length=0 does not opt these ids out of content-addressing") {
    // The flag documents 0 as "falls back to classic incrementing ids", and Symbol.
    // stableOrCounterId honours it -- but Namer's instance-member site calls StableName.suffix
    // directly, so the opt-out never reaches it and duplicates still share a symbol.
    val opts = Options.TestWithLibNix.copy(xstableNameLength = 0)
    val defs = instanceDefs(namedRootOf(DuplicateInstanceMember, opts))
    assert(defs.head.sym == defs(1).sym)
    assert(defs.head.sym.id.exists(_.isInstanceOf[SymId.Hash]))
  }

  test("the invariant never held for non-member defs") {
    // Two duplicate top-level defs have always shared a symbol -- they carry no id at all --
    // so "distinct declarations have distinct symbols" was never a global property of NamedAst.
    val source =
      """
        |def f(): Bool = true
        |def f(): Bool = false
        |""".stripMargin
    val defs = topLevelDefs(namedRootOf(source), "f")
    assert(defs.length == 2)
    assert(defs.head.sym == defs(1).sym)
    assert(defs.head.sym.id.isEmpty)
  }

  test("GAP: two derivations of one trait mint one derived symbol, and both survive") {
    // The Deriver half of the same gap, observable through the ordinary public API: Resolver
    // reports DuplicateDerivation but does not filter the duplicate out of `derives.traits`, and
    // `Deriver.run`'s fold appends to a multimap rather than replacing, so both derived instances
    // reach TypedAst -- each carrying a def whose symbol is keyed on `Eq[Color]#eq` alone.
    val (result, errors) = check("enum Color with Eq, Eq { case Red }", Options.TestWithLibMin)
    assert(errors.exists(_.isInstanceOf[ResolutionError.DuplicateDerivation]))
    val root = result.getOrElse(fail("Expected a typed root under error recovery."))
    val eqInstances = root.instances.m.collect {
      case (sym, instances) if sym.name == "Eq" => instances
    }.flatten.filter(_.tpe.toString.contains("Color")).toList
    assert(eqInstances.length == 2)
    val syms = eqInstances.flatMap(_.defs.map(_.sym))
    assert(syms.length == 2)
    assert(syms.head == syms(1))
  }

  ignore("INVARIANT (not yet implemented): an instance's member symbols are pairwise distinct") {
    val defs = instanceDefs(namedRootOf(DuplicateInstanceMember))
    assert(defs.map(_.sym).distinct.length == defs.length)
  }

}
