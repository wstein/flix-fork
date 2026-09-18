/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.{BuildManifest, Flix, LaunchSpec}
import ca.uwaterloo.flix.api.lsp.LspProject
import ca.uwaterloo.flix.api.lsp.provider.DebugEvalProvider.{Answer, Policy, ScopeId}
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.shared.{SecurityContext, SourceName}
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.language.phase.jvm.DebugScopes
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

class TestDebugEvalSidecar extends AnyFunSuite {
  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  private val Program =
    """def describe(n: Int32): String = "${n}"
      |def main(): Unit \ IO = println(describe(1))
      |""".stripMargin

  private val Describe = ScopeId("Def$describe", "staticApply")

  test("the same expression reuses its answer and compiler") {
    DebugEvalSidecar.evict()
    val project = build(Program)
    val firstCompiler = compiler(project)

    artifactFor(project, "n + 1")
    artifactFor(project, "n + 1")

    assert(DebugEvalSidecar.cachedAnswers(project) == 1)
    assert(compiler(project) eq firstCompiler)
  }

  test("a different expression reuses the compiler but has its own answer") {
    DebugEvalSidecar.evict()
    val project = build(Program)
    artifactFor(project, "n")
    val firstCompiler = compiler(project)

    artifactFor(project, "n + 1")

    assert(DebugEvalSidecar.cachedAnswers(project) == 2)
    assert(compiler(project) eq firstCompiler)
  }

  test("a rebuilt source digest replaces the compiler and cached answers") {
    DebugEvalSidecar.evict()
    val project = build(Program)
    artifactFor(project, "n + 1")
    val firstCompiler = compiler(project)

    build(Program.replace("\"${n}\"", "\"n=${n}\""), Some(project))
    artifactFor(project, "n + 1")

    assert(!(compiler(project) eq firstCompiler))
    assert(DebugEvalSidecar.cachedAnswers(project) == 1)
  }

  test("a dependency-only build identity change replaces the compiler") {
    DebugEvalSidecar.evict()
    val project = build(Program)
    val sources = sourceFiles(project)
    val first = DebugEvalSidecar.withCompiler(project, sources, "fingerprint-one:same-sources")(identity)

    val second = DebugEvalSidecar.withCompiler(project, sources, "fingerprint-two:same-sources")(identity)

    assert(!(second eq first))
  }

  test("a changed source set replaces the compiler") {
    DebugEvalSidecar.evict()
    val project = build(Program)
    artifactFor(project, "n")
    val firstCompiler = compiler(project)
    Files.writeString(project.resolve("Extra.flix"), "def extra(): Int32 = 1\n")
    build(Program, Some(project))

    artifactFor(project, "n")

    assert(!(compiler(project) eq firstCompiler))
  }

  test("the oldest answers are evicted when the session cache reaches its bound") {
    DebugEvalSidecar.evict()
    val project = Files.createTempDirectory("flix-debug-cache-bound")
    val sources = List.empty[Path]

    for (i <- 0 until DebugEvalSidecar.MaxAnswers + 7) {
      DebugEvalSidecar.cached(project, sources, "digest", "scope", s"expression-$i", "Pure",
        withArtifact = false)(i)
    }

    assert(DebugEvalSidecar.cachedAnswers(project) == DebugEvalSidecar.MaxAnswers)
  }

  test("closing the language-server project releases the evaluation compiler") {
    DebugEvalSidecar.evict()
    val cachedProject = Files.createTempDirectory("flix-debug-cache-close")
    DebugEvalSidecar.withCompiler(cachedProject, Nil, "build")(identity)
    val lspProject = new LspProject(Options.DefaultTest)

    lspProject.close()

    assert(!DebugEvalSidecar.isCaching(cachedProject))
  }

  test("an open document matching disk is a valid debug snapshot") {
    val source = Files.createTempFile("flix-debug-buffer", ".flix")
    Files.writeString(source, Program)
    val project = new LspProject(Options.DefaultTest)
    project.addSource(SourceName.PathName(source), Program)

    assert(project.debugBuffersMatchDisk)
    project.close()
  }

  test("an unsaved open document is not a valid debug snapshot") {
    val source = Files.createTempFile("flix-debug-buffer", ".flix")
    Files.writeString(source, Program)
    val project = new LspProject(Options.DefaultTest)
    project.addSource(SourceName.PathName(source), Program.replace("describe(1)", "describe(2)"))

    assert(!project.debugBuffersMatchDisk)
    project.close()
  }

  test("an open document without a file is not a valid debug snapshot") {
    val project = new LspProject(Options.DefaultTest)
    val missing = Files.createTempDirectory("flix-debug-buffer").resolve("New.flix")
    project.addSource(SourceName.PathName(missing), Program)

    assert(!project.debugBuffersMatchDisk)
    project.close()
  }

  private def artifactFor(project: Path, expression: String): DebugEvalProvider.Artifact =
    DebugEvalProvider.compile(Describe, expression, Policy.AllowEffects, project, TypedAst.empty,
      withArtifact = true) match {
      case Answer.Ok(_, _, Some(artifact)) => artifact
      case other => fail(s"no artifact for `$expression`: $other")
    }

  private def compiler(project: Path): Flix = {
    val sources = sourceFiles(project)
    DebugEvalSidecar.withCompiler(project, sources, buildIdInManifest(project))(identity)
  }

  private def build(program: String, existing: Option[Path] = None): Path = {
    val project = existing.getOrElse(Files.createTempDirectory("flix-debug-cache-test"))
    Files.writeString(project.resolve("Main.flix"), program)
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = true))
    sourceFiles(project).foreach(path => flix.addFile(path, sctx))
    flix.compile() match {
      case Result.Err(errors) => fail(errors.mkString(", "))
      case Result.Ok(result) =>
        val output = project.resolve("build/development")
        val classDir = output.resolve("class")
        val products = result.getClasses.values.toList.map { clazz =>
          val relative = ClassDescs.classFileNameOf(clazz.name)
          val target = classDir.resolve(relative)
          Files.createDirectories(target.getParent)
          Files.write(target, clazz.bytecode)
          relative
        }.sorted
        Files.createDirectories(output)
        DebugScopes.write(output.resolve(DebugScopes.FileName), result.getDebugDefinitions)
        val sources = sourceFiles(project)
        val manifest = BuildManifest(
          fingerprint = "test-fingerprint",
          frontendFingerprint = "test-frontend",
          products = products,
          sources = sources.map(project.relativize(_).toString.replace('\\', '/')),
          sourcesDigest = BuildManifest.digestOfSources(project, sources),
          hasMain = true,
          launch = LaunchSpec("java", Some("Main"), List(classDir.toString)),
        )
        BuildManifest.write(output.resolve(BuildManifest.FileName), manifest) match {
          case Result.Ok(_) => ()
          case Result.Err(error) => fail(s"the test manifest must be writable: $error")
        }
        project
    }
  }

  private def sourceFiles(project: Path): List[Path] = {
    val stream = Files.walk(project)
    try stream.toArray.toList.collect {
      case path: Path if Files.isRegularFile(path) && path.toString.endsWith(".flix") &&
        !project.relativize(path).toString.startsWith("build") => path
    }.sortBy(_.toString)
    finally stream.close()
  }

  private def buildIdInManifest(project: Path): String =
    BuildManifest.read(project.resolve("build/development/build.json")).get.debugBuildId
}
