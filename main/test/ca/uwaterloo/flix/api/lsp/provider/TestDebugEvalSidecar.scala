/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.provider.DebugEvalProvider.{Answer, Policy, ScopeId}
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.language.phase.jvm.DebugScopes
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

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

  private def artifactFor(project: Path, expression: String): DebugEvalProvider.Artifact =
    DebugEvalProvider.compile(Describe, expression, Policy.AllowEffects, project, TypedAst.empty,
      withArtifact = true) match {
      case Answer.Ok(_, _, Some(artifact)) => artifact
      case other => fail(s"no artifact for `$expression`: $other")
    }

  private def compiler(project: Path): Flix = {
    val sources = sourceFiles(project)
    DebugEvalSidecar.withCompiler(project, sources, digestInManifest(project))(identity)
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
        val digest = sourceDigest(sourceFiles(project))
        val entries = products.map(p => s"\"$p\"").mkString(",")
        Files.writeString(output.resolve("build.json"),
          s"{\"formatVersion\":4,\"sourcesDigest\":\"$digest\",\"products\":[$entries]}")
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

  private def sourceDigest(sources: List[Path]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    sources.foreach { path =>
      digest.update(path.toString.getBytes(StandardCharsets.UTF_8))
      digest.update(Files.readAllBytes(path))
    }
    digest.digest().map("%02x".format(_)).mkString
  }

  private def digestInManifest(project: Path): String = {
    val text = Files.readString(project.resolve("build/development/build.json"))
    """"sourcesDigest":"([^"]+)"""".r.findFirstMatchIn(text).map(_.group(1)).get
  }
}
