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

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.tools.pkg.PackageModules
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests the format-agnostic model that every documentation backend builds on.
  *
  * The interesting behaviour is not the shape of the tree but what it drops and what it joins:
  * non-public items must never reach a backend, and a companion module must be attached to the
  * declaration it belongs to instead of standing alone as a submodule.
  */
class TestDocumentor extends AnyFunSuite with TestUtils {

  /** Returns the documentation tree of `input`, which must compile without errors. */
  private def build(input: String, packageModules: PackageModules = PackageModules.All): Documentor.Module = {
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
    Documentor.build(result._1.get, packageModules)
  }

  /** Returns the module named `name` somewhere in the tree rooted at `mod`, if any. */
  private def findModule(mod: Documentor.Module, name: String): Option[Documentor.Module] =
    if (mod.sym.ns.lastOption.contains(name)) Some(mod)
    else mod.submodules.view.flatMap(findModule(_, name)).headOption

  test("build.enum.01") {
    val root = build(
      """
        |pub enum Color {
        |    case Red
        |    case Green
        |}
        |""".stripMargin)

    val enums = root.enums.map(_.name)
    assert(enums.contains("Color"), s"expected the tree to document 'Color', but found: $enums")
  }

  test("build.companion.01") {
    // The companion module of `Color` must hang off the enum, not off the root.
    val root = build(
      """
        |pub enum Color {
        |    case Red
        |}
        |
        |mod Color {
        |    pub def isRed(c: Color): Bool = match c {
        |        case Color.Red => true
        |    }
        |}
        |""".stripMargin)

    val color = root.enums.find(_.name == "Color").getOrElse(fail("expected 'Color' to be documented"))
    val companion = color.companionMod.getOrElse(fail("expected 'Color' to have a companion module"))

    assert(companion.defs.map(_.sym.name) == List("isRed"))
    assert(!root.submodules.exists(_.name == "Color"), "the companion module must not also appear as a submodule")
  }

  test("build.private.01") {
    // A non-public definition is not part of the public API and must not be documented.
    val root = build(
      """
        |mod Api {
        |    pub def visible(): Int32 = hidden()
        |    def hidden(): Int32 = 2
        |}
        |""".stripMargin)

    val api = findModule(root, "Api").getOrElse(fail("expected 'Api' to be documented"))
    assert(api.defs.map(_.sym.name) == List("visible"))
  }

  test("build.private.02") {
    // A module holding only non-public items has nothing to document and must be dropped entirely.
    val root = build(
      """
        |mod Empty {
        |    type alias Hidden = String
        |}
        |""".stripMargin)

    assert(findModule(root, "Empty").isEmpty, "a module with no public items must be dropped")
  }

  test("build.instances.01") {
    // Instances are collected onto the enum they apply to, including parameterized ones.
    val root = build(
      """
        |pub enum Box[a] {
        |    case Box(a)
        |}
        |
        |pub trait Describe[a] {
        |    pub def describe(x: a): String
        |}
        |
        |instance Describe[Box[a]] with Describe[a] {
        |    pub def describe(_x: Box[a]): String = "box"
        |}
        |""".stripMargin)

    val box = root.enums.find(_.name == "Box").getOrElse(fail("expected 'Box' to be documented"))
    assert(box.instances.map(_.trt.sym.name) == List("Describe"))
  }

  test("build.selected.01") {
    // Only the selected modules are documented, but the spine leading to them is kept.
    val input =
      """
        |mod Included {
        |    pub def f(): Int32 = 1
        |}
        |
        |mod Excluded {
        |    pub def g(): Int32 = 2
        |}
        |""".stripMargin

    val selected = PackageModules.Selected(Set(Symbol.mkModuleSym(List("Included"))))
    val root = build(input, selected)

    assert(findModule(root, "Included").exists(_.defs.map(_.sym.name) == List("f")))
    assert(findModule(root, "Excluded").isEmpty, "a module outside the selection must not be documented")
  }

  test("fileName.01") {
    val root = Symbol.mkModuleSym(Nil)
    assert(Documentor.moduleFileName(root, "md") == "index.md")
    assert(Documentor.moduleFileName(root, "html") == "index.html")
  }

  test("fileName.02") {
    val sym = Symbol.mkModuleSym(List("System", "StdOut"))
    assert(Documentor.moduleFileName(sym, "md") == "System.StdOut.md")
    assert(Documentor.moduleQualifiedName(sym) == "System.StdOut")
    assert(Documentor.moduleName(sym) == "StdOut")
  }

  test("fileName.03") {
    // `fileName` must agree with the per-symbol helper for every kind of item.
    val root = build(
      """
        |pub enum Color {
        |    case Red
        |}
        |""".stripMargin)

    val color: Documentor.Item = root.enums.find(_.name == "Color").getOrElse(fail("expected 'Color' to be documented"))
    assert(Documentor.fileName(color, "md") == "Color.md")
  }

  test("build.typeAlias.01") {
    val root = build(
      """
        |mod Api {
        |    pub type alias Name = String
        |}
        |""".stripMargin)

    val api = findModule(root, "Api").getOrElse(fail("expected 'Api' to be documented"))
    assert(api.typeAliases.map(_.sym.name) == List("Name"))
  }

  test("build.trait.01") {
    // Signatures without a default implementation and trait definitions with one are kept apart.
    val root = build(
      """
        |pub trait Greet[a] {
        |    pub def name(x: a): String
        |    pub def greet(_x: a): String = "hello"
        |}
        |""".stripMargin)

    val greet = root.traits.find(_.name == "Greet").getOrElse(fail("expected 'Greet' to be documented"))
    assert(greet.signatures.map(_.sym.name) == List("name"))
    assert(greet.defs.map(_.sym.name) == List("greet"))
  }

  test("build.effect.01") {
    val root = build(
      """
        |pub eff Ask {
        |    def ask(): String
        |}
        |""".stripMargin)

    val ask = root.effects.find(_.name == "Ask").getOrElse(fail("expected 'Ask' to be documented"))
    assert(ask.decl.ops.map(_.sym.name) == List("ask"))
  }

  test("build.root.01") {
    // The root module has no parent and reports the pseudo-name used on the pages.
    val root: Documentor.Module = build("pub def f(): Int32 = 1")
    assert(root.parent.isEmpty)
    assert(root.qualifiedName == Documentor.RootNS)
    assert(root.defs.exists(_.sym.name == "f"))
  }

  test("build.doc.01") {
    // Documentation comments survive into the tree; a backend has nothing to render without them.
    val root = build(
      """
        |mod Api {
        |    ///
        |    /// Returns one.
        |    ///
        |    pub def one(): Int32 = 1
        |}
        |""".stripMargin)

    val api = findModule(root, "Api").getOrElse(fail("expected 'Api' to be documented"))
    val one: TypedAst.Def = api.defs.find(_.sym.name == "one").getOrElse(fail("expected 'one' to be documented"))
    assert(one.spec.doc.text.contains("Returns one."))
  }
}
