/*
 * Copyright 2026 Din Jakupi
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

/**
  * Stability tests for the Flix code formatter.
  *
  * Stability is an *aesthetic* property: `forall l in stdlib ++ examples. f(p(l)) = l`.
  *
  * It holds of the default formatter, which reproduces its input exactly.
  *
  * It is worth being precise about *why*, because the obvious explanation is
  * false. The corpus is not maintained in a canonical form: it writes parameter
  * lists inline at 6,927 sites and broken at 152, and declaration bodies inline at
  * 2,158 and broken at 2,916. The same construct is laid out both ways, so no
  * formatter that imposes one layout per syntax tree can fix-point it — which
  * `flix format --canonical` demonstrably does not, and is not expected to.
  *
  * The property is still the strongest non-destructiveness statement available
  * and is kept for the default mode. Extending it to the canonical mode would
  * require a corpus that really is canonical, which is a decision for the
  * maintainers rather than something to weaken the test over.
  */
class TestFormatterStability extends TestFormatterCommon {

  /**
    * Stability: `forall l in stdlib ++ examples. f(p(l)) = l`.
    *
    * Formatting a file that is already in canonical form must reproduce it exactly.
    */
  private def checkStability(samples: List[Sample]): Unit = {
    for (sample <- samples) {
      val formatted = PrettyPrinter.format(sample.original.tree)
      val isFixedPoint = formatted == sample.content
      assert(isFixedPoint,
        s"Standard library is not preserved by the formatter (f(p(l)) != l) " +
          s"for ${sample.path}:\n${firstDivergence(sample.content, formatted)}")
    }
  }

  test("PrettyPrinter: stability (examples)") {
    checkStability(ExampleSamples)
  }
  test("PrettyPrinter: stability (stdlib)") {
    checkStability(StdlibSamples)
  }
}
