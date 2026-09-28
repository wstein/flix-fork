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
import ca.uwaterloo.flix.language.ast.shared.Source
import ca.uwaterloo.flix.language.ast.{ChangeSet, Name, ReadAst, SourceLocation, SyntaxTree, WeededAst}
import ca.uwaterloo.flix.language.jvm.JavaClasses
import ca.uwaterloo.flix.language.phase.jvm.classes.{GenExportedEnum, GenExportedRecord, GenExportedTuple}
import ca.uwaterloo.flix.language.phase.jvm.{ExportSignature, Mangle}
import ca.uwaterloo.flix.language.phase.{Lexer, Parser2, Weeder2}
import ca.uwaterloo.flix.util.Result

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.{CD_boolean, CD_byte, CD_char, CD_double, CD_float, CD_int, CD_long, CD_short}

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

  /**
    * One generated Java type: a Flix module's facade of exported defs, or a type an exported
    * signature names that the compiler generates too, such as a tuple's record class.
    */
  case class Facade(name: ClassDesc, shape: Shape, methods: List[Method])

  /** What kind of Java type a [[Facade]] declares. */
  sealed trait Shape

  object Shape {
    /** A final class holding only static methods. */
    case object Plain extends Shape

    /** A `record` with these components, the stand-in of a generated `java.lang.Record`. */
    case class Record(components: List[(String, ExportSignature)]) extends Shape

    /** An `enum` with these constants, in case-ordinal order. */
    case class Enum(constants: List[String]) extends Shape

    /** A `sealed interface` with one nested `record` per case, in case-ordinal order. */
    case class Sealed(cases: List[(String, List[(String, ExportSignature)])]) extends Shape
  }

  /**
    * One `public static` method on a facade.
    */
  case class Method(name: String, result: ExportSignature, params: List[ExportSignature])

  /** A def that could not be described, and why. */
  case class Unsupported(name: String, reason: String, loc: SourceLocation)

  /** A refusal to replace an invalid stub output destination. */
  case class WriteError(path: Path, message: String)

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
  def run(inputs: List[Source])(implicit flix: Flix): (List[Facade], List[Unsupported]) = {
    weed(inputs) match {
      case None => (Nil, Nil)
      case Some(root) =>
        val enums = root.units.values.flatMap(unit => enumsOf(unit.decls, Nil, imports(unit.usesAndImports), uses(unit.usesAndImports))).toMap
        val found = root.units.values.flatMap(unit => visitDecls(unit.decls, Nil, imports(unit.usesAndImports), uses(unit.usesAndImports), enums))
        val (described0, unsupported0) = partition(found.toList)
        val (described, clashes) = refuseEnumMemberClashes(described0)
        val modules = described
          .groupBy(_.ns)
          .map { case (ns, ds) => Facade(Mangle.namespaceFacadeDesc(ns), Shape.Plain, ds.map(_.method)) }
          .toList
        (merge(modules ++ described.flatMap(_.types)), unsupported0 ++ clashes)
    }
  }

  /**
    * Writes every facade under `destination`, replacing whatever was there.
    *
    * Replacing rather than merging is what makes a deleted export a build error. A stub left
    * behind for a def that no longer exists lets Java keep compiling against it, and the mistake
    * then arrives as a `NoSuchMethodError` in whoever runs it.
    */
  def write(facades: List[Facade], destination: Path): Result[Unit, WriteError] = {
    if (Files.exists(destination) && !Files.isDirectory(destination))
      return Result.Err(WriteError(destination, s"Stub output path is not a directory: $destination"))

    if (Files.isDirectory(destination)) deleteRecursively(destination)
    Files.createDirectories(destination)
    for (facade <- facades) {
      val binary = binaryName(facade.name)
      val segments = binary.split('.').toList
      val file = segments.init.foldLeft(destination)(_.resolve(_)).resolve(s"${segments.last}.java")
      Files.createDirectories(file.getParent)
      Files.writeString(file, javaSource(facade))
    }
    Result.Ok(())
  }

  /**
    * Splits off the exports of an exported enum's companion module whose names the Java enum
    * already has, which the compiler refuses with `IllegalExportEnumMember`: the companion's
    * methods are declared on the enum itself.
    */
  private def refuseEnumMemberClashes(described: List[Described]): (List[Described], List[Unsupported]) = {
    val enumClasses = described.flatMap(_.types).collect {
      case Facade(name, Shape.Enum(_) | Shape.Sealed(_), _) => name
    }.toSet
    val (clashing, rest) = described.partition { d =>
      enumClasses.contains(Mangle.namespaceFacadeDesc(d.ns)) && GenExportedEnum.MemberNames.contains(d.method.name)
    }
    (rest, clashing.map(d => Unsupported(d.method.name, "the name is already a method of the Java enum of its module", d.loc)))
  }

  /**
    * Returns one facade per class name, sorted by name.
    *
    * Several defs may name the same generated type, and each describes it the same way, since the
    * name is derived from exactly what the declaration holds.
    */
  private def merge(facades: List[Facade]): List[Facade] =
    facades.groupBy(_.name).values.map(_.reduce { (f1, f2) =>
      val shape = if (f1.shape == Shape.Plain) f2.shape else f1.shape
      Facade(f1.name, shape, (f1.methods ++ f2.methods).distinct)
    }).toList.sortBy(f => binaryName(f.name))

  /** Deletes `path` and everything below it. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      val stream = Files.list(path)
      try stream.forEach(deleteRecursively) finally stream.close()
    }
    Files.deleteIfExists(path)
    ()
  }

  /** Returns `facade` as Java source. */
  def javaSource(facade: Facade): String = {
    val binary = binaryName(facade.name)
    val split = binary.lastIndexOf('.')
    val pkg = if (split < 0) Nil else List(s"package ${binary.substring(0, split)};", "")
    val className = if (split < 0) binary else binary.substring(split + 1)
    // Inside the class, a qualified name whose first segment is the class's own simple name means a
    // member of the class, and Java has no syntax that names the package instead. Such a class is
    // imported and spelled by its simple name, unless that simple name is itself taken.
    val named = (facade.methods.flatMap(m => m.result :: m.params) ++ (facade.shape match {
      case Shape.Record(components) => components.map(_._2)
      case Shape.Sealed(cases) => cases.flatMap(_._2.map(_._2))
      case _ => Nil
    })).flatMap(_.classes).distinct
    val shadowed = named.filter(c => ExportSignature.qualifiedName(c).startsWith(className + "."))
    val imported = shadowed.groupBy(simpleNameOf).collect {
      case (simple, List(clazz)) if simple != className => clazz
    }.toList.sortBy(ExportSignature.qualifiedName)
    val spell: ClassDesc => String = c => if (imported.contains(c)) simpleNameOf(c) else ExportSignature.qualifiedName(c)
    val imports = if (imported.isEmpty) Nil else imported.map(c => s"import ${ExportSignature.qualifiedName(c)};") :+ ""

    val body = facade.methods.sortBy(_.name).flatMap(javaMethod(_, spell))
    val header = facade.shape match {
      // `final` with a private constructor: a facade holds only static methods, and letting a
      // caller extend or instantiate the stub would let it compile code the real facade rejects.
      case Shape.Plain => List(
        s"public final class $className {",
        "",
        s"    private $className() {",
        "    }",
        ""
      )
      // A Java record declares the same canonical constructor, accessors, and `equals`,
      // `hashCode` and `toString` the generated class does.
      case Shape.Record(components) =>
        val params = components.map { case (name, sig) => s"${sig.sourceNameWith(spell)} $name" }.mkString(", ")
        List(s"public record $className($params) {", "")
      // javac gives an enum its private constructor, `values()` and `valueOf(String)` itself.
      case Shape.Enum(constants) =>
        List(s"public enum $className {") ++ constants.map(c => s"    $c,") ++ List("    ;", "")
      // javac infers the permitted subclasses of a sealed interface from its nested records.
      case Shape.Sealed(cases) =>
        List(s"public sealed interface $className {", "") ++ cases.flatMap { case (name, components) =>
          val params = components.map { case (component, sig) => s"${sig.sourceNameWith(spell)} $component" }.mkString(", ")
          List(s"    record $name($params) implements $className {", "    }", "")
        }
    }
    val lines = List(Marker) ++ pkg ++ imports ++ header ++ body ++ List("}")
    lines.mkString("", "\n", "\n")
  }

  /** Returns `method` as the lines of a Java method declaration. */
  private def javaMethod(method: Method, spell: ClassDesc => String): List[String] = {
    val result = method.result.sourceNameWith(spell)
    val params = method.params.zipWithIndex.map { case (p, i) => s"${p.sourceNameWith(spell)} arg$i" }.mkString(", ")
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
  private def weed(inputs: List[Source])(implicit flix: Flix): Option[WeededAst.Root] = flix.withThreadPool {
    val read = ReadAst.Root(inputs.map(_ -> ()).toMap)
    val (tokens, _) = Lexer.run(read, Map.empty, ChangeSet.Everything)
    val (tree, _) = Parser2.run(tokens, SyntaxTree.empty, ChangeSet.Everything)
    val (result, _) = Weeder2.run(read, None, tree, WeededAst.empty, ChangeSet.Everything)
    result
  }

  /**
    * An exported def that can be described: its facade method, the namespace of that facade, and
    * every generated type its signature names.
    */
  private case class Described(ns: List[String], method: Method, types: List[Facade], loc: SourceLocation)

  /**
    * What describing one def's signature needs to know, and collects the generated types it names.
    *
    * `ns` and `uses` are where the def is and which names a `use` brings into scope there; `enums`
    * is every enum declared in the sources, by its full name. `types` is one per def and kept only
    * if the whole def can be described: a refused def must not leave a stub for a type nothing else
    * names.
    */
  /** An enum declaration, with the imports and uses in scope where it is declared. */
  private case class EnumDecl(decl: WeededAst.Declaration.Enum, imps: Map[String, String], uses: Map[String, List[String]])

  private class DefContext(var ns: List[String], var uses: Map[String, List[String]], val enums: Map[List[String], EnumDecl]) {
    val types: scala.collection.mutable.ListBuffer[Facade] = scala.collection.mutable.ListBuffer.empty

    /** The enums whose fields are being described, by full name. */
    val visiting: scala.collection.mutable.Set[List[String]] = scala.collection.mutable.Set.empty

    /**
      * Returns `body`, evaluated as if inside the declaration of the enum `name`, whose case
      * fields name types relative to where the enum is declared, not where the def is.
      */
    def within[A](name: List[String], enumUses: Map[String, List[String]])(body: => A): A = {
      val (outerNs, outerUses) = (ns, uses)
      ns = name.init
      uses = enumUses
      visiting += name
      try body finally {
        ns = outerNs
        uses = outerUses
        visiting -= name
      }
    }
  }

  /** Returns each exported def paired with the namespace it belongs to, or why it was refused. */
  private def visitDecls(decls: List[WeededAst.Declaration], ns: List[String], imps: Map[String, String], uses: Map[String, List[String]], enums: Map[List[String], EnumDecl]): List[Either[Unsupported, Described]] =
    decls.flatMap {
      case WeededAst.Declaration.Mod(_, _, _, qname, usesAndImports, inner, _) =>
        // Modules nest and each name may itself be dotted, so the namespace accumulates the same
        // way `Namer.visitMod` accumulates it. Diverging here would put the facade in the wrong
        // package, which is the one mistake a caller cannot work around.
        val nested = ns ++ qname.namespace.idents.map(_.name) :+ qname.ident.name
        visitDecls(inner, nested, imps ++ imports(usesAndImports), uses ++ this.uses(usesAndImports), enums)
      case defn: WeededAst.Declaration.Def if defn.ann.isExport =>
        List(visitDef(defn, imps)(new DefContext(ns, uses, enums)))
      case _ => Nil
    }

  /** Returns the facade method for `defn`, or why it cannot be described. */
  private def visitDef(defn: WeededAst.Declaration.Def, imps: Map[String, String])(implicit ctx: DefContext): Either[Unsupported, Described] = {
    def refuse(what: String) = Left(Unsupported(defn.ident.name, what, defn.loc))

    val declared = defn.fparams.flatMap(_.tpe)

    if (declared.lengthCompare(defn.fparams.length) != 0)
      refuse("a parameter has no declared type")
    else
      traverse(declared)(parameterSignatureOf(_, imps)) match {
        case None => refuse("a parameter type cannot be described in Java")
        case Some(ps) =>
          resultSignatureOf(defn.tpe, imps) match {
            case None => refuse("the return type cannot be described in Java")
            case Some(r) => Right(Described(ctx.ns, Method(defn.ident.name, r, ps), ctx.types.toList, defn.loc))
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
  private def signatureOf(tpe: WeededAst.Type, imps: Map[String, String], pos: Position)(implicit ctx: DefContext): Option[ExportSignature] = tpe match {
    case WeededAst.Type.Var(_, _) => None

    case WeededAst.Type.Ambiguous(qname, _) => named(qname, Nil, imps, pos)

    case WeededAst.Type.Apply(_, _, _) =>
      val (head, args) = flatten(tpe)
      head match {
        case WeededAst.Type.Ambiguous(qname, _) => named(qname, args, imps, pos)
        case _ => None
      }

    case WeededAst.Type.Tuple(tpes, _) if pos != Position.Parameter =>
      traverse(tpes.toList)(signatureOf(_, imps, Position.Component)).map { sigs =>
        val desc = GenExportedTuple.desc(sigs.map(_.javaType))
        ctx.types += Facade(desc, Shape.Record(sigs.zipWithIndex.map { case (sig, i) => s"component$i" -> sig }), Nil)
        ExportSignature.Exact(desc)
      }

    case WeededAst.Type.Record(row, _) if pos != Position.Parameter =>
      for {
        // Sorted as `Canonicalization` sorts a row during monomorphisation, which is the order the
        // generated class takes its name and components from.
        fields <- peelRecordRow(row).map(_.sortBy(_._1))
        sigs <- traverse(fields) { case (label, fieldTpe) => signatureOf(fieldTpe, imps, Position.Component).map(label -> _) }
      } yield {
        val desc = GenExportedRecord.desc(sigs.map { case (label, sig) => label -> sig.javaType })
        ctx.types += Facade(desc, Shape.Record(sigs), Nil)
        ExportSignature.Exact(desc)
      }

    case _ => None
  }

  /** Returns the fields of a closed record row, or `None` if it still carries a row variable. */
  private def peelRecordRow(row: WeededAst.Type): Option[List[(String, WeededAst.Type)]] = row match {
    case WeededAst.Type.RecordRowEmpty(_) => Some(Nil)
    case WeededAst.Type.RecordRowExtend(label, tpe, rest, _) => peelRecordRow(rest).map((label.name, tpe) :: _)
    case _ => None
  }

  /**
    * Where a type sits in an exported signature, which decides what it may be.
    *
    * These mirror the positions `EntryPoints` and `ExportPlan` check and convert.
    */
  private sealed trait Position

  private object Position {
    /** A parameter, passed through unchanged: nothing is converted. */
    case object Parameter extends Position

    /** The result itself. */
    case object Result extends Position

    /** A converted container's type argument. */
    case object Argument extends Position

    /** A tuple element, record field, or enum case field: anything a result may be but a container. */
    case object Component extends Position
  }

  /** Parameters are passed through unchanged, so converted containers are not accepted here. */
  private def parameterSignatureOf(tpe: WeededAst.Type, imps: Map[String, String])(implicit ctx: DefContext): Option[ExportSignature] =
    signatureOf(tpe, imps, Position.Parameter)

  /** Results may use conversions implemented by the namespace shim. */
  private def resultSignatureOf(tpe: WeededAst.Type, imps: Map[String, String])(implicit ctx: DefContext): Option[ExportSignature] =
    signatureOf(tpe, imps, Position.Result)

  /** Returns how the type named `qname` and applied to `args` crosses the boundary. */
  private def named(qname: Name.QName, args: List[WeededAst.Type], imps: Map[String, String], pos: Position)(implicit ctx: DefContext): Option[ExportSignature] = {
    val allowConvertedResult = pos == Position.Result || pos == Position.Argument
    (simpleName(qname, imps), args) match {
      case (Some(name), Nil) => builtin(name).orElse(imported(name, imps).map(ExportSignature.Exact(_)))
      case (Some("Option"), List(element)) if allowConvertedResult =>
        typeArgumentSignatureOf(element, imps, Position.Argument).map(sig => ExportSignature.Applied(ClassDesc.ofInternalName("java/util/Optional"), List(sig)))
      case (Some("List"), List(element)) if allowConvertedResult =>
        typeArgumentSignatureOf(element, imps, Position.Argument).map(sig => ExportSignature.Applied(ClassDesc.ofInternalName("java/util/List"), List(sig)))
      case (Some("Vector"), List(element)) if allowConvertedResult =>
        typeArgumentSignatureOf(element, imps, Position.Argument).map(sig => ExportSignature.Applied(ClassDesc.ofInternalName("java/util/List"), List(sig)))
      case (Some("Chain"), List(element)) if allowConvertedResult =>
        typeArgumentSignatureOf(element, imps, Position.Argument).map(sig => ExportSignature.Applied(ClassDesc.ofInternalName("java/util/Collection"), List(sig)))
      case (Some("Set"), List(element)) if allowConvertedResult =>
        typeArgumentSignatureOf(element, imps, Position.Argument).map(sig => ExportSignature.Applied(ClassDesc.ofInternalName("java/util/Set"), List(sig)))
      case (Some("Map"), List(key, value)) if allowConvertedResult =>
        for {
          keySig <- typeArgumentSignatureOf(key, imps, Position.Argument)
          valueSig <- typeArgumentSignatureOf(value, imps, Position.Argument)
        } yield ExportSignature.Applied(ClassDesc.ofInternalName("java/util/Map"), List(keySig, valueSig))
      // A product's shared record class cannot carry a component's generic signature.
      case (Some(name), targs) if targs.nonEmpty && pos != Position.Component =>
        for {
          clazz <- imported(name, imps)
          // A Java object's contents are never converted, so its type arguments must be exact.
          signatures <- traverse(targs)(typeArgumentSignatureOf(_, imps, Position.Parameter))
        } yield ExportSignature.Applied(clazz, signatures)
      case _ => None
    }
  }.orElse {
    // Only a name no import accounts for can be an enum: an imported Java class shadows it.
    if (pos != Position.Parameter && args.isEmpty && !imps.contains(qname.toString)) enumSignatureOf(qname)
    else None
  }

  /**
    * Returns the signature of the enum `qname` names, declaring its Java enum, or its sealed
    * interface if a case carries data.
    *
    * Without a resolver, the name is looked up conservatively: through a `use` alias of its first
    * segment, then in the def's own module, then from the root. These cover how an exported enum is
    * ordinarily named; a name found none of those ways, such as one declared in a sibling module
    * and written unqualified, is refused rather than guessed, which fails the stub build loudly.
    */
  private def enumSignatureOf(qname: Name.QName)(implicit ctx: DefContext): Option[ExportSignature] = {
    val parts = qname.namespace.idents.map(_.name) :+ qname.ident.name
    val candidates = ctx.uses.get(parts.head).map(_ ++ parts.tail).toList ++ List(ctx.ns ++ parts, parts)
    for {
      name <- candidates.find(ctx.enums.contains)
      // A recursive enum is refused, as the compiler refuses it.
      if !ctx.visiting.contains(name)
      EnumDecl(enm, imps, uses) = ctx.enums(name)
      if enm.tparams.isEmpty
      shape <-
        if (enm.cases.forall(_.tpes.isEmpty)) Some(Shape.Enum(enm.cases.map(_.ident.name)))
        else ctx.within(name, uses) {
          traverse(enm.cases) { c =>
            // A field is described where the enum is declared, with that module's names in scope.
            traverse(c.tpes)(signatureOf(_, imps, Position.Component)).map { sigs =>
              c.ident.name -> sigs.zipWithIndex.map { case (sig, i) => s"component$i" -> sig }
            }
          }.map(Shape.Sealed(_))
        }
    } yield {
      val desc = Mangle.namespaceFacadeDesc(name)
      ctx.types += Facade(desc, shape, Nil)
      ExportSignature.Exact(desc)
    }
  }

  /** Returns every enum declared in `decls`, by its full name. */
  private def enumsOf(decls: List[WeededAst.Declaration], ns: List[String], imps: Map[String, String], uses: Map[String, List[String]]): List[(List[String], EnumDecl)] =
    decls.flatMap {
      case WeededAst.Declaration.Mod(_, _, _, qname, usesAndImports, inner, _) =>
        enumsOf(inner, ns ++ qname.namespace.idents.map(_.name) :+ qname.ident.name, imps ++ imports(usesAndImports), uses ++ this.uses(usesAndImports))
      case enm: WeededAst.Declaration.Enum => List((ns :+ enm.ident.name) -> EnumDecl(enm, imps, uses))
      case _ => Nil
    }

  /** Returns the signature of a value used as a Java generic type argument, boxing primitives. */
  private def typeArgumentSignatureOf(tpe: WeededAst.Type, imps: Map[String, String], pos: Position)(implicit ctx: DefContext): Option[ExportSignature] =
    signatureOf(tpe, imps, pos).map {
      case ExportSignature.Exact(tpe0) if tpe0 == CD_boolean => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Boolean"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_char => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Character"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_byte => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Byte"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_short => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Short"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_int => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Integer"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_long => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Long"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_float => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Float"))
      case ExportSignature.Exact(tpe0) if tpe0 == CD_double => ExportSignature.Boxed(tpe0, ClassDesc.ofInternalName("java/lang/Double"))
      case sig => sig
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
    case "Bool" => Some(ExportSignature.Exact(CD_boolean))
    case "Char" => Some(ExportSignature.Exact(CD_char))
    case "Int8" => Some(ExportSignature.Exact(CD_byte))
    case "Int16" => Some(ExportSignature.Exact(CD_short))
    case "Int32" => Some(ExportSignature.Exact(CD_int))
    case "Int64" => Some(ExportSignature.Exact(CD_long))
    case "Float32" => Some(ExportSignature.Exact(CD_float))
    case "Float64" => Some(ExportSignature.Exact(CD_double))
    case "String" => Some(ExportSignature.Exact(JavaClasses.String))
    case _ => None
  }

  /** Returns the JVM class named by an explicit Java import. */
  private def imported(name: String, imps: Map[String, String]): Option[ClassDesc] =
    imps.get(name).map(fqn => ClassDesc.ofInternalName(fqn.replace('.', '/')))

  /** Returns the alias-to-class table an `import` list establishes. */
  private def imports(usesAndImports: List[WeededAst.UseOrImport]): Map[String, String] =
    usesAndImports.collect {
      case WeededAst.UseOrImport.Import(name, alias, _) => alias.name -> name.fqn.mkString(".")
    }.toMap

  /** Returns the alias-to-full-name table a `use` list establishes. */
  private def uses(usesAndImports: List[WeededAst.UseOrImport]): Map[String, List[String]] =
    usesAndImports.collect {
      case WeededAst.UseOrImport.Use(qname, alias, _) => alias.name -> (qname.namespace.idents.map(_.name) :+ qname.ident.name)
    }.toMap

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
  private def partition(xs: List[Either[Unsupported, Described]]): (List[Described], List[Unsupported]) =
    (xs.collect { case Right(x) => x }, xs.collect { case Left(x) => x })

  /** Returns the simple name of the class `desc`, the part after its package. */
  private def simpleNameOf(desc: ClassDesc): String = {
    val qualified = ExportSignature.qualifiedName(desc)
    qualified.substring(qualified.lastIndexOf('.') + 1)
  }

  /** Returns the binary class name represented by `desc`. */
  private def binaryName(desc: ClassDesc): String =
    desc.descriptorString().stripPrefix("L").stripSuffix(";").replace('/', '.')
}
