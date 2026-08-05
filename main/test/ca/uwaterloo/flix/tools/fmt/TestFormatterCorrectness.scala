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
  * Correctness tests for the Flix code formatter.
  *
  * The correctness properties checked here are part of the canonical set
  * required of a source code formatter. With `p`, `f` and `w` as defined in
  * [[TestFormatterCommon]], they are:
  *
  *   1. Can format:          forall s in S,  f(p(s)) is defined
  *   2. Idempotency:         forall c in C,  f(p(f(c))) = f(c)
  *   3. Non-destructiveness: forall c in C,  w(c) = w(p(f(c)))
  *
  * Each property is run on both corpora: the standard library and the `examples`.
  *
  * Properties 2 and 3 share a pass, because they share the intermediate value
  * that costs the time. `p` here is a full `Flix.check()` — the whole pipeline
  * through `Typer`, not just the parse the formatter needs — so a corpus-wide
  * reparse is the most expensive thing this suite does, and it is worth some
  * awkwardness not to do one twice.
  */
class TestFormatterCorrectness extends TestFormatterCommon {

  /**
    * Property 1 -- Can format: `forall s in S, f(p(s)) is defined`.
    *
    * The formatter produces a non-empty string for every [[SyntaxTree]] tree the parser produces.
    */
  private def checkCanFormat(samples: List[Sample]): Unit = {
    for (sample <- samples) {
      val formatted = PrettyPrinter.format(sample.original.tree)
      assert(formatted.nonEmpty, s"Formatter produced empty output for ${sample.path}")
    }
  }

  /**
    * Properties 2 and 3, in a single pass over the corpus.
    *
    *   2. Idempotency:         `forall c in C, f(p(f(c))) = f(c)`
    *   3. Non-destructiveness: `forall c in C, shape(w(c)) = shape(w(p(f(c))))`
    *
    * They are checked together because they need the *same* intermediate value:
    * `p(f(c))`, the parse of the formatted output. That parse is a full compile
    * and is the dominant cost of this suite, so running the two as separate
    * properties reparsed identical text twice per file — four corpus-wide compiles
    * where two suffice, which is most of the six minutes this suite used to take.
    *
    * The assertions stay distinct, and each names the property it belongs to, so a
    * failure still says which one broke.
    */
  private def checkIdempotentAndNonDestructive(samples: List[Sample]): Unit = {
    for (sample <- samples) {
      val once = PrettyPrinter.format(sample.original.tree)
      val reparsed = sample.reparse(once)

      val twice = PrettyPrinter.format(reparsed.tree)
      assert(once == twice,
        s"Formatter is not idempotent for ${sample.path}:\n${firstDivergence(once, twice)}")

      assert(sameShape(sample.original.weeded, reparsed.weeded),
        s"Formatter changed the AST shape for ${sample.path}")
    }
  }

  /**
    * Checks if two [[WeededAst]] have the same shape.
    * Meaning that they have the same structure of nodes, but not necessarily the same content.
    * This is used to check the non-destructiveness property of the formatter.
    *
    * TODO: This is a simple check, therefore, find a more robust way to check for the AST integrity.
    */
  private def sameShape(a: Any, b: Any): Boolean = (a, b) match {
    case (x: Iterable[_], y: Iterable[_]) =>
      // Two collections have the same shape if they have the same length
      x.size == y.size && x.iterator.zip(y.iterator).forall { case (p, q) => sameShape(p, q) }
    case (x: Product, y: Product) =>
      // Two case classes have the same shape if they have the same string prefix.
      x.productPrefix == y.productPrefix &&
        x.productIterator.zip(y.productIterator).forall { case (p, q) => sameShape(p, q) }
    case _ => true
  }

  test("PrettyPrinter: can format (examples)") {
    checkCanFormat(ExampleSamples)
  }
  test("PrettyPrinter: can format (stdlib)") {
    checkCanFormat(StdlibSamples)
  }

  test("PrettyPrinter: idempotency and non-destructiveness (examples)") {
    checkIdempotentAndNonDestructive(ExampleSamples)
  }
  test("PrettyPrinter: idempotency and non-destructiveness (stdlib)") {
    checkIdempotentAndNonDestructive(StdlibSamples)
  }
}
