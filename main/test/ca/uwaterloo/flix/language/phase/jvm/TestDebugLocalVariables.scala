/*
 * Copyright 2026 Magnus Madsen, Werner Stein
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

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.objectweb.asm.{ClassReader, ClassVisitor, Label, MethodVisitor, Opcodes, Type}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class TestDebugLocalVariables extends AnyFunSuite {

  private val Program =
    """def compute(a: Int32, b: Int32): Int32 = {
      |    let x = a + 1;
      |    let y = b + 2;
      |    x + y
      |}
      |
      |def main(): Unit \ IO = println(compute(1, 2))
      |""".stripMargin

  test("debug build records source parameters in static methods") {
    val names = localNamesOfCompute(xdebug = true)
    assert(Set("a", "b").subsetOf(names), s"Expected source parameters, got: $names")
    assert(!names.contains("anf"), s"Compiler-generated binding leaked into debug metadata: $names")
  }

  test("release build does not gain local-variable metadata") {
    assert(localNamesOfCompute(xdebug = false).isEmpty)
  }

  test("debug build exposes source lets as initialized JVM locals") {
    val names = localNamesOfCompute(xdebug = true)
    assert(Set("x", "y").subsetOf(names), s"Expected source locals, got: $names")
  }

  for (newMono <- List(false, true)) {
    test(s"debug local ranges end at nested initializer scope (mono2=$newMono)") {
      val program = """def compute(seed: Int64): Int64 = {
        |    let outer = seed + 1i64;
        |    let nested = {
        |        let inner = seed + 2i64;
        |        inner + 1i64
        |    };
        |    outer + nested
        |}
        |def main(): Unit \ IO = println(compute(40i64))
        |""".stripMargin
      val locals = localEntries(compile(xdebug = true, program, newMono), "Def$compute", "staticApply")
      val inner = locals.find(_._1 == "inner").getOrElse(fail(s"Missing inner: $locals"))
      val nested = locals.find(_._1 == "nested").getOrElse(fail(s"Missing nested: $locals"))
      assert(inner._4 <= nested._3, s"Initializer binding leaked into the enclosing scope: $locals")
    }

    test(s"debug locals survive suspension with disjoint wide slots (mono2=$newMono)") {
      val program = """eff Pause { def pause(): Unit }
        |def compute(seed: Int64): Int64 \ Pause = {
        |    let before = seed + 1i64;
        |    Pause.pause();
        |    let after = before + 2i64;
        |    after + before
        |}
        |def main(): Unit \ IO = run {
        |    println(compute(40i64))
        |} with handler Pause { def pause(k) = k() }
        |""".stripMargin
      val result = compile(xdebug = true, program, newMono)
      val locals = localEntries(result, "Def$compute", "applyFrame")
      assert(Set("seed", "before", "after").subsetOf(locals.map(_._1).toSet), locals.toString)
      assert(!locals.exists(_._1 == "anf"))
      val before = locals.find(_._1 == "before").get
      val after = locals.find(_._1 == "after").get
      assert(before._3 < after._3, "A post-suspension local must not be visible before its initializer.")
      assert(before._2 == "J" && after._2 == "J")
      for (a <- locals; b <- locals if a != b && a._3 < b._4 && b._3 < a._4) {
        val occupied = a._5 until (a._5 + Type.getType(a._2).getSize)
        val other = b._5 until (b._5 + Type.getType(b._2).getSize)
        assert(occupied.intersect(other).isEmpty, s"Overlapping live slots: $a and $b")
      }
      ca.uwaterloo.flix.runtime.JvmLoader.load(result).main.get(Array.empty)
    }

    test(s"debug nested initializer scopes can suspend (mono2=$newMono)") {
      val program = """eff Pause { def pause(): Unit }
        |def compute(seed: Int64): Int64 \ Pause = {
        |    let outer = seed + 1i64;
        |    let nested = {
        |        let inner = seed + 2i64;
        |        Pause.pause();
        |        inner + 1i64
        |    };
        |    outer + nested
        |}
        |def main(): Unit \ Assert = run {
        |    Assert.assertEq(expected = 84i64, compute(40i64))
        |} with handler Pause { def pause(k) = k() }
        |""".stripMargin
      val result = compile(xdebug = true, program, newMono)
      val locals = localEntries(result, "Def$compute", "applyFrame")
      assert(locals.find(_._1 == "inner").get._4 <= locals.find(_._1 == "nested").get._3)
      ca.uwaterloo.flix.runtime.JvmLoader.load(result).main.get(Array.empty)
    }
  }

  test("JDI reads continuation locals after resuming an effect") {
    val program = """eff Pause { def pause(): Unit }
      |def compute(seed: Int64): Int64 \ Pause = {
      |    let before = seed + 1i64;
      |    Pause.pause();
      |    let after = before + 2i64;
      |    after + before
      |}
      |def main(): Unit \ IO = run {
      |    println(compute(40i64))
      |} with handler Pause { def pause(k) = k() }
      |""".stripMargin
    val result = compile(xdebug = true, program)
    val directory = java.nio.file.Files.createTempDirectory("flix-debug-locals-")
    for (clazz <- result.getClasses.values) {
      val path = directory.resolve(ca.uwaterloo.flix.language.jvm.ClassDescs.classFileNameOf(clazz.name))
      java.nio.file.Files.createDirectories(path.getParent)
      java.nio.file.Files.write(path, clazz.bytecode)
    }
    val connector = com.sun.jdi.Bootstrap.virtualMachineManager().defaultConnector()
    val args = connector.defaultArguments()
    args.get("main").setValue("Main")
    args.get("options").setValue(s"-cp \"$directory\"")
    args.get("suspend").setValue("true")
    val vm = connector.launch(args)
    try {
      val prepare = vm.eventRequestManager().createClassPrepareRequest()
      prepare.addClassFilter("Def$compute")
      prepare.enable()
      vm.resume()
      val observed = mutable.Set.empty[Int]
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
      while (!observed.contains(6) && System.nanoTime() < deadline) {
        val events = vm.eventQueue().remove(1000)
        if (events != null) {
          for (event <- events.asScala) event match {
            case prepared: com.sun.jdi.event.ClassPrepareEvent =>
              for (line <- List(3, 4, 6)) {
                val locations = prepared.referenceType().locationsOfLine(line).asScala
                  .filter(_.method().name() == "applyFrame")
                assert(locations.nonEmpty, s"Missing breakpoint at line $line")
                vm.eventRequestManager().createBreakpointRequest(locations.head).enable()
              }
            case breakpoint: com.sun.jdi.event.BreakpointEvent =>
              val frame = breakpoint.thread().frame(0)
              def value(name: String): Long = {
                val variable = frame.visibleVariableByName(name)
                assert(variable != null, s"$name is not visible at the suspended frame")
                frame.getValue(variable).asInstanceOf[com.sun.jdi.LongValue].value()
              }
              assert(value("seed") == 40L)
              val line = breakpoint.location().lineNumber()
              if (line == 3) {
                assert(frame.visibleVariableByName("before") == null)
                assert(frame.visibleVariableByName("after") == null)
              } else if (line == 4) {
                assert(value("before") == 41L)
                assert(frame.visibleVariableByName("after") == null)
              } else {
                assert(value("before") == 41L)
                assert(value("after") == 43L)
              }
              observed += line
            case _: com.sun.jdi.event.VMDeathEvent | _: com.sun.jdi.event.VMDisconnectEvent =>
              fail("Debuggee exited without reaching the breakpoint")
            case _ => ()
          }
          events.resume()
        }
      }
      assert(observed == Set(3, 4, 6), s"Missing continuation breakpoints: $observed")
    } finally {
      try vm.dispose() catch { case _: com.sun.jdi.VMDisconnectedException => () }
      vm.process().destroyForcibly()
    }
  }

  test("debug compilation finalizes source bindings under a stable class name") {
    val bindings = compile(xdebug = true).getDebugDefinitions.getOrElse("Def$compute", fail("Missing debug definition for compute."))
      .getOrElse(ClassMaker.StaticApplyMethodName, fail("Missing staticApply debug bindings."))
    assert(bindings.map(_.name).toSet == Set("a", "b", "x", "y"))
    assert(compile(xdebug = false).getDebugDefinitions.isEmpty)
  }

  test("debug build records closure captures in applyFrame") {
    val program = """def make(prefix: Int32): Int32 -> Unit \ IO = value -> println(prefix + value)
      |
      |def main(): Unit \ IO = make(41)(1)
      |""".stripMargin
    val debug = compile(xdebug = true, program)
    val names = localNames(debug, "Clo$", "applyFrame")
    assert(names.contains("prefix"), s"Expected captured source name, got: $names")
    val bindings = debug.getDebugDefinitions.collectFirst {
      case (clazz, methods) if clazz.contains("Clo$") => methods("applyFrame").map(_.name).toSet
    }.getOrElse(fail("Expected debug scopes for an emitted closure, got: " + debug.getDebugDefinitions.keys))
    assert(bindings == Set("prefix", "value"), s"Expected closure scope names, got: $bindings")
    assert(localNames(compile(xdebug = false, program), "Clo$", "applyFrame").isEmpty)
  }

  test("debug build records effectful definition parameters in applyFrame") {
    val program = """eff Log {
      |  def write(x: Int32): Unit
      |}
      |
      |def log(prefix: Int32): Unit \ Log = Log.write(prefix)
      |
      |def main(): Unit = run {
      |  log(41)
      |} with handler Log {
      |  def write(_, k) = k()
      |}
      |""".stripMargin
    assert(localNames(compile(xdebug = true, program), "Def$log", "applyFrame").contains("prefix"))
    assert(localNames(compile(xdebug = false, program), "Def$log", "applyFrame").isEmpty)
  }

  test("debug build records a let bound after a suspension point in the debug scope") {
    // The debug-scope snapshot is captured from the pre-lowering source and joined to the
    // emitted method by the declaring def's symbol, so it does not depend on whether the
    // optimizer or ANF lowering left the binding intact -- unlike a real JVM local, which
    // this does not attempt to be. See docs/idea-debugging.md, "Variables".
    val program = """eff Log {
      |  def write(x: Int32): Unit
      |}
      |
      |def log(prefix: Int32): Int32 \ Log = {
      |    Log.write(prefix);
      |    let doubled = prefix + prefix;
      |    doubled
      |}
      |
      |def main(): Unit \ IO = run {
      |  println(log(41))
      |} with handler Log {
      |  def write(_, k) = k()
      |}
      |""".stripMargin
    val debug = compile(xdebug = true, program)
    val bindings = debug.getDebugDefinitions.collectFirst {
      case (clazz, methods) if clazz.contains("Def$log") => methods("applyFrame").map(_.name).toSet
    }.getOrElse(fail("Expected debug scopes for Def$log, got: " + debug.getDebugDefinitions.keys))
    assert(bindings.contains("doubled"), s"Expected the post-suspension let to be recorded, got: $bindings")
  }

  private def localNamesOfCompute(xdebug: Boolean): Set[String] = {
    val result = compile(xdebug)
    val clazz = result.getClasses.values.find(_.name.displayName() == "Def$compute")
      .getOrElse(fail(s"Expected a generated compute class, got: ${result.getClasses.keys}"))
    localNames(result, "Def$compute", ClassMaker.StaticApplyMethodName)
  }

  private def localNames(result: ca.uwaterloo.flix.runtime.CompilationResult, classPrefix: String, method: String): Set[String] = {
    val names = mutable.Set.empty[String]
    result.getClasses.values.filter(_.name.displayName().contains(classPrefix)).foreach { clazz => new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != method) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLocalVariable(name: String, descriptor: String, signature: String, start: Label, end: Label, index: Int): Unit = names += name
        }
      }
    }, 0) }
    names.toSet
  }

  private def localEntries(result: ca.uwaterloo.flix.runtime.CompilationResult, className: String, method: String): List[(String, String, Int, Int, Int)] = {
    val clazz = result.getClasses.values.find(_.name.displayName() == className).getOrElse(fail(s"Missing $className"))
    val offsets = new java.util.IdentityHashMap[Label, Integer]()
    val entries = mutable.ListBuffer.empty[(String, String, Int, Int, Int)]
    val reader = new ClassReader(clazz.bytecode) {
      override protected def readLabel(offset: Int, labels: Array[Label]): Label = {
        val label = super.readLabel(offset, labels)
        offsets.put(label, offset)
        label
      }
    }
    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitMethod(access: Int, name: String, descriptor: String, signature: String, exceptions: Array[String]): MethodVisitor = {
        if (name != method) return null
        new MethodVisitor(Opcodes.ASM9) {
          override def visitLocalVariable(name: String, descriptor: String, signature: String, start: Label, end: Label, index: Int): Unit = {
            val lo = offsets.get(start).intValue()
            val hi = offsets.get(end).intValue()
            assert(lo < hi, s"Empty local range for $name")
            entries += ((name, descriptor, lo, hi, index))
          }
        }
      }
    }, 0)
    entries.toList
  }

  private def compile(xdebug: Boolean, program: String = Program, newMono: Boolean = Options.DefaultTest.xnewmono) = {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(entryPoint = Some(Symbol.mkDefnSym("main")), xdebug = xdebug, xnewmono = newMono))
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx, text = program)
    flix.compile() match {
      case Result.Ok(value) => value
      case Result.Err(errors) => fail(s"Expected a successful compilation, got: $errors")
    }
  }
}
