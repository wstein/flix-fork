/*
 * Copyright 2025 Din Jakupi
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
import ca.uwaterloo.flix.api.lsp.{FormattingOptions, FormatterLsp, TextEdit}
import ca.uwaterloo.flix.tools.fmt.Canonical

import scala.annotation.unused

object FormattingProvider {

  /**
    * Formats the document at `uri` in response to `textDocument/formatting`.
    *
    * The editor gets the **canonical** layout, the same one `flix format
    * --canonical` produces. On the command line that layout is opt-in because
    * choosing it is consent to have code rewritten; asking an editor to reformat
    * a document is that same consent, so there is nothing further to opt into
    * here. Formatting with the default policy would return the document
    * unchanged, which is what this did before and why format-on-save appeared to
    * do nothing.
    *
    * `options` is ignored, deliberately. It carries the client's `tabSize` and
    * `insertSpaces`, and honouring them would make the formatter configurable
    * through the back door — one indentation for editor users and another for
    * everyone else. The indentation unit is fixed at four spaces by
    * `docs/STYLE.md`.
    *
    * A document that does not parse is still formatted: the declarations that
    * failed to parse are reproduced exactly and the rest are laid out, so this
    * works mid-edit, which is when an editor asks.
    */
  def formatDocument(uri: String, @unused options: FormattingOptions)(implicit flix: Flix): List[TextEdit] = {
    val parsedAst = flix.getParsedAst
    FormatterLsp.format(parsedAst, uri, Canonical)
  }
}
