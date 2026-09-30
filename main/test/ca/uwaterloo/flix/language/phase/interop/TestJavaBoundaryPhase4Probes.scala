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

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.jar.{JarEntry, JarOutputStream}
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

/** Executable feasibility probes; no automatic effect adapter or default collection policy change. */
class TestJavaBoundaryPhase4Probes extends AnyFunSuite with TestUtils {
  private val contractText = """export instance probe.Api = mod ProbeImpl as "probe.FlixProbe" {
                               | def invoke: (probe.Handler, int) -> int;
                               | def view: (java.util.concurrent.atomic.AtomicInteger) -> java.util.List[java.lang.Integer];
                               |}
                               |""".stripMargin
  private val source = """pub mod ProbeImpl {
                         | import probe.Handler
                         | import java.util.{List => JList, AbstractList, Collections}
                         | import java.lang.{Integer, IndexOutOfBoundsException}
                         | import java.util.concurrent.atomic.AtomicInteger
                         | pub eff Ask { def ask(value: Int32): Int32 }
                         | pub def invoke(h: Handler, x: Int32): Int32 \ IO =
                         |   run { Ask.ask(x) } with handler Ask {
                         |     def ask(value, resume) = resume(h.answer(value))
                         |   }
                         | pub def view(counter: AtomicInteger): JList[Integer] \ IO =
                         |   let xs = 10 :: 20 :: 30 :: Nil;
                         |   let result = new AbstractList[Integer] {
                         |     def size(_this: AbstractList[Integer]): Int32 = List.length(xs)
                         |     def get(_this: AbstractList[Integer], index: Int32): Integer \ IO =
                         |       match List.nth(index, xs) {
                         |         case None => throw new IndexOutOfBoundsException()
                         |         case Some(x) => discard counter.incrementAndGet(); Integer.valueOf(x)
                         |       }
                         |   };
                         |   let list: JList[Integer] = checked_cast(result);
                         |   Collections.unmodifiableList(list)
                         |}
                         |""".stripMargin

  private def runProbe(mode: String): Unit = {
    val dir = Files.createTempDirectory("flix-phase4-probe-")
    try {
      val javaClasses = Files.createDirectories(dir.resolve("java"))
      val inputs = List(
        "Handler" -> "package probe; public interface Handler { int answer(int value); }",
        "Api" -> "package probe; public interface Api { int invoke(Handler h, int x); java.util.List<Integer> view(java.util.concurrent.atomic.AtomicInteger counter); }",
        "Caller" -> """package probe;
                      |import java.util.*;
                      |import java.util.concurrent.atomic.AtomicInteger;
                      |public final class Caller {
                      | public static void main(String[] args) throws Exception {
                      |  Api api = (Api)Class.forName("probe.FlixProbe").getConstructor().newInstance();
                      |  if (args[0].equals("handler")) {
                      |   AtomicInteger calls = new AtomicInteger();
                      |   Handler handler = x -> { calls.incrementAndGet(); return x * 2; };
                      |   if (api.invoke(handler, 21) != 42 || api.invoke(handler, 3) != 6 || calls.get() != 2) throw new AssertionError();
                      |   RuntimeException failure = new IllegalStateException("handler failure");
                      |   try { api.invoke(x -> { throw failure; }, 1); throw new AssertionError(); }
                      |   catch (RuntimeException actual) { if (actual != failure) throw new AssertionError("exception identity"); }
                      |   if (api.invoke(x -> api.invoke(handler, x) + 1, 4) != 9) throw new AssertionError("reentrant handler");
                      |  } else {
                      |   AtomicInteger counter = new AtomicInteger();
                      |   List<Integer> view = api.view(counter);
                      |   if (counter.get() != 0 || view.size() != 3 || counter.get() != 0) throw new AssertionError("eager conversion");
                      |   if (view.get(1) != 20 || counter.get() != 1 || view.get(1) != 20 || counter.get() != 2) throw new AssertionError("lazy conversion");
                      |   try { view.add(40); throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
                      |   try { view.set(0, 40); throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
                      |   try { view.clear(); throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
                      |   Iterator<Integer> it = view.iterator(); it.next();
                      |   try { it.remove(); throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
                      |   try { view.get(-1); throw new AssertionError(); } catch (IndexOutOfBoundsException expected) {}
                      |   try { view.get(3); throw new AssertionError(); } catch (IndexOutOfBoundsException expected) {}
                      |   if (!view.equals(List.of(10, 20, 30))) throw new AssertionError("values");
                      |  }
                      | }
                      |}
                      |""".stripMargin).map { case (name, text) =>
        val path = dir.resolve(name + ".java"); Files.writeString(path, text); path.toString
      }
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null, (List("-d", javaClasses.toString) ++ inputs)*) == 0)
      val jar = dir.resolve("probe.jar")
      val stream = new JarOutputStream(Files.newOutputStream(jar))
      val paths = Files.walk(javaClasses)
      try paths.iterator().asScala.filter(Files.isRegularFile(_)).foreach { path =>
        stream.putNextEntry(new JarEntry(javaClasses.relativize(path).toString))
        stream.write(Files.readAllBytes(path)); stream.closeEntry()
      } finally { paths.close(); stream.close() }
      val flix = new Flix(jars = List(jar)).setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
        .addSource(Paths.get("ProbeImpl.flix"), source, sctx)
      try {
        val contract = JavaBoundaryContract.parse(Source.fromString(SourceName.PathName(Paths.get("Probe.flix-api")), Origin.User, sctx, contractText)).unsafeGet
        val output = JavaBoundary.compile(flix, contract).unsafeGet
        val runtime = dir.resolve("runtime")
        assert(JavaBoundary.writeClasses(output.compilation.getClasses.values, runtime) == Result.Ok(()))
        val log = dir.resolve("probe.log")
        val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
          "-cp", runtime.toString + java.io.File.pathSeparator + jar, "probe.Caller", mode)
          .redirectErrorStream(true).redirectOutput(log.toFile).start()
        try {
          assert(child.waitFor(30, TimeUnit.SECONDS))
          assert(child.exitValue() == 0, Files.readString(log))
        } finally if (child.isAlive) child.destroyForcibly().waitFor()
      } finally flix.close()
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally paths.close()
    }
  }

  test("a Java operation object can supply a lexically scoped synchronous Flix effect handler") { runProbe("handler") }
  test("an immutable native collection view defers conversion until Java access") { runProbe("view") }
}
