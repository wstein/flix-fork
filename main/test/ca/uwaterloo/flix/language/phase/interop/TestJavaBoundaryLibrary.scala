/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.phase.interop.JavaBoundaryWrappers.*
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryLibrary extends AnyFunSuite with TestUtils {
  test("opaque conversions are not public polymorphic Flix helpers") {
    List("pack", "unpack").foreach { name =>
      val input = s"def forge(): Java.Boundary.Opaque[Int32] \\ IO = Java.Boundary.$name(\"key\", \"Int32\", null)"
      val checked = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
        .addSource(Paths.get("Forge.flix"), input, sctx).check()
      expectError[ca.uwaterloo.flix.language.errors.ResolutionError.UndefinedName](checked)
    }
  }
  private val source = """pub mod LibraryApi {
                         |    import java.util.{Map => JMap}
                         |    pub def values(): List[Int32] = 1 :: 2 :: Nil
                         |    pub def nested(): List[List[Int32]] = (1 :: Nil) :: Nil
                         |    pub def optional(x: Option[Int32]): Option[Int32] = x
                         |    pub def vector(x: Vector[Int32]): Vector[Int32] = x
                         |    pub def chain(x: Chain[Int32]): Chain[Int32] = x
                         |    pub def echo(x: String): String = x
                         |    pub def nestedArgument(x: List[List[Int32]]): List[List[Int32]] = x
                         |    pub def nativeMap(x: JMap[String, String]): JMap[String, String] = x
                         |    pub def set(): Set[Int32] = Set.singleton(3)
                         |    pub def map(): Map[Int32, List[Int32]] = Map.singleton(4, 5 :: Nil)
                         |    pub def bools(): List[Bool] = true :: false :: Nil
                         |    pub def chars(): List[Char] = 'a' :: Nil
                         |    pub def big(x: List[BigInt]): List[BigInt] = x
                         |    pub def decimal(x: List[BigDecimal]): List[BigDecimal] = x
                         |    pub def checked(x: Bool): Unit \ Assert = Assert.assertTrue(x)
                         |    pub def primitive(x: Int32): Int32 \ (Chan + NonDet) = {
                         |        let (sender, receiver) = Channel.buffered(1);
                         |        Channel.send(x, sender);
                         |        Channel.recv(receiver)
                         |    }
                         |    pub eff Unhandled { def request(): Int32 }
                         |    pub def unhandled(): Int32 \ Unhandled = Unhandled.request()
                         |    pub enum ResultOnly { case Only }
                         |    instance Java.Boundary.JavaResult[ResultOnly] {
                         |        type Out = String
                         |        type Aef = Assert
                         |        pub def toJava(x: ResultOnly): String \ Assert = match x {
                         |            case ResultOnly.Only => { Assert.assertTrue(true); "only" }
                         |        }
                         |    }
                         |    pub def converted(): ResultOnly = ResultOnly.Only
                         |    pub def missing(x: ResultOnly): Unit = match x { case ResultOnly.Only => () }
                         |    pub enum Model { case Model(Int32) }
                         |    pub enum Order { case Order(Int32) }
                         |    pub def model(): Java.Boundary.Opaque[Model] = Java.Boundary.Opaque.Opaque(Model.Model(42))
                         |    pub def order(): Java.Boundary.Opaque[Order] = Java.Boundary.Opaque.Opaque(Order.Order(7))
                         |    pub def readModel(x: Java.Boundary.Opaque[Model]): Int32 = match x {
                         |        case Java.Boundary.Opaque.Opaque(Model.Model(n)) => n
                         |    }
                         |    pub enum Hidden { case Hidden(Array[Int32, Static]) }
                         |    pub def hidden(): Java.Boundary.Opaque[Hidden] \ IO =
                         |        Java.Boundary.Opaque.Opaque(Hidden.Hidden(Array#{} @ Static))
                         |}
                         |""".stripMargin
  private val traits = Traits(Symbol.mkTraitSym("Java.Boundary.JavaResult"), Symbol.mkTraitSym("Java.Boundary.JavaArgument"))

  private def compileMembers(names: List[String]): Result[Output, Error] = {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("LibraryApi.flix"), source, sctx)
    val checked = flix.check()
    expectSuccess(checked)
    val members = names.map { name =>
      val defn = checked._1.get.defs.values.find(_.sym.toString == s"LibraryApi.$name").get
      Member(name, defn.sym, defn.loc)
    }
    compile(flix, Declaration("com.acme.LibraryApi", members), traits, sctx)
  }

  test("packaged deep conversions and default handlers pass a staged Java caller") {
    implicit val flix: Flix = new Flix()
    val output = compileMembers(List("values", "nested", "optional", "vector", "chain", "echo", "nestedArgument", "nativeMap", "set", "map", "bools", "chars", "big", "decimal", "checked", "converted", "primitive", "model", "order", "readModel")).unsafeGet
    val signatures = output.plan.methods.map(method => method.member.name -> method.signature).toMap
    assert(signatures("map") == "()Ljava/util/Map<Ljava/lang/Integer;Ljava/util/List<Ljava/lang/Integer;>;>;")
    assert(signatures("chain") == "(Ljava/util/Collection<Ljava/lang/Integer;>;)Ljava/util/Collection<Ljava/lang/Integer;>;")
    val dir = Files.createTempDirectory("flix-boundary-library-")
    try {
      val stubs = dir.resolve("stubs")
      val runtime = dir.resolve("runtime")
      val callers = dir.resolve("callers")
      Files.createDirectories(callers)
      List(stubs -> (JavaBoundaryApi.stub(output.plan) :: JavaBoundaryRuntime.classes), runtime -> output.compilation.getClasses.values).foreach { case (dest, classes) =>
        classes.foreach { clazz =>
          val file = dest.resolve(clazz.name.descriptorString().drop(1).dropRight(1) + ".class")
          Files.createDirectories(file.getParent)
          Files.write(file, clazz.bytecode)
        }
      }
      // The lab's opt-in staged callers consume only these generated artifacts, never compiler classes.
      sys.env.get("FLIX_BOUNDARY_ARTIFACTS").foreach { path =>
        val dest = Paths.get(path)
        List(stubs, runtime).foreach { tree =>
          val files = Files.walk(tree)
          try files.iterator().asScala.filter(Files.isRegularFile(_)).foreach { file =>
            val target = dest.resolve(tree.getFileName).resolve(tree.relativize(file))
            Files.createDirectories(target.getParent)
            Files.copy(file, target)
          } finally files.close()
        }
      }
      val input = dir.resolve("LibraryCaller.java")
      Files.writeString(input, """import java.util.*;
                                  |import com.acme.LibraryApi;
                                  |import dev.flix.runtime.OpaqueHandle;
                                  |public class LibraryCaller {
                                  |  public static void main(String[] args) {
                                  |    List<Integer> xs = LibraryApi.values();
                                  |    if (!xs.equals(List.of(1, 2))) throw new AssertionError("list");
                                  |    if (!LibraryApi.nested().equals(List.of(List.of(1)))) throw new AssertionError("nested");
                                  |    if (!LibraryApi.optional(Optional.of(7)).equals(Optional.of(7))) throw new AssertionError("optional");
                                  |    if (!LibraryApi.optional(Optional.empty()).isEmpty()) throw new AssertionError("empty");
                                  |    if (!LibraryApi.vector(xs).equals(xs)) throw new AssertionError("vector");
                                  |    expectNull(() -> LibraryApi.vector(null), "x");
                                  |    expectNull(() -> LibraryApi.vector(Arrays.asList(1, null)), "x[1]");
                                  |    expectNull(() -> LibraryApi.optional(null), "x");
                                  |    expectNull(() -> LibraryApi.chain(Arrays.asList((Integer) null)), "x[0]");
                                  |    expectNull(() -> LibraryApi.echo(null), "x");
                                  |    expectNull(() -> LibraryApi.nestedArgument(List.of(Arrays.asList(1, null))), "x[0][1]");
                                  |    Map<String, String> nullable = new LinkedHashMap<>();
                                  |    nullable.put("key", null);
                                  |    expectNull(() -> LibraryApi.nativeMap(nullable), "x[0].value");
                                  |    nullable.clear(); nullable.put(null, "value");
                                  |    expectNull(() -> LibraryApi.nativeMap(nullable), "x[0].key");
                                  |    if (!new ArrayList<>(LibraryApi.chain(xs)).equals(xs)) throw new AssertionError("chain");
                                  |    if (!LibraryApi.set().equals(Set.of(3))) throw new AssertionError("set");
                                  |    if (!LibraryApi.map().equals(Map.of(4, List.of(5)))) throw new AssertionError("map");
                                  |    if (!LibraryApi.bools().equals(List.of(true, false))) throw new AssertionError("bool");
                                  |    if (!LibraryApi.chars().equals(List.of('a'))) throw new AssertionError("char");
                                  |    if (!LibraryApi.big(List.of(java.math.BigInteger.TEN)).equals(List.of(java.math.BigInteger.TEN))) throw new AssertionError("big integer");
                                  |    if (!LibraryApi.decimal(List.of(java.math.BigDecimal.TEN)).equals(List.of(java.math.BigDecimal.TEN))) throw new AssertionError("decimal");
                                  |    LibraryApi.checked(true);
                                  |    boolean failed = false;
                                  |    try { LibraryApi.checked(false); }
                                  |    catch (AssertionError expected) { failed = true; }
                                  |    if (!failed) throw new AssertionError("missing failure");
                                  |    if (!LibraryApi.converted().equals("only")) throw new AssertionError("conversion effect");
                                  |    if (LibraryApi.primitive(9) != 9) throw new AssertionError("primitive effects");
                                  |    OpaqueHandle<Object> model = LibraryApi.model();
                                  |    if (LibraryApi.readModel(model) != 42) throw new AssertionError("opaque round trip");
                                  |    if (!model.equals(model) || model.equals(LibraryApi.model())) throw new AssertionError("opaque identity");
                                  |    if (!model.toString().contains("Model") || model.toString().contains("42")) throw new AssertionError("opaque display");
                                  |    try { LibraryApi.readModel(LibraryApi.order()); throw new AssertionError("wrong opaque accepted"); }
                                  |    catch (IllegalArgumentException expected) {
                                  |      if (!expected.getMessage().contains("Model") || !expected.getMessage().contains("Order")) throw expected;
                                  |    }
                                  |    try { LibraryApi.readModel(null); throw new AssertionError("null opaque accepted"); }
                                  |    catch (IllegalArgumentException expected) { }
                                  |  }
                                  |  static void expectNull(Runnable call, String path) {
                                  |    try { call.run(); throw new AssertionError("null accepted: " + path); }
                                  |    catch (IllegalArgumentException expected) {
                                  |      if (!expected.getMessage().contains(path)) throw expected;
                                  |    }
                                  |  }
                                  |}
                                  |""".stripMargin)
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null, "-cp", stubs.toString, "-d", callers.toString, input.toString) == 0)
      val log = dir.resolve("runtime.log")
      val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", runtime.toString + java.io.File.pathSeparator + callers, "LibraryCaller")
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
      try {
        assert(child.waitFor(30, TimeUnit.SECONDS), "Staged Java caller timed out")
        assert(child.exitValue() == 0, Files.readString(log))
      } finally if (child.isAlive) child.destroyForcibly().waitFor()
    } finally {
      val files = Files.walk(dir)
      try files.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally files.close()
    }
  }

  test("unhandled effects fail at the API declaration") {
    compileMembers(List("unhandled")) match {
      case Result.Err(Invalid(message, loc)) =>
        assert(message.contains("default handler"))
        assert(loc.source.sourceName.toString.contains("LibraryApi.flix"))
      case other => fail(s"Expected an unhandled boundary effect, found $other")
    }
  }

  test("packaged conversions have no implicit opaque or reverse-direction fallback") {
    compileMembers(List("missing")) match {
      case Result.Err(BoundaryError(_: BoundaryTypeElaborator.MissingInstance, _)) => ()
      case other => fail(s"Expected missing argument evidence, found $other")
    }
  }

  test("opaque crossing cannot hide a region-bound nominal payload") {
    compileMembers(List("hidden")) match {
      case Result.Err(Invalid(message, _)) => assert(message.contains("Region-bound"))
      case other => fail(s"Expected region rejection, found $other")
    }
  }
}
