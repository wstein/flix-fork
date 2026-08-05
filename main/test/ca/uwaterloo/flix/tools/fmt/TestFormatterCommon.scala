/*
 * Copyright 2026 Din Jakupi
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
package ca.uwaterloo.flix.tools.fmt

import ca.uwaterloo.flix.api.{Flix, Library}
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.ast.{SyntaxTree, WeededAst}
import ca.uwaterloo.flix.util.Formatter.NoFormatter
import ca.uwaterloo.flix.util.{FileOps, LibLevel, Options}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}

/**
  * Shared fixtures and helpers for the Flix code formatter test suites.
  *
  * The formatter is tested against several properties.
  * Letting
  *   p : S -> C  (parser)
  *   f : C -> S  (formatter)
  *   w : C -> A  (weeder)
  * where `S` is the set of Flix programs accepted by the parser, `C` the set
  * of concrete syntax trees [[SyntaxTree]], and `A` the set of abstract syntax
  * trees [[WeededAst]], the properties are:
  *
  *   1. Can format:          forall s in S,  f(p(s)) is defined
  *   2. Idempotency:         forall c in C,  f(p(f(c))) = f(c)
  *   3. Non-destructiveness: forall c in C,  w(c) = w(p(f(c)))
  *   4. Stability:           forall l in stdlib ++ examples.  f(p(l)) = l
  *
  * Properties 1-3 are *correctness* properties tested by [[TestFormatterCorrectness]].
  * Property 4 is an *aesthetic* property tested by [[TestFormatterStability]].
  *
  * This trait provides [[ExampleSamples]], [[StdlibSamples]] and the
  * parsing ([[reparseAt]]) shared by both tests.
  */
object TestFormatterCommon {

  /**
    * The result of reparsing a source both the [[SyntaxTree.Tree]] and the
    * [[WeededAst.CompilationUnit]] for it.
    *
    * A single reparse yields both, so `Sample.reparse` can be one function
    * regardless of whether a test needs the syntax tree or the weeded unit.
    */
  case class Parsed(tree: SyntaxTree.Tree, weeded: WeededAst.CompilationUnit)

  /**
    * A sample program for testing
    *
    * @param path    the path to the sample file, used for error messages and as the virtual path in the Flix instance
    * @param content the original source code of the sample, used as the input for the first parse and for stability checks
    * @param reparse a function that takes a source string and returns the parsed SyntaxTree and WeededAst
    *                after substituting the source for the samples path in the Flix instance and running `check`.
    *                The function is responsible for restoring the Flix instance to its original state after parsing.
    */
  case class Sample(
    path: String,
    content: String,
    reparse: String => Parsed
  ) {

    /**
      * The parse of this sample's own content.
      *
      * Every property starts from the unmodified sample, and reparsing runs a full
      * compile, so without memoising this the corpus is compiled once per property
      * per suite rather than once. Only the parses of *formatted* output differ
      * between properties and those are not cached.
      */
    lazy val original: Parsed = reparse(content)
  }

  /**
    * A reviewed input/expected pair for canonical formatting.
    *
    * The corpus cannot serve as the canonical gate: it lays the same construct
    * out both ways, so no formatter that imposes one layout per syntax tree can
    * fix-point it. These fixtures are the material that really is canonical —
    * `expected` was produced by the formatter and then read by a human, which is
    * the only step that makes it evidence of anything.
    *
    * Both trees are memoised because each parse runs a full compile.
    */
  case class Fixture(name: String, input: String, expected: String) {

    lazy val inputTree: SyntaxTree.Tree =
      parseFixture(s"$CanonicalFixtureDir/input/$name.flix", input)

    lazy val expectedTree: SyntaxTree.Tree =
      parseFixture(s"$CanonicalFixtureDir/expected/$name.flix", expected)
  }

  /** Where the canonical fixtures live, relative to the repository root. */
  val CanonicalFixtureDir: String = "main/test/resources/fmt/canonical"

  /**
    * Every canonical fixture, by the base name shared by its input and expected file.
    *
    * A fixture whose `expected` file is missing is reported as an empty string
    * rather than skipped, so that adding an input and forgetting to run
    * `./mill flix.updateCanonicalFixtures` fails the suite instead of passing it.
    */
  val CanonicalFixtures: List[Fixture] = {
    val inputDir = Paths.get(CanonicalFixtureDir, "input")
    val expectedDir = Paths.get(CanonicalFixtureDir, "expected")
    FileOps.getFlixFilesIn(inputDir, depth = 1)
      .map(_.getFileName.toString.stripSuffix(".flix"))
      .sorted
      .map { name =>
        val expectedPath = expectedDir.resolve(s"$name.flix")
        val expected =
          if (Files.exists(expectedPath)) Files.readString(expectedPath) else ""
        Fixture(name, Files.readString(inputDir.resolve(s"$name.flix")), expected)
      }
  }

  /**
    * Parses a fixture, requiring only that it *parse* — not that it type check.
    *
    * Formatting never required a program to compile, so demanding it here would
    * exclude exactly the constructs a fixture is most useful for. The suite
    * checks separately that no fixture contains an [[SyntaxTree.TreeKind.ErrorTree]]:
    * a fixture that fails to parse would be quarantined and reproduced verbatim,
    * which fix-points trivially and would assert nothing at all.
    */
  def parseFixture(path: String, src: String): SyntaxTree.Tree =
    parseTolerantly(exampleFlix, path, src)
      .getOrElse(throw new AssertionError(s"No syntax tree found for fixture $path"))

  /** All stdlib files */
  private val StdlibFiles: List[(String, String)] =
    Library.CoreLibrary ++ Library.StandardLibrary

  /** Flix instance used to compile example programs. The standard library is loaded by default. */
  val exampleFlix: Flix = {
    val flix = new Flix().setOptions(Options.Default)
    flix.check()
    flix
  }

  /** Flix instance used to compile stdlib files. */
  private val stdlibFlix: Flix = {
    val flix = new Flix().setOptions(Options.Default.copy(lib = LibLevel.Nix))
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    for ((name, content) <- StdlibFiles) {
      flix.addVirtualPath(Paths.get(name), content)
    }
    flix.check()
    flix
  }

  /**
    * Every standalone `.flix` file under `examples/`, used as a corpus for all properties.
    *
    * Each sample is compiled on its own, so files belonging to a multi-file project
    * are excluded: they refer to declarations in sibling files and do not compile
    * alone. `datalog/train-schedule.flix` is excluded separately.
    */
  val ExampleSamples: List[Sample] =
    findFlixFiles(
      Paths.get("examples"),
      exclude = Set("datalog/train-schedule.flix")
    ).map { p =>
      val content = Files.readString(Paths.get(p))
      Sample(p, content, src => reparseAt(exampleFlix, p, src, restoreTo = None))
    }

  /**
    * Every `.flix` file in the standard library, used as a corpus for all properties.
    */
  val StdlibSamples: List[Sample] =
    StdlibFiles.map { case (p, content) =>
      Sample(p, content, src => reparseAt(stdlibFlix, p, src, restoreTo = Some(content)))
    }

  /**
    * Find all standalone `.flix` files under `root`, excluding those in the
    * `exclude` set and those belonging to a multi-file project.
    *
    * A directory holding a `flix.toml` is a project: its sources are compiled
    * together and generally do not compile individually. Detecting them by their
    * manifest rather than listing their names keeps this correct as examples are
    * added — a new project would otherwise fail the suite on the day it lands,
    * with an error about an orphaned module rather than about the corpus.
    *
    * Returns full paths (including `root`) as forward-slash strings, sorted
    * platform-independently. The `exclude` entries are matched as substrings.
    */
  private def findFlixFiles(root: Path, exclude: Set[String]): List[String] = {
    val projectDirs = FileOps.getFilesIn(root, depth = Int.MaxValue)
      .filter(_.getFileName.toString == "flix.toml")
      .map(_.getParent.normalize().toString)

    val files = FileOps.getFlixFilesIn(root, depth = Int.MaxValue)
    FileOps.sortPlatformIndependently(root, files)
      .map { case (path, _) => path.normalize().toString() }
      .filterNot(str => exclude.exists(str.contains))
      .filterNot(str => projectDirs.exists(str.startsWith))
  }

  /**
    * Substitutes `src` for `path` in `flix`, runs `check`, and returns the parsed syntax
    * tree for `path`. The instance is restored afterward via the
    * `finally` block:
    *   - `Some(orig)` re-binds the path to its original content (stdlib case).
    *   - `None` removes the path entirely (example case).
    *
    * Fails the test if `src` does not compile cleanly.
    */
  def reparseAt(
    flix: Flix,
    path: String,
    src: String,
    restoreTo: Option[String]
  ): Parsed = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val vpath = Paths.get(path)
    flix.addVirtualPath(vpath, src)
    try {
      val (optRoot, errors) = flix.check()
      if (errors.nonEmpty) {
        val msg = CompilationMessage.formatAll(errors)(NoFormatter, optRoot)
        throw new AssertionError(s"Failed to compile $path:\n$msg")
      }
      val tree = findTreeAt(flix.getParsedAst, path)
        .getOrElse(throw new AssertionError(s"No syntax tree found for $path"))
      val weeded = findWeededUnit(flix.getWeededAst, path)
        .getOrElse(throw new AssertionError(s"No weeded unit found for $path"))
      Parsed(tree, weeded)
    } finally {
      restoreTo match {
        case Some(orig) =>
          flix.addVirtualPath(vpath, orig)
          flix.check()
        case None =>
          flix.remVirtualPath(vpath)
      }
    }
  }

  /**
    * Substitutes `src` for `path` in `flix` and returns its syntax tree, whether or
    * not the program compiles.
    *
    * [[reparseAt]] fails the test when a sample does not compile, which is right
    * for the corpus properties and useless for partial formatting, where the input
    * failing to parse is the case under test. The parser produces a tree
    * containing `ErrorTree` nodes for a malformed program, and that tree is what
    * the formatter is expected to cope with.
    */
  def parseTolerantly(flix: Flix, path: String, src: String): Option[SyntaxTree.Tree] = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val vpath = Paths.get(path)
    flix.addVirtualPath(vpath, src)
    try {
      flix.check() // errors are expected here and are deliberately ignored
      findTreeAt(flix.getParsedAst, path)
    } finally {
      flix.remVirtualPath(vpath)
      ()
    }
  }

  /**
    * Finds the syntaxTree for the given URI in the root if it exists.
    *
    * @param root the syntax tree root to search
    * @param uri  the file URI to find the syntax tree for
    * @return an option containing the syntax tree if found, or None if not found
    */
  private def findTreeAt(root: SyntaxTree.Root, uri: String): Option[SyntaxTree.Tree] = {
    val normalized = Paths.get(uri).normalize().toString
    root.units.collectFirst { case (p, t) if p.toString == normalized => t }
  }

  /**
    * Finds the weeded compilation unit for the given URI in the root if it exists.
    *
    * @param root the weeded AST root to search
    * @param uri  the file URI to find the weeded compilation unit for
    * @return an option containing the weeded compilation unit if found, or None if not found
    */
  private def findWeededUnit(root: WeededAst.Root, uri: String): Option[WeededAst.CompilationUnit] = {
    val normalized = Paths.get(uri).normalize().toString
    root.units.collectFirst { case (s, u) if s.toString == normalized => u }
  }

  /**
    * Finds the first line where two strings diverge and reports surrounding context.
    * This helps debugging idempotency and stability failures by showing the first point the outputs differ.
    */
  def firstDivergence(a: String, b: String): String = {
    val linesA = a.linesIterator.toArray
    val linesB = b.linesIterator.toArray
    val minLen = math.min(linesA.length, linesB.length)

    var i = 0
    while (i < minLen && linesA(i) == linesB(i)) i += 1

    if (i < minLen) {
      val contextFirstPass = (math.max(0, i - 2) until math.min(minLen, i + 3)).map { j =>
        val marker = if (j == i) ">>>" else "   "
        f"$marker L${j + 1}%4d| ${linesA(j)}"
      }.mkString("\n")
      val contextSecondPass = (math.max(0, i - 2) until math.min(linesB.length, i + 3)).map { j =>
        val marker = if (j == i) ">>>" else "   "
        f"$marker L${j + 1}%4d| ${linesB(j)}"
      }.mkString("\n")
      s"""First divergence at line ${i + 1}:
         |--- first pass ---
         |$contextFirstPass
         |--- second pass ---
         |$contextSecondPass""".stripMargin
    } else if (linesA.length != linesB.length) {
      s"Same content up to line $minLen, but different lengths: ${linesA.length} vs ${linesB.length} lines"
    } else {
      "No divergence found (strings are equal)"
    }
  }
}

/**
  * Gives a formatter suite access to the shared fixtures in [[TestFormatterCommon]].
  *
  * The fixtures are held by the companion object rather than by this trait so that
  * they are built once for the whole run. Each one compiles the standard library,
  * so rebuilding them per suite cost more than every property they support.
  */
trait TestFormatterCommon extends AnyFunSuite {

  protected type Parsed = TestFormatterCommon.Parsed

  protected type Sample = TestFormatterCommon.Sample

  /** Flix instance used to compile example programs. */
  protected val exampleFlix: Flix = TestFormatterCommon.exampleFlix

  /** Every standalone `.flix` file under `examples/`. */
  protected val ExampleSamples: List[Sample] = TestFormatterCommon.ExampleSamples

  /** Every `.flix` file in the standard library. */
  protected val StdlibSamples: List[Sample] = TestFormatterCommon.StdlibSamples

  protected type Fixture = TestFormatterCommon.Fixture

  /** Every reviewed input/expected pair for canonical formatting. */
  protected val CanonicalFixtures: List[Fixture] = TestFormatterCommon.CanonicalFixtures

  /** Where the canonical fixtures live, relative to the repository root. */
  protected val CanonicalFixtureDir: String = TestFormatterCommon.CanonicalFixtureDir

  /** Substitutes `src` for `path` in `flix`, runs `check`, and returns the parse. */
  protected def reparseAt(
    flix: Flix,
    path: String,
    src: String,
    restoreTo: Option[String]
  ): Parsed = TestFormatterCommon.reparseAt(flix, path, src, restoreTo)

  /** Reports the first line at which two strings diverge, with context. */
  protected def firstDivergence(a: String, b: String): String =
    TestFormatterCommon.firstDivergence(a, b)

  /** Parses `src` at `path`, returning its tree whether or not the program compiles. */
  protected def parseTolerantly(flix: Flix, path: String, src: String): Option[SyntaxTree.Tree] =
    TestFormatterCommon.parseTolerantly(flix, path, src)
}
