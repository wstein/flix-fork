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
package ca.uwaterloo.flix.language.ast

import ca.uwaterloo.flix.language.ast.shared.SourceName
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Path

class TestSourceName extends AnyFunSuite {

  test("a package entry has a canonical archive URI identity") {
    val archive = Path.of("build", "packages", "nested", "..", "my package.fpkg")
    val name = SourceName.PackageEntry(archive, "src/example/Main.flix")
    val expectedArchive = archive.toAbsolutePath.normalize().toUri

    assert(name.toString == s"jar:$expectedArchive!/src/example/Main.flix")
  }

  test("a package entry retains its package-relative path") {
    val name = SourceName.PackageEntry(Path.of("my-package.fpkg"), "src/example/Main.flix")

    assert(name.toPath.contains(Path.of("src/example/Main.flix")))
  }

  test("a leading entry separator is canonicalized") {
    val archive = Path.of("build", "packages", "my-package.fpkg")
    val name = SourceName.PackageEntry(archive, "/src/Main.flix")

    assert(name.toString == s"jar:${archive.toAbsolutePath.normalize().toUri}!/src/Main.flix")
    assert(name.toPath.contains(Path.of("src/Main.flix")))
  }
}
