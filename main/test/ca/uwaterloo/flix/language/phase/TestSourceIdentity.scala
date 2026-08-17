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
import ca.uwaterloo.flix.language.errors.{InstanceError, NameError, ResolutionError}
import ca.uwaterloo.flix.util.{Options, Validation}
import ca.uwaterloo.flix.TestUtils
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Paths
import java.util.concurrent.ForkJoinPool

/**
  * The evidence behind docs/adr/0001-source-identity-vs-generated-name-identity.md.
  *
  * An instance member's id is derived from `(instance, member name)`, so two declarations that
  * say the same thing mint an equal [[ca.uwaterloo.flix.language.ast.Symbol.DefnSym]]. The ADR
  * accepts that and bounds the claim instead: *in a program that passes validation, distinct
  * declarations have distinct symbols*. These tests hold both halves of that sentence -- the
  * cases where duplicates share a symbol, and the invariant on the validated population.
  *
  * Some of them drive the front end directly rather than going through `Flix.check()`, because
  * a shared symbol among duplicate *members* is only observable in `NamedAst`:
  * `Resolver.checkDuplicateInstanceDefs` filters the repeat, so nothing downstream of it holds
  * two declarations to compare.
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

  test("two duplicate instance members carry one symbol in NamedAst") {
    val defs = instanceDefs(namedRootOf(DuplicateInstanceMember))
    assert(defs.head.sym == defs(1).sym)
    // A consumer that keys pre-validation declarations by symbol therefore sees one, not two.
    assert(defs.groupBy(_.sym).size == 1)
    assert(defs.map(_.sym).distinct.length == 1)
  }

  test("--Xstable-name-length=0 opts instance-member ids out of content-addressing") {
    // The flag documents 0 as "falls back to classic incrementing ids". Opted out, the ids come
    // from the GenSym counter, so two declarations of one member are distinct symbols again.
    val opts = Options.TestWithLibNix.copy(xstableNameLength = 0)
    val defs = instanceDefs(namedRootOf(DuplicateInstanceMember, opts))
    assert(defs.forall(_.sym.id.exists(_.isInstanceOf[SymId.Counter])))
    assert(defs.head.sym != defs(1).sym)
  }

  test("--Xstable-name-length=0 opts derived-def ids out of content-addressing") {
    val opts = Options.TestWithLibMin.copy(xstableNameLength = 0)
    val (result, _) = check("enum Color with Eq { case Red }", opts)
    val root = result.getOrElse(fail("Expected a typed root."))
    val derived = root.instances.m.collect {
      case (sym, instances) if sym.name == "Eq" => instances
    }.flatten.filter(_.tpe.toString.contains("Color")).flatMap(_.defs).toList
    assert(derived.nonEmpty)
    assert(derived.forall(_.sym.id.exists(_.isInstanceOf[SymId.Counter])))
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

  test("a repeated derivation yields one derived instance, not two") {
    // `Deriver.run`'s fold appends into a multimap rather than replacing, so a duplicate
    // derivation that reached it would produce two instances whose members share one symbol.
    // Resolver drops the repeat instead, which is what keeps the derived population distinct.
    val (result, errors) = check("enum Color with Eq, Eq { case Red }", Options.TestWithLibMin)
    assert(errors.exists(_.isInstanceOf[ResolutionError.DuplicateDerivation]))
    val root = result.getOrElse(fail("Expected a typed root under error recovery."))
    val eqInstances = root.instances.m.collect {
      case (sym, instances) if sym.name == "Eq" => instances
    }.flatten.filter(_.tpe.toString.contains("Color")).toList
    assert(eqInstances.length == 1)
    assert(eqInstances.flatMap(_.defs.map(_.sym)).distinct.length == 1)
  }

  test("two overlapping instances mint one member symbol") {
    // The case an ordinal within one instance cannot reach: the two members are content-identical
    // and live in *different* instance declarations, so `(instance, member name)` is the same key
    // for both, and nothing local to either declaration distinguishes them. Shown here in one
    // file for brevity; the two declarations may equally live in different files, where no
    // per-declaration ordinal exists at all.
    val source =
      """
        |trait Describable[a] {
        |    pub def describe(x: a): String
        |}
        |
        |instance Describable[Int32] {
        |    pub def describe(_x: Int32): String = "first"
        |}
        |
        |instance Describable[Int32] {
        |    pub def describe(_x: Int32): String = "second"
        |}
        |""".stripMargin
    val defs = instanceDefs(namedRootOf(source))
    assert(defs.length == 2)
    assert(defs.head.loc != defs(1).loc)
    assert(defs.head.sym == defs(1).sym)
  }

  test("overlapping instances are gated only by Instances.run, after Typer") {
    // Nothing before Instances.run rejects two instances of one trait at one type: Namer tables
    // instances in a list, Resolver has no overlap check, and Kinder/Deriver/Typer all run with
    // both present. So a recovered root -- the one every LSP provider sees -- holds two instances
    // whose member symbols are equal, and the only error saying otherwise is raised after Typer.
    val source =
      """
        |trait Describable[a] {
        |    pub def describe(x: a): String
        |}
        |
        |instance Describable[Int32] {
        |    pub def describe(_x: Int32): String = "first"
        |}
        |
        |instance Describable[Int32] {
        |    pub def describe(_x: Int32): String = "second"
        |}
        |""".stripMargin
    val (result, errors) = check(source, Options.TestWithLibMin)
    assert(errors.exists(_.isInstanceOf[InstanceError.OverlappingInstances]))
    // No earlier phase objected at all: overlap is the *only* complaint the compiler makes.
    assert(errors.forall(_.isInstanceOf[InstanceError.OverlappingInstances]),
      s"unexpected: ${errors.map(_.getClass.getName).distinct}")
    val root = result.getOrElse(fail("Expected a typed root under error recovery."))
    val instances = root.instances.m.collect {
      case (sym, is) if sym.name == "Describable" => is
    }.flatten.toList
    assert(instances.length == 2)
    val syms = instances.flatMap(_.defs.map(_.sym))
    assert(syms.length == 2)
    assert(syms.head == syms(1))
  }

  test("INVARIANT: a validated program's instance members have pairwise distinct symbols") {
    // The property the ADR commits to. Written instances of one trait at different types,
    // written instances of different traits, and a derived instance all coexist here; every
    // member of every one of them must be individually addressable.
    val source =
      """
        |trait Describable[a] {
        |    pub def describe(x: a): String
        |}
        |
        |enum Color with Eq, ToString { case Red }
        |
        |instance Describable[Int32] {
        |    pub def describe(_x: Int32): String = "int"
        |}
        |
        |instance Describable[Bool] {
        |    pub def describe(_x: Bool): String = "bool"
        |}
        |
        |instance Describable[Color] {
        |    pub def describe(_x: Color): String = "color"
        |}
        |""".stripMargin
    val (result, errors) = check(source, Options.TestWithLibMin)
    assert(errors.isEmpty, s"expected a clean program, got: ${errors.map(_.getClass.getName)}")
    val root = result.getOrElse(fail("Expected a typed root."))
    val members = root.instances.values.flatMap(_.defs).toList
    assert(members.length > 4)
    assert(members.map(_.sym).distinct.length == members.length)
  }

}
