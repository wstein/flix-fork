/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{BootstrapError, Flix, JavaBoundary}
import ca.uwaterloo.flix.language.ast.shared.{Origin, Source, SourceName}
import ca.uwaterloo.flix.util.{Formatter, InternalCompilerException, Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryNominals extends AnyFunSuite with TestUtils {
  private val contractText = """export mod Nominals as "com.acme.NominalApi" {
                               | enum com.acme.Color = Color { case Red; case Blue; };
                               | sealed com.acme.Shape = Shape { case Circle(radius: int); case Label(text: java.lang.String); };
                               | sealed com.acme.Tree = Tree { case Leaf(value: int); case Node(left: com.acme.Tree, right: com.acme.Tree); };
                               | sealed com.acme.IntBox = Box[Int32] { case Box(value: int); };
                               | sealed com.acme.StringBox = Box[String] { case Box(value: java.lang.String); };
                               | def colors: () -> java.util.List[com.acme.Color];
                               | def echoColors: (java.util.List[com.acme.Color]) -> java.util.List[com.acme.Color];
                               | def color: (com.acme.Color) -> com.acme.Color;
                               | def shape: () -> java.util.Optional[com.acme.Shape];
                               | def echoOptionalShape: (java.util.Optional[com.acme.Shape]) -> java.util.Optional[com.acme.Shape];
                               | def echoShape: (com.acme.Shape) -> com.acme.Shape;
                               | def tree: () -> com.acme.Tree;
                               | def echoTree: (com.acme.Tree) -> com.acme.Tree;
                               | def deepTree: (int) -> com.acme.Tree;
                               | def intBox: () -> com.acme.IntBox;
                               | def stringBox: () -> com.acme.StringBox;
                               | def echoBox: (com.acme.IntBox) -> com.acme.IntBox;
                               |}
                               |""".stripMargin
  private val source = """pub mod Nominals {
                         | pub enum Color { case Red, Blue }
                         | pub enum Shape { case Circle(Int32), Label(String) }
                         | pub enum Tree { case Leaf(Int32), Node(Tree, Tree) }
                         | pub enum Box[a] { case Box(a) }
                         | pub def colors(): List[Color] = Color.Red :: Color.Blue :: Nil
                         | pub def echoColors(x: List[Color]): List[Color] = x
                         | pub def color(x: Color): Color = x
                         | pub def shape(): Option[Shape] = Some(Shape.Circle(3))
                         | pub def echoOptionalShape(x: Option[Shape]): Option[Shape] = x
                         | pub def echoShape(x: Shape): Shape = x
                         | pub def tree(): Tree = Tree.Node(Tree.Leaf(1), Tree.Node(Tree.Leaf(2), Tree.Leaf(3)))
                         | pub def echoTree(x: Tree): Tree = x
                         | pub def deepTree(n: Int32): Tree = buildTree(n, Tree.Leaf(1))
                         | def buildTree(n: Int32, acc: Tree): Tree = if (n == 0) acc else buildTree(n - 1, Tree.Node(acc, Tree.Leaf(0)))
                         | pub def intBox(): Box[Int32] = Box.Box(42)
                         | pub def stringBox(): Box[String] = Box.Box("boxed")
                         | pub def echoBox(x: Box[Int32]): Box[Int32] = x
                         |}
                         |""".stripMargin
  private def parse(text: String): JavaBoundaryContract.Contract = JavaBoundaryContract.parse(
    Source.fromString(SourceName.PathName(Paths.get("Nominals.flix-api")), Origin.User, sctx, text)).unsafeGet
  private def compiler: Flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
    .addSource(Paths.get("Nominals.flix"), source, sctx)

  test("nominal conversion budgets are isolated between caller threads") {
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    try {
      dev.flix.runtime.OpaqueHandleBridge.exitConversion()
      dev.flix.runtime.OpaqueHandleBridge.exitConversion()
      var acquired = 0
      try {
        for (_ <- 0 until 1000) {
          dev.flix.runtime.OpaqueHandleBridge.enterConversion("held")
          acquired += 1
        }
        intercept[IllegalArgumentException] { dev.flix.runtime.OpaqueHandleBridge.enterConversion("held") }
        worker.submit(new java.util.concurrent.Callable[Unit] {
          def call(): Unit = {
            dev.flix.runtime.OpaqueHandleBridge.enterConversion("other-thread")
            dev.flix.runtime.OpaqueHandleBridge.exitConversion()
          }
        }).get(10, TimeUnit.SECONDS)
      } finally for (_ <- 0 until acquired) dev.flix.runtime.OpaqueHandleBridge.exitConversion()
      dev.flix.runtime.OpaqueHandleBridge.enterConversion("reset")
      dev.flix.runtime.OpaqueHandleBridge.exitConversion()
    } finally worker.shutdownNow()
  }

  test("direct nominal instances derive container and recursive ABIs without recursive instance constraints") {
    val flix = compiler
    try {
      val plan = JavaBoundaryWrappers.checkContract(flix, parse(contractText), sctx).unsafeGet
      assert(plan.methods.find(_.member.name == "colors").get.signature == "()Ljava/util/List<Lcom/acme/Color;>;")
      assert(plan.methods.find(_.member.name == "echoColors").get.signature == "(Ljava/util/List<Lcom/acme/Color;>;)Ljava/util/List<Lcom/acme/Color;>;")
      assert(plan.methods.find(_.member.name == "shape").get.signature == "()Ljava/util/Optional<Lcom/acme/Shape;>;")
      assert(plan.methods.find(_.member.name == "echoTree").get.descriptor == "(Lcom/acme/Tree;)Lcom/acme/Tree;")
      expectSuccess(flix.check())
    } finally flix.close()
  }

  /** Generated boundary instances must coexist with a caller's companion module. */
  private def checkWithCompanions(color: String, tree: String): Unit = {
    val flix = compiler
      .addSource(Paths.get("Nominals/Color.flix"), s"pub mod Nominals.Color {\n$color\n}\n", sctx)
      .addSource(Paths.get("Nominals/Tree.flix"), s"pub mod Nominals.Tree {\n$tree\n}\n", sctx)
    try {
      expectSuccess(flix.check())
      JavaBoundaryWrappers.checkContract(flix, parse(contractText), sctx) match {
        case Result.Ok(_) => expectSuccess(flix.check())
        case Result.Err(e) => fail(s"contract rejected: $e")
      }
    } finally flix.close()
  }

  test("enum and sealed targets with existing companion modules keep their direct instances") {
    checkWithCompanions(
      "pub def all(): List[Nominals.Color] = Nominals.Color.Red :: Nominals.Color.Blue :: Nil",
      "pub def leaf(x: Int32): Nominals.Tree = Nominals.Tree.Leaf(x)")
  }

  test("a caller-defined boundaryPayload in a companion module does not clash with generated code") {
    checkWithCompanions(
      "pub def boundaryPayload(x: Int32): Int32 = x",
      "pub def boundaryPayload(): String = \"user\"")
  }

  test("generated module and helper clashes report once at their contract member") {
    val contract = parse(contractText)
    val owner = JavaBoundaryNominals.helperOwner(contract, 0)
    val flix = compiler.addSource(Paths.get(owner.replace('.', '/') + ".flix"),
      s"pub mod $owner { pub def boundaryPayload(x: Int32): Int32 = x }", sctx)
    try {
      JavaBoundary.check(flix, contract) match {
        case Result.Err(BootstrapError.CompilationErrors(errors, _)) =>
          assert(errors.size == 2, errors.map(_.summary).mkString("\n"))
          assert(errors.map(_.summary).toSet == Set(s"Duplicate module: '$owner'.", "Duplicate definition: 'boundaryPayload'."))
          assert(errors.forall(_.loc == contract.nominals.head.loc))
          assert(errors.forall(_.locs.exists(_.source.sourceName == SourceName.PathName(Paths.get(owner.replace('.', '/') + ".flix")))))
          assert(errors.forall(_.messageWithLoc(Formatter.NoFormatter)(None).contains(owner.replace('.', '/') + ".flix")))
        case other => fail(s"Expected located generated name clashes, found $other")
      }
    } finally flix.close()
  }

  test("hand-written boundary instances overlap once at the contract member") {
    val contract = parse(contractText)
    val path = Paths.get("Nominals/Color.flix")
    List(
      """instance Java.Boundary.JavaResult[Nominals.Color] {
        | type Out = Int32
        | type Aef = {}
        | pub def toJava(_x: Nominals.Color): Int32 = 0
        |} """.stripMargin,
      """instance Java.Boundary.JavaArgument[Nominals.Color] {
        | type In = Int32
        | type Aef = {}
        | pub def toFlix(_x: Int32): Nominals.Color = Nominals.Color.Red
        |} """.stripMargin
    ).foreach { instance =>
      val flix = compiler.addSource(path, s"pub mod Nominals.Color { $instance }", sctx)
      try {
        expectSuccess(flix.check())
        JavaBoundary.check(flix, contract) match {
          case Result.Err(BootstrapError.CompilationErrors(errors, _)) =>
            assert(errors.size == 1, errors.map(_.summary).mkString("\n"))
            assert(errors.head.summary.contains("Overlapping instances"))
            assert(errors.head.loc == contract.nominals.head.loc)
            assert(errors.head.locs.exists(_.source.sourceName == SourceName.PathName(path)))
            assert(errors.head.messageWithLoc(Formatter.NoFormatter)(None).contains(path.toString))
          case other => fail(s"Expected a located overlap, found $other")
        }
      } finally flix.close()
    }
  }

  test("the generated parent closing brace retains the contract location") {
    val contract = parse(contractText)
    val (source, locations) = JavaBoundaryProducts.sourceWithLocations(contract, validationOnly = true, Map.empty)
    val brace = source.linesIterator.zipWithIndex.find(_._1 == "}").get._2 + 1
    assert(locations(brace) == contract.loc)
  }

  test("missing nominal argument-check shapes are internal errors") {
    val contract = parse(contractText)
    JavaBoundaryNominals.source(contract, validationOnly = true, Map.empty)
    val error = intercept[InternalCompilerException] {
      JavaBoundaryNominals.source(contract, validationOnly = false, Map.empty)
    }
    assert(error.message.contains("com.acme.Shape.Label.text"))
    assert(error.loc == contract.nominals(1).loc)
  }

  test("caller diagnostics keep their own locations alongside generated clashes") {
    val contract = parse(contractText)
    val owner = JavaBoundaryNominals.helperOwner(contract, 0)
    val path = Paths.get(owner.replace('.', '/') + ".flix")
    val flix = compiler.addSource(path,
      s"pub mod $owner { pub def value(): Int32 = 1 pub def value(): Int32 = 2 }", sctx)
    try {
      JavaBoundary.check(flix, contract) match {
        case Result.Err(BootstrapError.CompilationErrors(errors, _)) =>
          assert(errors.count(_.summary == s"Duplicate module: '$owner'.") == 1)
          assert(errors.exists(_.loc == contract.nominals.head.loc))
          assert(errors.exists(_.source.sourceName == SourceName.PathName(path)))
        case other => fail(s"Expected caller and generated diagnostics, found $other")
      }
    } finally flix.close()
  }

  test("invalid nominal targets point to the contract declaration") {
    val contract = parse(contractText.replace("= Color {", "= MissingColor {"))
    val flix = compiler
    try {
      JavaBoundary.check(flix, contract) match {
        case Result.Err(BootstrapError.CompilationErrors(errors, _)) =>
          assert(errors.nonEmpty)
          assert(errors.forall(_.loc == contract.nominals.head.loc))
        case other => fail(s"Expected a located nominal target error, found $other")
      }
    } finally flix.close()
  }

  test("enum and sealed contracts reject missing cases, wrong payloads and folded variant collisions") {
    List(contractText.replace("case Blue;", ""), contractText.replace("radius: int", "radius: java.lang.String")).foreach { text =>
      val flix = compiler
      try assert(JavaBoundaryWrappers.checkContract(flix, parse(text), sctx).isInstanceOf[Result.Err[?, ?]])
      finally flix.close()
    }
    val collision = contractText.replace("case Label(text: java.lang.String);", "case CIRCLE;")
    assert(JavaBoundaryContract.parse(Source.fromString(SourceName.PathName(Paths.get("Nominals.flix-api")),
      Origin.User, sctx, collision)).isInstanceOf[Result.Err[?, ?]])
  }

  test("staged Java sees real enums and exhaustive sealed records, including recursive and instantiated types") {
    val contract = parse(contractText)
    val kept = sys.env.get("FLIX_BOUNDARY_PHASE2_ARTIFACTS").map(path => Paths.get(path).resolve("nominals"))
    val dir = kept.getOrElse(Files.createTempDirectory("flix-boundary-nominals-"))
    Files.createDirectories(dir)
    val flix = compiler
    try {
      val stubs = dir.resolve("stubs")
      val runtime = dir.resolve("runtime")
      val caller = dir.resolve("caller")
      Files.createDirectories(caller)
      JavaBoundary.writeStubs(contract, stubs).unsafeGet
      val input = dir.resolve("Caller.java")
      Files.writeString(input, """import com.acme.*;
                                |import java.util.*;
                                |public class Caller {
                                | static int sum(Tree tree) {
                                |  return switch (tree) {
                                |   case Tree.Leaf(var value) -> value;
                                |   case Tree.Node(var left, var right) -> sum(left) + sum(right);
                                |  };
                                | }
                                | static String show(Shape shape) {
                                |  return switch (shape) {
                                |   case Shape.Circle(var radius) -> "circle:" + radius;
                                |   case Shape.Label(var text) -> text;
                                |  };
                                | }
                                | public static void main(String[] args) {
                                |  if (!NominalApi.colors().equals(List.of(Color.Red, Color.Blue))) throw new AssertionError();
                                |  if (!NominalApi.echoColors(NominalApi.colors()).equals(NominalApi.colors())) throw new AssertionError("nested enum argument");
                                |  try { NominalApi.echoColors(Arrays.asList(Color.Red, null)); throw new AssertionError("null enum element"); }
                                |  catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("x[1]")) throw expected; }
                                |  if (NominalApi.color(Color.valueOf("Blue")) != Color.Blue) throw new AssertionError();
                                |  Color[] colors = Color.values(); colors[0] = Color.Blue;
                                |  if (Color.values()[0] != Color.Red || Color.Blue.ordinal() != 1) throw new AssertionError();
                                |  Shape shape = NominalApi.shape().orElseThrow();
                                |  if (!NominalApi.echoOptionalShape(Optional.of(shape)).equals(Optional.of(shape)) ||
                                |      !NominalApi.echoOptionalShape(Optional.empty()).isEmpty()) throw new AssertionError("nested sealed argument");
                                |  if (!show(shape).equals("circle:3") || !NominalApi.echoShape(shape).equals(shape)) throw new AssertionError();
                                |  Tree tree = NominalApi.tree();
                                |  if (sum(tree) != 6 || !NominalApi.echoTree(tree).equals(tree)) throw new AssertionError();
                                |  if (!Tree.class.isSealed() || Tree.class.getPermittedSubclasses().length != 2) throw new AssertionError();
                                |  if (!Tree.Leaf.class.isRecord() || !Tree.Node.class.isRecord()) throw new AssertionError();
                                |  Tree deep = new Tree.Leaf(1);
                                |  for (int i = 0; i < 2048; i++) deep = new Tree.Node(deep, new Tree.Leaf(0));
                                |  for (int i = 0; i < 2; i++) {
                                |   try { NominalApi.echoTree(deep); throw new AssertionError("deep argument accepted"); }
                                |   catch (IllegalArgumentException expected) {
                                |    if (!expected.getMessage().contains("1000") || !expected.getMessage().contains("com.acme.Tree")) throw expected;
                                |   }
                                |   if (!NominalApi.echoTree(tree).equals(tree)) throw new AssertionError("argument guard leaked");
                                |   try { NominalApi.deepTree(2048); throw new AssertionError("deep result accepted"); }
                                |   catch (IllegalArgumentException expected) {
                                |    if (!expected.getMessage().contains("1000") || !expected.getMessage().contains("com.acme.Tree")) throw expected;
                                |   }
                                |   Tree limit = NominalApi.deepTree(999);
                                |   if (sum(NominalApi.echoTree(limit)) != 1) throw new AssertionError("guard leaked or rejected the limit");
                                |   try { NominalApi.deepTree(1000); throw new AssertionError("over-limit result accepted"); }
                                |   catch (IllegalArgumentException expected) { }
                                |   Tree over = new Tree.Node(limit, new Tree.Leaf(0));
                                |   try { NominalApi.echoTree(over); throw new AssertionError("over-limit argument accepted"); }
                                |   catch (IllegalArgumentException expected) { }
                                |   try { NominalApi.echoTree(new Tree.Node(new Tree.Leaf(1), null)); throw new AssertionError("null child accepted"); }
                                |   catch (IllegalArgumentException expected) { }
                                |   if (!NominalApi.echoTree(tree).equals(tree)) throw new AssertionError("exception guard leaked");
                                |  }
                                |  IntBox boxed = NominalApi.intBox();
                                |  if (!boxed.equals(new IntBox.Box(42)) || !NominalApi.echoBox(boxed).equals(boxed)) throw new AssertionError();
                                |  if (!NominalApi.stringBox().equals(new StringBox.Box("boxed"))) throw new AssertionError();
                                | }
                                |}
                                |""".stripMargin)
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null, "--release", "21", "-cp", stubs.toString,
        "-d", caller.toString, input.toString) == 0)
      val output = JavaBoundary.compile(flix, contract).unsafeGet
      JavaBoundary.writeClasses(output.compilation.getClasses.values, runtime).unsafeGet
      JavaBoundaryProducts.classes(contract).foreach { clazz =>
        val path = clazz.name.descriptorString().drop(1).dropRight(1) + ".class"
        assert(Files.readAllBytes(stubs.resolve(path)).sameElements(Files.readAllBytes(runtime.resolve(path))))
      }
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
