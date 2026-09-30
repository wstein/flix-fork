/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.{Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.phase.interop.JavaBoundaryWrappers.*
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider
import scala.jdk.CollectionConverters.*

class TestJavaBoundaryWrappers extends AnyFunSuite with TestUtils {
  test("frontend timing probe records the two checks separately") {
    if (sys.env.contains("FLIX_BOUNDARY_TIMINGS")) {
      val samples = (1 to 3).map { _ =>
        val times = scala.collection.mutable.ArrayBuffer.empty[Long]
        val measured = new Flix() {
          override def check(): (Option[TypedAst.Root], List[CompilationMessage]) = {
            val start = System.nanoTime()
            try super.check() finally times += System.nanoTime() - start
          }
        }
        measured.setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false)).addSource(path, source, sctx)
        try {
          compile(measured, api(methods), traits, sctx).unsafeGet
          assert(times.size == 2)
          (times(0) / 1000000.0, times(1) / 1000000.0)
        } finally measured.close()
      }
      info(s"Boundary frontend milliseconds (input, augmented): ${samples.mkString(", ")}")
    }
  }
  private val path = Paths.get("Wrappers.flix")
  private val source = """pub mod Wrappers {
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
                         |    pub trait JavaArgument[t: Type] {
                         |        type In[t]: Type
                         |        type Aef[t]: Eff = {}
                         |        pub def toFlix(x: JavaArgument.In[t]): t \ JavaArgument.Aef[t]
                         |    }
                         |    instance JavaArgument[Int32] {
                         |        type In = Integer
                         |        pub def toFlix(x: Integer): Int32 = Int32.intValue(x)
                         |    }
                         |    instance JavaArgument[List[a]] with JavaArgument[a] {
                         |        type In = JList[JavaArgument.In[a]]
                         |        type Aef = JavaArgument.Aef[a]
                         |        pub def toFlix(xs: JList[JavaArgument.In[a]]): List[a] \ JavaArgument.Aef[a] =
                         |            List.map(JavaArgument.toFlix, Adaptor.fromList(xs))
                         |    }
                         |    pub enum Token { case Token(Int32) }
                         |    instance JavaResult[Token] {
                         |        type Out = String
                         |        pub def toJava(x: Token): String = match x {
                         |            case Token.Token(n) => Int32.toString(n)
                         |        }
                         |    }
                         |    instance JavaArgument[Token] {
                         |        type In = String
                         |        pub def toFlix(x: String): Token = Token.Token(Integer.parseInt(x))
                         |    }
                         |    pub enum ResultOnly { case Only }
                         |    instance JavaResult[ResultOnly] {
                         |        type Out = String
                         |        pub def toJava(x: ResultOnly): String = match x { case ResultOnly.Only => "only" }
                         |    }
                         |    pub def values(): List[Int32] = 1 :: 2 :: 3 :: Nil
                         |    pub def nested(): List[List[Int32]] = (1 :: Nil) :: Nil
                         |    pub def sum(xs: List[Int32]): Int32 = List.foldLeft((a, b) -> a + b, 0, xs)
                         |    pub def reverse(xs: List[Int32]): List[Int32] = List.reverse(xs)
                         |    pub def token(): Token = Token.Token(42)
                         |    pub def tokenArg(x: Token): Int32 = match x { case Token.Token(n) => n }
                         |    pub def echo(s: String): String = s
                         |    pub def noop(): Unit = ()
                         |    pub def missing(): List[String] = "missing" :: Nil
                         |    pub def directional(x: ResultOnly): Unit = match x { case ResultOnly.Only => () }
                         |    pub def generic(x: a): a = x
                         |    pub def regionValue(): Array[Int32, Static] \ IO = Array#{} @ Static
                         |}
                         |""".stripMargin
  private val traits = Traits(Symbol.mkTraitSym("Wrappers.JavaResult"), Symbol.mkTraitSym("Wrappers.JavaArgument"))
  private val methods = List("values", "nested", "sum", "reverse", "token", "tokenArg", "echo", "noop")
  private def compiler(text: String): Flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
    .addSource(path, text, sctx)
  private lazy val root: TypedAst.Root = {
    val checked = compiler(source).check()
    expectSuccess(checked)
    checked._1.get
  }
  private def member(name: String): Member = {
    val defn = root.defs.values.find(_.sym.toString == s"Wrappers.$name").get
    Member(name, defn.sym, defn.loc)
  }
  private def api(names: List[String]): Declaration = Declaration("com.acme.Automatic", names.map(member))
  private def signature(output: Output, name: String): String = output.plan.methods.find(_.member.name == name).get.signature

  test("automatic wrappers derive recursive results, arguments, effects and user instances") {
    val flix = compiler(source)
    val output = compile(flix, api(methods), traits, sctx).unsafeGet
    assert(signature(output, "values") == "()Ljava/util/List<Ljava/lang/Integer;>;")
    assert(signature(output, "nested") == "()Ljava/util/List<Ljava/util/List<Ljava/lang/Integer;>;>;")
    assert(signature(output, "sum") == "(Ljava/util/List<Ljava/lang/Integer;>;)I")
    assert(signature(output, "reverse") == "(Ljava/util/List<Ljava/lang/Integer;>;)Ljava/util/List<Ljava/lang/Integer;>;")
    assert(signature(output, "token") == "()Ljava/lang/String;")
    assert(signature(output, "tokenArg") == "(Ljava/lang/String;)I")
    assert(output.plan.methods.find(_.member.name == "sum").get.defn.spec.eff == Type.Pure)
    assert(output.plan.methods.find(_.member.name == "values").get.defn.spec.eff == Type.IO)
    val cleaned = flix.check()
    expectSuccess(cleaned)
    assert(!cleaned._1.get.modules.keys.exists(_.ns.headOption.exists(_.startsWith("BoundaryGenerated"))))
  }

  test("instance edits rebuild the Java ABI without stale generated sources") {
    val flix = compiler(source)
    val declaration = api(List("token"))
    val before = compile(flix, declaration, traits, sctx).unsafeGet
    val repeated = compile(flix, declaration, traits, sctx).unsafeGet
    assert(before.plan.methods.head.member.wrapper == repeated.plan.methods.head.member.wrapper)
    assert(before.compilation.getClasses(before.plan.name).bytecode.sameElements(repeated.compilation.getClasses(repeated.plan.name).bytecode))
    val revised = source.replace("type Out = String\n        pub def toJava(x: Token): String", "type Out = Integer\n        pub def toJava(x: Token): Integer")
      .replace("case Token.Token(n) => Int32.toString(n)", "case Token.Token(n) => Int32.valueOf(n)")
    flix.addSource(path, revised, sctx)
    val after = compile(flix, declaration, traits, sctx).unsafeGet
    assert(signature(after, "token") == "()Ljava/lang/Integer;")
    assert(signature(before, "token") == "()Ljava/lang/String;")
    expectSuccess(flix.check())
  }

  test("missing directional evidence fails at the API member location") {
    List("missing", "directional").foreach { name =>
      compile(compiler(source), api(List(name)), traits, sctx) match {
        case Result.Err(BoundaryError(_: BoundaryTypeElaborator.MissingInstance, loc)) => assert(loc == member(name).loc)
        case other => fail(s"Expected missing evidence, found $other")
      }
    }
  }
  test("polymorphic and region-bound targets fail before wrapper generation") {
    List("generic", "regionValue").foreach { name =>
      compile(compiler(source), api(List(name)), traits, sctx) match {
        case Result.Err(Invalid(_, loc)) => assert(loc == member(name).loc)
        case other => fail(s"Expected an invalid target, found $other")
      }
    }
  }
  test("frontend errors map to the member and generated sources are removed after failure") {
    val flix = compiler(source.replace("toJava", "convert"))
    compile(flix, api(List("token")), traits, sctx) match {
      case Result.Err(WrapperErrors(messages, loc)) => assert(messages.nonEmpty); assert(loc == member("token").loc)
      case other => fail(s"Expected a generated-wrapper error, found $other")
    }
    expectSuccess(flix.check())
  }

  test("unsupported instance outputs are rejected instead of rendered as Unit or Object") {
    val revised = source.replace("type Out = String\n        pub def toJava(x: Token): String", "type Out = List[Int32]\n        pub def toJava(x: Token): List[Int32]")
      .replace("case Token.Token(n) => Int32.toString(n)", "case Token.Token(n) => n :: Nil")
    compile(compiler(revised), api(List("token")), traits, sctx) match {
      case Result.Err(Invalid(_, loc)) => assert(loc == member("token").loc)
      case other => fail(s"Expected an unsupported boundary output, found $other")
    }
  }

  test("caller-owned generated names and virtual sources are never overwritten") {
    val declaration = api(List("token"))
    val hash = MessageDigest.getInstance("SHA-256").digest(declaration.className.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString
    val module = "BoundaryGenerated" + hash
    val uri = URI.create(s"flix-boundary:/$module.flix")
    val flix = compiler(source).addSource(uri, s"pub mod $module { pub def owned(): Int32 = 17 }", sctx)
    compile(flix, declaration, traits, sctx) match {
      case Result.Err(Invalid(message, _)) => assert(message.contains("owned by the caller"))
      case other => fail(s"Expected a reserved-source collision, found $other")
    }
    val checked = flix.check()
    expectSuccess(checked)
    assert(checked._1.get.defs.keys.exists(sym => sym.namespace == List(module) && sym.text == "owned"))
  }

  test("staged Java calls automatically generated wrappers without hand-written Java signatures") {
    implicit val flix: Flix = compiler(source)
    val output = compile(flix, api(methods), traits, sctx).unsafeGet
    val dir = Files.createTempDirectory("flix-automatic-boundary-")
    try {
      val stubs = dir.resolve("stubs")
      val runtime = dir.resolve("runtime")
      val callers = dir.resolve("callers")
      Files.createDirectories(callers)
      List(stubs -> List(JavaBoundaryApi.stub(output.plan)), runtime -> output.compilation.getClasses.values).foreach { case (dest, classes) =>
        classes.foreach { clazz =>
          val file = dest.resolve(clazz.name.descriptorString().drop(1).dropRight(1) + ".class")
          Files.createDirectories(file.getParent)
          Files.write(file, clazz.bytecode)
        }
      }
      val caller = """import java.util.List;
                     |import com.acme.Automatic;
                     |public class Caller {
                     |  public static void main(String[] args) {
                     |    List<Integer> xs = Automatic.values();
                     |    if (!xs.equals(List.of(1, 2, 3))) throw new AssertionError();
                     |    if (!Automatic.nested().equals(List.of(List.of(1)))) throw new AssertionError();
                     |    if (Automatic.sum(xs) != 6) throw new AssertionError();
                     |    if (!Automatic.reverse(xs).equals(List.of(3, 2, 1))) throw new AssertionError();
                     |    if (!Automatic.token().equals("42") || Automatic.tokenArg("7") != 7) throw new AssertionError();
                     |    if (!Automatic.echo("ok").equals("ok")) throw new AssertionError();
                     |    Automatic.noop();
                     |  }
                     |}
                     |""".stripMargin
      val input = dir.resolve("Caller.java")
      Files.writeString(input, caller, StandardCharsets.UTF_8)
      assert(ToolProvider.getSystemJavaCompiler.run(null, null, null, "-cp", stubs.toString, "-d", callers.toString, input.toString) == 0)
      val log = dir.resolve("runtime.log")
      val child = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", runtime.toString + java.io.File.pathSeparator + callers, "Caller")
        .redirectErrorStream(true).redirectOutput(log.toFile).start()
      try {
        assert(child.waitFor(30, TimeUnit.SECONDS), "Staged Java caller timed out")
        assert(child.exitValue() == 0, Files.readString(log, StandardCharsets.UTF_8))
      } finally if (child.isAlive) child.destroyForcibly().waitFor()
    } finally {
      val files = Files.walk(dir)
      try files.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      finally files.close()
    }
  }
}
