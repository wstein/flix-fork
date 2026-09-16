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

  private def emitted(source: String, newMono: Boolean, threads: Int, checkRuntime: Boolean = false, fullLibrary: Boolean = false): Emission = {
    implicit val security: SecurityContext = SecurityContext.Unrestricted
    val options = if (fullLibrary) Options.TestWithLibAll else Options.TestWithLibMin
    val flix = new Flix().setOptions(options.copy(xnewmono = newMono, threads = threads))
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
    test(s"monomorphizer $newMono distinguishes lazy flatMap closure copies") {
      val source = """use DelayList.{ENil, ECons, LCons, LList}
                     |@Test
                     |pub def flatMapPure(): Unit \ Assert =
                     |  Assert.assertEq(expected = ECons(1, ECons(2, ECons(2, ECons(3, ECons(3, ECons(3, ENil)))))), DelayList.flatMap(value -> DelayList.repeat(value) |> DelayList.take(value), ECons(1, LList(lazy LCons(2, lazy LList(lazy ECons(3, LList(lazy ENil))))))))
                     |""".stripMargin
      assert(emitted(source, newMono, 1, checkRuntime = true, fullLibrary = true) ==
        emitted(source, newMono, 4, checkRuntime = true, fullLibrary = true))
    }

    test(s"monomorphizer $newMono preserves vector pipeline names when adding a list specialization") {
      val source = """pub def repeatedStdlibDemo(): Int32 = {
                     |  let integers = List.map(value -> value + 1, 1 :: 2 :: 3 :: Nil) |> List.length;
                     |  let longs = List.map(value -> value + 1i64, 1i64 :: 2i64 :: Nil) |> List.length;
                     |  let strings = List.map(value -> "${value}!", "p" :: "q" :: Nil) |> List.length;
                     |  let booleans = List.map(value -> not value, true :: false :: Nil) |> List.length;
                     |  let doubles = List.map(value -> value * 2.0f64, 1.0f64 :: 2.0f64 :: Nil) |> List.length;
                     |  let filteredIntegers = List.filter(value -> value > 1, 1 :: 2 :: 3 :: Nil) |> List.length;
                     |  let filteredStrings = List.filter(value -> String.length(value) > 1, "y" :: "zz" :: Nil) |> List.length;
                     |  let vectorIntegers = Vector.map(value -> value + 1, Vector#{1, 2, 3}) |> Vector.length;
                     |  let vectorStrings = Vector.map(value -> "${value}", Vector#{'a', 'b'}) |> Vector.length;
                     |  integers + longs + strings + booleans + doubles + filteredIntegers + filteredStrings + vectorIntegers + vectorStrings
                     |}
                     |@Test
                     |pub def pipelineResult(): Unit =
                     |  if (repeatedStdlibDemo() == 19) () else bug!("Incorrect pipeline result")
                     |""".stripMargin
      val edited = source.replace("  integers + longs", "  let bytes = List.map(value -> value + 1i8, 1i8 :: 2i8 :: Nil) |> List.length;\n  integers + longs")
        .replace(" + vectorStrings\n", " + vectorStrings + bytes\n")
        .replace("== 19", "== 21")
      val before = emitted(source, newMono, 1, checkRuntime = true, fullLibrary = true)
      val after = emitted(edited, newMono, 4, checkRuntime = true, fullLibrary = true)
      assert(before.suffixes("repeatedStdlibDemo").size > 1)
      assert(before.suffixes("repeatedStdlibDemo").subsetOf(after.suffixes("repeatedStdlibDemo")))
      assert(before.descriptors.subsetOf(after.descriptors), (before.descriptors -- after.descriptors).toList.sorted.mkString(", "))
    }

    test(s"monomorphizer $newMono inlines suspended caller arguments through deep forwarding chains") {
      val nested = (0 until CompilerConstants.MaxOptimizerRounds + 3).foldLeft("payload(value)") {
        case (argument, _) => s"forward($argument)"
      }
      val source = s"""mod Forwarding {
                      |@Inline
                      |def forward(value: Int32): Int32 = value
                      |@Inline
                      |def payload(value: Int32): Int32 = value + 7
                      |@DontInline
                      |pub def example(value: Int32): Int32 = $nested
                      |@Test
                      |pub def forwardsCallerArgument(): Unit =
                      |  if (example(35) == 42) () else bug!("Incorrect forwarded argument")
                      |}
                      |""".stripMargin
      val first = emitted(source, newMono, 1, checkRuntime = true)
      assert(!first.suffixes.contains("payload"), "Caller argument remained blocked by forwarding expansions")
      assert(!first.suffixes.contains("forward"), "Forwarding chain consumed the optimizer round budget")
      assert(first == emitted(source, newMono, 4, checkRuntime = true))
    }

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

    test(s"monomorphizer $newMono preserves emitted struct layouts across unrelated edits and executes field reads and writes") {
      val source =
        """struct Record[a, r] {
          |  keys: a,
          |  values: a,
          |  lock: a,
          |  mut size: Int32,
          |  mut isLeaf: Bool,
          |  other: a
          |}
          |
          |mod Record {
          |  pub def makeRecord(rc: Region[r]): Record[String, r] \ r =
          |    new Record @ rc {
          |      keys = "k",
          |      values = "v",
          |      lock = "l",
          |      size = 42,
          |      isLeaf = true,
          |      other = "o"
          |    }
          |
          |  @Test
          |  pub def testStruct(): Unit = region rc {
          |    let r = makeRecord(rc);
          |    let ok1 = r->isLeaf and r->size == 42 and r->keys == "k" and r->values == "v";
          |    r->size = 99;
          |    r->isLeaf = false;
          |    let ok2 = not r->isLeaf and r->size == 99;
          |    if (ok1 and ok2) () else bug!("Incorrect struct field read or write")
          |  }
          |}
          |""".stripMargin
      val unrelated = "pub def unrelatedFn(x: Int32): Int32 = x + 100\n"
      val first = emitted(source, newMono, 1, checkRuntime = true)
      val parallel = emitted(source, newMono, 4, checkRuntime = true)
      val edited = emitted(unrelated + source, newMono, 4, checkRuntime = true)
      assert(first == parallel)
      assertPreserved(first, edited)
      val structDescriptors = first.descriptors.filter(_.startsWith("LStruct$"))
      assert(structDescriptors.nonEmpty)
      assert(first.descriptors.filter(_.startsWith("LStruct$")) == edited.descriptors.filter(_.startsWith("LStruct$")))
    }

    test(s"monomorphizer $newMono preserves emitted definition names when trailing expressions change") {
      val source =
        """@DontInline
          |def applyFn(f: Int32 -> Int32, x: Int32): Int32 = f(x)
          |
          |pub def demo(): Int32 = {
          |  let a = applyFn(x -> x + 1, 10);
          |  let b = applyFn(x -> x + 2, 20);
          |  a + b
          |}
          |""".stripMargin
      val edited =
        """@DontInline
          |def applyFn(f: Int32 -> Int32, x: Int32): Int32 = f(x)
          |
          |pub def demo(): Int32 = {
          |  let a = applyFn(x -> x + 1, 10);
          |  let b = applyFn(x -> x + 2, 20);
          |  a + b + 100
          |}
          |""".stripMargin
      val first = emitted(source, newMono, 1)
      val second = emitted(edited, newMono, 4)
      assertPreserved(first, second)
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
