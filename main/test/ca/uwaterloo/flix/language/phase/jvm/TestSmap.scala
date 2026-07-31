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
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.shared.{Input, SecurityContext}
import ca.uwaterloo.flix.language.ast.{SourceLocation, SourcePosition}
import ca.uwaterloo.flix.language.ast.shared.Source
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Path

class TestSmap extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** Returns a source named `name` with `lines` lines. */
  private def mkSource(name: String, lines: Int): Source = {
    val text = List.fill(lines)("x").mkString("\n")
    Source(Input.VirtualFile(Path.of(name), text, sctx), text.toCharArray)
  }

  /** Returns a location on `line` of `src`. */
  private def mkLoc(src: Source, line: Int): SourceLocation =
    SourceLocation.point(isReal = true, src, SourcePosition.mkFromOneIndexed(line, 1))

  private val className: JvmName = JvmName(JvmName.packageOfNamespace(Nil), "Def$example")

  test("register: the primary source keeps the identity mapping") {
    val primary = mkSource("Main.flix", 20)
    val smap = new Smap(primary)
    assertResult(7)(smap.register(mkLoc(primary, 7)))
    assertResult(1)(smap.register(mkLoc(primary, 1)))
    assertResult(20)(smap.register(mkLoc(primary, 20)))
  }

  test("build: a single-source class needs no mapping") {
    val primary = mkSource("Main.flix", 20)
    val smap = new Smap(primary)
    smap.register(mkLoc(primary, 3))
    assertResult(None)(smap.build(className))
  }

  test("register: foreign lines are allocated above the primary source") {
    val primary = mkSource("Main.flix", 14)
    val foreign = mkSource("Array.flix", 200)
    val smap = new Smap(primary)

    // The primary source occupies 1..14, so foreign lines start at 15.
    assertResult(15)(smap.register(mkLoc(foreign, 114)))
    assertResult(16)(smap.register(mkLoc(foreign, 7)))
  }

  test("register: the same foreign line is only allocated once") {
    val primary = mkSource("Main.flix", 14)
    val foreign = mkSource("Array.flix", 200)
    val smap = new Smap(primary)

    val first = smap.register(mkLoc(foreign, 114))
    val second = smap.register(mkLoc(foreign, 114))
    assertResult(first)(second)
  }

  test("build: emits a well-formed SMAP for a multi-source class") {
    val primary = mkSource("Main.flix", 14)
    val foreign = mkSource("Array.flix", 200)
    val smap = new Smap(primary)
    smap.register(mkLoc(primary, 9))
    smap.register(mkLoc(foreign, 114))

    val expected =
      """SMAP
        |Def$example.flix
        |Flix
        |*S Flix
        |*F
        |+ 1 Main.flix
        |Main.flix
        |+ 2 Array.flix
        |Array.flix
        |*L
        |1#1,14:1
        |114#2,1:15
        |*E
        |""".stripMargin

    assertResult(Some(expected))(smap.build(className))
  }

  test("build: distinct foreign sources get distinct file ids") {
    val primary = mkSource("Main.flix", 5)
    val first = mkSource("List.flix", 100)
    val second = mkSource("Map.flix", 100)
    val smap = new Smap(primary)
    smap.register(mkLoc(first, 30))
    smap.register(mkLoc(second, 40))

    val actual = smap.build(className).get
    assert(actual.contains("+ 2 List.flix"))
    assert(actual.contains("+ 3 Map.flix"))
    assert(actual.contains("30#2,1:6"))
    assert(actual.contains("40#3,1:7"))
  }

  test("build: bails out when synthetic lines would exceed the u2 range") {
    val primary = mkSource("Main.flix", 65535)
    val foreign = mkSource("Array.flix", 10)
    val smap = new Smap(primary)
    smap.register(mkLoc(foreign, 3))
    assertResult(None)(smap.build(className))
  }

}
