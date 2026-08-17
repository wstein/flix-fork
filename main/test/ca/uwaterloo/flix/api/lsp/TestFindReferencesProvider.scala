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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.lsp.provider.FindReferencesProvider
import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests that instance members remain individually addressable through the LSP.
  *
  * Instance-member symbols are content-addressed on `(instance, member name)`, so two
  * declarations that say the same thing are one symbol -- and `FindReferencesProvider`
  * collects occurrences *by symbol*. That makes this provider the consumer where the
  * bound recorded in `docs/adr/0001-source-identity-vs-generated-name-identity.md`
  * either holds or is felt, so both sides of it are pinned here.
  */
class TestFindReferencesProvider extends AnyFunSuite {

  private val Uri: String = CompilerConstants.VirtualTestFile.toString

  /** Compiles `program` and returns its root, which may carry errors. */
  private def rootOf(program: String): TypedAst.Root = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
    flix.check() match {
      case (Some(root), _) => root
      case (None, errors) => fail(s"Compilation produced no root: ${errors.map(_.getClass.getName)}")
    }
  }

  /** Returns the declarations of the member `name` in every instance of the trait `trt`. */
  private def membersOf(root: TypedAst.Root, trt: String, name: String): List[TypedAst.Def] =
    root.instances.m.collect {
      case (sym, instances) if sym.name == trt => instances
    }.flatten.flatMap(_.defs).filter(_.sym.text == name).toList

  /** Returns the references reported at the declaration site of `defn`. */
  private def referencesAt(root: TypedAst.Root, defn: TypedAst.Def): Set[Location] =
    FindReferencesProvider.findRefs(Uri, Position(defn.sym.loc.startLine, defn.sym.loc.startCol))(root)

  private val TwoInstances: String =
    """
      |trait Describable[a] {
      |    pub def describe(x: a): String
      |}
      |
      |instance Describable[Int32] {
      |    pub def describe(_x: Int32): String = "int"
      |}
      |
      |instance Describable[Bool] {
      |    pub def describe(_x: Bool): String = "bool"
      |}
      |""".stripMargin

  private val OverlappingInstances: String =
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

  test("references at one instance member do not reach another instance of the same trait") {
    // The guarantee for a valid program: the two instances have different heads, so their
    // members have different keys, different symbols, and separate reference sets.
    val root = rootOf(TwoInstances)
    val members = membersOf(root, "Describable", "describe")
    assert(members.length == 2)
    assert(members.map(_.sym).distinct.length == 2)

    val first :: second :: Nil = members: @unchecked
    val firstRefs = referencesAt(root, first)
    assert(firstRefs.nonEmpty)
    assert(!firstRefs.exists(_.range.start.line == second.sym.loc.startLine))
  }

  test("overlapping instances share a member symbol, and each query still answers for its own site") {
    // The bound, made visible. Overlapping instances are rejected -- `Instances.run` reports
    // them, four phases after the symbols were minted -- but until then both declarations are
    // in the root an editor sees, sharing one symbol.
    //
    // Navigation survives that because it is entered by *position*: `getDefnSymOccurs` reports
    // the queried symbol's own `loc`, which is the declaration the cursor was on. What it cannot
    // do is relate the two declarations to each other -- neither query mentions the other site --
    // so a consumer that must enumerate both has to key on location rather than on the symbol.
    val root = rootOf(OverlappingInstances)
    val members = membersOf(root, "Describable", "describe")
    assert(members.length == 2)
    assert(members.map(_.sym).distinct.length == 1)

    val first :: second :: Nil = members: @unchecked
    assert(referencesAt(root, first).map(_.range.start.line) == Set(first.sym.loc.startLine))
    assert(referencesAt(root, second).map(_.range.start.line) == Set(second.sym.loc.startLine))
  }

}
