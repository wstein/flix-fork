/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{Symbol, TypedAst}
import ca.uwaterloo.flix.language.phase.jvm.{JavaBoundaryApi, JvmClass}
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi.{Declaration, Member}
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.ClassDesc
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryApi extends AnyFunSuite with TestUtils {
  private val source = """pub mod Api {
                         |    import java.lang.Integer
                         |    import java.util.{List => JList}
                         |    pub trait JavaResult[t: Type] {
                         |        type Out[t]: Type
                         |        type Aef[t]: Eff = {}
                         |        pub def toJava(x: t): JavaResult.Out[t] \ JavaResult.Aef[t]
                         |    }
                         |    instance JavaResult[Int32] {
                         |        type Out = Integer
                         |        pub def toJava(x: Int32): Integer = Int32.valueOf(x)
                         |    }
                         |    instance JavaResult[List[a]] with JavaResult[a] {
                         |        type Out = JList[JavaResult.Out[a]]
                         |        type Aef = IO + JavaResult.Aef[a]
                         |        pub def toJava(xs: List[a]): JList[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
                         |            Adaptor.toList(List.map(JavaResult.toJava, xs))
                         |    }
                         |    pub def values(): List[Int32] = 1 :: 2 :: 3 :: Nil
                         |    pub def ints(): JList[Integer] \ IO = JavaResult.toJava(values())
                         |    pub def nested(): JList[JList[Integer]] \ IO = JavaResult.toJava((1 :: Nil) :: Nil)
                         |    pub def identity(xs: JList[Integer]): JList[Integer] = xs
                         |    pub def plus(x: Int32, y: Int64, z: Float64): Int64 = Int32.toInt64(x) + y + Float64.truncateToInt64(z)
                         |    pub def echo(s: String): String = s
                         |    pub def noop(): Unit = ()
                         |    pub def failing(): Int32 = Integer.parseInt("invalid")
                         |    pub def generic(x: a): a = x
                         |    pub def wrong(xs: JList[Int32]): JList[Int32] = xs
                         |    pub def effect(): Unit \ Assert = Assert.assertEq(expected = 1, 1)
                         |}
                         |""".stripMargin

  private lazy val fixture: (Flix, TypedAst.Root) = {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("Api.flix"), source, sctx)
    val checked = flix.check()
    expectSuccess(checked)
    (flix, checked._1.get)
  }

  private def member(name: String): Member = Member(name,
    fixture._2.defs.values.find(_.sym.toString == s"Api.$name").get.sym)

  private def declaration(name: String): Declaration =
    Declaration(name, List("ints", "nested", "identity", "plus", "echo", "noop", "failing").map(member))

  private def signatures(clazz: JvmClass): Map[String, (String, String)] = {
    val methods = mutable.Map.empty[String, (String, String)]
    new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String,
                               exceptions: Array[String]): MethodVisitor = {
        assert((access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_STATIC) != 0)
        methods(name) = (descriptor, signature)
        null
      }
    }, ClassReader.SKIP_CODE)
    methods.toMap
  }

  private def writeClasses(dir: Path, classes: Iterable[JvmClass]): Unit = classes.foreach { clazz =>
    val relative = clazz.name.descriptorString().drop(1).dropRight(1) + ".class"
    val path = dir.resolve(relative)
    Files.createDirectories(path.getParent)
    Files.write(path, clazz.bytecode)
  }

  test("unvalidated interface plans cannot emit implementations") {
    implicit val flix: Flix = fixture._1
    val api = declaration("com.acme.Unvalidated").copy(interfaceName = Some("java.lang.Runnable"))
    val plan = JavaBoundaryApi.prepare(api, fixture._2).unsafeGet
    assert(JavaBoundaryApi.facade(plan, Map.empty).isInstanceOf[Result.Err[?, ?]])
    assert(JavaBoundaryInterfaces.prepare(api, fixture._2).isInstanceOf[Result.Err[?, ?]])
  }

  test("recorded ABI preserves primitive widths and recursive Java generic signatures") {
    implicit val flix: Flix = fixture._1
    val api = declaration("com.acme.Boundary")
    val plan = JavaBoundaryApi.prepare(api, fixture._2).unsafeGet
    val result = flix.codeGenWithJavaApi(fixture._2, api).unsafeGet
    val facade = result.getClasses(plan.name)
    val expected = Map(
      "ints" -> ("()Ljava/util/List;", "()Ljava/util/List<Ljava/lang/Integer;>;"),
      "nested" -> ("()Ljava/util/List;", "()Ljava/util/List<Ljava/util/List<Ljava/lang/Integer;>;>;"),
      "identity" -> ("(Ljava/util/List;)Ljava/util/List;", "(Ljava/util/List<Ljava/lang/Integer;>;)Ljava/util/List<Ljava/lang/Integer;>;"),
      "plus" -> ("(IJD)J", "(IJD)J"),
      "echo" -> ("(Ljava/lang/String;)Ljava/lang/String;", "(Ljava/lang/String;)Ljava/lang/String;"),
      "noop" -> ("()V", "()V"),
      "failing" -> ("()I", "()I"))
    assert(signatures(facade) == expected)
    assert(signatures(JavaBoundaryApi.stub(plan)) == expected)
    assert(new ClassReader(facade.bytecode).getClassName == "com/acme/Boundary")
    val outSym = new Symbol.AssocTypeSym(Symbol.mkTraitSym("Api.JavaResult"), "Out", api.members.head.wrapper.loc)
    val values = fixture._2.defs.values.find(_.sym.toString == "Api.values").get.spec.retTpe
    assert(BoundaryTypeElaborator.elaborate(outSym, values, fixture._2).unsafeGet == plan.methods.head.defn.spec.retTpe)
  }

  test("Java compiles against API-only stubs and runs against the real facade without stubs") {
    implicit val flix: Flix = fixture._1
    val api = declaration("com.acme.Boundary")
    val plan = JavaBoundaryApi.prepare(api, fixture._2).unsafeGet
    val compilation = flix.codeGenWithJavaApi(fixture._2, api).unsafeGet
    val dir = Files.createTempDirectory("flix-java-boundary-")
    try {
      val stubs = dir.resolve("stubs")
      val runtime = dir.resolve("runtime")
      val callers = dir.resolve("callers")
      Files.createDirectories(callers)
      writeClasses(stubs, List(JavaBoundaryApi.stub(plan)))
      writeClasses(runtime, compilation.getClasses.values)
      val javaSource = """import java.util.List;
                         |import com.acme.Boundary;
                         |public class Caller {
                         |  public static void main(String[] args) {
                         |    List<Integer> xs = Boundary.ints();
                         |    if (!xs.equals(List.of(1, 2, 3))) throw new AssertionError(xs);
                         |    if (!Boundary.nested().equals(List.of(List.of(1)))) throw new AssertionError();
                         |    if (Boundary.identity(xs) != xs) throw new AssertionError();
                         |    if (Boundary.plus(2, 37L, 3.0) != 42L) throw new AssertionError();
                         |    if (!Boundary.echo("checked").equals("checked")) throw new AssertionError();
                         |    Boundary.noop();
                         |    try {
                         |      Boundary.failing();
                         |      throw new AssertionError("exception was lost");
                         |    } catch (NumberFormatException expected) { }
                         |  }
                         |}
                         |""".stripMargin
      val input = dir.resolve("Caller.java")
      Files.writeString(input, javaSource, StandardCharsets.UTF_8)
      val javac = ToolProvider.getSystemJavaCompiler
      assert(javac != null, "A JDK with javac is required for staged interop validation")
      assert(javac.run(null, null, null, "-classpath", stubs.toString, "-d", callers.toString, input.toString) == 0)
      // The child JVM has neither stub classes nor the compiler's classpath.
      val javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString
      val log = dir.resolve("runtime.log")
      val child = new ProcessBuilder(javaExe, "-cp", runtime.toString + java.io.File.pathSeparator + callers, "Caller")
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
      try {
        assert(child.waitFor(30, TimeUnit.SECONDS), "Staged Java caller timed out")
        assert(child.exitValue() == 0, Files.readString(log, StandardCharsets.UTF_8))
      } finally {
        if (child.isAlive) child.destroyForcibly().waitFor()
      }
    } finally {
      val paths = Files.walk(dir)
      try paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally paths.close()
    }
  }

  test("invalid names and duplicate methods fail before code generation") {
    implicit val flix: Flix = fixture._1
    assert(JavaBoundaryApi.prepare(declaration("com.class.Api"), fixture._2).isInstanceOf[Result.Err[?, ?]])
    assert(JavaBoundaryApi.prepare(declaration("dev.flix.runtime.Api"), fixture._2).isInstanceOf[Result.Err[?, ?]])
    assert(JavaBoundaryApi.prepare(declaration("java.lang.Boundary"), fixture._2).isInstanceOf[Result.Err[?, ?]])
    assert(JavaBoundaryApi.prepare(Declaration("com.acme.Api", List(member("ints"), member("ints"))), fixture._2)
      .isInstanceOf[Result.Err[?, ?]])
    assert(JavaBoundaryApi.prepare(Declaration("com.acme.Api", List(member("ints").copy(name = "class"))), fixture._2)
      .isInstanceOf[Result.Err[?, ?]])
  }

  test("unconverted Flix types, polymorphism, unboxed generics and unhandled effects fail closed") {
    implicit val flix: Flix = fixture._1
    List("values", "generic", "wrong", "effect").foreach { name =>
      assert(JavaBoundaryApi.prepare(Declaration("com.acme.Api", List(member(name))), fixture._2)
        .isInstanceOf[Result.Err[?, ?]], name)
    }
  }

  test("API class names cannot overwrite generated classes or case-folded equivalents") {
    implicit val flix: Flix = fixture._1
    val plan = JavaBoundaryApi.prepare(declaration("com.acme.Boundary"), fixture._2).unsafeGet
    List("com.acme.Boundary", "com.acme.BOUNDARY").foreach { name =>
      val desc = ClassDesc.of(name)
      assert(JavaBoundaryApi.facade(plan, Map(desc -> JvmClass(desc, Array.emptyByteArray)))
        .isInstanceOf[Result.Err[?, ?]])
    }
  }
}
