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

import ca.uwaterloo.flix.language.ast.SyntaxTree.TreeKind
import ca.uwaterloo.flix.language.ast.{SyntaxTree, Token}

/**
  * Stability tests for the Flix code formatter, over two corpora and two policies.
  *
  * The two must not be confused, and an earlier version of this file did confuse
  * them. Running the *default* policy over the whole corpus asserts
  * `f_verbatim(p(l)) = l`, which is **non-destructiveness**: the formatter puts
  * back exactly what it was given. It is the strongest such statement available
  * and it runs over all 403 files.
  *
  * It is not canonicality, and the corpus cannot supply canonicality. The corpus
  * writes parameter lists inline at 6,927 sites and broken at 152, and
  * declaration bodies inline at 2,158 and broken at 2,916 — the same construct
  * laid out both ways — so no formatter that imposes one layout per syntax tree
  * can fix-point it. Asserting that it does would be a test no correct canonical
  * formatter could pass.
  *
  * So canonicality is asserted over material that really is canonical: the
  * fixtures under `main/test/resources/fmt/canonical`, whose expected output the
  * formatter produced and a human then read. Regenerate with
  * `./mill flix.updateCanonicalFixtures` and review the diff — that review is the
  * only thing that makes them evidence. Both defects found the first time they
  * were generated (a subtraction printed as `3 -4`, and a block comment whose
  * opening line was indented away from its body) had passed every other property
  * in this suite.
  */
class TestFormatterStability extends TestFormatterCommon {

  /** Non-destructiveness: `forall l in stdlib ++ examples. f_verbatim(p(l)) = l`. */
  private def checkReproduces(samples: List[Sample]): Unit = {
    for (sample <- samples) {
      val formatted = PrettyPrinter.format(sample.original.tree)
      assert(formatted == sample.content,
        s"The default policy did not reproduce ${sample.path}:\n" +
          firstDivergence(sample.content, formatted))
    }
  }

  test("PrettyPrinter: the default policy reproduces the corpus (examples)") {
    checkReproduces(ExampleSamples)
  }

  test("PrettyPrinter: the default policy reproduces the corpus (stdlib)") {
    checkReproduces(StdlibSamples)
  }

  test("Canonical: every fixture formats to its reviewed output") {
    assert(CanonicalFixtures.nonEmpty, s"no fixtures found under $CanonicalFixtureDir")
    for (fixture <- CanonicalFixtures) {
      val formatted = PrettyPrinter.format(fixture.inputTree, Canonical)
      assert(formatted == fixture.expected,
        s"Canonical formatting of '${fixture.name}' no longer matches its reviewed output.\n" +
          s"If the new layout is an improvement, run ./mill flix.updateCanonicalFixtures " +
          s"and review the diff.\n" +
          firstDivergence(fixture.expected, formatted))
    }
  }

  test("Canonical: every reviewed output is a fixed point") {
    // Idempotence on reviewed material specifically. The corpus-wide idempotence
    // property is satisfied by output that is consistently wrong; this one is
    // satisfied only by output that someone approved.
    for (fixture <- CanonicalFixtures) {
      val formatted = PrettyPrinter.format(fixture.expectedTree, Canonical)
      assert(formatted == fixture.expected,
        s"Formatting the reviewed output of '${fixture.name}' changed it:\n" +
          firstDivergence(fixture.expected, formatted))
    }
  }

  test("Canonical: no fixture is quarantined") {
    // A fixture that fails to parse is reproduced verbatim, so it would satisfy
    // every property above while asserting nothing at all. Partial formatting is a
    // real feature with its own suite; here it would be a silent hole.
    for (fixture <- CanonicalFixtures) {
      val trees = List((fixture.inputTree, "input"), (fixture.expectedTree, "expected"))
      for ((tree, which) <- trees) {
        assert(!TokenStream.quarantined(tree).contains(true),
          s"Fixture '${fixture.name}' ($which) does not parse, so it is quarantined " +
            s"and formatted verbatim.")
      }
    }
  }

  test("Canonical: the fixtures exercise every construct that has a layout rule") {
    // Golden fixtures rot behind the rules unless something says so. Without this,
    // the next layout rule gets zero golden coverage and the suite stays green.
    val covered = CanonicalFixtures.map(f => (f.name, contents(f.expectedTree)))

    for ((rule, isExercised) <- Rules) {
      val exercisedBy = covered.collect { case (name, c) if isExercised(c) => name }
      assert(exercisedBy.nonEmpty,
        s"No canonical fixture exercises $rule. Add one under $CanonicalFixtureDir/input, " +
          s"then run ./mill flix.updateCanonicalFixtures.")
    }
  }

  /**
    * Every layout rule, paired with a test for whether a fixture exercises it.
    *
    * Keyed on what each rule itself keys on — the tree kind it fires for, or in
    * the pipeline's case the operator it looks for — so that a rule and its
    * coverage check cannot drift apart while both still compile.
    */
  private val Rules: List[(String, Contents => Boolean)] = List(
    ("match layout and `=>` alignment", _.kinds.contains(TreeKind.Expr.Match)),
    ("struct field alignment", _.kinds.contains(TreeKind.Decl.Struct)),
    ("Datalog constraint sets", _.kinds.contains(TreeKind.Expr.FixpointConstraintSet)),
    ("predicate arity tightening", _.kinds.contains(TreeKind.PredicateAndArity)),
    ("record type alignment", _.kinds.contains(TreeKind.Type.Record)),
    ("pipeline breaking", _.tokens.contains("|>")),
    ("comment placement", _.kinds.contains(TreeKind.CommentList)),
    ("signed literals and subtraction", _.tokens.contains("-"))
  )

  /** The tree kinds and token texts appearing anywhere in a fixture. */
  private case class Contents(kinds: Set[TreeKind], tokens: Set[String])

  private def contents(tree: SyntaxTree.Tree): Contents = {
    val kinds = Set.newBuilder[TreeKind]
    val tokens = Set.newBuilder[String]

    def walk(node: SyntaxTree.Tree): Unit = {
      kinds += node.kind
      node.children.foreach {
        case child: SyntaxTree.Tree => walk(child)
        case token: Token => tokens += token.text
        case _ => ()
      }
    }

    walk(tree)
    Contents(kinds.result(), tokens.result())
  }
}
