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

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.tools.pkg.PackageModules

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Using

/**
  * A documentation backend phase that emits standalone SVG diagrams for trait inheritance hierarchies and module structures.
  */
object SvgDocumentor {

  import Documentor.{Item, Module, Trait}

  /**
    * The file extension of files written by this phase.
    */
  val Extension: String = "svg"

  /**
    * The sub-directory within the documentation directory where SVG diagrams live.
    */
  val SubDirectory: String = "diagrams"

  /**
    * Returns the path to the directory where diagrams are written.
    */
  private def DiagramDirectory(implicit flix: Flix): Path =
    flix.options.outputPath.resolve("doc").resolve(SubDirectory)

  /**
    * Runs diagram generation for the given module tree, writing SVG files to disk.
    *
    * Returns a set of the file names of the generated SVG diagrams.
    */
  def run(root: TypedAst.Root, packageModules: PackageModules)(implicit flix: Flix): Set[String] = {
    val moduleTree = Documentor.build(root, packageModules)
    val diagrams = generateAll(moduleTree)
    
    val dir = DiagramDirectory
    if (!Files.exists(dir)) {
      Files.createDirectories(dir)
    }

    val datalogDiagrams = generateDatalogDiagrams(root)
    val allDiagrams = diagrams ++ datalogDiagrams

    for ((fileName, content) <- allDiagrams) {
      writeDiagramFile(fileName, content)
    }

    deleteStaleDiagrams(allDiagrams.keySet)
    allDiagrams.keySet
  }

  def generateDatalogDiagrams(root: TypedAst.Root)(implicit flix: Flix): Map[String, String] = {
    if (!flix.options.docExtended && flix.options.xdatalogDebug.isEmpty) {
      return Map.empty
    }
    val datalogDir = DiagramDirectory.resolve("datalog")
    if (!Files.exists(datalogDir)) {
      Files.createDirectories(datalogDir)
    }

    val sb = new StringBuilder()
    sb.append(s"""<!-- ${Documentor.GeneratedMarker} -->\n""")
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 200" width="100%" height="100%">""")
    sb.append("""
      |<style>
      |  .edb-node { fill: #e8f5e9; stroke: #2e7d32; stroke-width: 2; rx: 8; ry: 8; }
      |  .idb-node { fill: #e3f2fd; stroke: #1565c0; stroke-width: 2; rx: 8; ry: 8; }
      |  .text { font-family: system-ui, sans-serif; font-size: 14px; fill: #1c1e21; text-anchor: middle; dominant-baseline: middle; }
      |  .edge { stroke: #555; stroke-width: 2; }
      |</style>
      |""".stripMargin)
    sb.append("""<rect x="50" y="40" width="220" height="120" class="edb-node" />""")
    sb.append("""<text x="160" y="80" class="text">EDB Fact Relation</text>""")
    sb.append("""<text x="160" y="110" class="text" font-size="12px">(Extensional Database)</text>""")
    sb.append("""<rect x="330" y="40" width="220" height="120" class="idb-node" />""")
    sb.append("""<text x="440" y="80" class="text">IDB Derived Rule</text>""")
    sb.append("""<text x="440" y="110" class="text" font-size="12px">(Intensional Database)</text>""")
    sb.append("""<path d="M 270 100 L 330 100" class="edge" marker-end="url(#arrow)" />""")
    sb.append("\n</svg>\n")

    Map("datalog/DatalogSchema.svg" -> sb.toString())
  }

  /**
    * Generates SVG diagrams for `mod` and all nested items.
    */
  def generateAll(mod: Module): Map[String, String] = {
    val items = collect(mod)
    items.flatMap { item =>
      generateDiagram(item).map(svg => (diagramFileName(item), svg))
    }.toMap
  }

  /**
    * Returns the diagram file name for `item`, e.g. 'Eq.svg'.
    */
  def diagramFileName(item: Item): String = s"${item.qualifiedName}.$Extension"

  /**
    * Generates an SVG diagram string for `item` if it has non-trivial structural relationships, or None otherwise.
    */
  def generateDiagram(item: Item): Option[String] = item match {
    case trt: Trait => generateTraitDiagram(trt)
    case mod: Module if mod.submodules.nonEmpty => generateModuleDiagram(mod)
    case _ => None
  }

  /**
    * Generates an SVG diagram for a Trait showing supertraits above and instances/subtraits below.
    */
  private def generateTraitDiagram(trt: Trait): Option[String] = {
    val superTraits = trt.decl.superTraits.map(_.symUse.sym.name)
    val instances = trt.instances.map(inst => inst.trt.sym.name).distinct.take(5)

    if (superTraits.isEmpty && instances.isEmpty) {
      return None
    }

    val width = 480
    val nodeWidth = 120
    val nodeHeight = 36
    val centerX = width / 2

    val sb = new StringBuilder()
    sb.append(s"<!-- ${Documentor.GeneratedMarker} -->\n")
    sb.append(s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width 220" width="$width" height="220" class="flix-diagram">""")
    sb.append("\n  <defs>\n")
    sb.append("""    <marker id="arrow" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">""")
    sb.append("""\n      <path d="M 0 0 L 10 5 L 0 10 z" fill="#555" />""")
    sb.append("\n    </marker>\n  </defs>\n")
    sb.append("""  <style>
                |    .node { fill: #f8f9fa; stroke: #ced4da; stroke-width: 1.5px; rx: 6px; }
                |    .node-target { fill: #e7f5ff; stroke: #1c7ed6; stroke-width: 2px; rx: 6px; }
                |    .text { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; font-size: 13px; fill: #212529; text-anchor: middle; dominant-baseline: central; }
                |    .text-target { font-weight: bold; fill: #1864ab; }
                |    .edge { stroke: #adb5bd; stroke-width: 1.5px; fill: none; }
                |  </style>
                |""".stripMargin)

    // Render SuperTraits Top Row
    val topY = 30
    val midY = 110
    val botY = 180

    if (superTraits.nonEmpty) {
      val count = superTraits.size
      val spacing = width / (count + 1)
      superTraits.zipWithIndex.foreach { case (stName, idx) =>
        val stX = spacing * (idx + 1)
        renderNode(sb, stName, stX - nodeWidth / 2, topY - nodeHeight / 2, nodeWidth, nodeHeight, isTarget = false)
        renderLine(sb, stX, topY + nodeHeight / 2, centerX, midY - nodeHeight / 2)
      }
    }

    // Render Main Target Trait Node
    renderNode(sb, trt.name, centerX - nodeWidth / 2, midY - nodeHeight / 2, nodeWidth, nodeHeight, isTarget = true)

    // Render Instances / Subtraits Bottom Row
    if (instances.nonEmpty) {
      val count = instances.size
      val spacing = width / (count + 1)
      instances.zipWithIndex.foreach { case (instName, idx) =>
        val instX = spacing * (idx + 1)
        renderNode(sb, instName, instX - nodeWidth / 2, botY - nodeHeight / 2, nodeWidth, nodeHeight, isTarget = false)
        renderLine(sb, centerX, midY + nodeHeight / 2, instX, botY - nodeHeight / 2)
      }
    }

    sb.append("\n</svg>\n")
    Some(sb.toString())
  }

  /**
    * Generates an SVG diagram for a Module showing parent and submodules.
    */
  private def generateModuleDiagram(mod: Module): Option[String] = {
    val subMods = mod.submodules.map(_.name).take(4)
    if (subMods.isEmpty) return None

    val width = 480
    val nodeWidth = 110
    val nodeHeight = 34
    val centerX = width / 2

    val topY = 40
    val botY = 140

    val sb = new StringBuilder()
    sb.append(s"<!-- ${Documentor.GeneratedMarker} -->\n")
    sb.append(s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width 190" width="$width" height="190" class="flix-diagram">""")
    sb.append("\n  <defs>\n")
    sb.append("""    <marker id="arrow" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">""")
    sb.append("""\n      <path d="M 0 0 L 10 5 L 0 10 z" fill="#555" />""")
    sb.append("\n    </marker>\n  </defs>\n")
    sb.append("""  <style>
                |    .node { fill: #f8f9fa; stroke: #ced4da; stroke-width: 1.5px; rx: 6px; }
                |    .node-target { fill: #e7f5ff; stroke: #1c7ed6; stroke-width: 2px; rx: 6px; }
                |    .text { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; font-size: 13px; fill: #212529; text-anchor: middle; dominant-baseline: central; }
                |    .text-target { font-weight: bold; fill: #1864ab; }
                |    .edge { stroke: #adb5bd; stroke-width: 1.5px; fill: none; }
                |  </style>
                |""".stripMargin)

    renderNode(sb, mod.name, centerX - nodeWidth / 2, topY - nodeHeight / 2, nodeWidth, nodeHeight, isTarget = true)

    val count = subMods.size
    val spacing = width / (count + 1)
    subMods.zipWithIndex.foreach { case (smName, idx) =>
      val smX = spacing * (idx + 1)
      renderNode(sb, smName, smX - nodeWidth / 2, botY - nodeHeight / 2, nodeWidth, nodeHeight, isTarget = false)
      renderLine(sb, centerX, topY + nodeHeight / 2, smX, botY - nodeHeight / 2)
    }

    sb.append("\n</svg>\n")
    Some(sb.toString())
  }

  private def renderNode(sb: StringBuilder, label: String, x: Int, y: Int, w: Int, h: Int, isTarget: Boolean): Unit = {
    val rectClass = if (isTarget) "node-target" else "node"
    val textClass = if (isTarget) "text text-target" else "text"
    val cx = x + w / 2
    val cy = y + h / 2
    sb.append(s"""  <rect x="$x" y="$y" width="$w" height="$h" class="$rectClass" />\n""")
    sb.append(s"""  <text x="$cx" y="$cy" class="$textClass">${xmlEscape(label)}</text>\n""")
  }

  private def renderLine(sb: StringBuilder, x1: Int, y1: Int, x2: Int, y2: Int): Unit = {
    sb.append(s"""  <path d="M $x1 $y1 L $x2 $y2" class="edge" marker-end="url(#arrow)" />\n""")
  }

  private def xmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

  private def collect(mod: Module): List[Item] = {
    mod ::
      mod.submodules.flatMap(collect) :::
      mod.traits.flatMap(t => t :: t.companionMod.toList.flatMap(collect)) :::
      mod.effects.flatMap(e => e :: e.companionMod.toList.flatMap(collect)) :::
      mod.enums.flatMap(e => e :: e.companionMod.toList.flatMap(collect))
  }

  private def writeDiagramFile(fileName: String, content: String)(implicit flix: Flix): Unit = {
    val path = DiagramDirectory.resolve(fileName)
    if (path.getParent != null && !Files.exists(path.getParent)) {
      Files.createDirectories(path.getParent)
    }
    Files.writeString(path, content, StandardCharsets.UTF_8)
  }

  /**
    * Cleans up stale generated SVG diagram files carrying [[Documentor.GeneratedMarker]].
    */
  def deleteStaleDiagrams(currentDiagrams: Set[String])(implicit flix: Flix): Unit = {
    val dir = DiagramDirectory
    if (Files.exists(dir) && Files.isDirectory(dir)) {
      Using(Files.list(dir)) { stream =>
        stream.forEach { path =>
          val name = path.getFileName.toString
          if (name.endsWith(s".$Extension") && !currentDiagrams.contains(name)) {
            try {
              val text = Files.readString(path, StandardCharsets.UTF_8)
              if (text.contains(Documentor.GeneratedMarker)) {
                Files.deleteIfExists(path)
              }
            } catch {
              case _: IOException => // Ignore unreadable files
            }
          }
        }
      }
    }
  }
}
