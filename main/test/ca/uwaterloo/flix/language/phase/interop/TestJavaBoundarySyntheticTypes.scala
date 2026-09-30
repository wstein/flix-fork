/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.errors.InstanceError
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassWriter, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.ClassDesc
import java.nio.file.Paths

class TestJavaBoundarySyntheticTypes extends AnyFunSuite with TestUtils {
  private val desc = ClassDesc.of("com.acme.SyntheticPoint")

  private def point: Array[Byte] = {
    val cw = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_RECORD,
      "com/acme/SyntheticPoint", null, "java/lang/Record", null)
    cw.visitRecordComponent("x", "I", null).visitEnd()
    List("<init>" -> "(I)V", "x" -> "()I").foreach { case (name, signature) =>
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, signature, null, null)
      mv.visitCode()
      mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException")
      mv.visitInsn(Opcodes.DUP)
      mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false)
      mv.visitInsn(Opcodes.ATHROW)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }
    cw.visitEnd()
    cw.toByteArray
  }

  test("ordinary Java interop type-checks synthetic metadata before any class exists") {
    val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xchaosMonkey = false))
      .addSource(Paths.get("Synthetic.flix"),
        "pub mod Synthetic { import com.acme.SyntheticPoint pub def value(): Int32 \\ IO = new SyntheticPoint(3).x() }", sctx)
    try {
      assert(flix.javaTypeProvider.lookupClass(desc).isInstanceOf[Result.Err[?, ?]])
      flix.withJavaBoundaryTypes(Map(desc -> point)) {
        assert(flix.javaTypeProvider.lookupClass(desc).unsafeGet.declaredConstructors.head.parameterTypes.size == 1)
        assert(flix.javaTypeProvider.isSubtype(desc, ClassDesc.of("java.lang.Record")) == Result.Ok(true))
        expectSuccess(flix.check())
        assertThrows[ClassNotFoundException](flix.jarLoader.loadClass("com.acme.SyntheticPoint"))
      }
      assert(flix.javaTypeProvider.lookupClass(desc).isInstanceOf[Result.Err[?, ?]])
      assert(flix.check()._2.nonEmpty)
    } finally flix.close()
  }

  test("failed scopes restore metadata and do not close dependency resources") {
    val flix = new Flix().setOptions(Options.TestWithLibNix)
    try {
      assertThrows[IllegalArgumentException] {
        flix.withJavaBoundaryTypes(Map(desc -> point)) { throw new IllegalArgumentException("probe") }
      }
      assert(flix.javaTypeProvider.lookupClass(desc).isInstanceOf[Result.Err[?, ?]])
      assert(flix.javaTypeProvider.lookupClass(ClassDesc.of("java.lang.String")).isInstanceOf[Result.Ok[?, ?]])
      flix.withJavaBoundaryTypes(Map(desc -> point)) {
        assert(flix.javaTypeProvider.lookupClass(desc).isInstanceOf[Result.Ok[?, ?]])
      }
    } finally flix.close()
  }

  test("a generated nominal adapter has an ordinary checked boundary instance") {
    val source = """pub mod Synthetic {
                   |    import com.acme.SyntheticPoint
                   |    pub enum Adapter { case Adapter(Int32) }
                   |    instance Java.Boundary.JavaResult[Adapter] {
                   |        type Out = SyntheticPoint
                   |        type Aef = IO
                   |        pub def toJava(x: Adapter): SyntheticPoint \ IO = match x {
                   |            case Adapter.Adapter(n) => new SyntheticPoint(n)
                   |        }
                   |    }
                   |    instance Java.Boundary.JavaArgument[Adapter] {
                   |        type In = SyntheticPoint
                   |        type Aef = IO
                   |        pub def toFlix(x: SyntheticPoint): Adapter \ IO = Adapter.Adapter(x.x())
                   |    }
                   |}
                   |""".stripMargin
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("Synthetic.flix"), source, sctx)
    try flix.withJavaBoundaryTypes(Map(desc -> point)) { expectSuccess(flix.check()) }
    finally flix.close()
  }

  test("nested metadata scopes cannot replace an active contract") {
    val flix = new Flix().setOptions(Options.TestWithLibMin)
    try flix.withJavaBoundaryTypes(Map(desc -> point)) {
      assertThrows[IllegalStateException] {
        flix.withJavaBoundaryTypes(Map.empty) { fail("A nested scope must not run") }
      }
      assert(flix.javaTypeProvider.lookupClass(desc).isInstanceOf[Result.Ok[?, ?]])
    } finally flix.close()
  }

  test("closed compilers refuse synthetic metadata scopes") {
    val flix = new Flix().setOptions(Options.TestWithLibMin)
    flix.close()
    assertThrows[IllegalStateException] {
      flix.withJavaBoundaryTypes(Map(desc -> point)) { fail("A closed compiler scope must not run") }
    }
  }

  test("concrete tuple heads still require an alternative to direct generated instances") {
    val source = """pub trait Boundary[t: Type]
                   |instance Boundary[(Int32, Int32)]
                   |""".stripMargin
    assertInstanceError(source, classOf[InstanceError.ComplexInstance])
  }

  test("concrete nominal instantiations still require an alternative to direct generated instances") {
    val source = """pub trait Boundary[t: Type]
                   |pub enum Box[a] { case Box(a) }
                   |instance Boundary[Box[Int32]]
                   |""".stripMargin
    assertInstanceError(source, classOf[InstanceError.ComplexInstance])
  }

  test("record aliases cannot become direct generated instance heads") {
    val source = """pub trait Boundary[t: Type]
                   |type alias Point = { x = Int32, y = Int32 }
                   |instance Boundary[Point]
                   |""".stripMargin
    assertInstanceError(source, classOf[InstanceError.IllegalTypeAliasInstance])
  }

  test("a generic instance cannot distinguish separately named concrete Java representations") {
    val source = """pub trait Boundary[t: Type]
                   |pub enum Box[a] { case Box(a) }
                   |instance Boundary[Box[a]]
                   |instance Boundary[Box[b]]
                   |""".stripMargin
    assertInstanceError(source, classOf[InstanceError.OverlappingInstances])
  }

  private def assertInstanceError(source: String, expected: Class[? <: InstanceError]): Unit = {
    val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xchaosMonkey = false))
      .addSource(Paths.get("InstanceHead.flix"), source, sctx)
    try {
      val errors = flix.check()._2
      assert(errors.exists(expected.isInstance), errors.map(_.summary).mkString("\n"))
    } finally flix.close()
  }
}
