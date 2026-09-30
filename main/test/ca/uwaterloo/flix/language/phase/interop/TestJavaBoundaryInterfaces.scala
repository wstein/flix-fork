/*
 * Copyright 2026 Werner Stein
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{Flix, JavaBoundary}
import ca.uwaterloo.flix.api.lsp.LspProject
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
                       | def value = valueInt: (int) -> int;
                       | def value = valueLong: (long) -> long;
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
                       | int value(int x);
                       | long value(long x);
                       | default int answer() { return 42; }
                       | static int seed() { return 9; }
                       |}
                       |""".stripMargin,
        "WithObject" -> "package example; public interface WithObject { boolean equals(Object o); java.util.List<Integer> values(); }",
        "ObjectCaller" -> """package example; public final class ObjectCaller {
                           | public static void main(String[] args) throws Exception {
                           |  WithObject s = (WithObject)Class.forName("example.FlixObject").getConstructor().newInstance();
                           |  if (!s.equals(s) || s.equals(new Object()) || !s.values().equals(java.util.List.of(9, 2))) throw new AssertionError();
                           | }
                           |} """.stripMargin,
        "Base" -> "package example; public interface Base<T> { T echo(T x); }",
        "Specific" -> "package example; public interface Specific extends Base<String> {}",
        "SpecificCaller" -> """package example; public final class SpecificCaller {
                             | public static void main(String[] args) throws Exception {
                             |  Specific s = (Specific)Class.forName("example.FlixSpecific").getConstructor().newInstance();
                             |  if (!s.echo("a").equals("a!") || !((Base<String>)s).echo("b").equals("b!")) throw new AssertionError();
                             | }
                             |} """.stripMargin,
        "GenericMethod" -> "package example; public interface GenericMethod { <T> java.util.List<Integer> values(); }",
        "Locked" -> "package example; public sealed interface Locked permits Locked.Permit { final class Permit implements Locked {} }",
        "Caller" -> """package example;
                      |public final class Caller {
                      | public static void main(String[] args) throws Exception {
                      |  Service s = (Service) Class.forName("example.FlixService").getConstructor().newInstance();
                      |  if (!s.values().equals(java.util.List.of(9, 2)) || s.sum(java.util.List.of(3, 4)) != 7 ||
                      |      s.wide(8L, 2.0) != 10L || s.answer() != 42 || s.value(6) != 7 || s.value(6L) != 8L) throw new AssertionError();
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
                                          | pub def wide(x: Int64, y: Float64): Int64 = x + Float64.truncateToInt64(y)
                                          | pub def touch(): Unit = ()
                                          | pub def echo(x: String): String = x + "!"
                                          | pub def valueInt(x: Int32): Int32 = x + 1
                                          | pub def valueLong(x: Int64): Int64 = x + 2i64
                                          |}
                                          |""".stripMargin, sctx)

  test("Java-first interface contracts parse with aliases and member locations") {
    val contract = parse(text.replace("def sum:", "def sum = sum:")).unsafeGet
    assert(contract.className == "example.FlixService")
    assert(contract.members(1).target.toString == "Impl.sum")
    assert(contract.members(1).loc.startLine == 3)
  }

  test("invalid interface declarations and missing methods produce located errors") {
    withJava { (_, jar) =>
      val flix = compiler(jar)
      try {
        List(
          text.replace("\"example.FlixService\"", "\"example.Caller\"") -> "already exists on the Java classpath",
          text.replace("example.Service =", "example.Missing =") -> "resolve Java interface",
          text.replace("example.Service =", "java.lang.String =") -> "public, non-sealed",
          text.replace("example.Service =", "java.lang.Deprecated =") -> "public, non-sealed",
          text.replace("example.Service =", "example.Locked =") -> "public, non-sealed",
          text.replace("example.Service =", "java.util.List =") -> "non-generic interface",
          text.replace(" def touch: () -> void;", "") -> "Missing Java interface implementations",
          text.replace("def values:", "def seed = values:") -> "No unique",
          text.replace("java.lang.Integer", "java.lang.Long") -> "signature",
          text.replace("example.Service =", "example.GenericMethod =") -> "non-generic Java interface method"
        ).foreach { case (input, expected) =>
          JavaBoundary.check(flix, parse(input).unsafeGet) match {
            case Result.Err(ca.uwaterloo.flix.api.BootstrapError.CompilationErrors(errors, _)) =>
              assert(errors.size == 1)
              assert(errors.head.summary.contains(expected), errors.head.summary)
              assert(errors.head.loc.source.sourceName == SourceName.PathName(Paths.get("Service.flix-api")))
            case other => fail(s"Expected $expected, found $other")
          }
        }
      } finally flix.close()
    }
  }

  test("interface stubs are rejected without writing output") {
    val dir = Files.createTempDirectory("flix-interface-no-stubs-")
    try {
      assert(JavaBoundary.writeStubs(parse(text).unsafeGet, dir).isInstanceOf[Result.Err[?, ?]])
      val files = Files.list(dir)
      try assert(files.count() == 0) finally files.close()
    } finally Files.delete(dir)
  }

  test("duplicate erased overloads are rejected at the declaration") {
    assert(parse(text.replace("(long) -> long", "(int) -> long")).isInstanceOf[Result.Err[?, ?]])
  }

  private def runCaller(dir: Path, jar: Path, runtime: Path, name: String): Unit = {
    val log = dir.resolve(name + ".log")
    val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
      "-cp", runtime.toString + java.io.File.pathSeparator + jar, name)
      .redirectErrorStream(true).redirectOutput(log.toFile).start()
    try {
      assert(child.waitFor(30, TimeUnit.SECONDS))
      assert(child.exitValue() == 0, Files.readString(log))
    } finally if (child.isAlive) child.destroyForcibly().waitFor()
  }

  test("concrete inherited generic methods dispatch through erased parent descriptors") {
    withJava { (dir, jar) =>
      val flix = compiler(jar)
      try {
        val contract = parse("""export instance example.Specific = mod Impl as "example.FlixSpecific" {
                             | def echo: (java.lang.String) -> java.lang.String;
                             |} """.stripMargin).unsafeGet
        val output = JavaBoundary.compile(flix, contract).unsafeGet
        val runtime = dir.resolve("runtime")
        assert(JavaBoundary.writeClasses(output.compilation.getClasses.values, runtime) == Result.Ok(()))
        runCaller(dir, jar, runtime, "example.SpecificCaller")
      } finally flix.close()
    }
  }

  test("public Object methods fulfill redeclared abstract interface methods") {
    withJava { (dir, jar) =>
      val flix = compiler(jar)
      try {
        val contract = parse("""export instance example.WithObject = mod Impl as "example.FlixObject" {
                             | def values: () -> java.util.List[java.lang.Integer];
                             |} """.stripMargin).unsafeGet
        val output = JavaBoundary.compile(flix, contract).unsafeGet
        val runtime = dir.resolve("runtime")
        assert(JavaBoundary.writeClasses(output.compilation.getClasses.values, runtime) == Result.Ok(()))
        runCaller(dir, jar, runtime, "example.ObjectCaller")
      } finally flix.close()
    }
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
        runCaller(dir, jar, runtime, "example.Caller")
      } finally flix.close()
    }
  }
}
