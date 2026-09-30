/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{BootstrapError, Flix, JavaBoundary}
import ca.uwaterloo.flix.api.lsp.{ClientUri, Diagnostic, FlixLanguageClient, LspProject, LspServer}
import ca.uwaterloo.flix.language.ast.shared.{Origin, SecurityContext, Source, SourceName}
import ca.uwaterloo.flix.language.errors.JavaBoundaryError
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite
import org.json4s.*

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryContract extends AnyFunSuite with TestUtils {
  private val text = """export mod Cycle as "com.acme.Cycle" {
                       |    def values: () -> java.util.List[java.lang.Integer];
                       |    def answer = value: () -> int;
                       |}
                       |""".stripMargin
  private def parse(value: String): Result[JavaBoundaryContract.Contract, JavaBoundaryContract.Error] =
    JavaBoundaryContract.parse(Source.fromString(SourceName.PathName(Paths.get("Cycle.flix-api")), Origin.User, sctx, value))
  private def compiler: Flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
    .addSource(Paths.get("Cycle.flix"), "pub mod Cycle { pub def values(): List[Int32] = 1 :: Nil pub def value(): Int32 = 42 }", sctx)

  test("explicit contracts retain source locations, aliases and generic signatures") {
    val contract = parse(text).unsafeGet
    assert(contract.className == "com.acme.Cycle")
    assert(contract.members.head.signature == "()Ljava/util/List<Ljava/lang/Integer;>;")
    assert(contract.members.last.target.toString == "Cycle.value")
    assert(contract.members.last.loc.startLine == 3)
    assert(JavaBoundaryWrappers.checkContract(compiler, contract, sctx).isInstanceOf[Result.Ok[?, ?]])
  }

  test("invalid syntax, reserved names, duplicate methods and unsupported generic arguments fail") {
    List(text.replace("com.acme.Cycle", "dev.flix.Cycle"), text.replace("java.lang.Integer", "int"),
      text.replace("answer = value", "values = value"), text.replace("() -> int", "(void) -> int"),
      text + "garbage", text.replace("java.lang.Integer", "java.util.List[" * 130 + "java.lang.Integer" + "]" * 130)).foreach { input =>
      assert(parse(input).isInstanceOf[Result.Err[?, ?]])
    }
  }

  test("ABI gate diffs classes, descriptors and generic signatures at the declaration") {
    val contract = parse(text).unsafeGet
    val plan = JavaBoundaryWrappers.checkContract(compiler, contract, sctx).unsafeGet
    val wrong = parse(text.replace("com.acme.Cycle", "com.acme.Other").replace("java.lang.Integer", "java.lang.Long").replace("() -> int", "() -> long")).unsafeGet
    JavaBoundaryContract.verify(wrong, plan) match {
      case Result.Err(error) =>
        assert(error.message.contains("class: expected com.acme.Other, actual com.acme.Cycle"))
        assert(error.message.contains("answer descriptor: expected ()J, actual ()I"))
        assert(error.message.contains("values signature:"))
        assert(error.loc.startLine == 2)
      case other => fail(s"Expected an ABI mismatch, found $other")
    }
    JavaBoundaryWrappers.compileContract(compiler, wrong, sctx) match {
      case Result.Err(JavaBoundaryWrappers.ContractError(error)) => assert(error.message.contains("signature:"))
      case other => fail(s"Expected pre-codegen rejection, found $other")
    }
  }

  test("syntax-only stubs break the Java-first cycle and run without stubs") {
    val dir = Files.createTempDirectory("flix-contract-cycle-")
    try {
      val contract = parse(text).unsafeGet
      val stubs = dir.resolve("stubs")
      val javaClasses = dir.resolve("java")
      val runtime = dir.resolve("runtime")
      Files.createDirectories(javaClasses)
      assert(JavaBoundary.writeStubs(contract, stubs) == Result.Ok(()))
      val source = """pub mod Cycle {
                       |    import example.JavaConsumer
                       |    pub def values(): List[Int32] \ IO = JavaConsumer.answer() :: Nil
                       |    pub def value(): Int32 = 42
                       |}
                       |""".stripMargin
      val missing = new Flix().setOptions(Options.TestWithLibAll).addSource(Paths.get("Cycle.flix"), source, sctx).check()
      assert(missing._2.nonEmpty, "The Java-first fixture must actually require the missing Java class")
      val input = dir.resolve("JavaConsumer.java")
      Files.writeString(input, """package example;
                                  |import com.acme.Cycle;
                                  |import java.util.List;
                                  |public final class JavaConsumer {
                                  |  public static int answer() { return 9; }
                                  |  public static void main(String[] args) {
                                  |    List<Integer> values = Cycle.values();
                                  |    if (!values.equals(List.of(9)) || Cycle.answer() != 42) throw new AssertionError();
                                  |  }
                                  |}
                                  |""".stripMargin)
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null, "-cp", stubs.toString, "-d", javaClasses.toString, input.toString) == 0)
      val jar = dir.resolve("java.jar")
      val stream = new JarOutputStream(Files.newOutputStream(jar))
      try {
        stream.putNextEntry(new JarEntry("example/JavaConsumer.class"))
        stream.write(Files.readAllBytes(javaClasses.resolve("example/JavaConsumer.class")))
        stream.closeEntry()
      } finally stream.close()
      val flix = new Flix(jars = List(jar)).setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
        .addSource(Paths.get("Cycle.flix"), source, sctx)
      val compiled = JavaBoundary.compile(flix, contract).unsafeGet
      assert(JavaBoundary.writeClasses(compiled.compilation.getClasses.values, runtime) == Result.Ok(()))
      val log = dir.resolve("runtime.log")
      val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", runtime.toString + java.io.File.pathSeparator + javaClasses, "example.JavaConsumer")
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
      try {
        assert(child.waitFor(30, TimeUnit.SECONDS))
        assert(child.exitValue() == 0, Files.readString(log))
      } finally if (child.isAlive) child.destroyForcibly().waitFor()
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally paths.close()
    }
  }

  test("LSP buffers validate source contracts without feeding them to the Flix parser") {
    val project = new LspProject(Options.TestWithLibAll.copy(xchaosMonkey = false))
    val name = SourceName.PathName(Paths.get("Cycle.flix-api"))
    try {
      project.addSource(SourceName.PathName(Paths.get("Cycle.flix")),
        "pub mod Cycle { pub def values(): List[Int32] = 1 :: Nil pub def value(): Int32 = 42 }")
      project.addSource(name, text.replace("java.lang.Integer", "java.lang.Long"))
      val checked = project.check()
      val error = checked._2.collectFirst { case message: JavaBoundaryError => message }.get
      assert(error.source.sourceName == name)
      assert(error.loc.startLine == 2)
      assert(Diagnostic.from(error, checked._1).toLsp4j.getRange.getStart.getLine == 1)
      project.addSource(name, text)
      assert(project.check()._2.isEmpty)
      project.remSource(name)
      assert(project.check()._2.isEmpty)
    } finally project.close()
  }

  test("output writer refuses to overwrite a non-class file") {
    val dir = Files.createTempDirectory("flix-api-output-")
    val file = dir.resolve("com/acme/Cycle.class")
    try {
      Files.createDirectories(file.getParent)
      Files.writeString(file, "preserve me")
      assert(JavaBoundary.writeStubs(parse(text).unsafeGet, dir).isInstanceOf[Result.Err[?, ?]])
      assert(Files.readString(file) == "preserve me")
    } finally {
      Files.delete(file); Files.delete(file.getParent); Files.delete(file.getParent.getParent); Files.delete(dir)
    }
  }

  test("plain LSP notifications route contract open, edits and close") {
    val server = new LspServer.FlixLanguageServer(Options.TestWithLibAll.copy(xchaosMonkey = false))
    val published = scala.collection.mutable.ArrayBuffer.empty[org.eclipse.lsp4j.PublishDiagnosticsParams]
    val client = java.lang.reflect.Proxy.newProxyInstance(classOf[FlixLanguageClient].getClassLoader,
      Array(classOf[FlixLanguageClient]), (_, method, args) => {
        if (method.getName == "publishDiagnostics") published += args(0).asInstanceOf[org.eclipse.lsp4j.PublishDiagnosticsParams]
        if (classOf[java.util.concurrent.CompletableFuture[?]].isAssignableFrom(method.getReturnType))
          java.util.concurrent.CompletableFuture.completedFuture(null)
        else null
      }).asInstanceOf[FlixLanguageClient]
    server.connect(client)
    val uri = Paths.get("Cycle.flix-api").toAbsolutePath.toUri.toString
    try {
      val service = server.getTextDocumentService
      service.didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(new org.eclipse.lsp4j.TextDocumentItem(
        Paths.get("Cycle.flix").toAbsolutePath.toUri.toString, "flix", 1,
        "pub mod Cycle { pub def values(): List[Int32] = 1 :: Nil pub def value(): Int32 = 42 }")))
      service.didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(new org.eclipse.lsp4j.TextDocumentItem(
        uri, "plaintext", 1, text.replace("java.lang.Integer", "java.lang.Long"))))
      val diagnostic = published.reverseIterator.find(_.getUri == uri).get.getDiagnostics.get(0)
      assert(diagnostic.getRange.getStart.getLine == 1)
      assert(diagnostic.getMessage.getLeft.contains("values signature:"))
      service.didChange(new org.eclipse.lsp4j.DidChangeTextDocumentParams(
        new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri, 2),
        List(new org.eclipse.lsp4j.TextDocumentContentChangeEvent(text)).asJava))
      assert(published.reverseIterator.find(_.getUri == uri).get.getDiagnostics.isEmpty)
      service.didClose(new org.eclipse.lsp4j.DidCloseTextDocumentParams(new org.eclipse.lsp4j.TextDocumentIdentifier(uri)))
      assert(!server.project.isOpen(ClientUri.toSourceName(java.net.URI.create(uri))))
    } finally server.shutdown().get()
  }

  test("CLI stubs, checked output, and mismatch JSON use the source contract") {
    val dir = Files.createTempDirectory("flix-api-cli-")
    try {
      val contract = dir.resolve("Cycle.flix-api")
      val source = dir.resolve("Cycle.flix")
      Files.writeString(contract, text)
      Files.writeString(source, "pub mod Cycle { pub def values(): List[Int32] = 1 :: Nil pub def value(): Int32 = 42 }")
      def invoke(arguments: List[String], log: String): (Int, String) = {
        val output = dir.resolve(log)
        val command = List(Paths.get(System.getProperty("java.home"), "bin", "java").toString, "-Xmx2g", "-cp",
          System.getProperty("java.class.path"), "ca.uwaterloo.flix.Main") ++ arguments
        val child = new ProcessBuilder(command.asJava).directory(dir.toFile).redirectError(dir.resolve(log + ".err").toFile)
          .redirectOutput(output.toFile).start()
        try {
          assert(child.waitFor(60, TimeUnit.SECONDS), "Java API command timed out")
          (child.exitValue(), Files.readString(output))
        } finally if (child.isAlive) child.destroyForcibly().waitFor()
      }
      val stubs = dir.resolve("stubs")
      val runtime = dir.resolve("runtime")
      val syntax = invoke(List("java-api-stubs", contract.toString, "--out", stubs.toString, "--diagnostics-json"), "stubs.json")
      assert(syntax._1 == 0, syntax._2)
      assert(Files.isRegularFile(stubs.resolve("com/acme/Cycle.class")))
      val compiled = invoke(List("java-api", contract.toString, "--out", runtime.toString, "--diagnostics-json", source.toString), "api.json")
      assert(compiled._1 == 0, compiled._2)
      assert(Files.isRegularFile(runtime.resolve("com/acme/Cycle.class")))
      Files.writeString(contract, text.replace("java.lang.Integer", "java.lang.Long"))
      val invalid = invoke(List("java-api", contract.toString, "--out", dir.resolve("invalid").toString,
        "--diagnostics-json", source.toString), "invalid.json")
      assert(invalid._1 != 0)
      val json = org.json4s.native.JsonMethods.parse(invalid._2)
      assert((json \ "success") == org.json4s.JsonAST.JBool(false))
      assert(invalid._2.contains("Java API contract mismatch"))
      assert(invalid._2.contains("Ljava/lang/Long;"))
      assert(!Files.exists(dir.resolve("invalid/com/acme/Cycle.class")))
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally paths.close()
    }
  }
}
