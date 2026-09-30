/*
 * Copyright 2026 Werner Stein
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{Flix, JavaBoundary}
import ca.uwaterloo.flix.language.ast.shared.{Origin, Source, SourceName}
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryInterfaces extends AnyFunSuite with TestUtils {
  private val text = """export instance example.Service = mod Impl as "example.FlixService" {
                       | def values: () -> java.util.List[java.lang.Integer];
                       | def sum: (java.util.List[java.lang.Integer]) -> int;
                       | def wide: (long, double) -> long;
                       | def touch: () -> void;
                       |}
                       |""".stripMargin
  private def parse(value: String) = JavaBoundaryContract.parse(Source.fromString(
    SourceName.PathName(Paths.get("Service.flix-api")), Origin.User, sctx, value))

  private def withJava[A](body: (Path, Path) => A): A = {
    val dir = Files.createTempDirectory("flix-interface-")
    try {
      val classes = Files.createDirectories(dir.resolve("java"))
      val files = List(
        "Parent" -> "package example; public interface Parent { long wide(long x, double y); }",
        "Service" -> """package example;
                       |public interface Service extends Parent {
                       | java.util.List<Integer> values();
                       | int sum(java.util.List<Integer> xs);
                       | void touch();
                       | default int answer() { return 42; }
                       | static int seed() { return 9; }
                       |}
                       |""".stripMargin,
        "Caller" -> """package example;
                      |public final class Caller {
                      | public static void main(String[] args) throws Exception {
                      |  Service s = (Service) Class.forName("example.FlixService").getConstructor().newInstance();
                      |  if (!s.values().equals(java.util.List.of(9, 2)) || s.sum(java.util.List.of(3, 4)) != 7 ||
                      |      s.wide(8L, 2.0) != 10L || s.answer() != 42) throw new AssertionError();
                      |  s.touch();
                      |  try { s.sum(java.util.Arrays.asList(1, null)); throw new AssertionError(); }
                      |  catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("xs[1]")) throw expected; }
                      | }
                      |}
                      |""".stripMargin)
      val inputs = files.map { case (name, source) =>
        val path = dir.resolve(name + ".java"); Files.writeString(path, source); path.toString
      }
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null,
        (List("-d", classes.toString) ++ inputs)* ) == 0)
      val jar = dir.resolve("java.jar")
      val stream = new JarOutputStream(Files.newOutputStream(jar))
      val paths = Files.walk(classes)
      try paths.iterator().asScala.filter(Files.isRegularFile(_)).foreach { file =>
        stream.putNextEntry(new JarEntry(classes.relativize(file).toString))
        stream.write(Files.readAllBytes(file)); stream.closeEntry()
      } finally { paths.close(); stream.close() }
      body(dir, jar)
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally paths.close()
    }
  }

  private def compiler(jar: Path): Flix = new Flix(jars = List(jar))
    .setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
    .addSource(Paths.get("Impl.flix"), """pub mod Impl {
                                          | import example.Service
                                          | pub def values(): List[Int32] \ IO = Service.seed() :: 2 :: Nil
                                          | pub def sum(xs: List[Int32]): Int32 = List.sum(xs)
                                          | pub def wide(x: Int64, y: Float64): Int64 = x + Float64.toInt64(y)
                                          | pub def touch(): Unit = ()
                                          |}
                                          |""".stripMargin, sctx)

  test("Java-first interface contracts parse with aliases and member locations") {
    val contract = parse(text.replace("def sum:", "def sum = sum:")).unsafeGet
    assert(contract.className == "example.FlixService")
    assert(contract.members(1).target.toString == "Impl.sum")
    assert(contract.members(1).loc.startLine == 3)
  }

  test("Java compiles first against the interface and invokes Flix without stubs or compiler jar") {
    withJava { (dir, jar) =>
      val flix = compiler(jar)
      try {
        val contract = parse(text).unsafeGet
        val compiled = JavaBoundary.compile(flix, contract).unsafeGet
        val runtime = dir.resolve("runtime")
        assert(JavaBoundary.writeClasses(compiled.compilation.getClasses.values, runtime) == Result.Ok(()))
        assert(!Files.exists(runtime.resolve("example/Service.class")))
        val log = dir.resolve("caller.log")
        val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
          "-cp", runtime.toString + java.io.File.pathSeparator + jar, "example.Caller")
          .redirectErrorStream(true).redirectOutput(log.toFile).start()
        try {
          assert(child.waitFor(30, TimeUnit.SECONDS))
          assert(child.exitValue() == 0, Files.readString(log))
        } finally if (child.isAlive) child.destroyForcibly().waitFor()
      } finally flix.close()
    }
  }
}
