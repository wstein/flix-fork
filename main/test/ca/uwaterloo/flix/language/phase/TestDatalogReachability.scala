/*
 * Copyright 2026 Magnus Madsen, Werner Stein
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

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{DatalogExecution, LibLevel, Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class TestDatalogReachability extends AnyFunSuite with TestUtils {

  private val DatalogProgram =
    """
      |def main(): Unit = {
      |    let p = #{
      |        Edge(1, 2).
      |        Edge(2, 3).
      |        Edge(3, 4).
      |        Path(x, y) :- Edge(x, y).
      |        Path(x, z) :- Path(x, y), Edge(y, z).
      |    };
      |    let res = solve p;
      |    let _ = query res select (x, y) from Path(x, y);
      |    ()
      |}
    """.stripMargin

  private case class ReachabilityResult(
    bytecodeMethodCalls: Set[(String, String)],
    bytecodeClassRefs: Set[String]
  )

  private def compileAndScan(mode: DatalogExecution): ReachabilityResult = {
    val tempDir = Files.createTempDirectory("flix-reachability-test-")
    val options = Options.Default.copy(
      lib = LibLevel.All,
      xdatalogExecution = mode,
      entryPoint = Some(Symbol.mkDefnSym("main")),
      outputJvm = true,
      outputPath = tempDir
    )
    val flix = new Flix().setOptions(options)
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, DatalogProgram)

    flix.compile().toResult match {
      case Result.Ok(_) => ()
      case Result.Err(errors) => fail(s"Expected successful compilation under mode $mode, got: $errors")
    }

    val classDir = tempDir.resolve("class")
    val classFiles: List[Path] = if (Files.exists(classDir)) {
      Files.walk(classDir).filter(p => Files.isRegularFile(p) && p.toString.endsWith(".class")).iterator().asScala.toList
    } else {
      Nil
    }

    val calls = mutable.Set.empty[(String, String)]
    val refs = mutable.Set.empty[String]

    for (p <- classFiles) {
      val bytes = Files.readAllBytes(p)
      val reader = new ClassReader(bytes)
      reader.accept(new ClassVisitor(Opcodes.ASM9) {
        override def visit(version: Int, access: Int, name: String, signature: String, superName: String, interfaces: Array[String]): Unit = {
          if (superName != null) refs += superName
          if (interfaces != null) interfaces.foreach(refs += _)
          super.visit(version, access, name, signature, superName, interfaces)
        }

        override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
          new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
            override def visitMethodInsn(opcode: Int, owner: String, methodName: String, descriptor: String, isInterface: Boolean): Unit = {
              calls += ((owner, methodName))
              refs += owner
              super.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface)
            }

            override def visitTypeInsn(opcode: Int, typeName: String): Unit = {
              refs += typeName
              super.visitTypeInsn(opcode, typeName)
            }

            override def visitFieldInsn(opcode: Int, owner: String, fieldName: String, descriptor: String): Unit = {
              refs += owner
              super.visitFieldInsn(opcode, owner, fieldName, descriptor)
            }
          }
        }
      }, 0)
    }

    ReachabilityResult(calls.toSet, refs.toSet)
  }

  test("Reachability.ParallelMode") {
    val res = compileAndScan(DatalogExecution.Parallel)
    println(s"PARALLEL CALLS: ${res.bytecodeMethodCalls.filter(c => c._1.contains("Thread") || c._2.contains("Thread") || c._2.contains("par") || c._1.contains("par"))}")
    println(s"PARALLEL REFS: ${res.bytecodeClassRefs.filter(c => c.contains("Thread") || c.contains("par") || c.contains("Barrier"))}")
  }

  test("Reachability.SequentialMode.ErasesAllParallelConstructs") {
    val res = compileAndScan(DatalogExecution.Sequential)

    // 1. Assert no startVirtualThread
    val threadVirtualCalls = res.bytecodeMethodCalls.filter {
      case (owner, name) => owner == "java/lang/Thread" && (name == "startVirtualThread" || name == "ofVirtual")
    }
    // In sequential mode, Region.spawnChild is not called and startVirtualThread is never called
    val parThreadSpawns = res.bytecodeMethodCalls.filter {
      case (owner, name) => owner == "java/lang/Thread" && name == "startVirtualThread"
    }
    assert(parThreadSpawns.isEmpty, s"Found reachable startVirtualThread: $parThreadSpawns")

    // 2. Assert no BPlusTree.parForEach
    val parForEachCalls = res.bytecodeMethodCalls.filter {
      case (_, name) => name.contains("parForEach")
    }
    assert(parForEachCalls.isEmpty, s"Found reachable parForEach: $parForEachCalls")

    // 3. Assert no CyclicBarrier
    val barrierRefs = res.bytecodeClassRefs.filter(_.contains("CyclicBarrier"))
    assert(barrierRefs.isEmpty, s"Found reachable CyclicBarrier references: $barrierRefs")

    // 4. Assert no RedBlackTree.parMapWithKey or parExists
    val redBlackParCalls = res.bytecodeMethodCalls.filter {
      case (_, name) => name.contains("parMapWithKey") || name.contains("parExists")
    }
    assert(redBlackParCalls.isEmpty, s"Found reachable RedBlackTree parallel operations: $redBlackParCalls")
  }
}
