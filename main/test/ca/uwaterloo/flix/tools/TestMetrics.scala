package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.TypedAst.Root
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.{Formatter, Options}
import org.json4s.{JInt, JString, jvalue2monadic}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests that the measurements are the right numbers.
  *
  * The program below is written a line at a time and counted by hand, because a metric that is
  * merely plausible is worse than none: nobody checks a number that looks about right, and a
  * beginner has no way to tell that it is not.
  */
class TestMetrics extends AnyFunSuite {

  /**
    * The program under measurement, one line per element.
    *
    * Counted by hand: 18 lines, of which 1 is a comment, 3 are blank and 14 are code.
    * It declares four definitions -- `double`, `classify`, `helper` and `testDouble` -- of which
    * two are public, one is a test, and one of the two public ones is documented.
    */
  private val Lines: List[String] = List(
    /*  1 */ "mod Demo {",
    /*  2 */ "    /// Doubles its argument.",
    /*  3 */ "    pub def double(x: Int32): Int32 = x * 2",
    /*  4 */ "",
    /*  5 */ "    pub def classify(x: Int32, y: Int32): Int32 =",
    /*  6 */ "        if (x > y)",
    /*  7 */ "            match x {",
    /*  8 */ "                case 0 => 0",
    /*  9 */ "                case _ => 1",
    /* 10 */ "            }",
    /* 11 */ "        else",
    /* 12 */ "            0",
    /* 13 */ "",
    /* 14 */ "    def helper(): Int32 = 42",
    /* 15 */ "",
    /* 16 */ "    @Test",
    /* 17 */ "    def testDouble(): Bool = double(2) == 4",
    /* 18 */ "}"
  )

  private def measure(lines: List[String]): Metrics.Report = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(Options.Default)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, lines.mkString("\n"))
    flix.check() match {
      case (Some(root), _) => Metrics.compute(root)
      case (None, errors) => fail(s"the program under measurement must compile, but got: $errors")
    }
  }

  private lazy val report: Metrics.Report = measure(Lines)

  test("the standard library is not counted as the project's own code") {
    // The compiled root holds thousands of library definitions. Counting them would tell a
    // beginner their first program has six thousand functions, and is the single easiest way for
    // every other number here to be wrong.
    assertResult(1)(report.files)
    assertResult(4)(report.defs.length)
  }

  test("lines are divided as they were written") {
    assertResult(Metrics.LineMetrics(total = 18, code = 14, comment = 1, blank = 3))(report.lines)
  }

  test("the parts of a line count add up to the whole") {
    val l = report.lines
    assertResult(l.total)(l.code + l.comment + l.blank)
  }

  test("tests are told apart from functions") {
    assertResult(3)(report.functions.length)
    assertResult(List("Demo.testDouble"))(report.tests.map(_.name))
  }

  test("the public surface is only what is public and not a test") {
    assertResult(List("Demo.classify", "Demo.double"))(report.publicApi.map(_.name).sorted)
  }

  test("documentation is counted from the doc comment the compiler recorded") {
    assertResult(List("Demo.double"))(report.publicApi.filter(_.hasDoc).map(_.name))
    assertResult(List("Demo.classify"))(report.publicApi.filterNot(_.hasDoc).map(_.name))
  }

  test("parameters are counted per definition") {
    assertResult(Some(2))(report.defs.find(_.name == "Demo.classify").map(_.parameters))
    assertResult(Some(1))(report.defs.find(_.name == "Demo.double").map(_.parameters))
    assertResult(Some(0))(report.defs.find(_.name == "Demo.helper").map(_.parameters))
  }

  test("nesting counts enclosing branches, not branches") {
    // `classify` is a `match` inside an `if`: two levels. Its `match` has two rules, which a
    // branch count would score higher than the nesting that actually makes it hard to read.
    assertResult(Some(2))(report.defs.find(_.name == "Demo.classify").map(_.nesting))
    assertResult(Some(0))(report.defs.find(_.name == "Demo.double").map(_.nesting))
  }

  test("a definition's length is the lines it spans") {
    assertResult(Some(1))(report.defs.find(_.name == "Demo.double").map(_.lines))
    // Lines 5 to 12.
    assertResult(Some(8))(report.defs.find(_.name == "Demo.classify").map(_.lines))
  }

  test("every format reports the same numbers") {
    // Four renderings of one measurement. They may look however they like, but they may not
    // disagree, so each is asked for the same fact.
    val json = Metrics.render(report, Metrics.Format.Json, Formatter.NoFormatter)
    val csv = Metrics.render(report, Metrics.Format.Csv, Formatter.NoFormatter)
    val md = Metrics.render(report, Metrics.Format.Markdown, Formatter.NoFormatter)

    assert(json.contains("\"functions\":3"), json)
    assert(md.contains("| functions | 3 |"), md)
    // One header line and one line per definition.
    assertResult(1 + report.defs.length)(csv.trim.split("\n").length)
  }

  test("csv names every definition, and quotes what would break a column") {
    val csv = Metrics.render(report, Metrics.Format.Csv, Formatter.NoFormatter)
    val header :: rows = csv.trim.split("\n").toList: @unchecked
    assertResult("name,module,file,line,lines,parameters,returnWidth,traitConstraints,datalogRules,datalogFacts,localDefs,maxLocalParameters,nesting,cognitive,maxLineTokens,maxLineLength,public,test,documented,pure,effects,smells")(header)
    assertResult(report.defs.map(_.name).sorted)(rows.map(_.takeWhile(_ != ',')).sorted)
  }

  test("json is a document a program can read") {
    val parsed = org.json4s.native.JsonMethods.parse(Metrics.render(report, Metrics.Format.Json, Formatter.NoFormatter))
    // Addressed directly rather than searched for: a deep search would also match a field of the
    // same name nested elsewhere in the document.
    val defs = (parsed \ "definitions").children
    assertResult(report.defs.length)(defs.length)
  }

  test("a finding points at the definition, not at the comment above it") {
    // `double` is written on line 3, under its doc comment on line 2. Sending a reader to line 2
    // makes them count lines from where the report pointed.
    assertResult(Some(3))(report.defs.find(_.name == "Demo.double").map(_.line))
  }

  test("no key in the report is ambiguous under a deep search") {
    // A consumer that searches the document for a name must not find two different things. This
    // caught `modules[].definitions` colliding with the top-level `definitions` array.
    val parsed = org.json4s.native.JsonMethods.parse(Metrics.render(report, Metrics.Format.Json, Formatter.NoFormatter))
    assertResult((parsed \ "definitions").children.length)((parsed \\ "definitions").children.length)
  }

  test("effects are reported as the set the compiler inferred") {
    // `pure` alone throws away which effects a function has, which is the thing Flix knows and
    // other languages do not.
    val effectful = measure(List(
      "mod Demo {",
      "    pub def shout(): Unit \\ IO = println(\"hi\")",
      "    pub def quiet(): Int32 = 1",
      "}"
    ))
    assertResult(Some(List("IO")))(effectful.defs.find(_.name == "Demo.shout").map(_.effects))
    assertResult(Some(Nil))(effectful.defs.find(_.name == "Demo.quiet").map(_.effects))
  }

  test("cognitive complexity charges for nesting, not for the number of arms") {
    // A wide but flat match is easy to read; the same branches nested are not. McCabe scores the
    // first higher, which is why it is not what is reported.
    val wide = measure(List(
      "mod Demo {",
      "    pub def wide(x: Int32): Int32 = match x {",
      "        case 0 => 0",
      "        case 1 => 1",
      "        case 2 => 2",
      "        case 3 => 3",
      "        case _ => 4",
      "    }",
      "}"
    ))
    val deep = measure(List(
      "mod Demo {",
      "    pub def deep(x: Int32): Int32 =",
      "        if (x > 0)",
      "            if (x > 1)",
      "                if (x > 2) 3 else 2",
      "            else 1",
      "        else 0",
      "}"
    ))
    val wideScore = wide.defs.find(_.name == "Demo.wide").map(_.cognitive).getOrElse(0)
    val deepScore = deep.defs.find(_.name == "Demo.deep").map(_.cognitive).getOrElse(0)
    assertResult(1)(wideScore)
    assert(deepScore > wideScore, s"nesting ($deepScore) should cost more than arms ($wideScore)")
  }

  test("a wide local definition is not hidden by a narrow outer signature") {
    // The case this was written for: a two-parameter function whose body threads eight
    // accumulators through a recursive local definition. Measuring only the outer signature reports
    // two, and the widest function in the project goes unnoticed.
    val wide = measure(List(
      "mod Demo {",
      "    pub def one(seed: Int32, budget: Int32): Int32 = {",
      "        def loop(left, acc, a, b, c, d, e, f) =",
      "            if (left <= 0) acc + a + b + c + d + e + f",
      "            else loop(left - 1, acc + 1, a, b, c, d, e, f);",
      "        loop(budget, seed, 0, 0, 0, 0, 0, 0)",
      "    }",
      "}"
    ))
    val one = wide.defs.find(_.name == "Demo.one").getOrElse(fail("the definition was not measured"))
    assertResult(2)(one.parameters)
    assertResult(1)(one.localDefs)
    assertResult(8)(one.maxLocalParameters)
    assertResult(8)(one.widestParameterList)
  }

  test("a definition with no local definitions reports none") {
    assertResult(Some(0))(report.defs.find(_.name == "Demo.double").map(_.localDefs))
    assertResult(Some(1))(report.defs.find(_.name == "Demo.double").map(_.widestParameterList))
  }

  test("a local definition is measured as the function it is") {
    val wide = measure(List(
      "mod Demo {",
      "    pub def one(seed: Int32, budget: Int32): Int32 = {",
      "        def loop(left, acc, a, b, c, d, e, f) =",
      "            if (left <= 0)",
      "                if (acc > 0) acc + a + b + c + d + e + f else 0",
      "            else loop(left - 1, acc + 1, a, b, c, d, e, f);",
      "        loop(budget, seed, 0, 0, 0, 0, 0, 0)",
      "    }",
      "}"
    ))
    val loop = wide.locals.find(_.name == "loop").getOrElse(fail("the local definition was not measured"))
    assertResult("Demo.one")(loop.owner)
    assertResult(8)(loop.parameters)
    // Two conditionals, one inside the other, both written inside `loop`.
    assertResult(2)(loop.nesting)
    assertResult(3)(loop.cognitive)
    // Its own span, not its parent's: it must not run to the end of the enclosing function.
    assert(loop.lines < wide.defs.find(_.name == "Demo.one").map(_.lines).getOrElse(0),
      s"a local definition spanning ${loop.lines} lines cannot be as long as the function holding it")
  }

  test("the width of what a function returns is measured") {
    // A wide record is a parameter list in the other direction, and nothing else here sees it.
    val shapes = measure(List(
      "mod Demo {",
      "    pub def wide(): { a = Int32, b = Int32, c = Int32, d = Int32, e = Int32, f = Int32 } =",
      "        { a = 1, b = 2, c = 3, d = 4, e = 5, f = 6 }",
      "    pub def pair(): (Int32, Int32) = (1, 2)",
      "    pub def plain(): Int32 = 1",
      "}"
    ))
    assertResult(Some(6))(shapes.defs.find(_.name == "Demo.wide").map(_.returnWidth))
    assertResult(Some(2))(shapes.defs.find(_.name == "Demo.pair").map(_.returnWidth))
    assertResult(Some(1))(shapes.defs.find(_.name == "Demo.plain").map(_.returnWidth))
  }

  test("no thresholds means nothing is over the limit") {
    // A report describes. It only says a project has failed once someone has said what failing is.
    assertResult(Nil)(Metrics.violations(report, Metrics.Thresholds()))
  }

  test("a threshold names what exceeded it, by how much, and where") {
    val vs = Metrics.violations(report, Metrics.Thresholds(maxNesting = Some(1)))
    assertResult(List("Demo.classify"))(vs.map(_.subject))
    assertResult(List("nesting"))(vs.map(_.category))
    assertResult(List(2.0))(vs.map(_.actual))
    assertResult(List(1.0))(vs.map(_.limit))
    assertResult(List(5))(vs.map(_.line))
  }

  test("a parameter limit counts the widest list anywhere inside") {
    // Otherwise a loop carrying eight accumulators is excused by a two-parameter signature.
    val wide = measure(List(
      "mod Demo {",
      "    pub def one(seed: Int32, budget: Int32): Int32 = {",
      "        def loop(left, acc, a, b, c, d) = if (left <= 0) acc + a + b + c + d else loop(left - 1, acc, a, b, c, d);",
      "        loop(budget, seed, 0, 0, 0, 0)",
      "    }",
      "}"
    ))
    assertResult(List(6.0))(Metrics.violations(wide, Metrics.Thresholds(maxParameters = Some(4))).map(_.actual))
    assertResult(Nil)(Metrics.violations(wide, Metrics.Thresholds(maxParameters = Some(6))))
  }

  test("a documentation floor is met or reported as a shortfall") {
    // Half the public API here is documented.
    assertResult(Nil)(Metrics.violations(report, Metrics.Thresholds(minDocCoverage = Some(0.5))))
    val short = Metrics.violations(report, Metrics.Thresholds(minDocCoverage = Some(0.9)))
    assertResult(List("docCoverage"))(short.map(_.category))
    assertResult(List(0.5))(short.map(_.actual))
    assertResult(List("50.0%"))(short.map(_.actualText))
  }

  test("the effect budget says which effects are used, not merely how many are pure") {
    val effectful = measure(List(
      "mod Demo {",
      "    pub def a(): Unit \\ IO = println(\"a\")",
      "    pub def b(): Unit \\ IO = println(\"b\")",
      "    pub def c(): Int32 = 1",
      "}"
    ))
    assertResult(List(("IO", 2)))(Metrics.effectBudget(effectful.publicApi))
  }

  test("Datalog is measured, since a rule is not a line of code like any other") {
    val dl = measure(List(
      "mod Demo {",
      "    pub def facts(): #{ Edge(Int32, Int32) } = #{ Edge(1, 2). Edge(2, 3). }",
      "    pub def rules(): #{ Edge(Int32, Int32), Path(Int32, Int32) } = #{",
      "        Path(x, y) :- Edge(x, y).",
      "    }",
      "}"
    ))
    assertResult(Some(2))(dl.defs.find(_.name == "Demo.facts").map(_.datalogFacts))
    assertResult(Some(0))(dl.defs.find(_.name == "Demo.facts").map(_.datalogRules))
    assertResult(Some(1))(dl.defs.find(_.name == "Demo.rules").map(_.datalogRules))
  }

  test("a flat match is flat, however many arms it has") {
    // Reported as a false positive; it is not. Arms do not compound, so a lookup table scores the
    // same as a single conditional, and a function that scores nine really does nest nine deep.
    val flat = measure(List("mod Demo {", "    pub def code(c: Int32): Int32 = match c {") ++
      (0 to 20).map(i => s"        case $i => $i").toList ++
      List("        case _ => 0", "    }", "}"))
    val code = flat.defs.find(_.name == "Demo.code").getOrElse(fail("not measured"))
    assertResult(1)(code.nesting)
    assertResult(1)(code.cognitive)
  }

  test("length is not charged against data") {
    // A long record literal is a table. Splitting it in two makes nothing easier to read.
    val data = measure(List(
      "mod Demo {",
      "    pub def defaults(): { a = Int32, b = Int32, c = Int32 } =",
      "        { a = 1,",
      "          b = 2,",
      "          c = 3 }",
      "}"
    ))
    assertResult(Nil)(Metrics.violations(data, Metrics.Thresholds(maxLines = Some(2))))
    // A function of the same length that branches is still charged.
    assert(Metrics.violations(report, Metrics.Thresholds(maxLines = Some(2))).nonEmpty)
  }

  test("a smell is addressed by a program, not parsed out of prose") {
    // What reads this is usually a CI script or an editor: a fixed category, a file and a line, and
    // numbers that are numbers.
    val vs = Metrics.violations(report, Metrics.Thresholds(maxNesting = Some(1)))
    val v = vs.headOption.getOrElse(fail("expected a violation"))
    assert(Set("length", "parameters", "nesting", "complexity", "docCoverage", "orphan").contains(v.category), v.category)
    assert(v.file.nonEmpty, "a definition's smell must say which file")
    assertResult(s"${v.file}:${v.line}")(v.where)
    assert(v.actual > v.limit, s"${v.actual} should exceed ${v.limit}")
  }

  test("sarif is a document the tools that read sarif accept") {
    // A schema, not a rendering: the three things below are what decide whether GitHub shows the
    // annotations or silently drops the file.
    val smells = Metrics.violations(report, Metrics.Thresholds(maxNesting = Some(1), minDocCoverage = Some(0.9)))
    val doc = org.json4s.native.JsonMethods.parse(Metrics.render(report, Metrics.Format.Sarif, Formatter.NoFormatter, smells))

    assertResult(JString("2.1.0"))(doc \ "version")
    val run = (doc \ "runs").children.head
    // One driver for both halves of the tool: diagnostics and smells share a run, so the name is
    // the compiler's rather than the subcommand's.
    assertResult(JString("flix"))(run \ "tool" \ "driver" \ "name")

    // Every rule a result names is also declared, or the result is dropped.
    def text(v: org.json4s.JValue): Option[String] = v match {
      case JString(x) => Some(x)
      case _ => None
    }
    val declared = (run \ "tool" \ "driver" \ "rules").children.flatMap(r => text(r \ "id")).toSet
    val used = (run \ "results").children.flatMap(r => text(r \ "ruleId")).toSet
    assert(used.nonEmpty, "expected results")
    assert(used.subsetOf(declared), s"undeclared rules: ${used -- declared}")

    // An index, where given, addresses the same rule the id names.
    val ruleIds = (run \ "tool" \ "driver" \ "rules").children.flatMap(r => text(r \ "id"))
    (run \ "results").children.foreach { r =>
      (r \ "ruleIndex", r \ "ruleId") match {
        case (JInt(i), JString(id)) => assertResult(id)(ruleIds(i.toInt))
        case _ => fail("a result must carry both a ruleId and a ruleIndex")
      }
    }

    // Documentation is worth knowing and is not a defect.
    (run \ "results").children.filter(r => text(r \ "ruleId").contains("docCoverage")).foreach { r =>
      assertResult(Some("note"))(text(r \ "level"))
    }

    // A region is one-based, so a smell with no line carries no location rather than line zero.
    (run \ "results").children.foreach { r =>
      (r \ "locations").children.foreach { l =>
        val line = l \ "physicalLocation" \ "region" \ "startLine"
        assert(line match { case JInt(n) => n >= 1; case _ => false },
          s"a region must start at line 1 or later, found $line")
      }
    }
  }

  test("a crammed line is told from the same code wrapped, which structure cannot do") {
    // Both have cognitive complexity 3 and nesting 1: every structural metric calls them the same
    // function. Buse and Weimer found tokens per line predicts reading time, and it is the only
    // thing here that separates them.
    val program = List(
      "mod K {",
      "    pub def crammed(x: Int32): Int32 = match x { case 0 => 1 case n if n > 0 and n < 9 => n + 1 case _ => 0 }",
      "    pub def wrapped(x: Int32): Int32 = match x {",
      "        case 0 => 1",
      "        case n if n > 0 and n < 9 => n + 1",
      "        case _ => 0",
      "    }",
      "}"
    )
    val m = measure(program)
    val crammed = m.defs.find(_.name == "K.crammed").getOrElse(fail("not measured"))
    val wrapped = m.defs.find(_.name == "K.wrapped").getOrElse(fail("not measured"))

    assertResult(crammed.cognitive)(wrapped.cognitive)
    assertResult(crammed.nesting)(wrapped.nesting)
    assert(crammed.maxLineTokens > wrapped.maxLineTokens,
      s"${crammed.maxLineTokens} should exceed ${wrapped.maxLineTokens}")
    assert(crammed.maxLineLength > wrapped.maxLineLength)
    assert(crammed.cognitiveDensity > wrapped.cognitiveDensity)

    // And only the crammed one is reported.
    val vs = Metrics.violations(m, Metrics.Thresholds(maxLineTokens = Some(25)))
    assertResult(List("K.crammed"))(vs.map(_.subject))
    assertResult(List("density"))(vs.map(_.category))
  }

  test("a token spanning lines is counted once, where it starts") {
    // A multi-line string is one token. Counted on every line it covers, each of those lines would
    // be charged for it; counted where it starts, only the declaration line carries any tokens at
    // all, which is what the count below shows -- the three lines of string contribute none.
    val m = measure(List("mod K {", "    pub def s(): String = \"\"\"", "abc", "def", "\"\"\"", "}"))
    val d = m.defs.find(_.name == "K.s").getOrElse(fail("not measured"))
    // Ten on the declaration line, and nothing charged to the three lines of string it opens.
    assertResult(10)(d.maxLineTokens)
  }

  test("an empty program measures as empty rather than as a failure") {
    val empty = measure(List("mod Demo {", "}"))
    assertResult(0)(empty.defs.length)
    assertResult(Metrics.LineMetrics(total = 2, code = 2, comment = 0, blank = 0))(empty.lines)
  }

  test("a line holding both code and a comment counts as code") {
    // It has to be read as code, so counting it as a comment would flatter the report.
    val mixed = measure(List("mod Demo {", "    pub def one(): Int32 = 1 // a trailing comment", "}"))
    assertResult(Metrics.LineMetrics(total = 3, code = 3, comment = 0, blank = 0))(mixed.lines)
  }

  test("the inside of a block comment counts as comment, whatever it looks like") {
    // The middle line reads exactly like code. Only the lexer knows it is not, which is the
    // reason these numbers are taken from tokens rather than from the text.
    val blocky = measure(List(
      "mod Demo {",
      "    /*",
      "    pub def notReallyAFunction(): Int32 = 1",
      "    */",
      "    pub def one(): Int32 = 1",
      "}"
    ))
    assertResult(Metrics.LineMetrics(total = 6, code = 3, comment = 3, blank = 0))(blocky.lines)
    assertResult(1)(blocky.defs.length)
  }
}
