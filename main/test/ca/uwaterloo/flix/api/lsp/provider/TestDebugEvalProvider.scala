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
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.provider.DebugEvalProvider.{Answer, Policy, ScopeId}
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.phase.jvm.DebugScopes
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/**
  * Typing an expression against the scope a debugger is paused in.
  *
  * Driven through a **real build**: the fixture is compiled with `--Xdebug`, so the scope table
  * under test is the one a build actually writes, and the class and method names are the ones a
  * debugger actually sees. A fixture table would be asserting about a format rather than about a
  * frame.
  *
  * The join is the point. A frame's names are erased in the class file, and the scope table cannot
  * supply their types either -- monomorphisation has already replaced `Option[String]` with a
  * specialised symbol whose arguments are gone. What the table supplies is *which definition* the
  * class came from; the types come from the typed AST. Several tests below exist to pin that both
  * halves are load-bearing.
  */
class TestDebugEvalProvider extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """|def describe(at: Option[String], n: Int32, _hidden: Bool): String =
       |    match at {
       |        case Some(s) => s
       |        case None    => "${n}"
       |    }
       |
       |def main(): Unit \ IO =
       |    println(describe(Some("x"), 1, true))
       |""".stripMargin

  /** The frame a debugger would be paused in inside `describe`. */
  private val Describe = ScopeId("Def$describe", "staticApply")

  test("a parameter's type comes back as it was written, not as it was erased") {
    // The claim. In the class file `at` is `Ldev/flix/gen/Tagged$;` and in the scope table it is
    // `Option$AxNJjn6TiM2` -- neither says `String`. The typed AST does, and the join finds it.
    val project = build()

    assertOk(evaluate(project, "at"), "Option[String]", "Pure")
  }

  test("an expression over two bindings is typed as one") {
    val project = build()

    assertOk(evaluate(project, "if (Option.isEmpty(at)) n else 0"), "Int32", "Pure")
  }

  test("a type error is the compiler's own, not a rejection") {
    // A rejection means this server declined; a failure means the expression is wrong. Reporting
    // one as the other sends a user looking in the wrong place.
    val project = build()

    evaluate(project, "at + 1") match {
      case Answer.Failed(diagnostics) => assert(diagnostics.nonEmpty, "a failure with nothing to say")
      case other => fail(s"expected the compiler's diagnostics, got $other")
    }
  }

  test("an effectful expression is refused under the pure policy and allowed under the other") {
    // Compile first, then decide: the effect is read off the typed expression rather than turned
    // into a type error, so the refusal can say what the effect *is*.
    val project = build()

    evaluate(project, "println(at)") match {
      case Answer.Rejected(reason) => assert(reason.contains("IO"), s"the refusal does not name the effect: $reason")
      case other => fail(s"an effectful expression was not refused: $other")
    }
    evaluate(project, "println(at)", Policy.AllowEffects) match {
      case Answer.Ok(_, eff, _) => assert(eff.contains("IO"), s"the effect was lost: $eff")
      case other => fail(s"allowEffects refused an effectful expression: $other")
    }
  }

  test("a frame the build never recorded is refused, not guessed at") {
    val project = build()

    evaluate(project, "at", frame = ScopeId("Def$nobody", "staticApply")) match {
      case Answer.Rejected(reason) => assert(reason.contains("Def$nobody"), s"the refusal is unspecific: $reason")
      case other => fail(s"an unknown frame was answered: $other")
    }
  }

  test("a method the build never recorded is refused, and says what it did record") {
    val project = build()

    evaluate(project, "at", frame = Describe.copy(methodName = "applyFrame")) match {
      case Answer.Rejected(reason) =>
        assert(reason.contains("applyFrame"), s"the refusal does not name the method: $reason")
        assert(reason.contains("staticApply"), s"the refusal does not say what exists: $reason")
      case other => fail(s"an unknown method was answered: $other")
    }
  }

  test("a project with no debug build is refused with what to do about it") {
    val empty = Files.createTempDirectory("flix-debug-eval-empty")

    DebugEvalProvider.compile(Describe, "at", Policy.Pure, empty, TypedAst.empty) match {
      case Answer.Rejected(reason) => assert(reason.contains("--Xdebug"), s"the refusal is unhelpful: $reason")
      case other => fail(s"a project with no debug build was answered: $other")
    }
  }

  test("an evaluation for a different launched build is refused") {
    val project = build()
    writeManifestIdentity(project, "current-fingerprint", "current-sources")

    DebugEvalProvider.compile(Describe, "at", Policy.Pure, project, TypedAst.empty,
      withArtifact = false, launchedBuildId = Some("older-fingerprint:older-sources")) match {
      case Answer.Rejected(reason) =>
        assert(reason.contains("running program"), s"the refusal does not name the mismatch: $reason")
        assert(reason.contains("rebuild"), s"the refusal does not say how to recover: $reason")
      case other => fail(s"an expression was compiled against a different build: $other")
    }
  }

  test("an evaluation for the current launched build is accepted") {
    val project = build()
    writeManifestIdentity(project, "current-fingerprint", "current-sources")

    val answer = DebugEvalProvider.compile(Describe, "at", Policy.Pure, project, TypedAst.empty,
      withArtifact = false, launchedBuildId = Some("current-fingerprint:current-sources"))

    assertOk(answer, "Option[String]", "Pure")
  }

  test("the build sidecar is authoritative when the server AST has been refreshed") {
    val project = build()

    DebugEvalProvider.compile(Describe, "at", Policy.Pure, project, TypedAst.empty) match {
      case Answer.Ok(tpe, eff, _) => assert(tpe == "Option[String]" && eff == "Pure")
      case other => fail(s"the published debug scope was not used: $other")
    }
  }

  test("a wildcard parameter cannot be evaluated, and the refusal names it") {
    // A parameter the author marked as ignored has no entry in the LocalVariableTable and no slot a
    // debugger could read, so an expression mentioning it could never be evaluated. It is refused
    // by the language rather than by anything here -- Flix will not let a wildcard-named parameter
    // be referenced -- and this pins that the refusal survives the wrapper and names the parameter,
    // instead of arriving as an unexplained failure about generated code.
    val project = build()

    evaluate(project, "_hidden") match {
      case Answer.Failed(diagnostics) =>
        assert(diagnostics.exists(_.contains("_hidden")), s"the diagnostics do not name it: $diagnostics")
      case other => fail(s"a name the frame does not hold was typed: $other")
    }
  }

  test("a name the frame does not hold does not resolve") {
    // The scope is a restriction, not a suggestion. `main` has no `at`, so an expression mentioning
    // it must fail rather than pick up a parameter of the same name from elsewhere.
    val project = build()

    evaluate(project, "at", frame = ScopeId("Def$main", "staticApply")) match {
      case Answer.Rejected(_) | Answer.Failed(_) => ()
      case other => fail(s"a name outside the frame resolved: $other")
    }
  }

  /** Compiles [[Program]] with `--Xdebug` and returns the project root. */
  private def build(): Path = {
    val project = Files.createTempDirectory("flix-debug-eval-test")
    Files.writeString(project.resolve("Main.flix"), Program)
    val opts = Options.DefaultTest.copy(xdebug = true)
    val flix = new Flix().setOptions(opts)
    flix.addFile(project.resolve("Main.flix"), sctx)
    flix.compile() match {
      case Result.Ok(result) =>
        val output = project.resolve("build").resolve("development")
        Files.createDirectories(output)
        DebugScopes.write(output.resolve(DebugScopes.FileName), result.getDebugDefinitions)
        project
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  /** The server's own snapshot of the project, which is where the types come from. */
  private def snapshot(project: Path): TypedAst.Root = {
    val flix = new Flix().setOptions(Options.DefaultTest)
    flix.addFile(project.resolve("Main.flix"), sctx)
    flix.check()._1.getOrElse(fail("the test program must type-check"))
  }

  private def evaluate(
                        project: Path,
                        expression: String,
                        policy: Policy = Policy.Pure,
                        frame: ScopeId = Describe,
                      ): Answer =
    DebugEvalProvider.compile(frame, expression, policy, project, snapshot(project))

  private def writeManifestIdentity(project: Path, fingerprint: String, sourcesDigest: String): Unit = {
    val path = project.resolve("build/development/build.json")
    Files.writeString(path,
      s"""{"formatVersion":4,"fingerprint":"$fingerprint","sourcesDigest":"$sourcesDigest","products":["Main.class"]}""")
  }

  private def assertOk(answer: Answer, tpe: String, eff: String): Unit = answer match {
    case Answer.Ok(actualType, actualEff, _) =>
      assert(actualType == tpe, s"the type is $actualType, expected $tpe")
      assert(actualEff == eff, s"the effect is $actualEff, expected $eff")
    case other => fail(s"expected $tpe \\ $eff, got $other")
  }
}
