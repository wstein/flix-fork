package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix, FlixEvent, FlixListener}
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{InternalCompilerException, Options}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.concurrent.TrieMap

class TestJvmProvenancePipeline extends AnyFunSuite {
  private def emitted(source: String, newMono: Boolean, threads: Int, checkRuntime: Boolean = false): Map[String, Set[String]] = {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xnewmono = newMono, threads = threads))
    val entries = TrieMap.empty[Symbol.DefnSym, GeneratedJvmKey]
    flix.addListener(new FlixListener {
      override def notify(event: FlixEvent): Unit = event match {
        case FlixEvent.EmittedClass(sym, _) => entries.put(sym, flix.jvmOrigins.symbols.origin(sym)); ()
        case _ => ()
      }
    })
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, source)
    val (checked, errors) = flix.check()
    assert(errors.isEmpty, errors.mkString("\n"))
    val root = checked.get
    val entriesToKeep = root.defs.values.filter(defn => defn.spec.mod.isPublic &&
      defn.loc.source.name == CompilerConstants.VirtualTestFile.toString).map(_.sym).toSet
    val compilation = flix.codeGen(root.copy(entryPoints = entriesToKeep))
    assert(entries.nonEmpty)
    intercept[InternalCompilerException] { flix.jvmOrigins }
    val table = JvmNameTable.build(entries)
    if (checkRuntime) {
      val tests = JvmLoader.load(compilation).tests
      assert(tests.nonEmpty)
      tests.values.foreach { test =>
        assert(!test.skip)
        test.run()
      }
    }
    entries.keys.groupBy(_.text).map { case (name, syms) => name -> syms.map(table.suffix).toSet }
  }

  private val program = """@DontInline
                          |def provenanceIdentity(value: a): a = value
                          |pub def example(value: Int32): Int32 -> Int32 = argument -> provenanceIdentity(if (true) value else argument)
                          |pub def other(value: Bool): Bool = provenanceIdentity(value)
                          |""".stripMargin

  for (newMono <- List(false, true)) {
    test(s"monomorphizer $newMono preserves emitted-symbol provenance across parallel builds and unrelated edits") {
      val first = emitted(program, newMono, 1)
      val second = emitted("pub def unrelated(): Int32 = 23\n" + program, newMono, 4)
      first.foreach { case (name, keys) => assert(second(name) == keys, name) }
      assert(first.keys.exists(_.contains("provenanceIdentity")))
    }

    test(s"monomorphizer $newMono distinguishes inlined clones of identical lambda sites") {
      val source = """@Inline
                     |def factory(value: Int32): Int32 -> Int32 = argument -> if (true) value else argument
                     |pub def example(): (Int32 -> Int32, Int32 -> Int32) = (factory(1), factory(1))
                     |""".stripMargin
      assert(emitted(source, newMono, 1) == emitted(source, newMono, 4))
    }

    test(s"monomorphizer $newMono shares tuple-switch fallback lambdas and preserves tail calls") {
      val source = """enum Choice { case Left, case Right, Other }
                     |@DontInline
                     |pub def chooseValue(choice: Choice, number: Int32): Int32 -> Int32 = match (choice, number) {
                     |  case (Choice.Left, 0) => argument -> argument + 1
                     |  case (Choice.Right, 0) => argument -> argument + 2
                     |  case _ => argument -> argument + number
                     |}
                     |@DontInline
                     |pub def countDown(choice: Choice, count: Int32): Int32 = match (choice, count) {
                     |  case (Choice.Left, remaining) => if (remaining <= 0) remaining else countDown(Choice.Right, remaining - 1)
                     |  case (Choice.Right, remaining) => if (remaining <= 0) remaining else countDown(Choice.Left, remaining - 1)
                     |  case _ => count
                     |}
                     |@Test
                     |pub def fallbackCapturesParameter(): Unit =
                     |  if (chooseValue(Choice.Left, 7)(10) == 17 and
                     |  chooseValue(Choice.Right, 9)(10) == 19 and
                     |  chooseValue(Choice.Other, 11)(10) == 21 and
                     |  chooseValue(Choice.Left, 0)(10) == 11 and
                     |  chooseValue(Choice.Right, 0)(10) == 12 and
                     |  countDown(Choice.Left, 100000) == 0) () else bug!("Incorrect tuple fallback result")
                     |""".stripMargin
      assert(emitted(source, newMono, 1, checkRuntime = true) == emitted(source, newMono, 4, checkRuntime = true))
    }

    test(s"monomorphizer $newMono distinguishes lambda-drop wrapper from nested local definition") {
      val source = """@DontInline
                     |pub def recurse(function: Int32 -> Int32, number: Int32): Int32 =
                     |  def inner(value) = if (value <= 0) function(number) else inner(value - 1);
                     |  if (number <= 0) inner(2) else recurse(function, number - 1) + inner(number)
                     |""".stripMargin
      assert(emitted(source, newMono, 1) == emitted(source, newMono, 4))
    }

    test(s"monomorphizer $newMono preserves provenance through patterns and anonymous classes") {
      val source = """import java.lang.Runnable
                     |enum Box[a] { case Empty, case Box(a) }
                     |@DontInline
                     |pub def selectValue(value: Box[Int32]): Int32 -> Int32 = match value {
                     |  case Box.Empty => argument -> argument
                     |  case Box.Box(inner) => argument -> if (true) inner else argument
                     |}
                     |pub def example(): Runnable \ IO = new Runnable { def $run(_this: Runnable): Unit = () }
                     |""".stripMargin
      assert(emitted(source, newMono, 1) == emitted(source, newMono, 4))
    }
  }
}
