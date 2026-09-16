package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

class TestJvmInlinerContexts extends AnyFunSuite {

  private def runTest(source: String, newMono: Boolean): Unit = {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xnewmono = newMono, threads = 1))
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, source)
    val (checked, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = checked.get
    val entryPoints = root.defs.values.filter(defn => defn.spec.mod.isPublic &&
      defn.loc.source.name == CompilerConstants.VirtualTestFile.toString).map(_.sym).toSet
    val compilation = flix.codeGen(root.copy(entryPoints = entryPoints))
    val tests = JvmLoader.load(compilation).tests
    assert(tests.size == 1)
    tests.values.foreach { test =>
      assert(!test.skip)
      test.run()
    }
  }

  for (newMono <- List(false, true)) {
    test(s"monomorphizer $newMono preserves caller parameters when forwarding reenters the same definition") {
      val source = """@Inline
                     |def forward(value: Int32): Int32 =
                     |  let shifted = value + 1;
                     |  shifted
                     |@DontInline
                     |pub def example(value: Int32): Int32 = forward(forward(value + 3)) + value
                     |@Test
                     |pub def reentersForward(): Unit =
                     |  if (example(37) == 79 and example(-4) == -3) ()
                     |  else bug!("Forwarding captured the wrong parameter")
                     |""".stripMargin
      runTest(source, newMono)
    }

    test(s"monomorphizer $newMono separates local definition captures from caller local arguments") {
      val source = """@DontInline
                     |def opaque(value: Int32): Int32 = value
                     |@DontInline
                     |pub def example(value: Int32): Int32 =
                     |  let captured = opaque(value);
                     |  def combine(argument) = captured * 10 + argument;
                     |  let callerLocal = opaque(value + 2);
                     |  combine(callerLocal) + callerLocal
                     |@Test
                     |pub def capturesOuterBinding(): Unit =
                     |  if (example(3) == 40 and example(7) == 88) ()
                     |  else bug!("Local definition confused capture and argument scopes")
                     |""".stripMargin
      runTest(source, newMono)
    }

    test(s"monomorphizer $newMono resolves suspended lambda aliases in their creation contexts") {
      val source = """@Inline
                     |def applyAlias(function: Int32 -> Int32, value: Int32): Int32 =
                     |  let functionAlias = function;
                     |  let forwarded = functionAlias;
                     |  forwarded(value)
                     |@DontInline
                     |pub def example(value: Int32): Int32 =
                     |  let function = argument -> argument + value;
                     |  let functionAlias = function;
                     |  applyAlias(functionAlias, applyAlias(argument -> argument * 2, value + 1))
                     |@Test
                     |pub def callsSuspendedAliases(): Unit =
                     |  if (example(5) == 17 and example(-3) == -7) ()
                     |  else bug!("Suspended lambda alias lost its captured binding")
                     |""".stripMargin
      runTest(source, newMono)
    }

    test(s"monomorphizer $newMono bounds expansion of recursive callee created suspensions") {
      val source = """@Inline
                     |def forward(value: Int32): Int32 = value
                     |@Inline
                     |def countdown(value: Int32): Int32 =
                     |  if (value <= 0) 0 else {
                     |    let suspended = countdown(value - 1);
                     |    forward(suspended) + 1
                     |  }
                     |@DontInline
                     |pub def example(value: Int32): Int32 = countdown(value)
                     |@Test
                     |pub def keepsRecursiveExpansionBounded(): Unit =
                     |  if (example(0) == 0 and example(40) == 40) ()
                     |  else bug!("Recursive suspended argument changed the result")
                     |""".stripMargin
      runTest(source, newMono)
    }
  }
}
