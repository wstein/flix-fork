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
    * `main` contains four emitted statements on consecutive source lines, so a debugger should
    * offer four stops after the declaration.
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

  /**
    * A single-expression function and its caller -- the shape the inliner folds away.
    *
    * Kept separate from [[Program]] so that its line numbers can be asserted directly without
    * every other assertion in this suite shifting.
    */
  private val InlineProgram: String =
    """import java.lang.Math
      |
      |pub def tiny(): Int32 \ IO = Math.max(10, 20)
      |
      |def main(): Unit \ IO =
      |    println(tiny())
      |""".stripMargin

  /**
    * Two functions whose bodies emit no statement of their own: a constant and a unit literal.
    *
    * `@DontInline` keeps them as methods to inspect rather than folding them into the caller.
    */
  private val NoBodyLineProgram: String =
    """@DontInline
      |pub def constantBody(): Int32 = 42
      |
      |@DontInline
      |pub def unitBody(): Unit = ()
      |
      |def main(): Unit \ IO =
      |    println(constantBody());
      |    unitBody()
      |""".stripMargin

  /** A compiled method: the lines it records, in bytecode order, and its class's SMAP. */
  private case class Method(lines: List[Int], smap: Option[Smap])

  /** The JSR-45 mapping of one class, parsed back from its `SourceDebugExtension`. */
  private case class Smap(files: Map[Int, String], strata: List[(Int, Int, Int, Int)]) {
    /** Resolves a synthetic output line to its `(file, line)`, or `None` if unmapped. */
    def resolve(output: Int): Option[(String, Int)] =
      strata.collectFirst {
        case (inputStart, fileId, count, outputStart) if output >= outputStart && output < outputStart + count =>
          (files(fileId), inputStart + (output - outputStart))
      }
  }

  /** Compiles `program` with `xdebug` as given and returns `method` of `className`. */
  private def compile(xdebug: Boolean, className: String, method: String, program: String = Program): Method = {
    val out = Files.createTempDirectory("flix-lnt-test")
    try {
      val opts = Options.DefaultTest.copy(xdebug = xdebug, outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, program)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      read(out.resolve("class").resolve(s"$className.class"), method)
    } finally {
      deleteRecursively(out)
    }
  }

  /** Reads `method`'s lines and the class's SMAP from the class file at `path`. */
  private def read(path: Path, method: String): Method = {
    assert(Files.exists(path), s"expected a generated class at $path")
    val lines = mutable.ListBuffer.empty[Int]
    var debug: Option[String] = None
    new ClassReader(Files.readAllBytes(path)).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitSource(source: String, dbg: String): Unit = debug = Option(dbg)
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != method) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLineNumber(line: Int, start: Label): Unit = lines += line
        }
      }
    }, 0)
    Method(lines.toList, debug.map(parseSmap))
  }

  /** Parses the `*F` and `*L` sections of an SMAP into a [[Smap]]. */
  private def parseSmap(text: String): Smap = {
    val ls = text.linesIterator.toList
    val files = mutable.Map.empty[Int, String]
    val strata = mutable.ListBuffer.empty[(Int, Int, Int, Int)]

    val fileSection = ls.dropWhile(_ != "*F").drop(1).takeWhile(l => l != "*L" && l != "*E")
    // Each file is "+ <id> <name>" followed by a path line.
    var rest = fileSection
    while (rest.nonEmpty) {
      val head = rest.head
      if (head.startsWith("+ ")) {
        val parts = head.drop(2).split(" ", 2)
        files(parts(0).toInt) = parts(1)
        rest = rest.drop(2) // skip the path line that follows
      } else {
        rest = rest.drop(1)
      }
    }

    // Each line entry is "<inputStart>#<fileId>,<count>:<outputStart>", with #fileId and
    // ,count optional. Our emission always writes them, which keeps the parser simple.
    val lineSection = ls.dropWhile(_ != "*L").drop(1).takeWhile(_ != "*E")
    for (entry <- lineSection if entry.contains(":")) {
      val Array(lhs, out) = entry.split(":", 2)
      val (inPart, fileId) = if (lhs.contains("#")) {
        val Array(a, b) = lhs.split("#", 2)
        (a, b.split(",").head.toInt)
      } else (lhs, files.keys.min)
      val inputStart = inPart.split(",").head.toInt
      val count = if (lhs.contains(",")) lhs.split(",").last.toInt else 1
      strata += ((inputStart, fileId, count, out.toInt))
    }
    Smap(files.toMap, strata.toList)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
  }

  /**
    * The path, relative to the class output directory, of the function class of the root-namespace
    * def `name`. Root-namespace classes live in [[JvmName.DevFlixGen]] rather than in the unnamed
    * package.
    */
  private def defClass(name: String): String =
    (JvmName.packageOfNamespace(Nil) :+ JvmName.mkClassName("Def", name)).mkString("/")

  test("without --Xdebug a method reports only its declaration line") {
    assertResult(List(7))(compile(xdebug = false, defClass("main"), JvmName.StaticApply).lines)
  }

  test("with --Xdebug every emitted statement of main is reported, in order") {
    // One stop per emitted statement, lines 8 to 11. The declaration on line 7 is absent because
    // the body begins at the same bytecode offset and the statement takes it -- see the next test.
    assertResult(List(8, 9, 10, 11))(compile(xdebug = true, defClass("main"), JvmName.StaticApply).lines)
  }

  test("the first statement of a function is reported, not swallowed by the declaration") {
    // A method opens by recording its declaration line, before any instruction is written, so that
    // entry sits at bytecode offset 0 -- and so does the body's first statement. Only one entry per
    // offset survives into the class file, and when the declaration won, the first statement of
    // *every* function was missing from the table: `locationsOfLine` returned nothing for it and no
    // breakpoint could ever bind, while the line above and every line below it worked.
    //
    // Line 8, `let p = compute(3, 4)`, is that first statement.
    val lines = compile(xdebug = true, defClass("main"), JvmName.StaticApply).lines
    assert(lines.contains(8), s"the first statement of main must be reachable, got $lines")
  }

  test("with --Xdebug the reported lines never repeat consecutively") {
    val lines = compile(xdebug = true, defClass("main"), JvmName.StaticApply).lines
    assertResult(lines)(lines.distinct)
  }

  test("a method drawing on a single file needs no SMAP and stays within that file") {
    // main calls compute but inlines nothing foreign, so its lines are its own.
    val m = compile(xdebug = true, defClass("main"), JvmName.StaticApply)
    assertResult(None)(m.smap)
    assert(m.lines.forall(l => l >= 1 && l <= Program.linesIterator.size))
  }

  test("a single-expression function keeps a class and a line of its own under --Xdebug") {
    // The case that motivated disabling the optimizer under --Xdebug. Inlined, `tiny` gets no
    // class at all, and its line does not survive at the call site either -- the inlined body
    // begins at the call site's own bytecode offset, and only one entry per offset reaches the
    // class file. The line then exists nowhere in the program and no breakpoint can ever bind to
    // it, which is the fate of every single-expression helper.
    //
    // Asserting the class's own line table is what proves the class exists: `compile` fails if
    // there is no class file to read.
    assertResult(List(3))(compile(xdebug = true, defClass("tiny"), JvmName.StaticApply, InlineProgram).lines)
  }

  test("without --Xdebug that same function is inlined away entirely") {
    // The other side of the trade, stated so that switching it back off is a visible decision
    // rather than a silent regression: optimization is unchanged for everything anyone ships.
    val thrown = intercept[Exception] {
      compile(xdebug = false, defClass("tiny"), JvmName.StaticApply, InlineProgram)
    }
    assert(thrown.getMessage.contains("tiny"), s"expected no class for the inlined tiny, got: ${thrown.getMessage}")
  }

  test("a body that records no line still reports its declaration line") {
    // A constant and a unit literal contribute no statement of their own, so the declaration is the
    // only line either method can report. Both hold under `--Xdebug`, where every other method in
    // this suite reports body lines as well.
    //
    // This does *not* cover `LineNumbers.finish()`, despite looking like it should. `finish()` is
    // documented as the reason a body that records nothing still reports its declaration, but
    // disabling it leaves these assertions -- and every other one here -- passing. It does run and
    // write for these very lines, yet nothing it writes reaches the class file: reading all four
    // methods of the generated class shows entries only in `staticApply`, and those survive its
    // removal. Whatever shape `finish()` is load-bearing for is not one this suite produces, so
    // there is nothing here to assert about it.
    for (xdebug <- List(false, true)) {
      assertResult(List(2), s"constantBody, xdebug=$xdebug")(
        compile(xdebug, defClass("constantBody"), JvmName.StaticApply, NoBodyLineProgram).lines)
      assertResult(List(5), s"unitBody, xdebug=$xdebug")(
        compile(xdebug, defClass("unitBody"), JvmName.StaticApply, NoBodyLineProgram).lines)
    }
  }
}
