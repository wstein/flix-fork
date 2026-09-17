package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

class TestJvmLocalClosureOrigins extends AnyFunSuite {
  for (newMono <- List(false, true)) {
    test(s"monomorphizer $newMono preserves provenance when rewriting captured recursive local calls") {
      implicit val security: SecurityContext = SecurityContext.Unrestricted
      val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xnewmono = newMono, threads = 1))
      val source = """@DontInline
                     |pub def example(captured: Int32, limit: Int32): Int32 -> Int32 =
                     |    def loop(remaining) =
                     |        if (remaining <= 0) captured else loop(remaining - 1);
                     |    increment -> loop(limit) + increment
                     |""".stripMargin
      flix.addSource(CompilerConstants.VirtualTestFile, sctx = security, text = source)
      val (checked, errors) = flix.check()
      assert(errors.isEmpty, errors.mkString("\n"))
      val root = checked.get
      val example = root.defs.values.find(_.sym.name == "example").get
      val result = flix.codeGen(root.copy(entryPoints = Set(example.sym)))
      assert(result.getClasses.nonEmpty)
    }
  }
}
