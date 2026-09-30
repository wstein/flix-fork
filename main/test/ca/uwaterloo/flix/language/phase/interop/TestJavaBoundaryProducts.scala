/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{Flix, JavaBoundary}
import ca.uwaterloo.flix.language.ast.shared.{Origin, Source, SourceName}
import ca.uwaterloo.flix.util.{Options, Result}
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.ClassDesc
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryProducts extends AnyFunSuite with TestUtils {
  test("frontend timings preserve incremental declaration and wrapper passes") {
    if (sys.env.contains("FLIX_BOUNDARY_TIMINGS")) {
      val times = scala.collection.mutable.ArrayBuffer.empty[Long]
      val roots = scala.collection.mutable.ArrayBuffer.empty[TypedAst.Root]
      val flix = new Flix() {
        override def check(): (Option[TypedAst.Root], List[CompilationMessage]) = {
          val start = System.nanoTime()
          val result = super.check()
          times += System.nanoTime() - start
          result._1.foreach(roots += _)
          result
        }
      }
      flix.setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false, incremental = true))
        .addSource(Paths.get("Products.flix"), source, sctx)
      try {
        JavaBoundaryWrappers.checkContract(flix, parse(text).unsafeGet, sctx).unsafeGet
        expectSuccess(flix.check())
        assert(times.size == 4)
        val libDef = roots.head.defs.keys.find(_.toString == "List.length").get
        assert(roots(0).defs(libDef) eq roots(1).defs(libDef))
        assert(roots(1).defs(libDef) eq roots(2).defs(libDef))
        info(s"Phase 2 frontend milliseconds (declaration gate, bodies, wrappers, ordinary cold): ${times.map(_ / 1000000.0).mkString(", ")}")
      } finally flix.close()
    }
  }
  private val text = """export mod Products as "com.acme.Api" {
                       |    record com.acme.Point(x: int, ys: java.util.List[java.lang.Integer]) = Products.Point;
                       |    tuple com.acme.Pair(left: long, right: double) = (Int64, Float64);
                       |    def point: () -> com.acme.Point;
                       |    def echo: (com.acme.Point) -> com.acme.Point;
                       |    def pair: () -> com.acme.Pair;
                       |    def total: (com.acme.Pair) -> double;
                       |}
                       |""".stripMargin
  private val source = """pub mod Products {
                         |    pub type alias Point = { x = Int32, ys = List[Int32] }
                         |    pub def point(): Point = { x = 7, ys = 1 :: 2 :: Nil }
                         |    pub def echo(x: Point): Point = x
                         |    pub def pair(): (Int64, Float64) = (5i64, 2.5f64)
                         |    pub def total(p: (Int64, Float64)): Float64 = let (_, y) = p; y
                         |}
                         |""".stripMargin
  private def parse(value: String): Result[JavaBoundaryContract.Contract, JavaBoundaryContract.Error] =
    JavaBoundaryContract.parse(Source.fromString(SourceName.PathName(Paths.get("Products.flix-api")), Origin.User, sctx, value))
  private def compiler: Flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
    .addSource(Paths.get("Products.flix"), source, sctx)

  test("declared products retain explicit component order, generic signatures and source locations") {
    val contract = parse(text).unsafeGet
    assert(contract.products.head.components.map(_.name) == List("x", "ys"))
    assert(contract.products.head.components.last.tpe.signature == "Ljava/util/List<Ljava/lang/Integer;>;")
    assert(contract.products.head.loc.startLine == 2)
    assert(contract.products.last.tuple)
    val flix = compiler
    try {
      JavaBoundaryWrappers.checkContract(flix, contract, sctx).unsafeGet
      assert(flix.javaTypeProvider.lookupClass(ClassDesc.of("com.acme.Point")).isInstanceOf[Result.Err[?, ?]])
      expectSuccess(flix.check())
    } finally flix.close()
  }

  test("the checked record gate rejects an open row even when declared labels match") {
    val parsed = parse(text).unsafeGet
    val product = parsed.products.head
    val contract = parsed.copy(products = List(product.copy(components = List(product.components.head))))
    val flix = compiler.addSource(Paths.get("Open.flix"),
      "pub mod Open { pub def identity(x: { x = Int32 | r }): { x = Int32 | r } = x }", sctx)
    try {
      val checked = flix.check()
      expectSuccess(checked)
      val root = checked._1.get
      val out = Symbol.mkDefnSym(s"${JavaBoundaryProducts.module(contract)}.out0")
      val open = root.defs(Symbol.mkDefnSym("Open.identity"))
      JavaBoundaryTypeGate.verify(contract, root.copy(defs = root.defs + (out -> open)))(flix) match {
        case Result.Err(error) =>
          assert(error.message.contains("closed"))
          assert(error.loc == contract.products.head.loc)
        case other => fail(s"Expected an open-record error, found $other")
      }
    } finally flix.close()
  }

  test("invalid product targets point to their contract declaration") {
    val contract = parse(text.replace("= Products.Point;", "= Products.MissingPoint;")).unsafeGet
    val flix = compiler
    try {
      JavaBoundaryWrappers.checkContract(flix, contract, sctx) match {
        case Result.Err(JavaBoundaryWrappers.WrapperErrors(errors, loc)) =>
          assert(errors.nonEmpty)
          assert(loc == contract.products.head.loc)
          assert(errors.forall(_.loc == contract.products.head.loc))
        case other => fail(s"Expected a located product target error, found $other")
      }
    } finally flix.close()
  }

  test("generated names, fields and concrete source types are validated before emission") {
    List(text.replace("com.acme.Pair", "com.acme.POINT"), text.replace("com.acme.Point", "com.acme.Api"),
      text.replace("ys:", "x:"), text.replace("x: int", "getClass: int"),
      text.replace("Products.Point;", "a;"), text.replace("Products.Point;", "Products.Point); def injected"),
      text.replace("com.acme.Point(x:", "java.acme.Point(x:")).foreach { invalid =>
      assert(parse(invalid).isInstanceOf[Result.Err[?, ?]], invalid)
    }
  }

  test("component ABI mismatches fail in checked conversion code and clean up generated sources") {
    val wrong = parse(text.replace("java.lang.Integer", "java.lang.Long")).unsafeGet
    val flix = compiler
    try {
      JavaBoundaryWrappers.checkContract(flix, wrong, sctx) match {
        case Result.Err(JavaBoundaryWrappers.ContractError(error)) => assert(error.loc == wrong.products.head.loc)
        case other => fail(s"Expected a checked, located component mismatch, found $other")
      }
      expectSuccess(flix.check())
      assert(flix.javaTypeProvider.lookupClass(ClassDesc.of("com.acme.Point")).isInstanceOf[Result.Err[?, ?]])
      JavaBoundaryWrappers.checkContract(flix, parse(text).unsafeGet, sctx).unsafeGet
    } finally flix.close()
  }

  test("aliases and their expansions cannot select ambiguous product representations") {
    val conflicting = parse(text.replace("def point:",
      "tuple com.acme.AliasPair(left: long, right: double) = Products.Pair; def point:")).unsafeGet
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("Products.flix"), source.replace("pub def pair():", "pub type alias Pair = (Int64, Float64) pub def pair():"), sctx)
    try {
      JavaBoundaryWrappers.checkContract(flix, conflicting, sctx) match {
        case Result.Err(JavaBoundaryWrappers.ContractError(JavaBoundaryContract.Error(message, loc))) =>
          assert(message.contains("same checked Flix payload type"))
          assert(loc == conflicting.loc)
        case other => fail(s"Expected a semantic target collision, found $other")
      }
      expectSuccess(flix.check())
    } finally flix.close()
  }

  test("nested structural products require an explicit nominal wrapper and report the API member") {
    val contract = parse(text.replace("def point: () -> com.acme.Point;", "def nested: () -> java.util.List[com.acme.Point];")).unsafeGet
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("Products.flix"), source.replace("pub def point():", "pub def nested(): List[Point] = point() :: Nil pub def point():"), sctx)
    try {
      JavaBoundaryWrappers.checkContract(flix, contract, sctx) match {
        case Result.Err(JavaBoundaryWrappers.Invalid(message, loc)) =>
          assert(message.contains("nominal enum wrapper"))
          assert(loc == contract.members.find(_.name == "nested").get.loc)
        case other => fail(s"Expected a located structural-nesting diagnostic, found $other")
      }
    } finally flix.close()
  }

  test("staged Java callers link against real declared records, not bootstrap stubs") {
    val contract = parse(text).unsafeGet
    val kept = sys.env.get("FLIX_BOUNDARY_PHASE2_ARTIFACTS").map(path => Paths.get(path).resolve("products"))
    val dir = kept.getOrElse(Files.createTempDirectory("flix-boundary-products-"))
    Files.createDirectories(dir)
    val flix = compiler
    try {
      val stubs = dir.resolve("stubs")
      val runtime = dir.resolve("runtime")
      val caller = dir.resolve("caller")
      Files.createDirectories(caller)
      JavaBoundary.writeStubs(contract, stubs).unsafeGet
      val javaSource = dir.resolve("Caller.java")
      Files.writeString(javaSource, """import com.acme.*;
                                     |import java.util.List;
                                     |public class Caller {
                                     |    public static void main(String[] args) throws Exception {
                                     |        Point p = Api.point();
                                     |        List<Integer> ys = p.ys();
                                     |        if (p.x() != 7 || !ys.equals(List.of(1, 2))) throw new AssertionError();
                                     |        if (!Api.echo(p).equals(p) || Api.echo(p).hashCode() != p.hashCode()) throw new AssertionError();
                                     |        if (!p.toString().equals("Point[x=7, ys=[1, 2]]")) throw new AssertionError(p);
                                     |        try { ys.add(9); throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
                                     |        try {
                                     |            Api.echo(new Point(1, java.util.Arrays.asList(1, null)));
                                     |            throw new AssertionError("null record element accepted");
                                     |        } catch (IllegalArgumentException expected) {
                                     |            if (!expected.getMessage().contains("com.acme.Point.ys[1]")) throw new AssertionError(expected);
                                     |        }
                                     |        Pair pair = Api.pair();
                                     |        if (pair.left() != 5 || pair.right() != 2.5 || Api.total(pair) != 2.5) throw new AssertionError();
                                     |        if (!Point.class.isRecord() || !Pair.class.isRecord()) throw new AssertionError();
                                     |        var fields = Point.class.getRecordComponents();
                                     |        if (!fields[0].getName().equals("x") || !fields[1].getName().equals("ys")) throw new AssertionError();
                                     |        if (!fields[1].getGenericType().getTypeName().equals("java.util.List<java.lang.Integer>")) throw new AssertionError();
                                     |    }
                                     |}
                                     |""".stripMargin)
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null, "-cp", stubs.toString,
        "-d", caller.toString, javaSource.toString) == 0)
      val compiled = JavaBoundary.compile(flix, contract).unsafeGet
      JavaBoundary.writeClasses(compiled.compilation.getClasses.values, runtime).unsafeGet
      assert(JavaBoundaryProducts.classes(contract).forall { clazz =>
        val stubFile = stubs.resolve(clazz.name.descriptorString().drop(1).dropRight(1) + ".class")
        assert(Files.readAllBytes(stubFile).sameElements(clazz.bytecode), s"Bootstrap bytes differ for ${clazz.name}")
        clazz.bytecode.sameElements(compiled.compilation.getClasses(clazz.name).bytecode)
      })
      val log = dir.resolve("caller.log")
      val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", runtime.toString + java.io.File.pathSeparator + caller, "Caller")
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
      try {
        assert(child.waitFor(30, TimeUnit.SECONDS))
        assert(child.exitValue() == 0, Files.readString(log))
      } finally if (child.isAlive) child.destroyForcibly().waitFor()
    } finally {
      flix.close()
      if (kept.isEmpty) {
        val paths = Files.walk(dir)
        try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
        finally paths.close()
      }
    }
  }
}
