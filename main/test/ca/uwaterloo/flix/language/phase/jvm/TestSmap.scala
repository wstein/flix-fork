/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.shared.{Origin, SecurityContext, Source, SourceName}
import ca.uwaterloo.flix.language.ast.{SourceLocation, SourcePosition}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.ClassDesc
import java.net.URI
import java.nio.file.Path

class TestSmap extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private def source(name: String, lines: Int): Source = {
    val text = List.fill(lines)("x").mkString("\n")
    Source.fromString(SourceName.PathName(Path.of(name)), Origin.User, sctx, text)
  }

  private def source(name: SourceName, lines: Int): Source = {
    val text = List.fill(lines)("x").mkString("\n")
    Source.fromString(name, Origin.User, sctx, text)
  }

  private def location(source: Source, line: Int): SourceLocation =
    SourceLocation.point(isReal = true, source, SourcePosition.mkFromOneIndexed(line, 1))

  private val ClassName: ClassDesc = ClassDesc.of("Def$example")

  test("primary source lines retain their numbers") {
    val primary = source("Main.flix", 20)
    val smap = new Smap(primary)
    assertResult(List(7, 1, 20))(List(7, 1, 20).map(line => smap.register(location(primary, line))))
  }

  test("a single-source class has no source debug extension") {
    val primary = source("Main.flix", 20)
    val smap = new Smap(primary)
    smap.register(location(primary, 3))
    assertResult(None)(smap.build(ClassName))
  }

  test("foreign lines are stable and allocated above the primary source") {
    val primary = source("Main.flix", 14)
    val foreign = source("Array.flix", 200)
    val smap = new Smap(primary)
    assertResult(15)(smap.register(location(foreign, 114)))
    assertResult(16)(smap.register(location(foreign, 7)))
    assertResult(15)(smap.register(location(foreign, 114)))
  }

  test("a multi-source class emits a deterministic Flix SMAP") {
    val primary = source("src/Main.flix", 14)
    val foreign = source("lib/Array.flix", 200)
    val smap = new Smap(primary)
    smap.register(location(primary, 9))
    smap.register(location(foreign, 114))

    val expected =
      """SMAP
        |Def$example.flix
        |Flix
        |*S Flix
        |*F
        |+ 1 Main.flix
        |src/Main.flix
        |+ 2 Array.flix
        |lib/Array.flix
        |*L
        |1#1,14:1
        |114#2,1:15
        |*E
        |""".stripMargin
    assertResult(Some(expected))(smap.build(ClassName))
  }

  test("distinct foreign sources receive distinct file ids") {
    val primary = source("Main.flix", 5)
    val first = source("List.flix", 100)
    val second = source("Map.flix", 100)
    val smap = new Smap(primary)
    smap.register(location(first, 30))
    smap.register(location(second, 40))
    val actual = smap.build(ClassName).get
    assert(actual.contains("+ 2 List.flix") && actual.contains("+ 3 Map.flix"))
    assert(actual.contains("30#2,1:6") && actual.contains("40#3,1:7"))
  }

  test("an opaque source URI contributes its document name, not its URI scheme") {
    val primary = source("Main.flix", 5)
    val foreign = source(SourceName.UriName(URI.create("untitled:Scratch.flix")), 10)
    val smap = new Smap(primary)
    smap.register(location(foreign, 3))

    val actual = smap.build(ClassName).get
    assert(actual.contains("+ 2 Scratch.flix\nuntitled:Scratch.flix"))
  }

  test("a package source retains its canonical archive identity") {
    val primary = source("Main.flix", 5)
    val archive = Path.of("build", "packages", "dependency package.fpkg")
    val foreign = source(SourceName.PackageEntry(archive, "src/Nested.flix"), 10)
    val smap = new Smap(primary)
    smap.register(location(foreign, 3))

    val identity = s"jar:${archive.toAbsolutePath.normalize().toUri}!/src/Nested.flix"
    val actual = smap.build(ClassName).get
    assert(actual.contains(s"+ 2 Nested.flix\n$identity"))
  }

  test("a nested bundled-library source retains its hierarchy") {
    val primary = source("Main.flix", 5)
    val foreign = source(SourceName.PathName(Path.of("BPlusTree", "Lock.flix")), 10)
    val smap = new Smap(primary)
    smap.register(location(foreign, 3))

    val actual = smap.build(ClassName).get
    assert(actual.contains("+ 2 Lock.flix\nBPlusTree/Lock.flix"))
  }

  test("an unrepresentable synthetic line suppresses the SMAP") {
    val primary = source("Main.flix", 65535)
    val smap = new Smap(primary)
    smap.register(location(source("Array.flix", 10), 3))
    assertResult(None)(smap.build(ClassName))
  }
}
