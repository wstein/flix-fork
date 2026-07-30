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

    val relDefs = root.defs.values.map(_.sym.name).filter(n => n.startsWith("rel") || n.contains("Rel") || n.contains("Edge") || n.contains("Path") || n.contains("Parent") || n.contains("Child")).toList.distinct
    val relNames = if (relDefs.nonEmpty) relDefs else List("EDB_Facts", "IDB_Rules")

    val sb = new StringBuilder()
    sb.append(s"<!-- ${Documentor.GeneratedMarker} -->\n")
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 220" width="100%" height="100%">""")
    sb.append("""
      |<style>
      |  .edb-node { fill: #e8f5e9; stroke: #2e7d32; stroke-width: 2; rx: 8; ry: 8; }
      |  .idb-node { fill: #e3f2fd; stroke: #1565c0; stroke-width: 2; rx: 8; ry: 8; }
      |  .text { font-family: system-ui, sans-serif; font-size: 14px; fill: #1c1e21; text-anchor: middle; dominant-baseline: middle; }
      |  .edge { stroke: #555; stroke-width: 2; }
      |</style>
      |""".stripMargin)

    val edbName = xmlEscape(relNames.headOption.getOrElse("EDB_Facts"))
    val idbName = xmlEscape(relNames.drop(1).headOption.getOrElse("IDB_Rules"))

    sb.append(s"""<rect x="40" y="40" width="240" height="130" class="edb-node" />""")
    sb.append(s"""<text x="160" y="80" class="text">$edbName</text>""")
    sb.append("""<text x="160" y="115" class="text" font-size="12px">Extensional Database (EDB)</text>""")

    sb.append(s"""<rect x="320" y="40" width="240" height="130" class="idb-node" />""")
    sb.append(s"""<text x="440" y="80" class="text">$idbName</text>""")
    sb.append("""<text x="440" y="115" class="text" font-size="12px">Intensional Database (IDB)</text>""")

    sb.append("""<path d="M 280 105 L 320 105" class="edge" marker-end="url(#arrow)" />""")
    sb.append("\n</svg>\n")

    Map("datalog/DatalogSchema.svg" -> sb.toString())
  }

  /**
    * Generates SVG diagrams for `mod` and all nested items.
    */
  def generateAll(mod: Module)(implicit flix: Flix): Map[String, String] = {
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
  def generateDiagram(item: Item)(implicit flix: Flix): Option[String] = item match {
    case trt: Trait => generateTraitDiagram(trt)
    case mod: Module if mod.submodules.nonEmpty => generateModuleDiagram(mod)
    case _ => None
  }

  /**
    * Generates an SVG diagram for a Trait showing supertraits above and instances/subtraits below.
    */
  private def generateTraitDiagram(trt: Trait)(implicit flix: Flix): Option[String] = {
    val superTraits = trt.decl.superTraits.map(_.symUse.sym.name)
    val instances = trt.instances.map(inst => ca.uwaterloo.flix.language.fmt.FormatType.formatType(inst.tpe)).distinct.take(5)

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
    sb.append("""
      |<style>
      |  .node { fill: #f8f9fa; stroke: #6c757d; stroke-width: 1.5; rx: 4; ry: 4; }
      |  .node-target { fill: #e7f5ff; stroke: #228be6; stroke-width: 2; rx: 4; ry: 4; }
      |  .text { font-family: system-ui, sans-serif; font-size: 13px; fill: #212529; text-anchor: middle; dominant-baseline: middle; }
      |  .text-target { font-weight: bold; fill: #1864ab; }
      |  .edge { stroke: #adb5bd; stroke-width: 1.5; }
      |</style>
      |<defs>
      |  <marker id="arrow" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      |    <path d="M 0 0 L 10 5 L 0 10 z" fill="#adb5bd" />
      |  </marker>
      |</defs>
      |""".stripMargin)

    val targetY = 92
    renderNode(sb, trt.name, centerX - nodeWidth / 2, targetY, nodeWidth, nodeHeight, isTarget = true)

    if (superTraits.nonEmpty) {
      val stY = 20
      val startX = centerX - ((superTraits.size - 1) * 70)
      superTraits.zipWithIndex.foreach { case (name, idx) =>
        val stX = startX + idx * 140 - nodeWidth / 2
        renderNode(sb, name, stX, stY, nodeWidth, nodeHeight, isTarget = false)
        renderLine(sb, stX + nodeWidth / 2, stY + nodeHeight, centerX, targetY)
      }
    }

    if (instances.nonEmpty) {
      val instY = 164
      val startX = centerX - ((instances.size - 1) * 70)
      instances.zipWithIndex.foreach { case (name, idx) =>
        val instX = startX + idx * 140 - nodeWidth / 2
        renderNode(sb, name, instX, instY, nodeWidth, nodeHeight, isTarget = false)
        renderLine(sb, centerX, targetY + nodeHeight, instX + nodeWidth / 2, instY)
      }
    }

    sb.append("\n</svg>\n")
    Some(sb.toString())
  }

  /**
    * Generates an SVG diagram for a Module showing submodules.
    */
  private def generateModuleDiagram(mod: Module): Option[String] = {
    val submodules = mod.submodules.map(_.name).take(5)
    if (submodules.isEmpty) {
      return None
    }

    val width = 480
    val nodeWidth = 120
    val nodeHeight = 36
    val centerX = width / 2

    val sb = new StringBuilder()
    sb.append(s"<!-- ${Documentor.GeneratedMarker} -->\n")
    sb.append(s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width 160" width="$width" height="160" class="flix-diagram">""")
    sb.append("""
      |<style>
      |  .node { fill: #f8f9fa; stroke: #6c757d; stroke-width: 1.5; rx: 4; ry: 4; }
      |  .node-target { fill: #e7f5ff; stroke: #228be6; stroke-width: 2; rx: 4; ry: 4; }
      |  .text { font-family: system-ui, sans-serif; font-size: 13px; fill: #212529; text-anchor: middle; dominant-baseline: middle; }
      |  .text-target { font-weight: bold; fill: #1864ab; }
      |  .edge { stroke: #adb5bd; stroke-width: 1.5; }
      |</style>
      |<defs>
      |  <marker id="arrow" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      |    <path d="M 0 0 L 10 5 L 0 10 z" fill="#adb5bd" />
      |  </marker>
      |</defs>
      |""".stripMargin)

    val topY = 20
    val botY = 100
    renderNode(sb, mod.name, centerX - nodeWidth / 2, topY, nodeWidth, nodeHeight, isTarget = true)

    val startX = centerX - ((submodules.size - 1) * 70)
    submodules.zipWithIndex.foreach { case (name, idx) =>
      val smX = startX + idx * 140 - nodeWidth / 2
      renderNode(sb, name, smX, botY, nodeWidth, nodeHeight, isTarget = false)
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
      Using(Files.walk(dir)) { stream =>
        stream.forEach { path =>
          if (Files.isRegularFile(path)) {
            val relPath = dir.relativize(path).toString.replace('\\', '/')
            if (relPath.endsWith(s".$Extension") && !currentDiagrams.contains(relPath)) {
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
}
