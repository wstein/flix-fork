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
package ca.uwaterloo.flix.tools.fmt

import org.scalatest.DoNotDiscover

import java.nio.file.{Files, Paths}

/**
  * Regenerates the expected output of every canonical fixture.
  *
  * Run with `./mill flix.updateCanonicalFixtures`, then **read the diff**. The
  * regenerated files are the only place where a layout change is visible as a
  * layout rather than as a passing test, and reviewing that diff is the whole
  * point of keeping them: [[TestFormatterStability]] can only tell you that the
  * output stopped matching what a human once approved, not whether the new
  * output is better.
  *
  * It is deliberately a separate task rather than a self-healing test. A test
  * that rewrites its own expectation on failure records whatever the formatter
  * currently does, which makes the golden corpus a mirror instead of a gate.
  */
@DoNotDiscover
class CanonicalFixtureUpdate extends TestFormatterCommon {

  test("regenerate the expected output of every canonical fixture") {
    val expectedDir = Paths.get(CanonicalFixtureDir, "expected")
    Files.createDirectories(expectedDir)

    for (fixture <- CanonicalFixtures) {
      val formatted = PrettyPrinter.format(fixture.inputTree, Canonical)
      val path = expectedDir.resolve(s"${fixture.name}.flix")
      val status = if (fixture.expected == formatted) "unchanged" else "UPDATED"
      Files.writeString(path, formatted)
      println(f"${fixture.name}%-16s $status")
    }

    println()
    println(s"Wrote ${CanonicalFixtures.size} fixtures to $expectedDir.")
    println("Read the diff before committing: it is the only review this material gets.")
  }
}
