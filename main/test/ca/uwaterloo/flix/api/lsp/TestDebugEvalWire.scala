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
package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.api.lsp.provider.DebugEvalProvider.Answer
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters.*

/**
  * That `flix/debugEval/compile` is actually on the wire, and that an answer survives the crossing.
  *
  * The method name is a string in two places -- the annotation here and the request a client sends
  * -- and LSP4J's dispatch is by exact match. A typo is answered with `MethodNotFound` and looks, to
  * a user, like a debugger that has stopped responding rather than like a name that does not agree.
  * Nothing else in this repository would notice.
  */
class TestDebugEvalWire extends AnyFunSuite {

  /**
    * The server class, by name.
    *
    * By name because it is private to [[LspServer]], and it should stay private: the endpoint is a
    * protocol detail, and nothing but the launcher has business holding one. Reaching it this way is
    * the smaller of two evils -- the alternative is widening the class so a test can see it.
    */
  private val serverClass: Class[?] =
    Class.forName("ca.uwaterloo.flix.api.lsp.LspServer$FlixLanguageServer")

  /** Every JSON-RPC method the server answers, by name. */
  private def supportedMethods: Set[String] =
    ServiceEndpoints.getSupportedMethods(serverClass).keySet().asScala.toSet

  test("the debug-eval request is registered under the name a client sends") {
    val methods = supportedMethods

    assert(
      methods.contains("flix/debugEval/compile"),
      s"the request is not on the wire. Registered: ${methods.toList.sorted.mkString(", ")}",
    )
  }

  test("the standard requests are still registered beside it") {
    // Without this, a change that broke annotation scanning entirely would leave the test above
    // failing for a reason that has nothing to do with this request -- or, worse, a change that
    // registered nothing would be read as a naming problem.
    assert(supportedMethods.contains("initialize"), s"annotation scanning found nothing: ${supportedMethods.toList}")
  }

  test("a typed expression crosses as its type and effect") {
    val result = DebugEvalResult.of(Answer.Ok("Option[String]", "Pure", None))

    assert(result.protocolVersion == DebugEvalProtocol.Version)
    assert(result.status == "ok")
    assert(result.tpe == "Option[String]")
    assert(result.eff == "Pure")
    assert(result.reason == null, "an answer carried a rejection reason")
  }

  test("a compiler diagnostic crosses as a failure, not as a rejection") {
    // The distinction a client acts on: a failure is about the expression the user typed, a
    // rejection is about this server declining to answer. Collapsing them sends a user looking in
    // the wrong place.
    val result = DebugEvalResult.of(Answer.Failed(List("Undefined name 'nope'.")))

    assert(result.status == "failed")
    assert(result.diagnostics.asScala.toList == List("Undefined name 'nope'."))
    assert(result.tpe == null, "a failure carried a type")
  }

  test("a rejection crosses with what would have to change") {
    val result = DebugEvalResult.of(Answer.Rejected("the program must be built with --Xdebug"))

    assert(result.status == "rejected")
    assert(result.reason.contains("--Xdebug"))
    assert(result.diagnostics.isEmpty, "a rejection carried diagnostics")
  }

  test("the policy is parsed from what a client may send, and nothing else") {
    import ca.uwaterloo.flix.api.lsp.provider.DebugEvalProvider.Policy

    assert(Policy.parse("pure").contains(Policy.Pure))
    assert(Policy.parse("allowEffects").contains(Policy.AllowEffects))
    assert(Policy.parse("Pure").isEmpty, "the policy is case-sensitive on the wire")
    assert(Policy.parse("anything").isEmpty)
  }

  test("a request defaults to the policy that runs nothing") {
    // A client that omits the field gets the conservative answer rather than permission to run the
    // debuggee's own code.
    assert(new DebugEvalParams().getPolicy() == "pure")
  }

  test("a request has to opt into the current protocol") {
    val request = new DebugEvalParams()

    assert(request.getProtocolVersion() == 0)
    request.setProtocolVersion(DebugEvalProtocol.Version)
    assert(request.getProtocolVersion() == 1)
  }

  test("a request carries the identity of the program that is actually paused") {
    val request = new DebugEvalParams()
    request.setBuildId("fingerprint:sources")

    assert(request.getBuildId() == "fingerprint:sources")
  }
}
