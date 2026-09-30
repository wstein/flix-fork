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
                               | def color: (com.acme.Color) -> com.acme.Color;
                               | def shape: () -> java.util.Optional[com.acme.Shape];
                               | def echoShape: (com.acme.Shape) -> com.acme.Shape;
                               | def tree: () -> com.acme.Tree;
                               | def echoTree: (com.acme.Tree) -> com.acme.Tree;
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
                         | pub def color(x: Color): Color = x
                         | pub def shape(): Option[Shape] = Some(Shape.Circle(3))
                         | pub def echoShape(x: Shape): Shape = x
                         | pub def tree(): Tree = Tree.Node(Tree.Leaf(1), Tree.Node(Tree.Leaf(2), Tree.Leaf(3)))
                         | pub def echoTree(x: Tree): Tree = x
                         | pub def intBox(): Box[Int32] = Box.Box(42)
                         | pub def stringBox(): Box[String] = Box.Box("boxed")
                         | pub def echoBox(x: Box[Int32]): Box[Int32] = x
                         |}
                         |""".stripMargin
  private def parse(text: String): JavaBoundaryContract.Contract = JavaBoundaryContract.parse(
    Source.fromString(SourceName.PathName(Paths.get("Nominals.flix-api")), Origin.User, sctx, text)).unsafeGet
  private def compiler: Flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
    .addSource(Paths.get("Nominals.flix"), source, sctx)

  test("direct nominal instances derive container and recursive ABIs without recursive instance constraints") {
    val flix = compiler
    try {
      val plan = JavaBoundaryWrappers.checkContract(flix, parse(contractText), sctx).unsafeGet
      assert(plan.methods.find(_.member.name == "colors").get.signature == "()Ljava/util/List<Lcom/acme/Color;>;")
      assert(plan.methods.find(_.member.name == "shape").get.signature == "()Ljava/util/Optional<Lcom/acme/Shape;>;")
      assert(plan.methods.find(_.member.name == "echoTree").get.descriptor == "(Lcom/acme/Tree;)Lcom/acme/Tree;")
      expectSuccess(flix.check())
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
                                |  if (NominalApi.color(Color.valueOf("Blue")) != Color.Blue) throw new AssertionError();
                                |  Color[] colors = Color.values(); colors[0] = Color.Blue;
                                |  if (Color.values()[0] != Color.Red || Color.Blue.ordinal() != 1) throw new AssertionError();
                                |  Shape shape = NominalApi.shape().orElseThrow();
                                |  if (!show(shape).equals("circle:3") || !NominalApi.echoShape(shape).equals(shape)) throw new AssertionError();
                                |  Tree tree = NominalApi.tree();
                                |  if (sum(tree) != 6 || !NominalApi.echoTree(tree).equals(tree)) throw new AssertionError();
                                |  if (!Tree.class.isSealed() || Tree.class.getPermittedSubclasses().length != 2) throw new AssertionError();
                                |  if (!Tree.Leaf.class.isRecord() || !Tree.Node.class.isRecord()) throw new AssertionError();
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
