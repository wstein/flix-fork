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

import ca.uwaterloo.flix.api.Version
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.SourceLocation
import org.json4s.JValue
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods
import org.json4s.native.JsonMethods.pretty

import java.nio.file.{Path, Paths}

/**
  * Renders compiler diagnostics as SARIF 2.1.0.
  *
  * The format a pull request is annotated from. What makes this worth having over the formatted
  * text is not the JSON but the position: a diagnostic already knows exactly which characters it is
  * about, and printing it as prose throws that away, so a reviewer reads "in Foo.flix" and goes
  * looking. Here the region is the span the compiler pointed at, and the annotation lands on it.
  *
  * Every diagnostic is an error. Flix has no warnings -- a thing worth saying is worth failing on --
  * so there is no severity to decide, only a level to state.
  */
object DiagnosticSarif {

  /**
    * Renders `messages` as a SARIF document.
    *
    * `base` is the directory paths are reported relative to. SARIF locations are resolved against
    * the repository, so an absolute path annotates nothing.
    */
  def format(messages: List[CompilationMessage], smells: List[Metrics.Violation], base: Option[Path]): String = {
    // A rule per error code, declared once however often it fires. The code is what a consumer
    // displays and what a project suppresses by, so it is the identity; the kind is its name.
    val byCode = messages.groupBy(m => m.code.toString)
    val codes = byCode.keys.toList.sorted

    val categories = smells.map(_.category).distinct.sorted
    // One namespace for both: a compiler code and a metric category cannot collide, and a consumer
    // suppressing a rule should not have to know which half of the tool produced it.
    val ids = codes ++ categories

    val diagnosticRules: List[JValue] = codes.map { code =>
      val kind = byCode(code).head.kind.toString
      ("id" -> code) ~
        ("name" -> kind.replace(" ", "")) ~
        ("shortDescription" -> ("text" -> kind)) ~
        ("defaultConfiguration" -> ("level" -> "error")) ~
        ("properties" -> ("tags" -> List("compiler", kind.toLowerCase.replace(" ", "-"))))
    }

    val smellRules: List[JValue] = categories.map { c =>
      ("id" -> c) ~
        ("name" -> c) ~
        ("shortDescription" -> ("text" -> Metrics.ruleDescription(c))) ~
        ("fullDescription" -> ("text" -> s"${Metrics.ruleDescription(c)} ${Metrics.action(c)}.")) ~
        ("help" -> ("text" -> s"${Metrics.action(c)}. See docs/METRIC-SMELLS.md.")) ~
        ("defaultConfiguration" -> ("level" -> Metrics.defaultLevel(c))) ~
        ("properties" -> ("tags" -> Metrics.tagsFor(c)) ~ ("precision" -> "very-high"))
    }

    val rules: JValue = diagnosticRules ++ smellRules

    val diagnosticResults: List[JValue] = messages.map { m =>
      val related = m.locs.filter(_ != m.loc).flatMap(location(_, base))
      val base0 =
        ("ruleId" -> m.code.toString) ~
          ("ruleIndex" -> ids.indexOf(m.code.toString)) ~
          ("level" -> "error") ~
          ("message" -> ("text" -> m.summary))
      val located = location(m.loc, base) match {
        case Some(loc) => base0 ~ ("locations" -> List(loc))
        case None => base0
      }
      // The other places a diagnostic points at -- where a name was bound, where a type came from --
      // which a reviewer otherwise has to find from the prose.
      if (related.isEmpty) located else located ~ ("relatedLocations" -> related)
    }

    val smellResults: List[JValue] = smells.map { v =>
      val message = s"${v.subject}: ${v.measure} ${v.actualText}, over ${v.limitText}. ${Metrics.action(v.category)}."
      val base0 =
        ("ruleId" -> v.category) ~
          ("ruleIndex" -> ids.indexOf(v.category)) ~
          ("level" -> Metrics.level(v)) ~
          ("message" -> ("text" -> message))
      if (v.file.isEmpty) base0
      else base0 ~ ("locations" -> List[JValue](
        ("physicalLocation" ->
          ("artifactLocation" -> ("uri" -> v.file) ~ ("uriBaseId" -> "%SRCROOT%")) ~
            ("region" -> ("startLine" -> v.line.max(1))))
      ))
    }

    // Diagnostics first: a file that does not compile is not a file whose parameter lists matter.
    val results: JValue = diagnosticResults ++ smellResults

    val doc: JValue =
      ("$schema" -> "https://json.schemastore.org/sarif-2.1.0.json") ~
        ("version" -> "2.1.0") ~
        ("runs" -> List[JValue](
          ("tool" -> ("driver" ->
            ("name" -> "flix") ~
              ("semanticVersion" -> Version.CurrentVersion.toString) ~
              ("informationUri" -> "https://flix.dev") ~
              ("rules" -> rules))) ~
            ("results" -> results)
        ))

    pretty(JsonMethods.render(doc))
  }

  /**
    * Renders a source location, or nothing when it does not describe real source.
    *
    * A synthetic location -- one the compiler made up for something it generated -- has nowhere to
    * put an annotation, and a region invented for it would point at whatever happens to be on that
    * line.
    */
  private def location(loc: SourceLocation, base: Option[Path]): Option[JValue] = {
    if (!loc.isReal) return None
    val uri = relativise(loc.source.name, base)
    if (uri.isEmpty) return None
    Some(
      ("physicalLocation" ->
        ("artifactLocation" -> ("uri" -> uri) ~ ("uriBaseId" -> "%SRCROOT%")) ~
          ("region" ->
            ("startLine" -> loc.start.lineOneIndexed.max(1)) ~
              ("startColumn" -> loc.start.colOneIndexed.toInt.max(1)) ~
              ("endLine" -> loc.end.lineOneIndexed.max(1)) ~
              ("endColumn" -> loc.end.colOneIndexed.toInt.max(1))))
    )
  }

  /**
    * Returns `name` relative to `base`, when it is underneath it.
    */
  private def relativise(name: String, base: Option[Path]): String = {
    val path = try Paths.get(name) catch { case _: Exception => return name }
    base match {
      case Some(b) if path.isAbsolute && path.startsWith(b) => b.relativize(path).toString
      case _ => name
    }
  }
}
