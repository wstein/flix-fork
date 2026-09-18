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
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.language.phase.jvm.DebugScopes
import ca.uwaterloo.flix.util.{Options, Result}
import dev.flix.runtime.{DebugEvalException, DebugEvalHost}
import org.scalatest.funsuite.AnyFunSuite

import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.Base64

/**
  * Running a compiled expression, and getting its value back.
  *
  * This is the half that could not be argued about. Everything up to it -- the scope table, the
  * type, the artifact -- is a claim about what *would* happen; here the classes are defined beside a
  * compiled program, the entry point is called with real values, and the answer is compared against
  * what the expression means.
  *
  * ==What stands in for the debuggee==
  *
  * A class loader over the program's own output, which is what the debuggee's loader is. The
  * artifact's classes are defined in a child of it, values are passed as ordinary objects, and the
  * result comes back as an ordinary object. What a real session adds is JDWP -- the same call, with
  * every argument built inside another process -- and that is the part these tests do not cover and
  * say so.
  */
class TestDebugEvalHost extends AnyFunSuite {

  test("the host exposes the launched build identity without an invocation") {
    val property = classOf[DebugEvalHost].getField("BUILD_ID_PROPERTY").get(null)
    val identity = classOf[DebugEvalHost].getField("BUILD_ID").get(null)

    assert(property == "flix.debug.buildId")
    assert(identity == System.getProperty("flix.debug.buildId", ""))
  }

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program: String =
    """|def describe(at: Option[String], n: Int32): String =
       |    match at {
       |        case Some(s) => s
       |        case None    => "${n}"
       |    }
       |
       |/// Throws when called, which an expression under test needs and Flix otherwise avoids.
       |def boom(n: Int32): Int32 = if (n > 0) bug!("boom") else 0
       |
       |def main(): Unit \ IO =
       |    println(describe(Some("x"), 1));
       |    println(boom(0))
       |""".stripMargin

  private val Describe = ScopeId("Def$describe", "staticApply")

  test("the evaluation host is a debug-only compilation product") {
    def names(xdebug: Boolean): Set[String] = {
      val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = xdebug))
      flix.addSource(Path.of("HostProduct.flix"), "def main(): Unit \\ IO = println(())", sctx)
      flix.compile() match {
        case Result.Ok(result) => result.getClasses.keys.map(ClassDescs.binaryNameOf).toSet
        case Result.Err(errors) => fail(errors.mkString(", "))
      }
    }
    assert(names(xdebug = true).contains("dev.flix.runtime.DebugEvalHost"))
    assert(!names(xdebug = false).contains("dev.flix.runtime.DebugEvalHost"))
  }

  test("an expression over the frame's values comes back as a value") {
    // The whole point of the programme, in one assertion: `n + 1` is not a name, not a projection,
    // and cannot be read out of the frame -- it has to be run.
    val project = build()

    assertEvaluates(project, "n + 1", args = Map("n" -> Integer.valueOf(41)), expected = Integer.valueOf(42))
  }

  test("an expression calling into the program reaches the program's own code") {
    // `describe` is a definition of the program under debug, and the artifact must *call* it rather
    // than carry a fresh copy: the parent loader has it, so parent-first delegation resolves it to
    // the running one.
    val project = build()

    assertEvaluates(
      project,
      "describe(None, n)",
      args = Map("n" -> Integer.valueOf(7)),
      expected = "7",
    )
  }

  test("a standard-library call is evaluated too") {
    val project = build()

    // Over a literal rather than a frame value: what is under test is that the standard library
    // resolves at all, and `describe` holds no String to pass.
    assertEvaluates(project, "String.length(\"hello\")", args = Map.empty, expected = Integer.valueOf(5))
  }

  test("the artifact carries only what the running program lacks") {
    // The rule that keeps this from shipping a second copy of the program on every watch. What the
    // debuggee was started with is in the build manifest; anything else is new.
    val project = build()
    val artifact = artifactFor(project, "n + 1")

    assert(artifact.classes.nonEmpty, "the artifact is empty, so nothing could be defined")
    assert(
      artifact.classes.size < 10,
      s"the artifact carries ${artifact.classes.size} classes, which suggests it is re-sending the program",
    )
    assert(
      artifact.classes.exists(_._1.contains("flixDebugEvalWrapper")),
      s"the expression's own class is missing: ${artifact.classes.map(_._1)}",
    )
  }

  test("artifact generation refuses class-directory contents without a manifest") {
    val project = build()
    Files.delete(project.resolve("build/development/build.json"))

    DebugEvalProvider.compile(
      Describe, "n + 1", Policy.AllowEffects, project, snapshot(project), withArtifact = true,
    ) match {
      case Answer.Rejected(reason) => assert(reason.contains("build.json"), s"the refusal is unspecific: $reason")
      case other => fail(s"class files without a manifest were treated as the launched build: $other")
    }
  }

  test("the entry point is named, and its arguments are named in order") {
    // A debugger reads each argument out of the frame by name and passes them in this order. Getting
    // the order wrong would pass an Int32 where a String was expected, and the failure would surface
    // inside generated code with nothing to point at.
    val project = build()
    val artifact = artifactFor(project, "describe(at, n)")

    assert(artifact.entryMethod == "staticApply", s"the entry method is ${artifact.entryMethod}")
    assert(artifact.parameters == List("at", "n"), s"the parameters are ${artifact.parameters}")
  }

  test("the result field is the one for the expression's type, not a guess") {
    // `Value` carries a field per erased type and no discriminator. Reading the wrong one gives the
    // default of that field -- 0, false, null -- which is a plausible answer and a wrong one.
    val project = build()

    assert(artifactFor(project, "n + 1").valueField == "i32")
    assert(artifactFor(project, "describe(at, n)").valueField == "o")
  }

  test("an expression that throws reports what it threw, not a failure of the machinery") {
    // What the expression did is a result the user asked for. Reporting it as a broken evaluator
    // would send them looking at the debugger instead of at their own expression.
    //
    // By calling a definition of the program that throws. Flix itself avoids exceptions -- `n / 0`
    // returns rather than throwing, which the first version of this test discovered by passing --
    // and a bare `bug!` has no concrete type for the wrapper to declare.
    val project = build()

    val (kind, message) = thrownBy(
      evaluate(project, "boom(n)", Map("n" -> Integer.valueOf(1))),
    )

    assert(kind == "dev.flix.runtime.DebugEvalException", s"it threw a $kind")
    assert(message.contains("threw"), s"the message does not say the expression threw: $message")
  }

  test("an artifact naming an entry class it does not contain is refused") {
    // The host is handed strings by a debugger and cannot assume they agree. A missing entry class
    // must be said plainly rather than surface as a ClassNotFoundException from inside a watch.
    val thrown = intercept[DebugEvalException] {
      DebugEvalHost.evaluate("", "dev.flix.gen.Def$nothing", "staticApply", "o", Array.empty)
    }
    assert(thrown.getMessage.contains("Def$nothing"), s"the refusal is unspecific: ${thrown.getMessage}")
  }

  /** Compiles [[Program]] with `--Xdebug` and returns the project root. */
  private def build(): Path = {
    val project = Files.createTempDirectory("flix-debug-host-test")
    Files.writeString(project.resolve("Main.flix"), Program)
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = true))
    flix.addFile(project.resolve("Main.flix"), sctx)
    flix.compile() match {
      case Result.Ok(result) =>
        val output = project.resolve("build").resolve("development")
        val classDir = output.resolve("class")
        val products = result.getClasses.values.toList.map { clazz =>
          val relative = ClassDescs.classFileNameOf(clazz.name)
          val target = classDir.resolve(relative)
          Files.createDirectories(target.getParent)
          Files.write(target, clazz.bytecode)
          relative
        }.sorted
        DebugScopes.write(output.resolve(DebugScopes.FileName), result.getDebugDefinitions)
        val productJson = products.map(p => s"\"$p\"").mkString(",")
        Files.writeString(output.resolve("build.json"), s"{\"formatVersion\":4,\"products\":[$productJson]}")
        project
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  private def snapshot(project: Path): TypedAst.Root = {
    val flix = new Flix().setOptions(Options.DefaultTest)
    flix.addFile(project.resolve("Main.flix"), sctx)
    flix.check()._1.getOrElse(fail("the test program must type-check"))
  }

  private def artifactFor(project: Path, expression: String): DebugEvalProvider.Artifact =
    DebugEvalProvider.compile(
      Describe, expression, Policy.AllowEffects, project, snapshot(project), withArtifact = true,
    ) match {
      case Answer.Ok(_, _, Some(artifact)) => artifact
      case other => fail(s"no artifact was produced for `$expression`: $other")
    }

  /**
    * Runs `expression` against `args`, inside a loader that holds the compiled program.
    *
    * The loader is the whole fidelity of this test. Its parent is the **bootstrap** loader, so the
    * only classes it can see are the program's own output -- which is where a `--Xdebug` build puts
    * the host as well. That is what a debuggee looks like: the host is loaded *by* the program's
    * loader, which is why it can use its own loader as the parent for an artifact and have calls
    * resolve into the running program.
    *
    * The first version of this test kept the host on the test's classpath and only set the thread's
    * context loader. It failed with `NoClassDefFoundError: dev/flix/gen/Fn1$Int32$Obj` -- the host's
    * parent was the compiler's loader, which has no program in it. The error was the design being
    * checked, not a fixture problem.
    */
  private def evaluate(project: Path, expression: String, args: Map[String, AnyRef]): AnyRef = {
    val artifact = artifactFor(project, expression)
    val encoded = artifact.classes
      .map { case (name, bytes) => s"$name=${Base64.getEncoder.encodeToString(bytes)}" }
      .mkString(";")
    val ordered = artifact.parameters.map(name => args.getOrElse(name, fail(s"no value given for `$name`")))

    val loader = programLoader(project)
    val host = Class.forName("dev.flix.runtime.DebugEvalHost", true, loader)
    val evaluate = host.getMethod(
      "evaluate",
      classOf[String], classOf[String], classOf[String], classOf[String], classOf[Array[AnyRef]],
    )
    try {
      evaluate.invoke(null, encoded, artifact.entryClass, artifact.entryMethod, artifact.valueField, ordered.toArray)
    } catch {
      case e: java.lang.reflect.InvocationTargetException => throw e.getCause
    }
  }

  /**
    * A loader holding the compiled program and nothing else.
    *
    * Bootstrap parent on purpose: with the test's own loader as parent, `dev.flix.runtime.DebugEvalHost`
    * would resolve to the copy compiled into this compiler rather than the one the build delivered,
    * and the test would prove nothing about delivery.
    */
  private def programLoader(project: Path): ClassLoader = {
    val classDir = project.resolve("build").resolve("development").resolve("class")
    assert(
      Files.isRegularFile(classDir.resolve("dev/flix/runtime/DebugEvalHost.class")),
      s"the build did not deliver the host into $classDir",
    )
    new URLClassLoader(Array(classDir.toUri.toURL), null)
  }

  private def assertEvaluates(project: Path, expression: String, args: Map[String, AnyRef], expected: AnyRef): Unit = {
    val actual = evaluate(project, expression, args)
    assert(actual == expected, s"`$expression` gave $actual, expected $expected")
  }

  /**
    * Runs `body` and returns what it threw, by class name.
    *
    * By name because the exception comes from the program's loader and this test's own
    * `DebugEvalException` is a different class from a different loader -- which is exactly the
    * situation in a real session, where the only thing that crosses is a name and a message.
    */
  private def thrownBy(body: => Any): (String, String) = {
    // The catch must not swallow the failure of *this* test: an earlier version called `fail`
    // inside the `try`, and its own TestFailedException came back as the thing supposedly thrown.
    // A failure of the fixture -- no artifact, no value to pass -- is this test failing, not the
    // expression throwing, and must not be reported as the latter.
    val outcome: Either[Throwable, Any] = try Right(body) catch {
      case e: org.scalatest.exceptions.TestFailedException => throw e
      case e: Throwable => Left(e)
    }
    outcome match {
      case Left(e) => (e.getClass.getName, String.valueOf(e.getMessage))
      case Right(value) => fail(s"nothing was thrown; the expression gave $value")
    }
  }
}
