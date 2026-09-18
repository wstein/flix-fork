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
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.TypedAst.{Expr, Root}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.fmt.FormatType
import ca.uwaterloo.flix.language.phase.jvm.DebugScopes
import ca.uwaterloo.flix.util.Options

import java.nio.file.{Files, Path, Paths}

/**
  * Type-checks an expression against the scope a debugger is paused in.
  *
  * ==What this answers, and what it does not==
  *
  * Given a frame -- a generated class and a method -- and an expression a programmer typed into a
  * watch, this says what the expression *is*: its type, its effect, and any errors. It does not run
  * it. Producing something runnable is a separate step and a separate artifact; deciding whether an
  * expression may run at all is a question about its effect, and that question cannot be asked until
  * it has been typed. Compile first, then decide.
  *
  * ==Why a debugger cannot do this itself==
  *
  * A frame's names are in the class file's `LocalVariableTable`, but their types are erased there,
  * and the scope table a `--Xdebug` build writes cannot supply them either: by the time it is
  * derived, monomorphisation has replaced `Option[String]` with a specialised symbol
  * (`Option$AxNJjn6TiM2`) whose arguments are gone. Measured, not assumed.
  *
  * What the table *can* do is say which definition a class came from. That one string is the join:
  * the source type of every parameter is still in the typed AST this server already holds, under
  * that symbol. So the table identifies the scope and the compiler supplies its types -- which is
  * also the only arrangement in which the answer is the *compiler's*, rather than an IDE's guess at
  * what the compiler would have said.
  *
  * ==How the expression is typed==
  *
  * By compiling a definition that has the scope's parameters and the expression as a binding:
  *
  * {{{
  * def __flixDebugEval__(at: Option[String], xs: List[Int32]): Unit \ IO =
  *     let _flixDebugResult = <expression>;
  *     ()
  * }}}
  *
  * A `let` rather than the definition's return type, because the return type would have to be
  * written and is exactly what is being asked. `\ IO` on the wrapper so that an effectful expression
  * still *compiles* -- its effect is then read off the typed binding and judged against the policy,
  * rather than being turned into a type error that says nothing about effects. A name starting with
  * `_` so that an unused binding is not reported.
  *
  * ==What is deliberately absent==
  *
  * No artifact, no execution, no caching. A fresh [[Flix]] compiles the project's sources plus the
  * wrapper on every request, which is correct and slow; the persistent instance is a later step, and
  * the expensive thing here is compilation rather than anything this adds.
  */
object DebugEvalProvider {

  /** What an evaluation is allowed to do, decided by the caller and enforced here. */
  sealed trait Policy

  object Policy {
    /** Only an expression the compiler proves pure. The default, and what a watch may run. */
    case object Pure extends Policy

    /** Anything that type-checks. The caller has taken responsibility for running it. */
    case object AllowEffects extends Policy

    def parse(s: String): Option[Policy] = s match {
      case "pure" => Some(Pure)
      case "allowEffects" => Some(AllowEffects)
      case _ => None
    }
  }

  /**
    * The answer.
    *
    * `Rejected` is not `Failed`: a rejection is this server declining to answer -- no debug build,
    * a frame it cannot place, a policy the expression does not satisfy -- and names what would have
    * to change. `Failed` carries the compiler's own diagnostics about the expression itself.
    */
  sealed trait Answer

  object Answer {
    case class Ok(tpe: String, eff: String) extends Answer

    case class Failed(diagnostics: List[String]) extends Answer

    case class Rejected(reason: String) extends Answer
  }

  /** The identity of a scope, as a debugger has it from a paused frame. */
  case class ScopeId(className: String, methodName: String)

  /**
    * Types `expression` against the scope of `frame`, using the scope table under `projectRoot`.
    *
    * `root` is the server's own typed AST, and it is where every parameter type comes from. The
    * table only says which definition to look at.
    */
  def compile(frame: ScopeId, expression: String, policy: Policy, projectRoot: Path, root: Root): Answer = {
    val table = readTable(projectRoot) match {
      case Some(t) => t
      case None => return Answer.Rejected(
        s"no ${DebugScopes.FileName} under $projectRoot: the program must be built with --Xdebug " +
          "before a frame's names can be typed",
      )
    }

    val internalClassName = frame.className.replace('.', '/')
    val scope = table.get(frame.className).orElse(table.get(internalClassName)) match {
      case Some(s) => s
      case None => return Answer.Rejected(
        s"the debug build records no scope for ${frame.className}",
      )
    }

    if (!scope.methods.contains(frame.methodName)) {
      return Answer.Rejected(
        s"${frame.className} has no ${frame.methodName}; the build recorded ${scope.methods.keys.mkString(", ")}",
      )
    }

    // The debugger-visible bindings the expression mentions, in deterministic sidecar order.
    //
    // Only the ones mentioned, because Flix's redundancy check makes an unused parameter an *error*
    // rather than a warning: a wrapper carrying the whole scope would fail on every expression that
    // did not mention all of it, with a diagnostic about the wrapper. Mentioning is judged by word,
    // which can over-include (a name inside a string literal) and never under-includes; an
    // over-included parameter is reported as unused, which says exactly that.
    //
    // Not restricted to the scope table's names, and that is worth stating rather than guarding
    // against. The table and the definition agree by construction: the lookup above has already
    // rejected every frame whose definition is not this one -- a capture belongs to a lifted lambda
    // whose symbol is not in the snapshot, and a specialised copy carries a hash the snapshot does
    // not have. A filter here would be a second statement of that, and one no fixture could reach:
    // measured by removing it, whereupon every test still passed.
    implicit val flix: Flix = new Flix().setOptions(Options.DefaultTest)
    val params = scope.methods(frame.methodName)
      .filter(p => mentions(expression, p.name))
      .map(p => s"${p.name}: ${p.tpe}")

    typeCheck(params, expression, policy, projectRoot)
  }

  /**
    * Compiles the wrapper alongside the project's sources and reads the binding back.
    *
    * The project's own sources are compiled too, because the expression may mention anything they
    * declare -- a definition, an enum case, a trait instance. Compiling the wrapper alone would
    * reject every expression that is not built out of the standard library.
    */
  private def typeCheck(params: List[String], expression: String, policy: Policy, projectRoot: Path)(implicit flix: Flix): Answer = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted

    for (source <- sourcesUnder(projectRoot)) {
      flix.addFile(source, sctx)
    }
    flix.addSource(Paths.get(WrapperFile), wrapper(params, expression), sctx)

    val (rootOpt, errors) = flix.check()
    if (errors.nonEmpty) {
      return Answer.Failed(errors.map(_.summary))
    }

    rootOpt.flatMap(resultOf) match {
      case None => Answer.Rejected(
        "the expression compiled but produced no binding to read; this is a defect in the wrapper",
      )
      case Some(exp) =>
        val tpe = FormatType.formatType(exp.tpe)
        val eff = FormatType.formatType(exp.eff)
        policy match {
          case Policy.AllowEffects => Answer.Ok(tpe, eff)
          case Policy.Pure if isPure(exp) => Answer.Ok(tpe, eff)
          case Policy.Pure => Answer.Rejected(
            s"the expression has effect $eff, and the policy allows only a pure one. Running it " +
              "would run the debuggee's own code, which has to be asked for.",
          )
        }
    }
  }

  /** The expression bound in the wrapper, found by the name only the wrapper uses. */
  private def resultOf(root: Root): Option[Expr] = {
    def find(exp: Expr): Option[Expr] = exp match {
      case Expr.Let(bnd, exp1, exp2, _, _, _) if bnd.sym.text == ResultName => Some(exp1)
      case Expr.Let(_, exp1, exp2, _, _, _) => find(exp1).orElse(find(exp2))
      case _ => None
    }

    root.defs.collectFirst { case (sym, d) if sym.text == WrapperName => d }.flatMap(d => find(d.exp))
  }

  /**
    * Whether `exp` is pure, as the compiler proved it.
    *
    * Asked of the formatted effect rather than of the type structure on purpose: the question a
    * policy asks is the one a user would read off a hover, and any answer other than a plain `Pure`
    * -- an effect variable, a set, a region -- is something this policy is not able to reason about
    * and must therefore refuse.
    */
  private def isPure(exp: Expr)(implicit flix: Flix): Boolean =
    FormatType.formatType(exp.eff) == "Pure"

  /**
    * The definition the expression is type-checked inside.
    *
    * Two details are forced by Flix's redundancy checker, which reports these as errors rather than
    * as warnings -- so a wrapper that ignored either would fail on every request, with a diagnostic
    * about itself rather than about the expression:
    *
    *   - the binding is named with a leading `_`, so an expression whose value is not used again is
    *     not an unused binding;
    *   - the body ends in an IO call, so the `\ IO` on the signature is not an unused effect. The
    *     annotation is there so that an *effectful* expression still compiles; its own effect is
    *     read off the binding afterwards and judged against the policy, rather than being turned
    *     into a type error that says nothing about effects.
    *
    * Nothing here is ever run. The wrapper exists to be type-checked.
    */
  private def wrapper(params: List[String], expression: String): String =
    s"""def $WrapperName(${params.mkString(", ")}): Unit \\ IO =
       |    let $ResultName = $expression;
       |    println("")
       |""".stripMargin

  /**
    * Whether `expression` mentions `name` as a word.
    *
    * Deliberately crude: this decides which parameters to declare, and the cost of being wrong is a
    * diagnostic rather than a wrong answer. A name inside a string literal over-includes and the
    * compiler then reports it as unused; a name this misses would fail to resolve, which is why the
    * boundary test is on both sides rather than a plain `contains`.
    */
  private def mentions(expression: String, name: String): Boolean =
    s"(^|[^A-Za-z0-9_])${java.util.regex.Pattern.quote(name)}([^A-Za-z0-9_]|$$)".r
      .findFirstIn(expression).isDefined

  /** Every `.flix` file under `projectRoot`, excluding what a build wrote. */
  private def sourcesUnder(projectRoot: Path): List[Path] = {
    if (!Files.isDirectory(projectRoot)) return Nil
    val stream = Files.walk(projectRoot)
    try {
      stream.toArray.toList.collect {
        case p: Path if Files.isRegularFile(p) && p.toString.endsWith(".flix") &&
          !projectRoot.relativize(p).toString.startsWith("build") => p
      }
    } finally {
      stream.close()
    }
  }

  /**
    * The wrapper's name.
    *
    * Long and unlovely rather than decorated, because a Flix definition's name must begin with a
    * lowercase letter: the first version was `__flixDebugEval__` and every request came back as a
    * parse error about `'_'`, from the wrapper rather than from the expression.
    */
  private val WrapperName: String = "flixDebugEvalWrapper"

  private val ResultName: String = "_flixDebugResult"

  private val WrapperFile: String = "__flixDebugEval__.flix"

  /** The scope table, or `None` if this project has no debug build. */
  private def readTable(projectRoot: Path): Option[Map[String, DebugScopes.Scope]] = {
    val path = projectRoot.resolve("build").resolve("development").resolve(DebugScopes.FileName)
    if (!Files.isRegularFile(path)) None else Some(DebugScopes.read(Files.readString(path)))
  }
}
