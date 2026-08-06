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
package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.api.lsp.Diagnostic
import ca.uwaterloo.flix.language.ast.TypedAst
import org.json4s.JsonAST.JValue
import org.json4s.JsonDSL.*

/**
  * What a build tool reads instead of the compiler's console output.
  *
  * A build tool has to place a problem in a file at a line, decide whether to fail, and hand the
  * result to a UI it owns. Recovering that from rendered text means matching on prose and on
  * decorations that exist to be read by people -- so it breaks whenever a message is reworded, and
  * it cannot be right for a message it has not seen before.
  *
  * This is deliberately a *protocol* rather than an API. A build plugin that links against the
  * compiler is pinned to its binary version, which is the problem `zinc` solves by compiling a
  * bridge per Scala version. A plugin that reads this is pinned to [[Version]] instead, which is a
  * contract we choose rather than one the JVM imposes.
  *
  * ==Positions are LSP's, not the console's==
  *
  * Ranges come from [[Diagnostic]], which is the same conversion the language server uses, so they
  * are **zero-based** -- while the text the compiler prints is one-based, as a person expects. The
  * two therefore disagree by one on purpose. Producing a second convention here would have been the
  * worse trade: the requests that follow this one are language-server requests, and a build client
  * that converted twice would be converting in opposite directions.
  */
object BuildProtocol {

  /**
    * The version of this document's shape.
    *
    * Bumped when a consumer that understands the current shape would misread the new one. Adding a
    * field is not that; removing one, renaming one, or changing what a value means is.
    */
  val ProtocolVersion: Int = 1

  /**
    * The oldest client this compiler will serve.
    *
    * Separate from [[ProtocolVersion]] because the two move independently: raising the current
    * version says what we can do, and raising this one says what we have stopped doing. A client
    * older than this is refused with a message rather than served something it will misread.
    */
  val MinimumClientVersion: Int = 1

  /**
    * How a request identifies what to build.
    *
    * `"project-directory"`: a request names a project directory, plus explicit libraries, output
    * locations, and options. It does not enumerate sources or resolved dependencies.
    *
    * The alternative -- a request that names every input -- was rejected. A client would have to
    * resolve `flix.toml`, Maven coordinates, and `.fpkg` files for itself, which is a second
    * dependency resolver that has to agree with `Bootstrap`'s forever. The property that buys, for
    * a build tool, is knowing what to declare as an input so its cache is sound.
    *
    * That property is provided instead by the *response* reporting what was actually consumed. A
    * client compares it against what it declared and fails when its declaration was too narrow --
    * which catches under-declaration rather than assuming it away, and is the failure that makes
    * caching unsafe. See `docs/BUILD-PROTOCOL.md`.
    */
  val InputModel: String = "project-directory"

  /**
    * Returns what this compiler offers a build client, or why it cannot serve one.
    *
    * `clientVersion` is the version the client speaks. Refusing an incompatible one here, by
    * number, is the point of the handshake: the alternative is a client discovering the mismatch
    * as a missing field halfway through a build, and reporting it as a compiler error.
    *
    * Capabilities are named rather than inferred from the version, because they will not arrive in
    * lockstep -- a daemon exists or it does not, independently of what else changed.
    */
  def initialize(clientVersion: Option[Int]): (Boolean, JValue) = {
    val incompatible = clientVersion.filter(v => v > ProtocolVersion || v < MinimumClientVersion)
    incompatible match {
      case Some(v) =>
        (false,
          ("protocolVersion" -> ProtocolVersion) ~
            ("minimumClientVersion" -> MinimumClientVersion) ~
            ("flixVersion" -> Version.CurrentVersion.toString) ~
            ("success" -> false) ~
            ("error" -> s"This compiler speaks build protocol $MinimumClientVersion..$ProtocolVersion; the client asked for $v."))
      case None =>
        (true,
          ("protocolVersion" -> ProtocolVersion) ~
            ("minimumClientVersion" -> MinimumClientVersion) ~
            ("flixVersion" -> Version.CurrentVersion.toString) ~
            ("success" -> true) ~
            ("inputModel" -> InputModel) ~
            // Only what is implemented. A capability advertised ahead of its implementation is
            // worse than one absent: a client trusts it and fails at the point of use, where the
            // handshake exists to have already said no.
            ("capabilities" ->
              ("diagnostics" -> true) ~
                ("exportStubs" -> true) ~
                ("explicitLibraries" -> true) ~
                ("daemon" -> false)))
    }
  }

  /**
    * Returns the result of a build as a single JSON document.
    *
    * `errors` is empty exactly when the build succeeded, which is what `success` reports -- stated
    * rather than implied, so a consumer need not decide whether an empty list means "no problems"
    * or "problems not collected".
    */
  def result(errors: List[BootstrapError], root: Option[TypedAst.Root]): JValue =
    ("protocolVersion" -> ProtocolVersion) ~
      ("flixVersion" -> Version.CurrentVersion.toString) ~
      ("success" -> errors.isEmpty) ~
      ("diagnostics" -> errors.flatMap(diagnostics(_, root)))

  /**
    * Returns `error` as diagnostics.
    *
    * Only [[BootstrapError.CompilationErrors]] carries source locations. Everything else -- a
    * malformed manifest, an unreachable dependency, a path that may not be written -- is a failure
    * of the build rather than of the code, and is reported with no location rather than pinned to
    * an arbitrary one. A consumer still sees it, because a build that failed for a reason it cannot
    * display is worse than one it displays imprecisely.
    */
  private def diagnostics(error: BootstrapError, root: Option[TypedAst.Root]): List[JValue] =
    error match {
      case BootstrapError.CompilationErrors(errors, errorRoot) =>
        val effectiveRoot = errorRoot.orElse(root)
        errors.map { m =>
          // Built from the diagnostic's fields rather than by editing its JSON, so this owns the
          // document's shape while `Diagnostic` keeps owning the conversion.
          val diagnostic = Diagnostic.from(m, effectiveRoot)
          // `path`, because a diagnostic alone does not say which file it is about: LSP carries
          // that on the notification, where the client already knows which document it asked
          // about. A build reports on every file at once and has no such context.
          ("path" -> m.loc.source.name) ~
            ("range" -> diagnostic.range.toJSON) ~
            ("severity" -> diagnostic.severity.map(_.toInt)) ~
            // The stable identifier, not the category. `Diagnostic.code` is `kind` -- "Resolution
            // Error" -- which reads well in an editor's problem list and is useless to key on:
            // hundreds of distinct errors share it. A build tool suppressing or escalating a
            // specific error needs `E2136`, and it was previously recoverable only by matching the
            // rendered text.
            ("code" -> m.code.toString) ~
            ("kind" -> diagnostic.code) ~
            ("message" -> diagnostic.message) ~
            // Re-rendered without the terminal formatter. `Diagnostic.from` uses the ANSI one,
            // which an editor strips and a build tool would paste into a report verbatim.
            ("fullMessage" -> m.messageWithLoc(NoFormatter)(effectiveRoot))
        }
      case other =>
        List(
          ("path" -> (null: String)) ~
            ("severity" -> 1) ~
            ("code" -> (null: String)) ~
            ("message" -> other.message(NoFormatter)) ~
            ("fullMessage" -> other.message(NoFormatter))
        )
    }

  /** The formatter used for machine-read text: no colour, no cursor control. */
  private val NoFormatter: ca.uwaterloo.flix.util.Formatter = ca.uwaterloo.flix.util.Formatter.NoFormatter
}
