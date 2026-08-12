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
import ca.uwaterloo.flix.language.ast.shared.SymUse.{DefSymUse, EffSymUse, TraitSymUse}
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
                        returnWidth: Int,
                        traitConstraints: Int,
                        datalogRules: Int,
                        datalogFacts: Int,
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
    * A local definition, measured as the function it is.
    *
    * @param owner the definition it is written inside.
    */
  case class LocalMetrics(name: String,
                          owner: String,
                          file: String,
                          line: Int,
                          lines: Int,
                          parameters: Int,
                          nesting: Int,
                          cognitive: Int)

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
                    modules: List[ModuleMetrics],
                    locals: List[LocalMetrics]) {

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
          returnWidth = shapeWidth(d.spec.retTpe),
          traitConstraints = d.spec.tconstrs.length,
          datalogRules = analysis.datalog.getOrElse(d.sym.toString, (0, 0))._1,
          datalogFacts = analysis.datalog.getOrElse(d.sym.toString, (0, 0))._2,
          localDefs = analysis.locals.getOrElse(d.sym.toString, Nil).length,
          maxLocalParameters = analysis.locals.getOrElse(d.sym.toString, Nil).map(_._2).maxOption.getOrElse(0),
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
      modules = moduleMetrics(defs, analysis.edges),
      locals = localMetrics(root, analysis, base)
    )
  }

  /**
    * Returns how many parts the value a function returns has.
    *
    * A record of ten fields or a tuple of six is a parameter list in the other direction: it is
    * wide for the same reason and read for the same reason, and nothing else here would notice it.
    * A type that is neither is one part.
    */
  private def shapeWidth(tpe: Type): Int = {
    def recordFields(t: Type): Int = t.typeConstructor match {
      case Some(TypeConstructor.RecordRowExtend(_)) => 1 + t.typeArguments.map(recordFields).sum
      case _ => t.typeArguments.map(recordFields).sum
    }

    tpe.typeConstructor match {
      case Some(TypeConstructor.Tuple(arity)) => arity
      case _ =>
        val fields = recordFields(tpe)
        if (fields == 0) 1 else fields
    }
  }

  /**
    * Measures every local definition as a function in its own right.
    *
    * Its nesting and complexity are computed from the branches written inside it, taken from the
    * same collection its enclosing definition uses -- so a local definition is charged for what it
    * contains, and is not merely a line in its parent's total.
    */
  private def localMetrics(root: Root, analysis: Analysis, base: Option[Path]): List[LocalMetrics] = {
    root.defs.values.toList.filter(d => isProjectSource(d.loc.source)).flatMap { d =>
      val owner = d.sym.toString
      val enclosing = analysis.branches.getOrElse(owner, Nil)
      analysis.locals.getOrElse(owner, Nil).map { case (name, parameters, loc) =>
        val inside = enclosing.filter(b => contains(loc, b))
        LocalMetrics(
          name = name,
          owner = owner,
          file = relativise(loc.source.name, base),
          line = loc.start.lineOneIndexed,
          lines = loc.end.lineOneIndexed - loc.start.lineOneIndexed + 1,
          parameters = parameters,
          nesting = deepestChain(inside),
          cognitive = cognitiveComplexity(inside, 0, 0)
        )
      }
    }.sortBy(l => (-l.lines, l.owner))
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
    * Returns how many public functions use each effect, commonest first.
    *
    * A single purity percentage says how much of an API is pure and nothing about what the rest
    * does. Which effects a project actually reaches for is the thing this language can answer and
    * others cannot.
    */
  def effectBudget(defs: List[DefMetrics]): List[(String, Int)] =
    defs.flatMap(_.effects).groupBy(identity).view.mapValues(_.size).toList.sortBy { case (e, n) => (-n, e) }

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
                              branches: Map[String, List[SourceLocation]],
                              locals: Map[String, List[(String, Int, SourceLocation)]],
                              datalog: Map[String, (Int, Int)],
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
    val locals = scala.collection.mutable.Map.empty[String, List[(String, Int, SourceLocation)]]
    val datalog = scala.collection.mutable.Map.empty[String, (Int, Int)]
    val edges = scala.collection.mutable.Set.empty[(String, String)]
    var current: Option[TypedAst.Def] = None

    val consumer = new Consumer {
      override def consumeDef(defn: TypedAst.Def): Unit = current = Some(defn)

      override def consumeDefSymUse(symUse: DefSymUse): Unit = depend(symUse.sym.loc, symUse.sym.namespace)

      override def consumeTraitSymUse(symUse: TraitSymUse): Unit = depend(symUse.sym.loc, symUse.sym.namespace)

      override def consumeEffSymUse(symUse: EffSymUse): Unit = depend(symUse.sym.loc, symUse.sym.namespace)

      /**
        * Records that the definition being visited depends on the module a symbol belongs to.
        *
        * A module that uses another's types or traits depends on it just as surely as one that
        * calls its functions, and counting only calls made a module that merely names another's
        * enum look independent of it.
        *
        * Only the project's own modules: a reference into the standard library says nothing about
        * how this project is put together.
        */
      private def depend(loc: SourceLocation, namespace: List[String]): Unit = current.foreach { from =>
        if (isProjectSource(loc.source)) {
          edges += ((moduleOf(from.sym), namespace.mkString(".")))
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
          case Expr.FixpointConstraintSet(cs, _, _) =>
            // Flix embeds Datalog in the language, so how much of a project is solved declaratively
            // rather than written out is a question only this compiler can answer. A constraint
            // with no body is a fact; one with a body is a rule.
            val (rules, facts) = cs.partition(_.body.nonEmpty)
            val (r, f) = datalog.getOrElse(name, (0, 0))
            datalog.update(name, (r + rules.length, f + facts.length))
          case Expr.LocalDef(_, bnd, fparams, body, _, _, _, _) =>
            // A local definition is a function too, and an invisible one: it is not in `root.defs`,
            // so without this a project's widest and longest function can be one nobody counted.
            // Its own span is the body's, not the whole `let`-in expression, which runs to the end
            // of the enclosing function and would make every local definition look enormous.
            locals.update(name, (bnd.sym.text, declaredParameters(fparams), body.loc) :: locals.getOrElse(name, Nil))
          case Expr.Binary(SemanticOp.BoolOp.And | SemanticOp.BoolOp.Or, _, _, _, _, _) =>
            booleans.update(name, booleans.getOrElse(name, 0) + 1)
          case _ => ()
        }
      }
    }

    Visitor.visitRoot(root, consumer, Everything)

    Analysis(
      nesting = branches.map { case (name, locs) => name -> deepestChain(locs) }.toMap,
      branches = branches.toMap,
      locals = locals.toMap,
      datalog = datalog.toMap,
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
    * Limits a project asks to be held to.
    *
    * Empty by default: a report describes, and only says a project has failed when someone has
    * said what failing means. A limit on a *judgement* is worth being wary of -- a cap on function
    * length is met by splitting a function at the line before the cap, which improves nothing --
    * so these are supplied deliberately rather than defaulted to something round.
    */
  case class Thresholds(maxLines: Option[Int] = None,
                        maxParameters: Option[Int] = None,
                        maxNesting: Option[Int] = None,
                        maxComplexity: Option[Int] = None,
                        minDocCoverage: Option[Double] = None) {

    /** Whether anything at all was asked for. */
    def isEmpty: Boolean =
      maxLines.isEmpty && maxParameters.isEmpty && maxNesting.isEmpty && maxComplexity.isEmpty && minDocCoverage.isEmpty
  }

  /**
    * The limits `--smells` applies when nobody has said what failing means.
    *
    * Round numbers chosen to be defensible rather than derived: 40 lines and 5 parameters are
    * where a function stops fitting on a screen or in a signature, and 15 is where nesting and
    * conditions stop being followable in one reading. They are a starting point for a conversation
    * in a project, not a standard -- which is why they are only applied when asked for.
    */
  val SmellThresholds: Thresholds = Thresholds(
    maxLines = Some(40),
    maxParameters = Some(5),
    maxNesting = Some(4),
    maxComplexity = Some(15)
  )

  /**
    * Something measured that exceeded what was asked for.
    *
    * The numbers are numbers, and the category is a fixed word rather than a sentence, because
    * what reads this is usually a program: a CI script comparing magnitudes, or an editor placing
    * a marker, neither of which should have to parse prose or convert a string to compare it.
    *
    * @param category one of `length`, `parameters`, `nesting`, `complexity`, `docCoverage`, `orphan`.
    */
  case class Violation(category: String, subject: String, file: String, line: Int, actual: Double, limit: Double) {

    /** Where it is, as a person writes it. Empty when nothing locates it. */
    def where: String = if (file.isEmpty) "" else s"$file:$line"

    /** What the category is called in prose. */
    def measure: String = category match {
      case "docCoverage" => "doc coverage"
      case "orphan" => "no module depends on it"
      case other => other
    }

    /** The measurement, written as its kind is written. */
    def actualText: String = quantity(actual)

    /** The limit, written as its kind is written. */
    def limitText: String = quantity(limit)

    private def quantity(value: Double): String = category match {
      case "docCoverage" => f"${value * 100}%.1f%%"
      case "orphan" => s"${value.toInt} dependents"
      case _ => value.toInt.toString
    }
  }

  /**
    * Returns everything in `report` that exceeds `thresholds`.
    *
    * Each violation names what exceeded, by how much, and where. A gate that reports only a count
    * cannot be acted on, and one that reports only the first hides how much work is left.
    */
  def violations(report: Report, thresholds: Thresholds): List[Violation] = {
    val perDefinition = report.functions.flatMap { d =>
      // A function with no branching at all is a table, not logic: a hundred lines of record
      // literal are a hundred lines of data, and splitting them in two makes nothing easier to
      // read. Length is charged against code that does something.
      val isData = d.cognitive == 0 && d.nesting == 0
      List(
        thresholds.maxLines.filterNot(_ => isData).filter(d.lines > _).map(l => Violation("length", d.name, d.file, d.line, d.lines, l)),
        // The widest list anywhere inside, so that a loop carrying eight accumulators is not
        // excused by a two-parameter signature.
        thresholds.maxParameters.filter(d.widestParameterList > _).map(l => Violation("parameters", d.name, d.file, d.line, d.widestParameterList, l)),
        thresholds.maxNesting.filter(d.nesting > _).map(l => Violation("nesting", d.name, d.file, d.line, d.nesting, l)),
        thresholds.maxComplexity.filter(d.cognitive > _).map(l => Violation("complexity", d.name, d.file, d.line, d.cognitive, l))
      ).flatten
    }

    // A module of tests is depended upon by the test runner, not by other modules, so having no
    // dependents is what it is for. Decided from the annotation the compiler recorded rather than
    // from where the file sits.
    val testOnly = report.defs.groupBy(_.module).collect {
      case (module, ds) if ds.nonEmpty && ds.forall(_.isTest) => module
    }.toSet

    val orphans = report.modules
      .filter(m => m.fanIn == 0 && m.fanOut > 0 && m.name != "(top level)" && !testOnly.contains(m.name))
      .map { m =>
        // Located at the first definition it declares, so that an editor can put a marker on the
        // module rather than leaving the one smell that points nowhere.
        val at = report.defs.filter(_.module == m.name).minByOption(_.line)
        Violation("orphan", m.name, at.map(_.file).getOrElse(""), at.map(_.line).getOrElse(1), 0, 1)
      }

    val coverage = thresholds.minDocCoverage
      .filter(_ > report.docCoverage)
      .map(l => Violation("docCoverage", "public API", "", 0, report.docCoverage, l))

    (perDefinition ++ orphans.filter(_ => thresholds.maxLines.isDefined) ++ coverage).sortBy(v => (v.category, v.subject))
  }

  /**
    * Renders violations for reading.
    */
  def formatViolations(vs: List[Violation], f: Formatter): String = {
    if (vs.isEmpty) return ""
    val sb = new StringBuilder
    sb.append("\n" + f.red(s"${vs.length} over the limit") + "\n")
    vs.foreach { v =>
      val where = if (v.where.isEmpty) "" else s"  ${f.cyan(v.where)}"
      sb.append(s"    ${f.blue(v.subject)} -- ${v.measure} ${v.actualText}, limit ${v.limitText}$where\n")
    }
    sb.toString
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
  def render(report: Report, format: Format, f: Formatter, smells: List[Violation] = Nil): String = format match {
    case Format.Text => text(report, f)
    case Format.Json => json(report, smells)
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
    val header = "name,module,file,line,lines,parameters,returnWidth,traitConstraints,datalogRules,datalogFacts,localDefs,maxLocalParameters,nesting,cognitive,public,test,documented,pure,effects"
    val rows = report.defs.map { d =>
      List(
        d.name,
        d.module,
        d.file,
        d.line.toString,
        d.lines.toString,
        d.parameters.toString,
        d.returnWidth.toString,
        d.traitConstraints.toString,
        d.datalogRules.toString,
        d.datalogFacts.toString,
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
  private def json(report: Report, smells: List[Violation]): String = {
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
        ("codeLinesPerTest" -> report.codeLinesPerTest) ~
        ("effectBudget" -> effectBudget(report.publicApi).map { case (e, n) => ("effect" -> e) ~ ("definitions" -> n) })

    val definitions: JValue = report.defs.map { d =>
      ("name" -> d.name) ~
        ("module" -> d.module) ~
        ("file" -> d.file) ~
        ("line" -> d.line) ~
        ("lines" -> d.lines) ~
        ("parameters" -> d.parameters) ~
        ("returnWidth" -> d.returnWidth) ~
        ("traitConstraints" -> d.traitConstraints) ~
        ("datalogRules" -> d.datalogRules) ~
        ("datalogFacts" -> d.datalogFacts) ~
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

    val locals: JValue = report.locals.map { l =>
      ("name" -> l.name) ~
        ("owner" -> l.owner) ~
        ("file" -> l.file) ~
        ("line" -> l.line) ~
        ("lines" -> l.lines) ~
        ("parameters" -> l.parameters) ~
        ("nesting" -> l.nesting) ~
        ("cognitive" -> l.cognitive)
    }

    // Emitted so that whatever reads the report reads the verdict too, rather than reimplementing
    // the thresholds and disagreeing with the exit code.
    val smellsJson: JValue = smells.map { v =>
      ("category" -> v.category) ~ ("subject" -> v.subject) ~
        ("file" -> v.file) ~ ("line" -> v.line) ~ ("where" -> v.where) ~
        ("actual" -> v.actual) ~ ("limit" -> v.limit)
    }

    pretty(JsonMethods.render(("summary" -> summary) ~ ("modules" -> modules) ~
      ("definitions" -> definitions) ~ ("localDefinitions" -> locals) ~ ("smells" -> smellsJson)))
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
      effectBudget(report.publicApi).foreach { case (e, n) => sb.append(s"| effect `$e` | $n |\n") }
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
      longest.foreach(d => sb.append(s"| `${d.name}` | ${d.lines} | ${d.file}:${d.line} |\n"))
    }

    val nested = report.functions.filter(_.nesting > 1).sortBy(-_.nesting).take(FindingsShown)
    if (nested.nonEmpty) {
      sb.append("\n## Most deeply nested\n\n| function | levels | at |\n| --- | --- | --- |\n")
      nested.foreach(d => sb.append(s"| `${d.name}` | ${d.nesting} | ${d.file}:${d.line} |\n"))
    }

    val undocumented = report.publicApi.filterNot(_.hasDoc).sortBy(_.name).take(FindingsShown)
    if (undocumented.nonEmpty) {
      sb.append("\n## Undocumented public functions\n\n| function | at |\n| --- | --- |\n")
      undocumented.foreach(d => sb.append(s"| `${d.name}` | ${d.file}:${d.line} |\n"))
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
      val budget = effectBudget(api)
      if (budget.nonEmpty) {
        sb.append(row("effects used", budget.map { case (e, n) => s"$e ($n)" }.mkString(", ")))
      }
      sb.append("\n")
    }

    val functions = report.functions
    if (functions.nonEmpty) {
      sb.append(f.bold("Findings") + "\n")
      sb.append(finding(f, "longest", functions.sortBy(-_.lines).take(FindingsShown), d => s"${d.lines} lines"))
      sb.append(finding(f, "most deeply nested", functions.filter(_.nesting > 1).sortBy(-_.nesting).take(FindingsShown), d => s"${d.nesting} levels"))
      sb.append(finding(f, "hardest to follow", functions.filter(_.cognitive > 4).sortBy(-_.cognitive).take(FindingsShown), d => s"cognitive ${d.cognitive}"))
      sb.append(finding(f, "widest returned shape", functions.filter(_.returnWidth > 5).sortBy(-_.returnWidth).take(FindingsShown), d => s"${d.returnWidth} fields returned"))
      sb.append(finding(f, "widest parameter lists", functions.filter(_.widestParameterList > 3).sortBy(-_.widestParameterList).take(FindingsShown),
        d => if (d.maxLocalParameters > d.parameters) s"${d.maxLocalParameters} parameters, in a local definition" else s"${d.parameters} parameters"))
      sb.append(finding(f, "undocumented public", api.filterNot(_.hasDoc).sortBy(_.name).take(FindingsShown), _ => "no doc comment"))
    }

    val wideLocals = report.locals.filter(l => l.lines > 10 || l.parameters > 3).sortBy(-_.lines).take(FindingsShown)
    if (wideLocals.nonEmpty) {
      sb.append("\n" + f.bold("Local definitions") + "\n")
      wideLocals.foreach { l =>
        sb.append(s"    ${f.blue(l.name)} in ${l.owner} -- ${l.lines} lines, ${l.parameters} parameters, nesting ${l.nesting}  ${f.cyan(s"${l.file}:${l.line}")}\n")
      }
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
      sb.append(s"    ${f.blue(d.name)} -- ${measure(d)}  ${f.cyan(s"${d.file}:${d.line}")}\n")
    }
    sb.toString
  }
}
