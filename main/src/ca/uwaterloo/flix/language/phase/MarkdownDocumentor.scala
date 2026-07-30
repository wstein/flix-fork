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

import ca.uwaterloo.flix.api.{Flix, Version}
import ca.uwaterloo.flix.language.ast.shared.*
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.fmt.{DisplayType, FormatType}
import ca.uwaterloo.flix.tools.pkg.PackageModules

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.{Failure, Success, Using}

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
  * page's definitions come from is named once instead of being linked per definition.
  *
  * Nothing a page shows is truncated: if a page reports twenty-four instances, it lists all
  * twenty-four, and prints the count so a reader can check. What no page shows is what the shared
  * model does not carry -- [[Documentor]] has no representation for structs or restrictable enums,
  * so neither this backend nor [[HtmlDocumentor]] documents them. Instance member definitions are
  * left out deliberately: they are implementations of signatures the trait's own page already
  * lists.
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
    * The separator between entries of an inline, comma-free list such as `## Instances`.
    */
  private val Separator: String = " ·"

  /**
    * The first line of every generated page.
    *
    * It tells a reader not to edit the file, and it tells the next run which files it may delete.
    * It deliberately carries no version: a marker that changed between releases would stop
    * matching the files it exists to identify.
    */
  private val GeneratedMarker: String = s"<!-- ${Documentor.GeneratedMarker} -->"

  /**
    * The directory where to write the output.
    */
  private def OutputDirectory(implicit flix: Flix): Path = flix.options.outputPath.resolve("doc/")

  /**
    * Writes Markdown documentation for `root`, restricted to `packageModules`, to the output directory.
    */
  def run(root: TypedAst.Root, packageModules: PackageModules, manifest: SvgDocumentor.DiagramManifest = SvgDocumentor.DiagramManifest(Map.empty))(implicit flix: Flix): Unit = {
    val pages = documentAll(root, packageModules, manifest)
    deleteStalePages(pages.keySet)
    for ((name, content) <- pages) {
      writeDocFile(name, content)
    }
  }

  /**
    * Deletes pages left in the output directory by an earlier run that this run does not produce.
    *
    * Renaming or removing a module otherwise leaves its page behind, still readable and no longer
    * reachable from the index -- documentation that describes code that no longer exists. Only
    * files carrying [[GeneratedMarker]] are removed, so a file that someone put here by hand is
    * left alone.
    */
  private def deleteStalePages(current: Set[String])(implicit flix: Flix): Unit = {
    val dir = OutputDirectory
    if (!Files.isDirectory(dir)) {
      return
    }

    Using(Files.list(dir)) { entries =>
      entries.iterator().asScala.toList
    } match {
      case Failure(ex) => throw new RuntimeException(s"Unable to list the path '$dir'.", ex)
      case Success(entries) =>
        for (path <- entries if isStalePage(path, current)) {
          try Files.delete(path)
          catch {
            case ex: IOException => throw new RuntimeException(s"Unable to delete the path '$path'.", ex)
          }
        }
    }
  }

  /**
    * Returns whether `path` is a page an earlier run wrote and this run, producing `current`, does not.
    */
  private def isStalePage(path: Path, current: Set[String]): Boolean = {
    val name = path.getFileName.toString
    if (!name.endsWith(s".$Extension") || current.contains(name) || !Files.isRegularFile(path)) {
      return false
    }

    Using(Files.lines(path, StandardCharsets.UTF_8)) { lines =>
      lines.findFirst().orElse("") == GeneratedMarker
    }.getOrElse(false)
  }

  /**
    * Returns the Markdown documentation for `root`, restricted to `packageModules`, as a map from
    * file name to page content.
    *
    * This is the whole backend; [[run]] only puts the result on disk. Keeping it separate means the
    * output can be inspected without a file system.
    */
  def documentAll(root: TypedAst.Root,
                  packageModules: PackageModules,
                  manifest: SvgDocumentor.DiagramManifest = SvgDocumentor.DiagramManifest(Map.empty))(implicit flix: Flix): Map[String, String] = {
    // Which items get a page is decided before anything is rendered, so that a page can tell
    // whether the page it is about to link to will exist. Deriving the set from the same
    // traversal that renders keeps the two from drifting apart.
    val items = collect(Documentor.build(root, packageModules))
    implicit val pages: Pages = Pages(items.map(fileName).toSet)
    implicit val diagrams: SvgDocumentor.DiagramManifest = manifest

    val rendered = items.map(i => fileName(i) -> document(i)).toMap
    // Documenting the root module always yields the index, so it is there to be extended.
    rendered.updated(IndexFile, rendered(IndexFile) + docAllPages(rendered.keys.toList))
  }

  /**
    * The pages a documentation run will emit.
    *
    * A link to a page that was never generated is worse than no link: it tells a reader that
    * something is documented when it is not. Every generated link is checked against this.
    */
  private case class Pages(names: Set[String]) {
    def contains(name: String): Boolean = names.contains(name)
  }

  /**
    * Returns every item that gets a page of its own, starting with `mod`.
    */
  private def collect(mod: Module): List[Item] = {
    mod ::
      mod.submodules.flatMap(collect) :::
      mod.traits.flatMap(collectTrait) :::
      mod.effects.flatMap(collectEffect) :::
      mod.enums.flatMap(collectEnum)
  }

  /**
    * Returns `trt` and every item in its companion module that gets a page of its own.
    */
  private def collectTrait(trt: Trait): List[Item] = trt :: collectCompanion(trt.companionMod)

  /**
    * Returns `eff` and every item in its companion module that gets a page of its own.
    */
  private def collectEffect(eff: Effect): List[Item] = eff :: collectCompanion(eff.companionMod)

  /**
    * Returns `enm` and every item in its companion module that gets a page of its own.
    */
  private def collectEnum(enm: Enum): List[Item] = enm :: collectCompanion(enm.companionMod)

  /**
    * Returns the items nested inside a companion module that get a page of their own.
    *
    * The companion module itself has no page: its contents are shown on the page of the trait,
    * effect, or enum it belongs to.
    */
  private def collectCompanion(mod: Option[Module]): List[Item] = mod match {
    case None => Nil
    case Some(m) =>
      m.submodules.flatMap(collect) :::
        m.traits.flatMap(collectTrait) :::
        m.effects.flatMap(collectEffect) :::
        m.enums.flatMap(collectEnum)
  }

  /**
    * Returns the Markdown page of `item`.
    */
  private def document(item: Item)(implicit flix: Flix, pages: Pages, diagrams: SvgDocumentor.DiagramManifest): String = item match {
    case m: Module => documentModule(m)
    case t: Trait => documentTrait(t)
    case e: Effect => documentEffect(e)
    case e: Enum => documentEnum(e)
  }

  /**
    * Returns the Markdown page of the module `mod`.
    */
  private def documentModule(mod: Module)(implicit flix: Flix, pages: Pages): String = {
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
  private def documentTrait(trt: Trait)(implicit flix: Flix, pages: Pages, diagrams: SvgDocumentor.DiagramManifest): String = {
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
    val diagramFileName = SvgDocumentor.diagramFileName(trt)
    if (diagrams.contains(diagramFileName)) {
      sb.append(s"![Trait Hierarchy](diagrams/$diagramFileName)\n\n")
    }
    sb.append(docBlock(trt.decl.doc))

    sb.append(docSection("Associated Types", trt.decl.assocs.sortBy(_.sym.name)) { assoc =>
      // A default makes the associated type optional for an implementor, which a reader has to know.
      val default = assoc.tpe.map(t => s" = ${FormatType.formatType(t)}").getOrElse("")
      docEntry(s"type ${assoc.sym.name}: ${assoc.kind}$default", assoc.doc)
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
  private def documentEffect(eff: Effect)(implicit flix: Flix, pages: Pages): String = {
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
  private def documentEnum(enm: Enum)(implicit flix: Flix, pages: Pages): String = {
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
    sb.append(s"$GeneratedMarker\n\n")
    sb.append(s"# $title\n\n")
    sb.append(s"> Generated by Flix ${Version.CurrentVersion}.\n")
    // Say what is actually true: pages are named after the fully qualified item, `List` is only
    // at `List.md` because it happens to be top-level, and a name may have no page at all.
    sb.append(s"> Cross-references: each page is named after the fully qualified item it documents, e.g. `List.$Extension`, `Util.Json.ToJson.$Extension`. [index]($IndexFile) lists every page; a name absent from it is not documented here.\n")
    // "Every definition lives in F" is only true when there is exactly one F.
    locs.map(_.source).distinct match {
      case source :: Nil =>
        sb.append(s"> Source: every definition lives in ${docSourcePath(source)}; search the file for `def <name>`.\n")
      case _ => // Several sources, or none: say nothing rather than something false.
    }
    sb.append("\n")
    sb.toString()
  }

  /**
    * Returns `source` as a path a reader can open, in backticks.
    */
  private def docSourcePath(source: Source): String = {
    val normalized = source.name.replace('\\', '/')
    source.input match {
      case Input.RealFile(_, _) => s"`$normalized`"
      case _ => s"`$normalized`"
    }
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
  private def docInstances(instances: List[TypedAst.Instance], self: Option[Symbol.TraitSym])(implicit flix: Flix, pages: Pages): String = {
    if (instances.isEmpty) {
      return ""
    }

    val entries = instances.map { i =>
      val head = s"`${docAnnotations(i.ann)}${i.trt.sym.name}[${FormatType.formatType(i.tpe)}]${docTraitConstraints(i.tconstrs)}${docEqualityConstraints(i.econstrs)}`"
      val target = Documentor.traitFileName(i.trt.sym, Extension)
      // A trait that is not itself documented -- because it is not public, or falls outside the
      // selected package -- still has instances worth listing, but no page to point at.
      if (self.contains(i.trt.sym) || !pages.contains(target)) head
      else s"$head ([${Documentor.traitName(i.trt.sym)}]($target))"
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

    val entries = items.sortBy(_.name).map(i => s"- [${i.name}](${fileName(i)})")
    s"## Modules\n\n${entries.mkString("\n")}\n\n"
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
    head + rest.map(r => s"${demoteHeadings(r)}\n\n").getOrElse("")
  }

  /**
    * Returns `doc` as a Markdown block, or the empty string if it is blank.
    */
  private def docBlock(doc: Doc): String = {
    val text = doc.text.strip()
    if (text.isEmpty) "" else s"${demoteHeadings(text)}\n\n"
  }

  /**
    * Returns `text` with every heading pushed below the levels this backend owns.
    *
    * A page uses `#` for its title and `##` for its sections. A heading written in a documentation
    * comment would otherwise sit alongside them and break the page outline: `Fs.flix` really does
    * write `## Effect Hierarchy`, which would compete with the generated `## Modules`.
    *
    * Headings inside a fenced code block are left alone -- there they are content, not structure.
    */
  private def demoteHeadings(text: String): String = {
    var fence: Option[String] = None
    val lines = text.linesIterator.map { line =>
      val trimmed = line.trim
      fence match {
        case Some(f) =>
          if (trimmed.startsWith(f)) fence = None
          line
        case None =>
          if (trimmed.startsWith("```")) {
            fence = Some("```")
            line
          } else if (trimmed.startsWith("~~~")) {
            fence = Some("~~~")
            line
          } else line match {
            case Heading(hashes, rest) => "#" * math.min(hashes.length + HeadingOffset, MaxHeadingLevel) + rest
            case _ => line
          }
      }
    }
    lines.mkString("\n")
  }

  /**
    * An ATX heading: its leading hashes, and everything after them.
    */
  private val Heading: scala.util.matching.Regex = """^(#{1,6})(\s.*)$""".r

  /**
    * How far a heading written in a documentation comment is pushed down.
    *
    * A page owns `#` and `##`, so the first level a comment may use is `###`.
    */
  private val HeadingOffset: Int = 2

  /**
    * The deepest heading Markdown has; demotion cannot go past it.
    */
  private val MaxHeadingLevel: Int = 6

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
    * Markdown constructs that must start a line: thematic breaks, setext underlines, fences, list
    * items, headings, quotes, and tables.
    */
  private val ProseBreak: scala.util.matching.Regex =
    """(?s)((?:[-*_]\s*){3,}|=+|```|~~~|[-*+]\s|\d+[.)]\s|#{1,6}\s|>|\|).*""".r

  /**
    * Returns the `## All Pages` section listing every page in `names`.
    *
    * The preamble of every page promises that a type `X` can be found at `X.md`. This is where a
    * reader checks that promise.
    */
  private def docAllPages(names: List[String]): String = {
    val entries = names.sorted.map(n => s"- [${n.stripSuffix(s".$Extension")}]($n)")
    s"## All Pages\n\n${entries.mkString("\n")}\n"
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
      Files.write(path, output.getBytes(StandardCharsets.UTF_8))
    } catch {
      case ex: IOException => throw new RuntimeException(s"Unable to write to path '$path'.", ex)
    }
  }
}
