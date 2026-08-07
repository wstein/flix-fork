/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, Label, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
  * Tests that `--Xdebug` emits a `LocalVariableTable`, which is what lets a debugger show a
  * variable by its source name rather than as a numbered JVM slot, and hides synthetic compiler locals.
  */
class TestLocalVariableTable extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** `compute` is `@DontInline` so its own frame, and therefore its own locals, survive. */
  private val Program: String =
    """
      |@DontInline
      |pub def compute(a: Int32, b: Int32): Int32 =
      |    let x = a + 1;
      |    let y = b + 2;
      |    let z = x * y;
      |    z - a
      |
      |def main(): Unit \ IO =
      |    println(compute(3, 4))
      |""".stripMargin

  /** One `LocalVariableTable` entry. */
  private case class Entry(name: String, descriptor: String, slot: Int)

  /** Returns the entries recorded for the generated `compute.staticApply`, compiled with `xdebug` as given. */
  private def entriesOf(xdebug: Boolean): List[Entry] = {
    val out = Files.createTempDirectory("flix-lvt-test")
    try {
      val opts = Options.DefaultTest.copy(xdebug = xdebug, outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      readEntries(out.resolve("class").resolve(JvmName(JvmName.packageOfNamespace(Nil), JvmName.mkClassName("Def", "compute")).toPath))
    } finally {
      deleteRecursively(out)
    }
  }

  /** Returns the entries `staticApply` records in the class file at `path`. */
  private def readEntries(path: Path): List[Entry] = {
    assert(Files.exists(path), s"expected a generated class at $path")
    val found = mutable.ListBuffer.empty[Entry]
    val reader = new ClassReader(Files.readAllBytes(path))
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != JvmName.StaticApply) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLocalVariable(name: String, descriptor: String, signature: String, start: Label, end: Label, index: Int): Unit =
            found += Entry(name, descriptor, index)
        }
      }
    }, 0)
    found.toList
  }

  /** Reads all `LocalVariableTable` entries across all `.class` files in the output directory tree. */
  private def readAllEntries(classDir: Path): List[(Path, Entry)] = {
    if (!Files.exists(classDir)) return Nil
    val found = mutable.ListBuffer.empty[(Path, Entry)]
    val classFiles = Using.resource(Files.walk(classDir)) { stream =>
      stream.iterator().asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".class")).toList
    }
    for (classPath <- classFiles) {
      val reader = new ClassReader(Files.readAllBytes(classPath))
      reader.accept(new ClassVisitor(Opcodes.ASM9) {
        override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
          new MethodVisitor(Opcodes.ASM9) {
            override def visitLocalVariable(name: String, descriptor: String, signature: String, start: Label, end: Label, index: Int): Unit = {
              found += ((classPath, Entry(name, descriptor, index)))
            }
          }
        }
      }, 0)
    }
    found.toList
  }

  private def compileAndReadAllEntries(program: String, xdebug: Boolean = true): List[(Path, Entry)] = {
    val out = Files.createTempDirectory("flix-lvt-test")
    try {
      val opts = Options.DefaultTest.copy(xdebug = xdebug, outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      readAllEntries(out.resolve("class"))
    } finally {
      deleteRecursively(out)
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
  }

  test("without --Xdebug no local variable information is emitted") {
    assertResult(Nil)(entriesOf(xdebug = false))
  }

  test("with --Xdebug the parameters are named and typed") {
    val entries = entriesOf(xdebug = true)
    assert(entries.contains(Entry("a", "I", 0)), s"expected parameter a in $entries")
    assert(entries.contains(Entry("b", "I", 1)), s"expected parameter b in $entries")
  }

  test("with --Xdebug the let-bindings are named and typed") {
    val entries = entriesOf(xdebug = true)
    assertResult(List("x", "y", "z"))(entries.map(_.name).filter(Set("x", "y", "z")).sorted)
    assert(entries.filter(e => Set("x", "y", "z")(e.name)).forall(_.descriptor == "I"))
  }

  test("with --Xdebug every variable occupies a distinct slot") {
    val entries = entriesOf(xdebug = true)
    assertResult(entries.size)(entries.map(_.slot).distinct.size)
  }

  test("with --Xdebug delimiter-marked synthetic variables are hidden in match and match-lambda") {
    val program =
      """
        |@DontInline
        |pub def classify(x: Option[Option[Int32]]): Int32 = match x {
        |    case Some(Some(n)) => n
        |    case _              => 0
        |}
        |
        |@DontInline
        |pub def classifyLambda(v: Option[Option[Int32]]): Int32 =
        |    (match x -> match x {
        |        case Some(Some(n)) => n
        |        case _              => 0
        |    })(v)
        |
        |def main(): Unit = {
        |    let _ = classify(None);
        |    let _ = classifyLambda(None);
        |    ()
        |}
        |""".stripMargin
    val allEntries = compileAndReadAllEntries(program, xdebug = true)
    val names = allEntries.map(_._2.name)
    assert(names.contains("n"), s"expected user variable 'n' in entries: $names")
    assert(!names.exists(_.contains(Flix.Delimiter)), s"expected no delimiter in local variable names, but got: ${names.filter(_.contains(Flix.Delimiter))}")
  }

  test("with --Xdebug derived instance synthetic variables (x, y, z) are hidden") {
    val program =
      """
        |enum Color with Eq {
        |    case Red, Green, Blue
        |}
        |
        |@DontInline
        |pub def sameColor(a: Color, b: Color): Bool = a == b
        |
        |def main(): Unit = {
        |    let _ = sameColor(Color.Red, Color.Blue);
        |    ()
        |}
        |""".stripMargin
    val allEntries = compileAndReadAllEntries(program, xdebug = true)
    val names = allEntries.map(_._2.name)
    assert(!names.contains("x"), s"derived parameter 'x' should be hidden, but found in $names")
    assert(!names.contains("y"), s"derived parameter 'y' should be hidden, but found in $names")
    assert(!names.contains("z"), s"derived pattern variable 'z' should be hidden, but found in $names")
  }

  test("with --Xdebug synthetic variables remain synthetic after specialization clone") {
    val program =
      """
        |@DontInline
        |pub def describe(x: a): Int32 = match Option.Some(x) {
        |    case Some(_) => 1
        |    case None    => 0
        |}
        |
        |@DontInline
        |pub def useTwice(): Int32 = describe(1) + describe(true)
        |
        |def main(): Unit = {
        |    let _ = useTwice();
        |    ()
        |}
        |""".stripMargin
    val allEntries = compileAndReadAllEntries(program, xdebug = true)
    val describeClasses = allEntries.map(_._1.getFileName.toString).filter(_.contains("describe"))
    assert(describeClasses.distinct.size >= 2, s"expected at least 2 specialized describe classes, but found: $describeClasses")
    val names = allEntries.map(_._2.name)
    assert(!names.exists(_.contains(Flix.Delimiter)), s"expected no delimiter in specialized local variable names, but got: ${names.filter(_.contains(Flix.Delimiter))}")
  }

}
