/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix, InstalledPackage}
import ca.uwaterloo.flix.language.ast.shared.{SecurityContext, SourceName}
import ca.uwaterloo.flix.runtime.CompilationResult
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}

class TestPackageSourceIdentity extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  test("package paths are normalized once when sources are loaded") {
    val (_, result, archive) = compilePackage()
    val packageNames = result.getSources.keysIterator.map(_.sourceName).collect {
      case name: SourceName.PackageEntry => name
    }.toList

    assert(packageNames.nonEmpty)
    assert(packageNames.forall(_.pkg == archive))
  }

  test("package identities reach bytecode and debug sidecars unchanged") {
    val (identity, result, _) = compilePackage()
    val index = DebugIndex.of(result.getClasses.values)

    assert(index.contains(identity), s"package source is absent from debug-index: ${index.keys}")
    assert(result.getDebugCalls.exists(_.source == identity),
      s"package call source is absent from debug-calls: ${result.getDebugCalls.map(_.source)}")
    assert(sourceFiles(result).contains(identity),
      s"package source is absent from bytecode SourceFile attributes: ${sourceFiles(result)}")
  }

  private def compilePackage(): (String, CompilationResult, Path) = {
    val root = Files.createTempDirectory("flix package source identity ")
    val archive = root.resolve("dependency package.fpkg")
    writePackage(archive)
    val unnormalizedArchive = root.resolve(".").resolve(archive.getFileName)
    val pkg = InstalledPackage(unnormalizedArchive, "test:debug-package", sctx, Map.empty)
    val flix = new Flix(pkgs = List(pkg)).setOptions(Options.DefaultTest.copy(xdebug = true))
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx,
      text = "def main(): Unit \\ IO = println(DebugPackage.answer())\n")
    val result = flix.compile() match {
      case Result.Ok(compilationResult) => compilationResult
      case Result.Err(errors) => fail(s"the package program must compile, but got: $errors")
    }
    val identity = s"jar:${archive.toAbsolutePath.normalize().toUri}!/src/DebugPackage.flix"
    (identity, result, archive.toAbsolutePath.normalize())
  }

  private def writePackage(path: Path): Unit = {
    val source =
      """|mod DebugPackage {
         |    def helper(x: Int32): Int32 = x + 1
         |    pub def answer(): Int32 = helper(41)
         |}
         |""".stripMargin
    val out = new ZipOutputStream(Files.newOutputStream(path))
    try {
      out.putNextEntry(new ZipEntry("src/DebugPackage.flix"))
      out.write(source.getBytes(StandardCharsets.UTF_8))
      out.closeEntry()
    } finally out.close()
  }

  private def sourceFiles(result: CompilationResult): Set[String] = {
    val sources = Set.newBuilder[String]
    result.getClasses.valuesIterator.foreach { clazz =>
      new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
        override def visitSource(source: String, debug: String): Unit = {
          if (source != null) sources += source
        }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES)
    }
    sources.result()
  }
}
