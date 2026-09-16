package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix, FlixEvent, FlixListener}
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{InternalCompilerException, Options}
import org.objectweb.asm.ClassReader
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.concurrent.TrieMap

class TestJvmProvenancePipeline extends AnyFunSuite {
  private case class Emission(suffixes: Map[String, Set[String]], descriptors: Set[String])

  private def emitted(source: String, newMono: Boolean, threads: Int, checkRuntime: Boolean = false): Emission = {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.TestWithLibMin.copy(xnewmono = newMono, threads = threads))
    val entries = TrieMap.empty[Symbol.DefnSym, String]
    flix.addListener(new FlixListener {
      override def notify(event: FlixEvent): Unit = event match {
        case FlixEvent.EmittedClass(sym, _) => entries.put(sym, flix.jvmOrigins.nameTable.suffix(sym)); ()
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
    val descriptors = compilation.getClasses.iterator.map { case (descriptor, clazz) =>
      val internalName = new ClassReader(clazz.bytecode).getClassName
      val actualDescriptor = s"L$internalName;"
      assert(actualDescriptor == descriptor.descriptorString(), s"Classfile name disagrees with map key: $internalName")
      assert(clazz.name == descriptor)
      actualDescriptor
    }.toSet
    entries.foreach { case (sym, suffix) =>
      if (sym.id.nonEmpty) {
        assert(descriptors.exists(_.contains(suffix)), s"No emitted class contains the frozen suffix for $sym: $suffix")
      }
    }
    if (checkRuntime) {
      val tests = JvmLoader.load(compilation).tests
      assert(tests.nonEmpty)
      tests.values.foreach { test =>
        assert(!test.skip)
        test.run()
      }
    }
    val suffixes = entries.keys.groupBy(_.text).map { case (name, syms) => name -> syms.map(entries.apply).toSet }
    Emission(suffixes, descriptors)
  }

  private def assertPreserved(before: Emission, after: Emission): Unit = {
    before.suffixes.foreach { case (name, suffixes) => assert(after.suffixes(name) == suffixes, name) }
    assert(before.descriptors.subsetOf(after.descriptors),
      s"Emitted class descriptors changed: ${(before.descriptors -- after.descriptors).toList.sorted.mkString(", ")}")
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
      assertPreserved(first, second)
      assert(first.suffixes.keys.exists(_.contains("provenanceIdentity")))
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

    test(s"monomorphizer $newMono preserves emitted nullary and anonymous classes across edits and parallel builds") {
      val imports = "import java.util.function.IntSupplier\nimport java.util.ArrayList\n"
      val source = """enum Box[a] { case Empty, case Box(a) }
                     |@DontInline
                     |pub def selectValue(value: Box[Int32]): Int32 -> Int32 = match value {
                     |  case Box.Empty => argument -> argument
                     |  case Box.Box(inner) => argument -> if (true) inner else argument
                     |}
                     |@DontInline
                     |pub def boolValue(value: Box[Bool]): Bool = match value {
                     |  case Box.Empty => true
                     |  case Box.Box(inner) => inner
                     |}
                     |@DontInline
                     |pub def example(value: Int32): IntSupplier \ IO = new IntSupplier {
                     |  def $getAsInt(_this: IntSupplier): Int32 = value
                     |}
                     |@DontInline
                     |pub def superList(): ArrayList[String] \ IO = new ArrayList[String] {
                     |  def new(): ArrayList[String] \ IO = super()
                     |  def size(_this: ArrayList[String]): Int32 \ IO = super.size()
                     |}
                     |@Test
                     |pub def nullaryAndAnonymousReferences(): Unit \ IO = {
                     |  let supplier = example(37);
                     |  let actual = supplier.getAsInt();
                     |  let list = superList();
                     |  let _ = list.add("entry");
                     |  let size = list.size();
                     |  if (actual == 37 and size == 1 and selectValue(Box.Empty)(7) == 7 and
                     |      selectValue(Box.Box(12))(7) == 12 and boolValue(Box.Empty) and
                     |      not boolValue(Box.Box(false))) () else bug!("Incorrect nullary or anonymous class reference")
                     |}
                     |""".stripMargin
      val unrelated = """enum Unrelated { case First, case Second }
                        |pub def unrelatedCase(): Unrelated = Unrelated.First
                        |pub def unrelatedObject(): Runnable \ IO = new Runnable { def $run(_this: Runnable): Unit = () }
                        |""".stripMargin
      val first = emitted(imports + source, newMono, 1, checkRuntime = true)
      val parallel = emitted(imports + source, newMono, 4, checkRuntime = true)
      val edited = emitted(imports + "import java.lang.Runnable\n" + unrelated + source, newMono, 4, checkRuntime = true)
      assert(first == parallel)
      assertPreserved(first, edited)
      val nullaryDescriptors = first.descriptors.filter(desc => desc.startsWith("LCase$Box") && desc.contains("$Empty"))
      assert(nullaryDescriptors.size >= 2, nullaryDescriptors.toString)
      assert(first.descriptors.exists(_.startsWith("LAnon$")))
      assert(edited.descriptors.count(_.startsWith("LAnon$")) > first.descriptors.count(_.startsWith("LAnon$")))
    }
  }
}
