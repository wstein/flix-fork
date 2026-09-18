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
import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.language.phase.jvm.{ClassMaker, DebugScopes}
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
    /**
      * The expression typed, and — when it was asked for — the classes that would run it.
      *
      * `artifact` is absent when only typing was asked for, which is the cheaper question and the
      * one a watch asks first. It is a string rather than a structure because it crosses a debug
      * connection next, where every argument is built inside the debuggee one value at a time: one
      * string is one value to construct, and a map of byte arrays is thousands.
      */
    case class Ok(tpe: String, eff: String, artifact: Option[Artifact] = None) extends Answer

    case class Failed(diagnostics: List[String]) extends Answer

    case class Rejected(reason: String) extends Answer
  }

  /** The identity of a scope, as a debugger has it from a paused frame. */
  case class ScopeId(className: String, methodName: String)

  /**
    * Everything the debuggee needs in order to run the expression.
    *
    * `classes` holds only what the running program does not already have: the expression's own
    * class, and any specialisation the program never needed. Which those are is not guessed — the
    * build manifest lists exactly the class files the debuggee was started with, so the difference
    * is a set subtraction rather than a model of another process's loader.
    *
    * `parameters` is the order the entry method takes its arguments in, by the names the frame
    * holds them under. A debugger reads each from the frame and passes them in this order; getting
    * it wrong would pass an `Int32` where a `String` was expected, and the failure would surface
    * inside generated code.
    *
    * `valueField` is which field of the runtime's `Value` holds the answer. It carries one field per
    * erased type and no discriminator, so this is the compiler telling the reader where to look
    * rather than the reader guessing.
    */
  case class Artifact(
                       classes: List[(String, Array[Byte])],
                       entryClass: String,
                       entryMethod: String,
                       valueField: String,
                       parameters: List[String],
                     )

  /**
    * Types `expression` against the scope of `frame`, using the scope table under `projectRoot`.
    *
    * `root` is the server's own typed AST, and it is where every parameter type comes from. The
    * table only says which definition to look at.
    */
  def compile(frame: ScopeId, expression: String, policy: Policy, projectRoot: Path, root: Root): Answer =
    compile(frame, expression, policy, projectRoot, root, withArtifact = false)

  /**
    * As [[compile]], and when `withArtifact` is set, also the classes that would run the expression.
    *
    * Separate because the two questions cost differently. Typing is one compilation; producing an
    * artifact is a second one that also emits, and a watch asks the first on every step. A caller
    * asks for the artifact when the user has asked for a value rather than for a description.
    */
  def compile(frame: ScopeId, expression: String, policy: Policy, projectRoot: Path, root: Root, withArtifact: Boolean): Answer =
    compile(frame, expression, policy, projectRoot, root, withArtifact, launchedBuildId = None)

  /**
    * As [[compile]], tied to the build whose JVM the debugger actually paused in.
    *
    * A rebuild may replace every sidecar while that old JVM remains paused. In that state compiling
    * against the files on disk would create an artifact for a different program. A client therefore
    * sends the identity it captured from the manifest at launch, and this refuses the request unless
    * the current manifest still describes that exact build.
    */
  def compile(frame: ScopeId, expression: String, policy: Policy, projectRoot: Path, root: Root,
              withArtifact: Boolean, launchedBuildId: Option[String]): Answer = {
    launchedBuildId.foreach { launched =>
      currentBuildId(projectRoot) match {
        case Some(current) if current == launched => ()
        case Some(_) => return Answer.Rejected(
          "the debug files now describe a different build from the running program. " +
            "Stop the session and rebuild before evaluating expressions",
        )
        case None => return Answer.Rejected(
          "the running program has a build identity, but the current build manifest does not. " +
            "Rebuild with --Xdebug before evaluating expressions",
        )
      }
    }

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
    val sources = sourcesUnder(projectRoot)
    val sourcesDigest = sourcesDigestOf(projectRoot)
    val scopeId = s"${frame.className}#${frame.methodName}"
    DebugEvalSidecar.cached(
      projectRoot, sources, sourcesDigest, scopeId, expression, policy.toString, withArtifact,
    ) {
      DebugEvalSidecar.withCompiler(projectRoot, sources, sourcesDigest) { compiler =>
        implicit val flix: Flix = compiler
        val params = scope.methods(frame.methodName)
          .filter(p => mentions(expression, p.name))
          .map(p => s"${p.name}: ${p.tpe}")

        answerFor(params, expression, policy, projectRoot, withArtifact)
      }
    }
  }

  /**
    * What identifies the sources the debuggee was built from.
    *
    * `sourcesDigest`, and **not** `fingerprint`, which is the trap here: the manifest carries both,
    * the names suggest the opposite, and only the first changes when a source file changes.
    * Measured -- two builds of a program whose only definition was edited kept the same
    * `fingerprint` and differed in `sourcesDigest`. Keying a cache on the other one would answer a
    * watch from before a rebuild, handing the debuggee classes that call into code it no longer has.
    *
    * An absent manifest yields the empty string, which matches only other absent manifests: correct,
    * because there is then nothing recorded to have changed.
    */
  private def sourcesDigestOf(projectRoot: Path): String = {
    val manifest = projectRoot.resolve("build").resolve("development").resolve(BuildManifest)
    if (!Files.isRegularFile(manifest)) return ""
    val text = Files.readString(manifest)
    """"sourcesDigest"\s*:\s*"([^"]*)"""".r.findFirstMatchIn(text).map(_.group(1)).getOrElse("")
  }

  /** The identity stored by the current manifest, if both required halves are present. */
  private def currentBuildId(projectRoot: Path): Option[String] = {
    val manifest = projectRoot.resolve("build").resolve("development").resolve(BuildManifest)
    if (!Files.isRegularFile(manifest)) return None
    val text = Files.readString(manifest)
    def field(name: String): Option[String] =
      ("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").r
        .findFirstMatchIn(text).map(_.group(1))
    for {
      fingerprint <- field("fingerprint")
      sourcesDigest <- field("sourcesDigest")
    } yield ca.uwaterloo.flix.api.BuildManifest.debugBuildId(fingerprint, sourcesDigest)
  }

  /** Types the expression, and produces something runnable when one was asked for. */
  private def answerFor(
                         params: List[String],
                         expression: String,
                         policy: Policy,
                         projectRoot: Path,
                         withArtifact: Boolean,
                       )(implicit flix: Flix): Answer = {
    typeCheck(params, expression, policy) match {
      case Answer.Ok(tpe, eff, _) if withArtifact =>
        // Only now, with the type in hand. The wrapper that *runs* an expression has to declare what
        // it returns, and that is exactly what the first pass was asked to work out -- so the second
        // pass writes down the answer of the first.
        emit(params, expression, tpe, eff, projectRoot) match {
          case Left(reason) => Answer.Rejected(reason)
          case Right(artifact) => Answer.Ok(tpe, eff, Some(artifact))
        }
      case other => other
    }
  }

  /**
    * Compiles a wrapper that *returns* the expression, and collects what the debuggee lacks.
    *
    * ==Why a second compilation==
    *
    * The first wrapper binds the expression to a name and returns unit, because a definition's
    * return type has to be written down and the type was the question. With the answer in hand the
    * second wrapper declares it, so the generated method returns the value instead of discarding it.
    *
    * ==Why generation stays in memory==
    *
    * The project's build directory is the running program and must never be modified by a watch.
    * The compiler returns its generated class map directly; filtering therefore needs neither a
    * temporary output tree nor stale-file cleanup.
    *
    * ==Why only some classes are kept==
    *
    * The build manifest lists every class file the debuggee was started with. Anything this
    * compilation produced that is not in that list is new -- the expression's own class, and any
    * specialisation the program never needed -- and anything that *is* in it must be left alone, so
    * that a call resolves to the code the program is actually running.
    */
  private def emit(
                    params: List[String],
                    expression: String,
                    tpe: String,
                    eff: String,
                    projectRoot: Path,
                  )(implicit flix: Flix): Either[String, Artifact] = {
    val existing = productsOf(projectRoot)
    if (existing.isEmpty) {
      return Left(
        s"$BuildManifest lists no classes, so there is no way to tell which of the expression's " +
          "classes the running program already has",
      )
    }

    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    flix.addSource(Paths.get(WrapperFile), returningWrapper(params, expression, tpe, eff), sctx)

    // Checked and generated separately, rather than through `compile`, for one reason: nothing calls
    // the wrapper, so tree shaking removes it before code generation and the build produces every
    // class except the one that was asked for. Naming it an entry point is what "keep this" means to
    // the compiler, and the provider is the caller that knows to ask.
    val (checked, errors) = flix.check()
    val generated = checked match {
      case Some(root) if errors.isEmpty =>
        val wrapper = root.defs.keys.filter(_.text == WrapperName).toSet
        if (wrapper.isEmpty) {
          return Left("the wrapper did not survive type-checking, which is a defect in this provider")
        }
        Right(flix.codeGen(root.copy(entryPoints = root.entryPoints ++ wrapper)))
      case _ => Left(errors)
    }

    generated match {
      case Left(errors) =>
        // The first pass typed it, so a failure here is about the wrapper rather than the
        // expression -- most likely an effect that cannot be written in a signature.
        Left(s"the expression typed as `$tpe \\ $eff` but could not be compiled: ${errors.map(_.summary).mkString("; ")}")

      case Right(result) =>
        val produced = result.getClasses.values.toList.map { clazz =>
          ClassDescs.classFileNameOf(clazz.name) -> clazz.bytecode
        }
        val fresh = produced.filterNot { case (relative, _) => existing.contains(relative) }
        val classes = fresh.map { case (relative, bytes) => binaryNameOf(relative) -> bytes }
        classes.map(_._1).find(_.contains(WrapperName)) match {
          case None =>
            Left(
              "the expression compiled but produced no class of its own, which means the running " +
                "program already has every class it needs and this one too -- a name collision",
            )
          case Some(entry) =>
            Right(Artifact(classes, entry, ClassMaker.StaticApplyMethodName, valueFieldFor(tpe), params.map(nameOf)))
        }
    }
  }

  /**
    * The wrapper that returns the expression's value.
    *
    * Written only after the type is known, because a definition has to declare what it returns and
    * that was the question. The effect is declared the same way, and omitted when the expression is
    * pure -- an unused effect is an error in Flix, not a warning, so a blanket `\ IO` would fail on
    * every pure expression.
    */
  private def returningWrapper(params: List[String], expression: String, tpe: String, eff: String): String = {
    val effect = if (eff == "Pure") "" else s" \\ $eff"
    s"""def $WrapperName(${params.mkString(", ")}): $tpe$effect =
       |    $expression
       |""".stripMargin
  }

  /** `at: Option[String]` as `at`. */
  private def nameOf(param: String): String = param.takeWhile(_ != ':').trim

  /**
    * Which field of the runtime's `Value` holds a result of type `tpe`.
    *
    * `Value` carries one field per erased type and no discriminator, so a reader has to be told.
    * Everything that is not a primitive is an object, which is what erasure leaves.
    */
  private def valueFieldFor(tpe: String): String = tpe match {
    case "Bool" => "b"
    case "Char" => "c"
    case "Int8" => "i8"
    case "Int16" => "i16"
    case "Int32" => "i32"
    case "Int64" => "i64"
    case "Float32" => "f32"
    case "Float64" => "f64"
    case _ => "o"
  }

  /**
    * The class files the debuggee was started with, by their path under the class directory.
    *
    * From the build manifest rather than from the directory, because the directory is what a *new*
    * build would leave and the manifest is what *this* program was launched from. The two differ
    * exactly when someone has rebuilt while a session is running, which is the case that matters.
    */
  private def productsOf(projectRoot: Path): Set[String] = {
    val development = projectRoot.resolve("build").resolve("development")
    val manifest = development.resolve(BuildManifest)
    val fromManifest =
      if (!Files.isRegularFile(manifest)) Set.empty[String]
      else {
        val text = Files.readString(manifest)
        val products = """"products"\s*:\s*\[([^]]*)]""".r
        val entry = """"([^"]+)"""".r
        products.findFirstMatchIn(text)
          .map(m => entry.findAllMatchIn(m.group(1)).map(_.group(1)).toSet)
          .getOrElse(Set.empty)
      }
    // The class directory when there is no manifest to read -- a build made by the compiler API
    // rather than by the command line writes classes and no manifest. The manifest is preferred
    // because it says what the debuggee was *launched* with, and the directory only says what the
    // last build left; the two differ exactly when someone has rebuilt during a session, which is
    // the case worth being right about.
    if (fromManifest.nonEmpty) fromManifest
    else classFilesUnder(development.resolve("class")).map(_._1).toSet
  }

  /** Every class file under `dir`, by its path relative to it. */
  private def classFilesUnder(dir: Path): List[(String, Array[Byte])] = {
    if (!Files.isDirectory(dir)) return Nil
    val stream = Files.walk(dir)
    try {
      stream.toArray.toList.collect {
        case p: Path if Files.isRegularFile(p) && p.toString.endsWith(".class") =>
          dir.relativize(p).toString.replace('\\', '/') -> Files.readAllBytes(p)
      }
    } finally {
      stream.close()
    }
  }

  private def binaryNameOf(relative: String): String =
    relative.stripSuffix(".class").replace('/', '.')

  /** The build manifest, which names the classes the debuggee was launched with. */
  private val BuildManifest: String = "build.json"

  /**
    * Compiles the wrapper alongside the project's sources and reads the binding back.
    *
    * The project's own sources are compiled too, because the expression may mention anything they
    * declare -- a definition, an enum case, a trait instance. Compiling the wrapper alone would
    * reject every expression that is not built out of the standard library.
    */
  private def typeCheck(params: List[String], expression: String, policy: Policy)(implicit flix: Flix): Answer = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted

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
          case Policy.AllowEffects => Answer.Ok(tpe, eff, None)
          case Policy.Pure if isPure(exp) => Answer.Ok(tpe, eff, None)
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
