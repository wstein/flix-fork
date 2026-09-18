package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Options, Result}
import org.json4s.native.JsonMethods
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

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

  test("call names omit monomorphization identities") {
    val flix = new Flix().setOptions(Options.DefaultTest.copy(xdebug = true))
    flix.addSource(CompilerConstants.VirtualTestFile, sctx = sctx,
      text = "def main(): Unit \\ IO = println(List.map(x -> x + 1, 1 :: Nil))\n")
    val calls = flix.compile() match {
      case Result.Ok(result) => result.getDebugCalls
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
    val map = calls.find(_.className.contains("List.Def$map$"))
      .getOrElse(fail(s"the specialized List.map call was not recorded: $calls"))

    assert(map.label == "List.map")
  }

  test("the sidecar groups calls by source and separates ranges from JVM targets") {
    val path = Files.createTempFile("debug-calls", ".json")
    DebugCalls.write(path, List(
      DebugCalls.Call("/work/src/Main.flix", 9, 12, 9, 20, "two", "Def$two", "staticApply"),
      DebugCalls.Call("/work/src/Other.flix", 3, 5, 4, 8, "Other.run", "Other.Def$run", "applyFrame"),
      DebugCalls.Call("/work/src/Main.flix", 7, 4, 7, 10, "one", "Def$one", "staticApply")
    ))

    val json = Files.readString(path)
    JsonMethods.parse(json)
    assert(json.contains("\"formatVersion\":2"))
    assert(json.contains("\"sources\":{\n    \"/work/src/Main.flix\":["))
    assert(json.contains("\"range\":[7,4,7,10]"))
    assert(json.contains("\"name\":\"one\""))
    assert(json.contains("\"target\":{\"className\":\"Def$one\"}"))
    assert(json.contains("\"target\":{\"className\":\"Other.Def$run\",\"methodName\":\"applyFrame\"}"))
    assert(json.indexOf("\"name\":\"one\"") < json.indexOf("\"name\":\"two\""))
    assert(json.sliding("/work/src/Main.flix".length).count(_ == "/work/src/Main.flix") == 1)
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
