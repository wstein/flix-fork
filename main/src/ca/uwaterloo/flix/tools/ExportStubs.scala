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

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.{AvailableClasses, Input}
import ca.uwaterloo.flix.language.ast.{ChangeSet, Name, SourceLocation, SyntaxTree, WeededAst}
import ca.uwaterloo.flix.language.phase.jvm.{BackendObjType, BackendType, ExportSignature, JvmName}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Reader, Weeder2}
import ca.uwaterloo.flix.util.Validation

import java.nio.file.{Files, Path}

/**
  * Derives the Java face of a Flix project's `@Export`ed defs without compiling it.
  *
  * This exists to break a cycle that has no valid build order. A Java class may call an exported
  * Flix def, and a Flix def may call a method on that same Java class; the first needs Flix codegen
  * to have run and the second needs Java class files to exist. Giving `javac` a *stub* facade to
  * compile against turns the cycle into a sequence: stubs, then Java, then Flix against the real
  * Java classes, then Java again against the real facade. `docs/JOINT-COMPILATION.md` is the full
  * argument.
  *
  * The stubs are compile-only scaffolding and must never reach a runtime classpath. Every generated
  * body throws, so a stub that leaks into one fails loudly at the first call rather than quietly
  * returning nothing.
  *
  * ==Why this runs the front end itself==
  *
  * There is no entry point that stops after weeding. `Flix.check` runs the whole pipeline, and the
  * cached parse and weeded roots it exposes are populated only when the *resolver* succeeded -- so
  * in the one situation this code exists for, a Flix source naming a Java class that does not exist
  * yet, they are empty. The four phases below are all that is needed and none of them resolves
  * anything.
  *
  * ==Why an unrecognised type is refused, not guessed==
  *
  * `WeededAst` has no case for a Java type: `ArrayList` and `Option` are both `Type.Ambiguous`, and
  * telling them apart is exactly the job of the resolver that cannot run here. So a name is
  * accounted for only when it is a builtin or appears in an enclosing `import`, and anything else
  * is reported rather than guessed at.
  *
  * That asymmetry is deliberate. A missing stub makes the build fail with the name of the def it
  * could not describe. A *wrong* stub compiles, and the mismatch surfaces as a `NoSuchMethodError`
  * at run time, in a caller that did nothing wrong.
  */
object ExportStubs {

  /** A generated facade: one Java class standing in for one Flix module's exported defs. */
  case class Facade(name: JvmName, methods: List[Method])

  /**
    * One `public static` method on a facade.
    *
    * `result` is `None` for a def returning `Unit`, which is a `void` method rather than a method
    * returning some unit value.
    */
  case class Method(name: String, result: Option[ExportSignature], params: List[ExportSignature])

  /** A def that could not be described, and why. */
  case class Unsupported(name: String, reason: String, loc: SourceLocation)

  /** Opens every generated file, so a build tool can delete its own stale output and nothing else. */
  val Marker: String = "// flix-stub: generated, compile-only. Do not edit, do not ship."

  /**
    * Returns a facade for each module with exported defs, and one entry per def that could not be
    * described.
    *
    * Parse errors are not reported here. A file that does not parse contributes no exported defs,
    * and the real compile that follows reports it properly with source locations -- duplicating
    * that would give the user the same error twice, worded worse.
    */
  def run(inputs: List[Input])(implicit flix: Flix): (List[Facade], List[Unsupported]) = {
    weed(inputs) match {
      case None => (Nil, Nil)
      case Some(root) =>
        val found = root.units.values.flatMap(unit => visitDecls(unit.decls, Nil, imports(unit.usesAndImports)))
        val (methods, unsupported) = partition(found.toList)
        val facades = methods
          .groupBy(_._1)
          .map { case (ns, ms) => Facade(JvmName.facadeOfNamespace(ns), ms.map(_._2)) }
          .toList
          .sortBy(_.name.toBinaryName)
        (facades, unsupported)
    }
  }

  /**
    * Writes every facade under `destination`, replacing whatever was there.
    *
    * Replacing rather than merging is what makes a deleted export a build error. A stub left
    * behind for a def that no longer exists lets Java keep compiling against it, and the mistake
    * then arrives as a `NoSuchMethodError` in whoever runs it.
    */
  def write(facades: List[Facade], destination: Path): Unit = {
    if (Files.isDirectory(destination)) deleteRecursively(destination)
    Files.createDirectories(destination)
    for (facade <- facades) {
      val file = facade.name.pkg.foldLeft(destination)(_.resolve(_)).resolve(s"${facade.name.name}.java")
      Files.createDirectories(file.getParent)
      Files.writeString(file, javaSource(facade))
    }
  }

  /** Deletes `path` and everything below it. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
    ()
  }

  /** Returns `facade` as Java source. */
  def javaSource(facade: Facade): String = {
    val pkg = if (facade.name.pkg.isEmpty) Nil else List(s"package ${facade.name.pkg.mkString(".")};", "")
    val body = facade.methods.sortBy(_.name).flatMap(javaMethod)
    // `final` with a private constructor: a facade holds only static methods, and letting a caller
    // extend or instantiate the stub would let it compile code the real facade rejects.
    val lines = List(Marker) ++ pkg ++ List(
      s"public final class ${facade.name.name} {",
      "",
      s"    private ${facade.name.name}() {",
      "    }",
      ""
    ) ++ body ++ List("}")
    lines.mkString("", "\n", "\n")
  }

  /** Returns `method` as the lines of a Java method declaration. */
  private def javaMethod(method: Method): List[String] = {
    val result = method.result.map(_.sourceName).getOrElse("void")
    val params = method.params.zipWithIndex.map { case (p, i) => s"${p.sourceName} arg$i" }.mkString(", ")
    // The body is unreachable by construction, but it has to satisfy definite assignment, and
    // throwing says what has gone wrong if a stub is ever on a runtime classpath.
    List(
      s"    public static $result ${method.name}($params) {",
      """        throw new UnsupportedOperationException("Flix export stub: not for runtime use.");""",
      "    }",
      ""
    )
  }

  /**
    * Runs the front end up to and including the weeder.
    *
    * `AvailableClasses.empty` is correct rather than merely convenient: nothing here resolves a
    * Java name, and seeding the index would suggest otherwise.
    */
  private def weed(inputs: List[Input])(implicit flix: Flix): Option[WeededAst.Root] = flix.withThreadPool {
    val (read, _) = Reader.run(inputs, AvailableClasses.empty)
    val (tokens, _) = Lexer.run(read, Map.empty, ChangeSet.Everything)
    val (tree, _) = Parser2.run(tokens, SyntaxTree.empty, ChangeSet.Everything)
    val (result, _) = Weeder2.run(read, None, tree, WeededAst.empty, ChangeSet.Everything)
    result match {
      case Validation.Success(root) => Some(root)
      case Validation.Failure(_) => None
    }
  }

  /** Returns each exported def paired with the namespace it belongs to, or why it was refused. */
  private def visitDecls(decls: List[WeededAst.Declaration], ns: List[String], imps: Map[String, String]): List[Either[Unsupported, (List[String], Method)]] =
    decls.flatMap {
      case WeededAst.Declaration.Mod(_, _, _, qname, usesAndImports, inner, _) =>
        // Modules nest and each name may itself be dotted, so the namespace accumulates the same
        // way `Namer.visitMod` accumulates it. Diverging here would put the facade in the wrong
        // package, which is the one mistake a caller cannot work around.
        val nested = ns ++ qname.namespace.idents.map(_.name) :+ qname.ident.name
        visitDecls(inner, nested, imps ++ imports(usesAndImports))
      case defn: WeededAst.Declaration.Def if defn.ann.isExport =>
        List(visitDef(defn, ns, imps))
      case _ => Nil
    }

  /** Returns the facade method for `defn`, or why it cannot be described. */
  private def visitDef(defn: WeededAst.Declaration.Def, ns: List[String], imps: Map[String, String]): Either[Unsupported, (List[String], Method)] = {
    def refuse(what: String) = Left(Unsupported(defn.ident.name, what, defn.loc))

    // A def taking no arguments is written with a single `Unit` parameter and compiles to a
    // nullary Java method, so the parameter is dropped rather than described.
    val declared = defn.fparams.flatMap(_.tpe)
    val params = if (declared.forall(isUnit)) Nil else declared

    if (declared.lengthCompare(defn.fparams.length) != 0)
      refuse("a parameter has no declared type")
    else
      traverse(params)(signatureOf(_, imps)) match {
        case None => refuse("a parameter type cannot be described in Java")
        case Some(ps) =>
          val result = if (isUnit(defn.tpe)) Some(None) else signatureOf(defn.tpe, imps).map(Some(_))
          result match {
            case None => refuse("the return type cannot be described in Java")
            case Some(r) => Right((ns, Method(defn.ident.name, r, ps)))
          }
      }
  }

  /**
    * Returns how `tpe` crosses the boundary, or `None` if it cannot be described.
    *
    * The cases mirror `EntryPoints.isExportableType` and `ExportPlan`, which decide the same
    * question over resolved types. They are two readings of one boundary and are asserted to agree
    * in `TestExportStubs`; that test is what makes this safe to rely on, because nothing in the
    * types stops them drifting.
    */
  private def signatureOf(tpe: WeededAst.Type, imps: Map[String, String]): Option[ExportSignature] = tpe match {
    // An unconstrained type variable is exported as `Object`; a constrained one is rejected
    // outright by `EntryPoints`, so it never reaches a stub.
    case WeededAst.Type.Var(_, _) => Some(ExportSignature.Exact(BackendType.Object))

    case WeededAst.Type.Ambiguous(qname, _) => named(qname, Nil, imps)

    case WeededAst.Type.Apply(_, _, _) =>
      val (head, args) = flatten(tpe)
      head match {
        case WeededAst.Type.Ambiguous(qname, _) => named(qname, args, imps)
        case _ => None
      }

    case _ => None
  }

  /** Returns how the type named `qname` and applied to `args` crosses the boundary. */
  private def named(qname: Name.QName, args: List[WeededAst.Type], imps: Map[String, String]): Option[ExportSignature] = {
    def targs = traverse(args)(signatureOf(_, imps)).map(_.map(asTypeArgument))

    (simpleName(qname, imps), args) match {
      case (Some(name), Nil) => builtin(name).orElse(imported(name, imps).map(c => ExportSignature.Exact(c.toTpe)))
      // The two converted containers. Their element is a type argument, so it appears boxed --
      // an `Option[Int32]` is an `Optional<Integer>`, never an `Optional<int>`.
      case (Some("Option"), List(_)) => targs.map(ts => ExportSignature.Applied(JvmName.Optional, ts))
      case (Some("List"), List(_)) => targs.map(ts => ExportSignature.Applied(JvmName.JavaList, ts))
      case (Some(name), _) =>
        for (clazz <- imported(name, imps); ts <- targs) yield ExportSignature.Applied(clazz, ts)
      case (None, _) => None
    }
  }

  /**
    * Returns the name `qname` denotes, if this can be established without resolving it.
    *
    * An unqualified name is taken as written. A qualified one is refused: at this stage
    * `Acme.Greeter.T` and `java.util.ArrayList` are the same shape, and the import table is the
    * only evidence available about which is a Java class.
    */
  private def simpleName(qname: Name.QName, imps: Map[String, String]): Option[String] =
    if (qname.namespace.isEmpty) Some(qname.ident.name)
    else if (imps.contains(qname.toString)) Some(qname.toString)
    else None

  /** Returns the Flix type named `name`, when it is one with a fixed Java counterpart. */
  private def builtin(name: String): Option[ExportSignature] = name match {
    case "Bool" => Some(ExportSignature.Exact(BackendType.Bool))
    case "Char" => Some(ExportSignature.Exact(BackendType.Char))
    case "Int8" => Some(ExportSignature.Exact(BackendType.Int8))
    case "Int16" => Some(ExportSignature.Exact(BackendType.Int16))
    case "Int32" => Some(ExportSignature.Exact(BackendType.Int32))
    case "Int64" => Some(ExportSignature.Exact(BackendType.Int64))
    case "Float32" => Some(ExportSignature.Exact(BackendType.Float32))
    case "Float64" => Some(ExportSignature.Exact(BackendType.Float64))
    case "String" => Some(ExportSignature.Exact(BackendType.String))
    case "BigInt" => Some(ExportSignature.Exact(BackendObjType.Native(JvmName.BigInteger).toTpe))
    case "BigDecimal" => Some(ExportSignature.Exact(BackendObjType.Native(JvmName.BigDecimal).toTpe))
    case "Regex" => Some(ExportSignature.Exact(BackendObjType.Native(JvmName.Regex).toTpe))
    case _ => None
  }

  /** Returns the Java class `name` was imported as. */
  private def imported(name: String, imps: Map[String, String]): Option[JvmName] =
    imps.get(name).map { fqn =>
      val segments = fqn.split('.').toList
      JvmName(segments.init, segments.last)
    }

  /** Returns the alias-to-class table an `import` list establishes. */
  private def imports(usesAndImports: List[WeededAst.UseOrImport]): Map[String, String] =
    usesAndImports.collect {
      case WeededAst.UseOrImport.Import(name, alias, _) => alias.name -> name.fqn.mkString(".")
    }.toMap

  /** Returns `sig` as it appears in a type argument position, where primitives must be boxed. */
  private def asTypeArgument(sig: ExportSignature): ExportSignature = sig match {
    case ExportSignature.Exact(BackendType.Bool) => ExportSignature.Boxed(BackendType.Bool, JvmName.Boolean)
    case ExportSignature.Exact(BackendType.Char) => ExportSignature.Boxed(BackendType.Char, JvmName.Character)
    case ExportSignature.Exact(BackendType.Int8) => ExportSignature.Boxed(BackendType.Int8, JvmName.Byte)
    case ExportSignature.Exact(BackendType.Int16) => ExportSignature.Boxed(BackendType.Int16, JvmName.Short)
    case ExportSignature.Exact(BackendType.Int32) => ExportSignature.Boxed(BackendType.Int32, JvmName.Integer)
    case ExportSignature.Exact(BackendType.Int64) => ExportSignature.Boxed(BackendType.Int64, JvmName.Long)
    case ExportSignature.Exact(BackendType.Float32) => ExportSignature.Boxed(BackendType.Float32, JvmName.Float)
    case ExportSignature.Exact(BackendType.Float64) => ExportSignature.Boxed(BackendType.Float64, JvmName.Double)
    case other => other
  }

  /** Returns `true` if `tpe` is the unit type. */
  private def isUnit(tpe: WeededAst.Type): Boolean = tpe match {
    case WeededAst.Type.Unit(_) => true
    case WeededAst.Type.Ambiguous(qname, _) => qname.namespace.isEmpty && qname.ident.name == "Unit"
    case _ => false
  }

  /** Returns the head of a type application together with its arguments, left to right. */
  private def flatten(tpe: WeededAst.Type): (WeededAst.Type, List[WeededAst.Type]) = tpe match {
    case WeededAst.Type.Apply(tpe1, tpe2, _) =>
      val (head, args) = flatten(tpe1)
      (head, args :+ tpe2)
    case other => (other, Nil)
  }

  /** Returns the results for every element of `xs`, or `None` if any of them has none. */
  private def traverse[A, B](xs: List[A])(f: A => Option[B]): Option[List[B]] =
    xs.foldRight(Option(List.empty[B])) {
      case (x, acc) => for (ys <- acc; y <- f(x)) yield y :: ys
    }

  /** Splits the described defs from the refused ones. */
  private def partition(xs: List[Either[Unsupported, (List[String], Method)]]): (List[(List[String], Method)], List[Unsupported]) =
    (xs.collect { case Right(x) => x }, xs.collect { case Left(x) => x })
}
