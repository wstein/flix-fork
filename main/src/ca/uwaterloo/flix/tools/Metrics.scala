/*
 * Copyright 2026 Werner Stein
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
package ca.uwaterloo.flix.tools

import ca.uwaterloo.flix.api.lsp.{Acceptor, Consumer, Visitor}
import ca.uwaterloo.flix.language.ast.TypedAst.{Expr, Root}
import ca.uwaterloo.flix.language.ast.shared.{Input, Source}
import ca.uwaterloo.flix.language.ast.shared.SymUse.DefSymUse
import ca.uwaterloo.flix.language.ast.{SemanticOp, SourceLocation, Symbol, TokenKind, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.phase.Lexer
import ca.uwaterloo.flix.util.Formatter

import java.nio.file.{Path, Paths}
import org.json4s.JsonDSL.*
import org.json4s.JValue
import org.json4s.native.JsonMethods
import org.json4s.native.JsonMethods.pretty

/**
  * Measurements of a project's own source code.
  *
  * Every number here is counted from what the compiler itself built -- the lexer's tokens and the
  * typed abstract syntax tree -- rather than from matching patterns against text. That is the
  * whole point of computing this inside the compiler: a text scanner has to guess at what a `def`
  * is, whether a `case` is a branch or a data constructor, and whether a line inside a block
  * comment is code, and each guess is a number that is quietly wrong.
  *
  * Two rules keep the output honest:
  *
  *   - Nothing is counted that is not the project's. A compiled root holds the standard library
  *     as well, some six thousand definitions of it, so a report that counted everything would
  *     credit a beginner's first project with all of it. See [[isProjectSource]].
  *   - Nothing that cannot be measured is reported as zero. A metric that needs information the
  *     compiler did not produce is absent from the report, and says so.
  */
object Metrics {

  /** How many findings of each kind to name. */
  private val FindingsShown: Int = 5

  /**
    * A definition, measured.
    *
    * @param name       the definition's fully qualified name.
    * @param loc        where it is written.
    * @param lines      how many lines it spans.
    * @param parameters how many formal parameters it declares.
    * @param nesting    how deeply its branches nest. See [[nestingDepth]].
    * @param isPublic   whether it is part of the project's public surface.
    * @param isTest     whether it is a test.
    * @param hasDoc     whether it carries a documentation comment.
    * @param effect     the effect it has, as the compiler inferred or checked it.
    */
  case class DefMetrics(name: String,
                        module: String,
                        file: String,
                        loc: SourceLocation,
                        line: Int,
                        lines: Int,
                        parameters: Int,
                        nesting: Int,
                        isPublic: Boolean,
                        isTest: Boolean,
                        hasDoc: Boolean,
                        localDefs: Int,
                        maxLocalParameters: Int,
                        cognitive: Int,
                        effects: List[String]) {

    /** Whether it has no effect at all. */
    def isPure: Boolean = effects.isEmpty

    /**
      * The widest parameter list anywhere in this definition.
      *
      * A recursive loop written as a local definition carries its state in its parameters, so the
      * outer signature says nothing about how wide the function really is: a `def one(tuning, seed)`
      * whose body threads eight accumulators through a local `loop` reads as taking two.
      */
    def widestParameterList: Int = parameters.max(maxLocalParameters)
  }

  /**
    * A module, and what it depends on.
    *
    * @param fanIn  how many modules depend on this one.
    * @param fanOut how many modules this one depends on.
    */
  case class ModuleMetrics(name: String, definitions: Int, lines: Int, fanIn: Int, fanOut: Int) {

    /**
      * Martin's instability: 0 is depended upon and depends on nothing, 1 is the reverse.
      *
      * A module coupled to nothing at all has no instability to speak of, so it is reported as 0
      * rather than as a division by zero.
      */
    def instability: Double =
      if (fanIn + fanOut == 0) 0.0 else fanOut.toDouble / (fanIn + fanOut)
  }

  /**
    * How the lines of a file divide up.
    *
    * A line holding code and a trailing comment counts as code: it is a line one has to read as
    * code. A line inside a block comment counts as a comment even though nothing on it says so,
    * which is where counting by hand goes wrong.
    */
  case class LineMetrics(total: Int, code: Int, comment: Int, blank: Int)

  /** Everything measured about a project. */
  case class Report(files: Int,
                    lines: LineMetrics,
                    defs: List[DefMetrics],
                    traits: Int,
                    instances: Int,
                    enums: Int,
                    structs: Int,
                    effects: Int,
                    typeAliases: Int,
                    modules: List[ModuleMetrics]) {

    /** The definitions that are not tests. */
    def functions: List[DefMetrics] = defs.filterNot(_.isTest)

    /** The definitions that are tests. */
    def tests: List[DefMetrics] = defs.filter(_.isTest)

    /** The public, non-test definitions: what someone else would use. */
    def publicApi: List[DefMetrics] = functions.filter(_.isPublic)

    /** How much of the public surface is documented, between 0 and 1. */
    def docCoverage: Double = ratio(publicApi.count(_.hasDoc), publicApi.length)

    /** How much of the public surface is pure, between 0 and 1. */
    def purity: Double = ratio(publicApi.count(_.isPure), publicApi.length)

    /** How much of the code is comment, between 0 and 1. */
    def commentDensity: Double = ratio(lines.comment, lines.total)

    /** Code lines per test, or nothing when there are no tests to divide by. */
    def codeLinesPerTest: Option[Double] =
      if (tests.isEmpty) None else Some(lines.code.toDouble / tests.length)
  }

  /** A ratio that is 0 rather than undefined when there is nothing to divide. */
  private def ratio(part: Int, whole: Int): Double = if (whole == 0) 0.0 else part.toDouble / whole

  /**
    * Measures the project's own code in `root`.
    */
  def compute(root: Root, base: Option[Path] = None): Report = {
    val sources = root.sources.keys.filter(isProjectSource).toList
    val analysis = analyse(root)

    val defs = root.defs.values.toList
      .filter(d => isProjectSource(d.loc.source))
      .map { d =>
        DefMetrics(
          name = d.sym.toString,
          module = moduleOf(d.sym),
          file = relativise(d.loc.source.name, base),
          loc = d.loc,
          line = firstDeclarationLine(d),
          lines = declarationLines(d),
          parameters = declaredParameters(d.spec.fparams),
          nesting = analysis.nesting.getOrElse(d.sym.toString, 0),
          isPublic = d.spec.mod.isPublic,
          isTest = d.spec.ann.isTest,
          hasDoc = d.spec.doc.text.trim.nonEmpty,
          localDefs = analysis.locals.getOrElse(d.sym.toString, Nil).length,
          maxLocalParameters = analysis.locals.getOrElse(d.sym.toString, Nil).maxOption.getOrElse(0),
          cognitive = analysis.cognitive.getOrElse(d.sym.toString, 0),
          effects = d.spec.eff.effects.toList.map(_.toString).sorted
        )
      }
      .sortBy(_.name)

    Report(
      files = sources.length,
      lines = sources.map(lineMetrics).foldLeft(LineMetrics(0, 0, 0, 0)) { (a, b) =>
        LineMetrics(a.total + b.total, a.code + b.code, a.comment + b.comment, a.blank + b.blank)
      },
      defs = defs,
      traits = root.traits.values.count(t => isProjectSource(t.loc.source)),
      instances = root.instances.values.count(i => isProjectSource(i.loc.source)),
      enums = root.enums.values.count(e => isProjectSource(e.loc.source)),
      structs = root.structs.values.count(s => isProjectSource(s.loc.source)),
      effects = root.effects.values.count(e => isProjectSource(e.loc.source)),
      typeAliases = root.typeAliases.values.count(a => isProjectSource(a.loc.source)),
      modules = moduleMetrics(defs, analysis.edges)
    )
  }

  /**
    * Returns the module a definition belongs to, or `""` for one at the top level.
    */
  private def moduleOf(sym: Symbol.DefnSym): String = sym.namespace.mkString(".")

  /**
    * Returns `name` relative to `base`, when it is underneath it.
    *
    * An absolute path is particular to the machine that produced it, so a report full of them
    * cannot be compared between a laptop and a build server, or committed and diffed. Anything
    * outside the project is left as it is rather than turned into a chain of `..`.
    */
  private def relativise(name: String, base: Option[Path]): String = {
    val path = try Paths.get(name) catch { case _: Exception => return name }
    base match {
      case Some(b) if path.isAbsolute && path.startsWith(b) => b.relativize(path).toString
      case _ => name
    }
  }

  /**
    * Aggregates definitions into modules, and counts what depends on what.
    */
  private def moduleMetrics(defs: List[DefMetrics], edges: Set[(String, String)]): List[ModuleMetrics] = {
    val byModule = defs.groupBy(_.module)
    byModule.toList.map { case (name, ds) =>
      ModuleMetrics(
        name = if (name.isEmpty) "(top level)" else name,
        definitions = ds.length,
        lines = ds.map(_.lines).sum,
        fanIn = edges.count { case (from, to) => to == name && from != name },
        fanOut = edges.count { case (from, to) => from == name && to != name }
      )
    }.sortBy(m => (-m.fanIn, m.name))
  }

  /**
    * Returns `true` if `src` is the project's own code.
    *
    * The standard library arrives as [[Input.BundledLibraryFile]] and a dependency as a package
    * input; neither was written by whoever is asking. Everything is listed rather than matched
    * with a wildcard, so that a new kind of input has to be classified deliberately instead of
    * silently counting as the user's.
    */
  private def isProjectSource(src: Source): Boolean = src.input match {
    case Input.RealFile(_, _) => true
    case Input.VirtualFile(_, _, _) => true
    case Input.Unknown => false
    case Input.BundledLibraryFile(_, _, _) => false
    case Input.VirtualUri(_, _, _) => false
    case Input.PkgFile(_, _) => false
    case Input.FileInPackage(_, _, _, _) => false
  }

  /**
    * Returns how many parameters were actually written.
    *
    * A function declared with none is given a single parameter of type `Unit` by the compiler, so
    * counting the list would report one parameter for `def f(): Int32`. The same rule is applied
    * where signatures are rendered for documentation, and the two must agree: a report that
    * disagrees with the signature it describes is worse than no report.
    */
  private def declaredParameters(fparams: List[TypedAst.FormalParam]): Int = fparams match {
    case List(TypedAst.FormalParam(_, Type.Cst(TypeConstructor.Unit, _), _, _, _)) => 0
    case _ => fparams.length
  }

  /**
    * Returns how many lines a definition spans, not counting its documentation.
    *
    * The parser folds a declaration's doc comment into the declaration, so a location taken at
    * face value makes a documented function longer than an undocumented one. Measuring the comment
    * as part of the function would penalise writing it, which is the opposite of what the report
    * is for.
    */
  private def declarationLines(d: TypedAst.Def): Int =
    (d.loc.end.lineOneIndexed - firstDeclarationLine(d) + 1).max(1)

  /**
    * Returns the line a definition starts on, past its documentation.
    *
    * A finding has to point at the `def`, not at the comment above it, or following it means
    * counting lines by hand from where the report sent you.
    */
  private def firstDeclarationLine(d: TypedAst.Def): Int = {
    val docEnd = if (d.spec.doc.text.trim.isEmpty) None else Some(d.spec.doc.loc.end.lineOneIndexed)
    docEnd.map(_ + 1).getOrElse(d.loc.start.lineOneIndexed).max(d.loc.start.lineOneIndexed)
  }

  /**
    * Divides `src` into code, comment and blank lines, using the lexer's own tokens.
    *
    * A line is blank when it holds nothing but whitespace, a comment when every token on it is a
    * comment, and code otherwise. Block comments are covered because a token is asked which lines
    * it spans rather than what it looks like, so the middle of a block comment -- which reads like
    * anything at all -- is classified by the lexer and not by its appearance.
    */
  private def lineMetrics(src: Source): LineMetrics = {
    val text = new String(src.data)
    val lines = if (text.isEmpty) Array.empty[String] else text.split("\n", -1)
    // A trailing newline produces a final empty element that is not a line of the file.
    val lineCount = if (lines.nonEmpty && lines.last.isEmpty) lines.length - 1 else lines.length

    val commentLines = scala.collection.mutable.Set.empty[Int]
    val codeLines = scala.collection.mutable.Set.empty[Int]

    // Lexed here rather than read from `Root.tokens`: by the time `check` returns, that map holds
    // only what later phases still needed, so a file of any size arrives with a handful of tokens
    // and every line after the first would be counted blank.
    val (tokens, _) = Lexer.lex(src)
    tokens.foreach { token =>
      if (token.kind != TokenKind.Eof) {
        val target = if (token.kind.isComment) commentLines else codeLines
        for (line <- token.start.lineOneIndexed to token.end.lineOneIndexed) target += line
      }
    }

    var code = 0
    var comment = 0
    var blank = 0
    for (line <- 1 to lineCount) {
      if (lines(line - 1).trim.isEmpty) blank += 1
      else if (codeLines.contains(line)) code += 1
      else if (commentLines.contains(line)) comment += 1
      else blank += 1
    }

    LineMetrics(lineCount, code, comment, blank)
  }

  /**
    * What one pass over the tree yields.
    *
    * @param nesting   how deeply each definition's branches nest.
    * @param cognitive how hard each definition is to follow. See [[cognitiveComplexity]].
    * @param edges     which module depends on which, from resolved references.
    */
  private case class Analysis(nesting: Map[String, Int],
                              cognitive: Map[String, Int],
                              locals: Map[String, List[Int]],
                              edges: Set[(String, String)])

  /**
    * Walks the tree once, collecting everything that needs the tree.
    *
    * The compiler's own visitor is used rather than a hand-written traversal, so that an
    * expression added to the language cannot be silently skipped here.
    */
  private def analyse(root: Root): Analysis = {
    // Collected per definition, so that two definitions in one file cannot be confused for one.
    val branches = scala.collection.mutable.Map.empty[String, List[SourceLocation]]
    val booleans = scala.collection.mutable.Map.empty[String, Int]
    val guards = scala.collection.mutable.Map.empty[String, Int]
    val locals = scala.collection.mutable.Map.empty[String, List[Int]]
    val edges = scala.collection.mutable.Set.empty[(String, String)]
    var current: Option[TypedAst.Def] = None

    val consumer = new Consumer {
      override def consumeDef(defn: TypedAst.Def): Unit = current = Some(defn)

      override def consumeDefSymUse(symUse: DefSymUse): Unit = current.foreach { from =>
        // Only the project's own modules: a call into the standard library says nothing about how
        // this project is put together.
        if (isProjectSource(symUse.sym.loc.source)) {
          edges += ((moduleOf(from.sym), moduleOf(symUse.sym)))
        }
      }

      override def consumeMatchRule(rule: TypedAst.MatchRule): Unit = current.foreach { defn =>
        if (rule.guard.isDefined) {
          val name = defn.sym.toString
          guards.update(name, guards.getOrElse(name, 0) + 1)
        }
      }

      override def consumeExpr(exp: Expr): Unit = current.foreach { defn =>
        val name = defn.sym.toString
        exp match {
          case _: Expr.IfThenElse | _: Expr.Match | _: Expr.ExtMatch | _: Expr.RestrictableChoose =>
            branches.update(name, exp.loc :: branches.getOrElse(name, Nil))
          case Expr.LocalDef(_, _, fparams, _, _, _, _, _) =>
            // A local definition is a function too, and an invisible one: it is not in `root.defs`,
            // so without this a project's widest and longest function can be one nobody counted.
            locals.update(name, declaredParameters(fparams) :: locals.getOrElse(name, Nil))
          case Expr.Binary(SemanticOp.BoolOp.And | SemanticOp.BoolOp.Or, _, _, _, _, _) =>
            booleans.update(name, booleans.getOrElse(name, 0) + 1)
          case _ => ()
        }
      }
    }

    Visitor.visitRoot(root, consumer, Everything)

    Analysis(
      nesting = branches.map { case (name, locs) => name -> deepestChain(locs) }.toMap,
      locals = locals.toMap,
      cognitive = branches.keySet.++(booleans.keySet).++(guards.keySet).map { name =>
        name -> cognitiveComplexity(branches.getOrElse(name, Nil), booleans.getOrElse(name, 0), guards.getOrElse(name, 0))
      }.toMap,
      edges = edges.toSet
    )
  }

  /**
    * Returns how hard a definition is to follow.
    *
    * Not McCabe. A total `match` over a twelve-case enum has a cyclomatic complexity of twelve and
    * is perfectly readable -- that number describes the enum, not the code -- whereas three
    * conditionals inside one another are hard however few cases each has. So a branch costs one
    * plus however many branches enclose it, which charges for nesting rather than for arity, and
    * a `match` costs the same whether it has two arms or twenty.
    *
    * A guard and a boolean operator each cost one: both are decisions that nesting cannot see,
    * which is the gap in reporting nesting alone.
    */
  private def cognitiveComplexity(branches: List[SourceLocation], booleans: Int, guards: Int): Int = {
    val nested = branches.map(loc => branches.count(other => contains(other, loc)))
    nested.sum + booleans + guards
  }

  /**
    * Returns the length of the longest chain of locations each contained in the last.
    */
  private def deepestChain(locs: List[SourceLocation]): Int =
    locs.map(loc => locs.count(other => contains(other, loc))).maxOption.getOrElse(0)

  /**
    * Returns `true` if `outer` covers `inner`.
    */
  private def contains(outer: SourceLocation, inner: SourceLocation): Boolean = {
    if (outer.source != inner.source) return false
    val startsBefore = before(outer.start.lineOneIndexed, outer.start.colOneIndexed.toInt, inner.start.lineOneIndexed, inner.start.colOneIndexed.toInt)
    val endsAfter = before(inner.end.lineOneIndexed, inner.end.colOneIndexed.toInt, outer.end.lineOneIndexed, outer.end.colOneIndexed.toInt)
    startsBefore && endsAfter
  }

  /**
    * Returns `true` if the position (`line1`, `col1`) is at or before (`line2`, `col2`).
    */
  private def before(line1: Int, col1: Int, line2: Int, col2: Int): Boolean =
    line1 < line2 || (line1 == line2 && col1 <= col2)

  /** Visits the whole tree. */
  private object Everything extends Acceptor {
    override def accept(loc: SourceLocation): Boolean = true
  }

  /**
    * How a report is written out.
    *
    * Four, because they answer four different questions: [[Format.Text]] for someone reading it
    * now, [[Format.Json]] for a program, [[Format.Csv]] for a spreadsheet or a plot, and
    * [[Format.Markdown]] for a pull request or a report handed in.
    */
  sealed trait Format

  object Format {
    case object Text extends Format
    case object Json extends Format
    case object Csv extends Format
    case object Markdown extends Format

    /** Parses a format by name, as it is written on the command line. */
    def ofString(s: String): Option[Format] = s.toLowerCase match {
      case "text" => Some(Text)
      case "json" => Some(Json)
      case "csv" => Some(Csv)
      case "md" | "markdown" => Some(Markdown)
      case _ => None
    }

    /** The names accepted, for a message that has to list them. */
    val names: String = "text, json, csv, md"
  }

  /**
    * Renders `report` in `format`.
    */
  def render(report: Report, format: Format, f: Formatter): String = format match {
    case Format.Text => text(report, f)
    case Format.Json => json(report)
    case Format.Csv => csv(report)
    case Format.Markdown => markdown(report)
  }

  /**
    * Renders every definition as a row.
    *
    * One row per definition rather than a summary, because a summary can be computed from rows and
    * rows cannot be recovered from a summary. Fields are quoted per RFC 4180 so that a name holding
    * a comma cannot shift every column after it.
    */
  private def csv(report: Report): String = {
    val header = "name,module,file,line,lines,parameters,localDefs,maxLocalParameters,nesting,cognitive,public,test,documented,pure,effects"
    val rows = report.defs.map { d =>
      List(
        d.name,
        d.module,
        d.file,
        d.line.toString,
        d.lines.toString,
        d.parameters.toString,
        d.localDefs.toString,
        d.maxLocalParameters.toString,
        d.nesting.toString,
        d.cognitive.toString,
        d.isPublic.toString,
        d.isTest.toString,
        d.hasDoc.toString,
        d.isPure.toString,
        d.effects.mkString(" ")
      ).map(f => quote(f)).mkString(",")
    }
    (header :: rows).mkString("\n") + "\n"
  }

  /** Quotes a field for CSV, per RFC 4180. */
  private def quote(field: String): String =
    if (field.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r'))
      "\"" + field.replace("\"", "\"\"") + "\""
    else field

  /**
    * Renders the report as JSON.
    */
  private def json(report: Report): String = {
    val summary: JValue =
      ("files" -> report.files) ~
        ("lines" ->
          (("total" -> report.lines.total) ~
            ("code" -> report.lines.code) ~
            ("comment" -> report.lines.comment) ~
            ("blank" -> report.lines.blank))) ~
        ("functions" -> report.functions.length) ~
        ("publicFunctions" -> report.publicApi.length) ~
        ("documentedPublicFunctions" -> report.publicApi.count(_.hasDoc)) ~
        ("purePublicFunctions" -> report.publicApi.count(_.isPure)) ~
        ("tests" -> report.tests.length) ~
        ("enums" -> report.enums) ~
        ("structs" -> report.structs) ~
        ("traits" -> report.traits) ~
        ("instances" -> report.instances) ~
        ("effects" -> report.effects) ~
        ("typeAliases" -> report.typeAliases) ~
        ("docCoverage" -> report.docCoverage) ~
        ("purity" -> report.purity) ~
        ("commentDensity" -> report.commentDensity) ~
        ("codeLinesPerTest" -> report.codeLinesPerTest)

    val definitions: JValue = report.defs.map { d =>
      ("name" -> d.name) ~
        ("module" -> d.module) ~
        ("file" -> d.file) ~
        ("line" -> d.line) ~
        ("lines" -> d.lines) ~
        ("parameters" -> d.parameters) ~
        ("localDefs" -> d.localDefs) ~
        ("maxLocalParameters" -> d.maxLocalParameters) ~
        ("nesting" -> d.nesting) ~
        ("cognitive" -> d.cognitive) ~
        ("public" -> d.isPublic) ~
        ("test" -> d.isTest) ~
        ("documented" -> d.hasDoc) ~
        ("pure" -> d.isPure) ~
        ("effects" -> d.effects)
    }

    val modules: JValue = report.modules.map { m =>
      ("name" -> m.name) ~
        // Not "definitions": the report already has a `definitions` array, and a consumer
        // searching the document for that name would find both and silently mix a count with a
        // list.
        ("definitionCount" -> m.definitions) ~
        ("lines" -> m.lines) ~
        ("fanIn" -> m.fanIn) ~
        ("fanOut" -> m.fanOut) ~
        ("instability" -> m.instability)
    }

    pretty(JsonMethods.render(("summary" -> summary) ~ ("modules" -> modules) ~ ("definitions" -> definitions)))
  }

  /**
    * Renders the report as Markdown.
    */
  private def markdown(report: Report): String = {
    val sb = new StringBuilder
    sb.append("# Metrics\n\n")
    sb.append("| measure | value |\n| --- | --- |\n")
    sb.append(s"| files | ${report.files} |\n")
    sb.append(s"| lines | ${report.lines.total} |\n")
    sb.append(s"| code | ${share(report.lines.code, report.lines.total)} |\n")
    sb.append(s"| comment | ${share(report.lines.comment, report.lines.total)} |\n")
    sb.append(s"| blank | ${share(report.lines.blank, report.lines.total)} |\n")
    sb.append(s"| functions | ${report.functions.length} |\n")
    sb.append(s"| public functions | ${report.publicApi.length} |\n")
    sb.append(s"| tests | ${report.tests.length} |\n")
    sb.append(s"| enums | ${report.enums} |\n")
    sb.append(s"| structs | ${report.structs} |\n")
    sb.append(s"| traits | ${report.traits} |\n")
    sb.append(s"| instances | ${report.instances} |\n")
    sb.append(s"| effects | ${report.effects} |\n")
    sb.append(s"| type aliases | ${report.typeAliases} |\n")

    if (report.publicApi.nonEmpty) {
      sb.append(s"| documented public | ${share(report.publicApi.count(_.hasDoc), report.publicApi.length)} |\n")
      sb.append(s"| pure public | ${share(report.publicApi.count(_.isPure), report.publicApi.length)} |\n")
    }

    if (report.modules.length > 1) {
      sb.append("\n## Modules\n\n| module | definitions | lines | fan-in | fan-out | instability |\n| --- | --- | --- | --- | --- | --- |\n")
      report.modules.foreach(m =>
        sb.append(f"| `${m.name}` | ${m.definitions} | ${m.lines} | ${m.fanIn} | ${m.fanOut} | ${m.instability}%.2f |\n"))
    }

    val longest = report.functions.sortBy(-_.lines).take(FindingsShown)
    if (longest.nonEmpty) {
      sb.append("\n## Longest functions\n\n| function | lines | at |\n| --- | --- | --- |\n")
      longest.foreach(d => sb.append(s"| `${d.name}` | ${d.lines} | ${d.loc.source.name}:${d.line} |\n"))
    }

    val nested = report.functions.filter(_.nesting > 1).sortBy(-_.nesting).take(FindingsShown)
    if (nested.nonEmpty) {
      sb.append("\n## Most deeply nested\n\n| function | levels | at |\n| --- | --- | --- |\n")
      nested.foreach(d => sb.append(s"| `${d.name}` | ${d.nesting} | ${d.loc.source.name}:${d.line} |\n"))
    }

    val undocumented = report.publicApi.filterNot(_.hasDoc).sortBy(_.name).take(FindingsShown)
    if (undocumented.nonEmpty) {
      sb.append("\n## Undocumented public functions\n\n| function | at |\n| --- | --- |\n")
      undocumented.foreach(d => sb.append(s"| `${d.name}` | ${d.loc.source.name}:${d.line} |\n"))
    }

    if (report.tests.isEmpty) sb.append("\nThis project has no tests.\n")
    sb.toString
  }

  /**
    * Renders `report` for reading.
    */
  private def text(report: Report, f: Formatter): String = {
    val sb = new StringBuilder

    sb.append(f.bold("Project") + "\n")
    sb.append(row("files", report.files.toString))
    sb.append(row("lines", s"${report.lines.total}"))
    sb.append(row("  code", share(report.lines.code, report.lines.total)))
    sb.append(row("  comment", share(report.lines.comment, report.lines.total)))
    sb.append(row("  blank", share(report.lines.blank, report.lines.total)))
    sb.append("\n")

    sb.append(f.bold("Declarations") + "\n")
    sb.append(row("functions", s"${report.functions.length} (${report.publicApi.length} public)"))
    sb.append(row("tests", report.tests.length.toString))
    sb.append(row("enums", report.enums.toString))
    sb.append(row("structs", report.structs.toString))
    sb.append(row("traits", report.traits.toString))
    sb.append(row("instances", report.instances.toString))
    sb.append(row("effects", report.effects.toString))
    sb.append(row("type aliases", report.typeAliases.toString))
    sb.append("\n")

    val api = report.publicApi
    if (api.nonEmpty) {
      val documented = api.count(_.hasDoc)
      sb.append(f.bold("Public API") + "\n")
      sb.append(row("documented", share(documented, api.length)))
      sb.append(row("pure", share(api.count(_.isPure), api.length)))
      sb.append(row("effects", api.flatMap(_.effects).distinct.sorted.take(8).mkString(", ")))
      sb.append("\n")
    }

    val functions = report.functions
    if (functions.nonEmpty) {
      sb.append(f.bold("Findings") + "\n")
      sb.append(finding(f, "longest", functions.sortBy(-_.lines).take(FindingsShown), d => s"${d.lines} lines"))
      sb.append(finding(f, "most deeply nested", functions.filter(_.nesting > 1).sortBy(-_.nesting).take(FindingsShown), d => s"${d.nesting} levels"))
      sb.append(finding(f, "hardest to follow", functions.filter(_.cognitive > 4).sortBy(-_.cognitive).take(FindingsShown), d => s"cognitive ${d.cognitive}"))
      sb.append(finding(f, "widest parameter lists", functions.filter(_.widestParameterList > 3).sortBy(-_.widestParameterList).take(FindingsShown),
        d => if (d.maxLocalParameters > d.parameters) s"${d.maxLocalParameters} parameters, in a local definition" else s"${d.parameters} parameters"))
      sb.append(finding(f, "undocumented public", api.filterNot(_.hasDoc).sortBy(_.name).take(FindingsShown), _ => "no doc comment"))
    }

    if (report.modules.length > 1) {
      sb.append("\n" + f.bold("Modules") + "\n")
      report.modules.take(FindingsShown).foreach { m =>
        sb.append(f"    ${f.blue(m.name)} -- ${m.definitions} definitions, fan-in ${m.fanIn}, fan-out ${m.fanOut}, instability ${m.instability}%.2f\n")
      }
    }

    if (report.tests.isEmpty) {
      sb.append("\n" + f.yellow("This project has no tests.") + "\n")
    }

    sb.toString
  }

  private def row(label: String, value: String): String =
    f"  ${label}%-14s $value%s\n"

  private def share(part: Int, whole: Int): String =
    if (whole == 0) part.toString else f"$part%d (${part * 100.0 / whole}%.1f%%)"

  /**
    * Renders one group of findings, or nothing when there is nothing to report.
    */
  private def finding(f: Formatter, label: String, items: List[DefMetrics], measure: DefMetrics => String): String = {
    if (items.isEmpty) return ""
    val sb = new StringBuilder
    sb.append(s"  $label\n")
    items.foreach { d =>
      sb.append(s"    ${f.blue(d.name)} -- ${measure(d)}  ${f.cyan(s"${d.loc.source.name}:${d.line}")}\n")
    }
    sb.toString
  }
}
