/*
 * Copyright 2026 Flix Authors
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
package ca.uwaterloo.flix.api.lsp

import org.scalatest.funsuite.AnyFunSuite

/**
  * What the language server tells a client it can do.
  *
  * A capability is not documentation. A client must not send a request whose capability is absent,
  * so a request handler that is implemented but unannounced is unreachable -- and unreachable in a
  * way nothing reports, because the server is never asked and the client has nothing to show.
  *
  * `inlayHint` was in exactly that state: `FlixTextDocumentService.inlayHint` was implemented and
  * `InlayHintProvider` produced type, effect and parameter hints, while `mkServerCapabilities`
  * never named it. Every standards-compliant client -- LSP4IJ among them -- correctly declined to
  * ask. It survived because the VS Code extension serves hints over Flix's own JSON protocol, which
  * negotiates no capabilities, so the feature demonstrably worked in the one client that never
  * exercised this path.
  */
class TestServerCapabilities extends AnyFunSuite {

  private val capabilities = LspServer.mkServerCapabilities()

  test("inlayHint is advertised, so a client may actually request hints") {
    assert(
      Option(capabilities.getInlayHintProvider).isDefined,
      "FlixTextDocumentService.inlayHint is implemented; without this capability no client may call it",
    )
  }

  test("inlayHint claims no resolve support, because none is implemented") {
    // Claiming resolveProvider would invite `inlayHint/resolve`, which the server does not handle.
    // Advertising a boolean rather than InlayHintRegistrationOptions is what keeps that honest.
    val provider = capabilities.getInlayHintProvider
    assert(provider.isLeft, s"expected a bare boolean capability, got $provider")
    assert(provider.getLeft, "the boolean must be true, or the capability is absent in effect")
  }

  test("executeCommand is advertised, and names the commands it accepts") {
    // The `View Diagram` link in a hover is written as a `command:` URI, which only VS Code
    // resolves by itself. Every other client needs the command to exist as a real LSP command --
    // and may not send one at all unless it is named here.
    val provider = Option(capabilities.getExecuteCommandProvider)
      .getOrElse(fail("no executeCommandProvider; no client may run flix.showDiagram"))
    assert(
      provider.getCommands.contains("flix.showDiagram"),
      s"expected flix.showDiagram among ${provider.getCommands}",
    )
  }

  test("showAst is a command, not only a VS Code protocol request") {
    // ShowAstProvider was implemented and reachable only through `lsp/showAst` on Flix's own
    // protocol, which negotiates no capabilities and which no other client speaks. A client may not
    // send a command that is not named here, so without this the feature exists and cannot be used.
    val provider = Option(capabilities.getExecuteCommandProvider)
      .getOrElse(fail("no executeCommandProvider; no client may run flix.showAst"))
    assert(
      provider.getCommands.contains("flix.showAst"),
      s"expected flix.showAst among ${provider.getCommands}",
    )
  }

  test("the capabilities that already worked are still advertised") {
    // Guards the lift of mkServerCapabilities out of FlixLanguageServer: it must keep describing
    // the same server. These are the ones with observable editor behaviour.
    assert(capabilities.getHoverProvider.getLeft, "hover")
    assert(capabilities.getDefinitionProvider.getLeft, "definition")
    assert(capabilities.getReferencesProvider.getLeft, "references")
    assert(capabilities.getDocumentSymbolProvider.getLeft, "documentSymbol")
    assert(capabilities.getFoldingRangeProvider.getLeft, "foldingRange")
    assert(capabilities.getDocumentFormattingProvider.getLeft, "formatting")
    assert(Option(capabilities.getCompletionProvider).isDefined, "completion")
    assert(Option(capabilities.getSignatureHelpProvider).isDefined, "signatureHelp")
    assert(Option(capabilities.getSemanticTokensProvider).isDefined, "semanticTokens")
    assert(Option(capabilities.getCodeLensProvider).isDefined, "codeLens")
  }
}
