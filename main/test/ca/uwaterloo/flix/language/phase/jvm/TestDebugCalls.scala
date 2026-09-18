package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

class TestDebugCalls extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  test("a debug compilation records direct calls against stable generated definitions") {
    val calls = compile(xdebug = true)
    val one = calls.find(_.label == "one").getOrElse(fail(s"one() was not recorded: $calls"))

    assert(one.source.contains(CompilerConstants.VirtualTestFile.toString))
    assert(one.className.endsWith("Def$one"))
    assert(one.methodName == "staticApply")
    assert(one.startLine == one.endLine)
  }

  test("a normal compilation retains no debugger call provenance") {
    assert(compile(xdebug = false).isEmpty)
  }

  private def compile(xdebug: Boolean): List[DebugCalls.Call] = {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = xdebug))
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx,
      text = "def one(): Int32 = 1\ndef main(): Unit \\ IO = println(one())\n")
    flix.compile() match {
      case Result.Ok(result) => result.getDebugCalls
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }
}
