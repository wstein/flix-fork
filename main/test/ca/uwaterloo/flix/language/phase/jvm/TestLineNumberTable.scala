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

/**
  * Tests the `LineNumberTable` emitted under `--Xdebug`, which is what lets a debugger stop on a
  * line and step to the next one.
  */
class TestLineNumberTable extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /**
    * `main` is five statements on five consecutive lines, so a debugger should offer five stops.
    *
    * `compute` is `@DontInline` so those statements survive as calls rather than being folded in.
    */
  private val Program: String =
    """@DontInline
      |pub def compute(a: Int32, b: Int32): Int32 =
      |    let x = a + 1;
      |    let y = b + 2;
      |    x * y
      |
      |def main(): Unit \ IO =
      |    let p = compute(3, 4);
      |    let q = compute(p, 2);
      |    println(p);
      |    println(q)
      |""".stripMargin

  /** The number of lines in [[Program]]; no emitted line may exceed it. */
  private val ProgramLines: Int = Program.linesIterator.size

  /** Returns the lines recorded for `method` of `className`, compiled with `xdebug` as given. */
  private def linesOf(xdebug: Boolean, className: String, method: String): List[Int] = {
    val out = Files.createTempDirectory("flix-lnt-test")
    try {
      val opts = Options.DefaultTest.copy(xdebug = xdebug, outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      readLines(out.resolve("class").resolve(s"$className.class"), method)
    } finally {
      deleteRecursively(out)
    }
  }

  /** Returns the lines `method` records in the class file at `path`, in bytecode order. */
  private def readLines(path: Path, method: String): List[Int] = {
    assert(Files.exists(path), s"expected a generated class at $path")
    val found = mutable.ListBuffer.empty[Int]
    new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != method) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLineNumber(line: Int, start: Label): Unit = found += line
        }
      }
    }, 0)
    found.toList
  }

  /**
    * Returns the output (synthetic) line numbers listed in the class's JSR-45 SMAP at `path`,
    * or the empty set if the class carries no `SourceDebugExtension`.
    *
    * `ClassVisitor.visitSource`'s `debug` parameter is exactly the `SourceDebugExtension`
    * attribute content, so no custom attribute parsing is needed to read it back.
    */
  private def readSmapOutputLines(path: Path): Set[Int] = {
    assert(Files.exists(path), s"expected a generated class at $path")
    var smap: Option[String] = None
    new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitSource(source: String, debug: String): Unit = smap = Option(debug)
    }, 0)
    // Each `*L` entry has the form `<inputLine>#<fileId>,<lineCount>:<outputLine>`.
    val LineEntry = """.*:(\d+)""".r
    smap match {
      case None => Set.empty
      case Some(text) =>
        text.linesIterator
          .dropWhile(_ != "*L").drop(1)
          .takeWhile(_ != "*E")
          .collect { case LineEntry(outputLine) => outputLine.toInt }
          .toSet
    }
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
  }

  test("without --Xdebug a method reports only its declaration line") {
    assertResult(List(7))(linesOf(xdebug = false, "Def$main", JvmName.StaticApply))
  }

  test("with --Xdebug every statement of main is reported, in order") {
    // The declaration on line 7, then one stop per statement on lines 8 to 11.
    assertResult(List(7, 8, 9, 10, 11))(linesOf(xdebug = true, "Def$main", JvmName.StaticApply))
  }

  test("with --Xdebug the reported lines never repeat consecutively") {
    val lines = linesOf(xdebug = true, "Def$main", JvmName.StaticApply)
    assertResult(lines)(lines.distinct)
  }

  test("a line outside the compiled file is backed by a JSR-45 mapping") {
    // A location belonging to another file (e.g. an inlined stdlib call) is assigned a
    // synthetic line above the program's own, rather than being misattributed to a line of
    // *this* file that may not exist. The class's SMAP is what lets a debugger resolve that
    // synthetic line back to the real (file, line) it came from.
    val out = Files.createTempDirectory("flix-lnt-test")
    try {
      val opts = Options.DefaultTest.copy(xdebug = true, outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, Program)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      for (name <- List("Def$main", "Def$compute")) {
        val path = out.resolve("class").resolve(s"$name.class")
        val lines = readLines(path, JvmName.StaticApply)
        val foreign = lines.filter(_ > ProgramLines)
        if (foreign.nonEmpty) {
          val synthetic = readSmapOutputLines(path)
          assert(foreign.forall(synthetic.contains), s"$name reported $foreign beyond $ProgramLines lines, but its SMAP only maps $synthetic")
        }
      }
    } finally {
      deleteRecursively(out)
    }
  }

}
