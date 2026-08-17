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
package ca.uwaterloo.flix.util

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{Name, SourceLocation, SymId, Symbol}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Pins the generated-name format: its version, and one rendered id per key it is asked for.
  *
  * The version is part of every preimage, so bumping it necessarily changes every generated
  * name -- and these goldens with it. That is the point: a key shape cannot change quietly,
  * because the goldens move, and the version cannot move quietly, because they move too. A
  * failure here is a prompt to decide which of the two happened, not to re-record the values.
  */
class TestNameFormat extends AnyFunSuite {

  /** Returns the rendered id of `sym`, failing if it is not content-addressed. */
  private def idOf(sym: Symbol): String = {
    val id = sym match {
      case s: Symbol.DefnSym => s.id
      case s: Symbol.EnumSym => s.id
      case s: Symbol.StructSym => s.id
      case s: Symbol.AnonClassSym => s.id
      case other => fail(s"unexpected symbol kind: $other")
    }
    id match {
      case Some(SymId.Hash(value)) => value
      case other => fail(s"expected a content-addressed id, got $other")
    }
  }

  test("version.01") {
    assert(NameFormat.Version == 1)
  }

  test("preimage.CarriesVersion.01") {
    assert(NameFormat.preimage("Some.key") == "v1|Some.key")
  }

  test("preimage.PrefixesEveryKey.01") {
    // Whatever the key, the version is in front of it: that is what makes a bump reach every
    // generated name rather than only the ones whose key shape changed.
    for (key <- List("", "a", "Eq[Color]#eq", "List.map|Int32", "f#lift0")) {
      assert(NameFormat.preimage(key).startsWith(s"v${NameFormat.Version}|"))
      assert(NameFormat.preimage(key).endsWith(key))
    }
  }

  test("goldens.EveryFamily.01") {
    // One rendered id per mint function, at the default width and the current version. If a
    // key shape changes, these move; if [[NameFormat.Version]] changes, these move. Either
    // way the change is deliberate before it is recorded, which is the whole point of pinning
    // them -- do not regenerate without deciding which of the two happened.
    implicit val flix: Flix = new Flix().setOptions(Options.Default)
    val enclosingDef = Symbol.mkDefnSym("Test.f")
    assert(idOf(Symbol.specializedDefnSym(Symbol.mkDefnSym("List.map"), "List.map|Int32")) == "ft7l0liiukqu")
    assert(idOf(Symbol.liftedDefnSym(enclosingDef, 0)) == "90q8hne9ba64")
    assert(idOf(Symbol.memberDefnSym(Name.RootNS, Name.Ident("eq", SourceLocation.Unknown), "Eq[Color]")) == "rpr4bu6fe9as")
    assert(idOf(Symbol.specializedEnumSym(Symbol.mkEnumSym("List"), "List[Int32]")) == "wj8o0ika2ukv")
    assert(idOf(Symbol.specializedStructSym(Symbol.mkStructSym(Name.RootNS, Name.Ident("S", SourceLocation.Unknown)), "S[Int32]")) == "43w3gpkmd4gc")
    assert(idOf(Symbol.specializedAnonClassSym(enclosingDef, 0, SourceLocation.Unknown)) == "ppb09k9eeeq7")
  }

  test("collisionAdvice.AtSupportedWidth.01") {
    val advice = NameFormat.collisionAdvice(StableName.DefaultWidth)
    assert(advice.contains(s"${StableName.DefaultWidth} base-36 digits"))
    assert(advice.contains("key defect"))
  }

  test("collisionAdvice.BelowSupportedWidth.01") {
    val advice = NameFormat.collisionAdvice(4)
    assert(advice.contains("4 base-36 digits"))
    assert(advice.contains("expected"))
  }

}
