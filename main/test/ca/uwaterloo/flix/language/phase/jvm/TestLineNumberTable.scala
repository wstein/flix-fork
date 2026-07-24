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

  test("no line outside the compiled file is reported") {
    // An inlined callee's location belongs to another file. Recording it would send a debugger
    // to that line of *this* file, which may not exist.
    for (method <- List(JvmName.StaticApply)) {
      for (name <- List("Def$main", "Def$compute")) {
        val lines = linesOf(xdebug = true, name, method)
        assert(lines.forall(l => l >= 1 && l <= ProgramLines), s"$name reported $lines, file has $ProgramLines lines")
      }
    }
  }

}
