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

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.{Flix, Library}
import ca.uwaterloo.flix.language.ast.shared.*
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.fmt.{DisplayType, FormatType}
import ca.uwaterloo.flix.tools.pkg.PackageModules

import java.io.IOException
import java.nio.file.{Files, Path}

/**
  * A documentation backend that emits one Markdown page per documentable item.
  *
  * Where [[HtmlDocumentor]] targets a browser, this targets a reader that pays for every token: a
  * language model, a code-review bot, or anyone grepping a checkout. Pages are therefore flat and
  * dense — a declaration, its documentation, and one line per definition — rather than nested in
  * navigation chrome.
  *
  * Two conventions keep the output small without losing information. Cross-references are stated
  * once in a preamble instead of being repeated as inline links on every type, and the file that a
  * page's definitions come from is named once instead of being linked per definition. Nothing is
  * truncated: if a page reports twenty-four instances, it lists twenty-four instances.
  */
object MarkdownDocumentor {

  import Documentor.{Effect, Enum, Item, Module, Trait}

  /**
    * The file extension of every page emitted by this backend.
    */
  private val Extension: String = "md"

  /**
    * The page that lists every other page.
    */
  private val IndexFile: String = s"${Documentor.RootFileName}.$Extension"

  /**
    * The directory of the standard library within the `flix/flix` repository.
    *
    * Standard library sources reach the compiler as virtual files named after the file alone, so a
    * page that points a reader at `List.flix` has to say where that file lives.
    */
  private val LibraryDirectory: String = "main/src/library/"

  /**
    * The names of the standard library sources, used to tell them apart from user code.
    *
    * Forced lazily: a caller that documents user code only should not pay to load the library.
    */
  private lazy val LibraryFileNames: Set[String] =
    (Library.CoreLibrary ++ Library.StandardLibrary).map { case (name, _) => name }.toSet

  /**
    * The separator between entries of an inline, comma-free list such as `## Instances`.
    */
  private val Separator: String = " ·"

  /**
    * The directory where to write the output.
    */
  private def OutputDirectory(implicit flix: Flix): Path = flix.options.outputPath.resolve("doc/")

  /**
    * Writes Markdown documentation for `root`, restricted to `packageModules`, to the output directory.
    */
  def run(root: TypedAst.Root, packageModules: PackageModules)(implicit flix: Flix): Unit = {
    for ((name, content) <- documentAll(root, packageModules)) {
      writeDocFile(name, content)
    }
  }

  /**
    * Returns the Markdown documentation for `root`, restricted to `packageModules`, as a map from
    * file name to page content.
    *
    * This is the whole backend; [[run]] only puts the result on disk. Keeping it separate means the
    * output can be inspected without a file system.
    */
  def documentAll(root: TypedAst.Root, packageModules: PackageModules)(implicit flix: Flix): Map[String, String] = {
    // Documenting the root module always yields the index, so it is there to be extended.
    val pages = visitMod(Documentor.build(root, packageModules)).toMap
    pages.updated(IndexFile, pages(IndexFile) + docAllPages(pages.keys.toList))
  }

  /**
    * Returns the pages of `mod` and of every item it contains.
    */
  private def visitMod(mod: Module)(implicit flix: Flix): List[(String, String)] = {
    (fileName(mod) -> documentModule(mod)) ::
      mod.submodules.flatMap(visitMod) :::
      mod.traits.flatMap(visitTrait) :::
      mod.effects.flatMap(visitEffect) :::
      mod.enums.flatMap(visitEnum)
  }

  /**
    * Returns the pages of `trt` and of everything in its companion module.
    */
  private def visitTrait(trt: Trait)(implicit flix: Flix): List[(String, String)] = {
    (fileName(trt) -> documentTrait(trt)) :: visitCompanion(trt.companionMod)
  }

  /**
    * Returns the pages of `eff` and of everything in its companion module.
    */
  private def visitEffect(eff: Effect)(implicit flix: Flix): List[(String, String)] = {
    (fileName(eff) -> documentEffect(eff)) :: visitCompanion(eff.companionMod)
  }

  /**
    * Returns the pages of `enm` and of everything in its companion module.
    */
  private def visitEnum(enm: Enum)(implicit flix: Flix): List[(String, String)] = {
    (fileName(enm) -> documentEnum(enm)) :: visitCompanion(enm.companionMod)
  }

  /**
    * Returns the pages of the items nested inside a companion module.
    *
    * The companion module itself has no page: its contents are shown on the page of the trait,
    * effect, or enum it belongs to.
    */
  private def visitCompanion(mod: Option[Module])(implicit flix: Flix): List[(String, String)] = mod match {
    case None => Nil
    case Some(m) =>
      m.submodules.flatMap(visitMod) :::
        m.traits.flatMap(visitTrait) :::
        m.effects.flatMap(visitEffect) :::
        m.enums.flatMap(visitEnum)
  }

  /**
    * Returns the Markdown page of the module `mod`.
    */
  private def documentModule(mod: Module)(implicit flix: Flix): String = {
    val sb = new StringBuilder()

    val locs = mod.defs.map(_.loc) ::: mod.typeAliases.map(_.loc)
    sb.append(docHeader(mod.qualifiedName, locs))
    sb.append(docBlock(mod.doc))
    // On the index, `## All Pages` already lists these, and more.
    if (!mod.sym.isRoot) {
      sb.append(docModules(Some(mod)))
    }
    sb.append(docTypeAliases(mod.typeAliases))
    sb.append(docDefs("Definitions", mod.defs))

    sb.toString()
  }

  /**
    * Returns the Markdown page of the trait `trt`.
    */
  private def documentTrait(trt: Trait)(implicit flix: Flix): String = {
    val sb = new StringBuilder()

    val comp = trt.companionMod
    val typeAliases = comp.map(_.typeAliases).getOrElse(Nil)
    val defs = comp.map(_.defs).getOrElse(Nil)

    val locs = trt.decl.loc :: trt.signatures.map(_.loc) ::: trt.defs.map(_.loc) :::
      defs.map(_.loc) ::: typeAliases.map(_.loc)
    sb.append(docHeader(trt.qualifiedName, locs))

    val decl = new StringBuilder()
    decl.append("trait ").append(trt.name)
    decl.append(docTypeParams(List(trt.decl.tparam)))
    decl.append(docTraitConstraints(trt.decl.superTraits))
    sb.append(docDeclaration(trt.decl.ann, decl.toString()))
    sb.append(docBlock(trt.decl.doc))

    sb.append(docSection("Associated Types", trt.decl.assocs.sortBy(_.sym.name)) { assoc =>
      docEntry(s"type ${assoc.sym.name}: ${assoc.kind}", assoc.doc)
    })
    // The page is the trait's own page, so a link back to it would say nothing.
    sb.append(docInstances(trt.instances.sortBy(_.loc), Some(trt.decl.sym)))
    sb.append(docSigs("Signatures", trt.signatures))
    sb.append(docSigs("Trait Definitions", trt.defs))
    sb.append(docModules(comp))
    sb.append(docTypeAliases(typeAliases))
    sb.append(docDefs("Definitions", defs))

    sb.toString()
  }

  /**
    * Returns the Markdown page of the effect `eff`.
    */
  private def documentEffect(eff: Effect)(implicit flix: Flix): String = {
    val sb = new StringBuilder()

    val comp = eff.companionMod
    val typeAliases = comp.map(_.typeAliases).getOrElse(Nil)
    val defs = comp.map(_.defs).getOrElse(Nil)

    val locs = eff.decl.loc :: eff.decl.ops.map(_.loc) ::: defs.map(_.loc) ::: typeAliases.map(_.loc)
    sb.append(docHeader(eff.qualifiedName, locs))

    sb.append(docDeclaration(eff.decl.ann, s"eff ${eff.name}"))
    sb.append(docBlock(eff.decl.doc))

    sb.append(docSection("Operations", eff.decl.ops.sortBy(_.sym.name)) { op =>
      docEntry(docSpec(op.sym.name, op.spec), op.spec.doc)
    })
    sb.append(docModules(comp))
    sb.append(docTypeAliases(typeAliases))
    sb.append(docDefs("Definitions", defs))

    sb.toString()
  }

  /**
    * Returns the Markdown page of the enum `enm`.
    */
  private def documentEnum(enm: Enum)(implicit flix: Flix): String = {
    val sb = new StringBuilder()

    val comp = enm.companionMod
    val typeAliases = comp.map(_.typeAliases).getOrElse(Nil)
    val defs = comp.map(_.defs).getOrElse(Nil)

    val locs = enm.decl.loc :: defs.map(_.loc) ::: typeAliases.map(_.loc)
    sb.append(docHeader(enm.qualifiedName, locs))

    sb.append(docDeclaration(enm.decl.ann, docEnumDeclaration(enm.decl)))
    sb.append(docBlock(enm.decl.doc))

    sb.append(docInstances(enm.instances.sortBy(_.trt.sym.name), None))
    sb.append(docModules(comp))
    sb.append(docTypeAliases(typeAliases))
    sb.append(docDefs("Definitions", defs))

    sb.toString()
  }

  /**
    * Returns the title and preamble of a page named `title` whose contents come from `locs`.
    *
    * The preamble states the two conventions that let the rest of the page stay terse: how to
    * resolve a type name to a page, and where the source lives.
    */
  private def docHeader(title: String, locs: List[SourceLocation]): String = {
    val sb = new StringBuilder()
    sb.append(s"# $title\n\n")
    sb.append(s"> Cross-references: a type `X` in any signature links to `X.$Extension`, when that page exists; [index]($IndexFile) lists every page.\n")
    // "Every definition lives in F" is only true when there is exactly one F.
    locs.map(_.source.name).distinct match {
      case name :: Nil =>
        sb.append(s"> Source: every definition lives in ${docSourcePath(name)}; search the file for `def <name>`.\n")
      case _ => // Several sources, or none: say nothing rather than something false.
    }
    sb.append("\n")
    sb.toString()
  }

  /**
    * Returns `name` as a path a reader can open, in backticks.
    */
  private def docSourcePath(name: String): String = {
    // Library sources are named with a forward slash, but a Path prints with the platform separator.
    val normalized = name.replace('\\', '/')
    if (LibraryFileNames.contains(normalized)) s"`$LibraryDirectory$normalized` (flix/flix repo)"
    else s"`$name`"
  }

  /**
    * Returns a fenced Flix code block holding `annotations` and the declaration `decl`.
    */
  private def docDeclaration(annotations: Annotations, decl: String): String = {
    val anns = annotations.annotations.map(a => s"${a.toString}\n").mkString
    s"```flix\n$anns$decl\n```\n\n"
  }

  /**
    * Returns the declaration of the enum `enm`, as valid Flix source.
    */
  private def docEnumDeclaration(enm: TypedAst.Enum)(implicit flix: Flix): String = {
    val sb = new StringBuilder()
    sb.append("enum ").append(enm.sym.name)
    sb.append(docTypeParams(enm.tparams))
    sb.append(docDerivations(enm.derives))
    sb.append(" {\n")
    for (c <- enm.cases.values.toList.sortBy(_.loc)) {
      sb.append("    case ").append(c.sym.name)
      c.tpes match {
        case Nil => // A case without a term, e.g. `case Nil`.
        case tpes => sb.append(tpes.map(t => FormatType.formatDisplayType(DisplayType.fromWellKindedType(t))).mkString("(", ", ", ")"))
      }
      sb.append("\n")
    }
    sb.append("}")
    sb.toString()
  }

  /**
    * Returns the `## Instances` section for `instances`, or the empty string if there are none.
    *
    * Each instance links to the page of its trait, unless that is `self` -- the page we are on.
    */
  private def docInstances(instances: List[TypedAst.Instance], self: Option[Symbol.TraitSym])(implicit flix: Flix): String = {
    if (instances.isEmpty) {
      return ""
    }

    val entries = instances.map { i =>
      val head = s"`${i.trt.sym.name}[${FormatType.formatType(i.tpe)}]${docTraitConstraints(i.tconstrs)}`"
      if (self.contains(i.trt.sym)) head
      else s"$head ([${Documentor.traitName(i.trt.sym)}](${Documentor.traitFileName(i.trt.sym, Extension)}))"
    }

    // A count lets a reader confirm nothing was dropped on the way to the page.
    val body = entries.mkString(s"$Separator\n") + s" *(${entries.length} total)*"
    s"## Instances\n\n$body\n\n"
  }

  /**
    * Returns the `## Modules` section listing everything nested inside `mod` that has its own page.
    */
  private def docModules(mod: Option[Module]): String = {
    val items: List[Item] = mod match {
      case None => Nil
      case Some(m) => m.submodules ::: m.traits ::: m.effects ::: m.enums
    }

    if (items.isEmpty) {
      return ""
    }

    val entries = items.sortBy(_.name).map(i => s"[${i.name}](${fileName(i)})")
    s"## Modules\n\n${entries.mkString(s"$Separator\n")}\n\n"
  }

  /**
    * Returns the `## Type Aliases` section for `typeAliases`, or the empty string if there are none.
    */
  private def docTypeAliases(typeAliases: List[TypedAst.TypeAlias])(implicit flix: Flix): String =
    docSection("Type Aliases", typeAliases.sortBy(_.sym.name)) { ta =>
      docEntry(s"type alias ${ta.sym.name}${docTypeParams(ta.tparams)} = ${FormatType.formatType(ta.tpe)}", ta.doc)
    }

  /**
    * Returns the section `name` for the definitions `defs`, or the empty string if there are none.
    */
  private def docDefs(name: String, defs: List[TypedAst.Def])(implicit flix: Flix): String =
    docSection(name, defs.sortBy(_.sym.name)) { d =>
      docEntry(docAnnotations(d.spec.ann) + docSpec(d.sym.name, d.spec), d.spec.doc)
    }

  /**
    * Returns the section `name` for the trait signatures `sigs`, or the empty string if there are none.
    */
  private def docSigs(name: String, sigs: List[TypedAst.Sig])(implicit flix: Flix): String =
    docSection(name, sigs.sortBy(_.sym.name)) { s =>
      docEntry(docAnnotations(s.spec.ann) + docSpec(s.sym.name, s.spec), s.spec.doc)
    }

  /**
    * Returns a `## name` section built by applying `docElt` to each element of `group`, or the empty
    * string if `group` is empty.
    */
  private def docSection[T](name: String, group: List[T])(docElt: T => String): String = {
    if (group.isEmpty) {
      return ""
    }

    s"## $name\n\n${group.map(docElt).mkString}"
  }

  /**
    * Returns one entry of a section: the declaration `decl`, followed by its documentation.
    *
    * The first paragraph of `doc` goes on the same line as `decl`, which is all there is to say
    * about most definitions. Anything further -- an example, a list of cases -- follows as ordinary
    * Markdown, because collapsing a fenced code block onto one line would destroy it.
    */
  private def docEntry(decl: String, doc: Doc): String = {
    val (summary, rest) = splitDoc(doc)
    val head = summary match {
      case None => s"`$decl`\n\n"
      case Some(s) => s"`$decl` — $s\n\n"
    }
    head + rest.map(r => s"$r\n\n").getOrElse("")
  }

  /**
    * Returns `doc` as a Markdown block, or the empty string if it is blank.
    */
  private def docBlock(doc: Doc): String = {
    val text = doc.text.strip()
    if (text.isEmpty) "" else s"$text\n\n"
  }

  /**
    * Returns the first paragraph of `doc` collapsed onto one line, and whatever follows it.
    *
    * A comment that opens with something other than prose -- a code fence, a list -- has no line
    * that can be collapsed, so all of it is returned as the remainder.
    */
  private def splitDoc(doc: Doc): (Option[String], Option[String]) = {
    val lines = doc.text.strip().linesIterator.toList
    if (lines.isEmpty) {
      return (None, None)
    }
    if (!isProse(lines.head)) {
      return (None, Some(lines.mkString("\n")))
    }

    val end = lines.indexWhere(l => l.isBlank || !isProse(l), 1)
    val (paragraph, rest) = if (end < 0) (lines, Nil) else lines.splitAt(end)

    val summary = paragraph.map(_.trim).mkString(" ")
    val remainder = rest.dropWhile(_.isBlank).mkString("\n").strip()
    (Some(summary), if (remainder.isEmpty) None else Some(remainder))
  }

  /**
    * Returns whether `line` is prose, i.e. whether it can be joined onto the preceding line without
    * changing what the Markdown means.
    */
  private def isProse(line: String): Boolean = {
    val trimmed = line.trim
    !trimmed.isEmpty && !ProseBreak.matches(trimmed)
  }

  /**
    * Markdown constructs that must start a line: fences, list items, headings, quotes, and tables.
    */
  private val ProseBreak: scala.util.matching.Regex = """(?s)(```|~~~|[-*+]\s|\d+[.)]\s|#{1,6}\s|>|\|).*""".r

  /**
    * Returns the `## All Pages` section listing every page in `names`.
    *
    * The preamble of every page promises that a type `X` can be found at `X.md`. This is where a
    * reader checks that promise.
    */
  private def docAllPages(names: List[String]): String = {
    val entries = names.sorted.map(n => s"[${n.stripSuffix(s".$Extension")}]($n)")
    s"## All Pages\n\n${entries.mkString(s"$Separator\n")}\n"
  }

  /**
    * Returns the signature of a definition, signature, or operation named `name`.
    */
  private def docSpec(name: String, spec: TypedAst.Spec)(implicit flix: Flix): String = {
    val sb = new StringBuilder()
    sb.append("def ").append(name)
    sb.append(docFormalParams(spec.fparams))
    sb.append(": ").append(FormatType.formatType(spec.retTpe))
    sb.append(docEffect(spec.eff))
    sb.append(docTraitConstraints(spec.tconstrs))
    sb.append(docEqualityConstraints(spec.econstrs))
    sb.toString()
  }

  /**
    * Returns `anns` as a prefix of a declaration, or the empty string if there are none.
    */
  private def docAnnotations(anns: Annotations): String =
    anns.annotations.map(a => s"${a.toString} ").mkString

  /**
    * Returns the formal parameters `fparams`, wrapped in parentheses.
    */
  private def docFormalParams(fparams: List[TypedAst.FormalParam])(implicit flix: Flix): String = fparams match {
    // A function declared with zero formal parameters is given a single parameter of the unit type.
    case List(TypedAst.FormalParam(_, Type.Cst(TypeConstructor.Unit, _), _, _, _)) => "()"
    case _ => fparams.sortBy(_.loc).map(p => s"${p.bnd.sym.text}: ${FormatType.formatType(p.tpe)}").mkString("(", ", ", ")")
  }

  /**
    * Returns the type parameters `tparams` wrapped in brackets, or the empty string if there are none.
    */
  private def docTypeParams(tparams: List[TypedAst.TypeParam]): String = {
    if (tparams.isEmpty) {
      return ""
    }

    tparams.sortBy(_.loc).map(p => s"${p.name.name}: ${p.sym.kind}").mkString("[", ", ", "]")
  }

  /**
    * Returns the trait constraints `tconstrs` as a `with` clause, or the empty string if there are none.
    */
  private def docTraitConstraints(tconstrs: List[TraitConstraint])(implicit flix: Flix): String = {
    if (tconstrs.isEmpty) {
      return ""
    }

    tconstrs.sortBy(_.loc).map(t => s"${t.symUse.sym.name}[${FormatType.formatType(t.arg)}]").mkString(" with ", ", ", "")
  }

  /**
    * Returns the derived traits of `derives` as a `with` clause, or the empty string if there are none.
    */
  private def docDerivations(derives: Derivations): String = {
    if (derives.traits.isEmpty) {
      return ""
    }

    derives.traits.sortBy(_.loc).map(_.sym.name).mkString(" with ", ", ", "")
  }

  /**
    * Returns the equality constraints `econstrs` as a `where` clause, or the empty string if there are none.
    */
  private def docEqualityConstraints(econstrs: List[TypedAst.EqualityConstraint])(implicit flix: Flix): String = {
    if (econstrs.isEmpty) {
      return ""
    }

    val entries = econstrs.sortBy(_.loc).map { e =>
      e.tpe1 match {
        case Type.AssocType(cst, arg, _, _) =>
          s"${cst.sym.trt.name}.${cst.sym.name}[${FormatType.formatType(arg)}] ~ ${FormatType.formatType(e.tpe2)}"
        case _ =>
          s"${FormatType.formatType(e.tpe1)} ~ ${FormatType.formatType(e.tpe2)}"
      }
    }
    entries.mkString(" where ", ", ", "")
  }

  /**
    * Returns `eff` as an effect suffix, e.g. `" \ IO"`, or the empty string if it is pure.
    */
  private def docEffect(eff: Type)(implicit flix: Flix): String =
    DisplayType.fromWellKindedType(eff) match {
      case DisplayType.Pure => ""
      case displayEff => s" \\ ${FormatType.formatDisplayType(displayEff)}"
    }

  /**
    * Returns the Markdown file name of `item`.
    */
  private def fileName(item: Item): String = Documentor.fileName(item, Extension)

  /**
    * Writes `output` to the file `name` in the output directory.
    */
  private def writeDocFile(name: String, output: String)(implicit flix: Flix): Unit = {
    val path = OutputDirectory.resolve(name)
    try {
      Files.createDirectories(OutputDirectory)
      Files.write(path, output.getBytes)
    } catch {
      case ex: IOException => throw new RuntimeException(s"Unable to write to path '$path'.", ex)
    }
  }
}
