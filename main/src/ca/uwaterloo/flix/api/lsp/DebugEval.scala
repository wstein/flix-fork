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

import java.util.Base64

import scala.beans.BeanProperty

/**
  * The request of `flix/debugEval/compile`.
  *
  * ==Why a custom request==
  *
  * Nothing in the LSP standard describes this. It is not about a document: the client is a debugger
  * paused in a *frame*, and what it names is a generated class and a method, which no position in
  * any file identifies. The standard's evaluation-shaped features are for a document, and a debug
  * adapter's are for an adapter this project deliberately does not have (ADR 0002).
  *
  * It goes over LSP rather than over a build protocol because the state it needs is already here: a
  * long-lived compiler with the project's typed AST in memory. BSP has no scoped-expression
  * operation, and its compile model writes build output, which is exactly what an evaluation must
  * not do.
  *
  * ==Fields==
  *
  * `className` and `methodName` are the frame, as JDI reports it. `expression` is what the
  * programmer typed. `policy` is `pure` or `allowEffects` and decides what the answer is allowed to
  * be, not what it is: the expression is typed either way, and the effect is reported either way.
  *
  * Plain mutable beans because that is what LSP4J's reflective JSON binding requires; nothing here
  * is read before the framework has finished populating it.
  */
class DebugEvalParams {
  @BeanProperty var className: String = _

  /**
    * Whether to produce the classes that would *run* the expression, not only type it.
    *
    * Off by default: typing is one compilation and producing an artifact is a second one that also
    * emits, and a watch asks the first on every step.
    */
  @BeanProperty var withArtifact: Boolean = false
  @BeanProperty var methodName: String = _
  @BeanProperty var expression: String = _
  @BeanProperty var policy: String = "pure"
}

/**
  * The reply of `flix/debugEval/compile`.
  *
  * ==Why an outcome rather than a result or an error==
  *
  * Three things can happen and only one of them is a failure of the *request*:
  *
  *   - `ok` — the expression typed, and its type and effect are reported;
  *   - `failed` — it did not type, and the compiler's own diagnostics say why;
  *   - `rejected` — this server declined: there is no debug build, the frame belongs to a snapshot
  *     it does not have, or the policy forbids the effect. A rejection names what would have to
  *     change.
  *
  * Sending a rejection as a JSON-RPC error would put "you have not built with --Xdebug" in the same
  * channel as "the server crashed", and a client cannot tell them apart or act on the first.
  */
class DebugEvalResult {
  /** One of `ok`, `failed`, `rejected`. */
  @BeanProperty var status: String = _

  /** The expression's type, when `ok`. */
  @BeanProperty var tpe: String = _

  /** The expression's effect, when `ok`. `Pure` is the answer a watch may act on. */
  @BeanProperty var eff: String = _

  /** The compiler's diagnostics, when `failed`. */
  @BeanProperty var diagnostics: java.util.List[String] = new java.util.ArrayList[String]()

  /** What would have to change, when `rejected`. */
  @BeanProperty var reason: String = _

  /**
    * The classes that would run the expression, as `name=base64` entries separated by `;`.
    *
    * Absent unless the client asked for one: typing is the cheaper question and the one a watch asks
    * on every step. One string rather than a structure because it crosses a debug connection next,
    * where every argument has to be built inside the debuggee one value at a time.
    */
  @BeanProperty var artifact: String = _

  /** The class holding the expression, and the static method to call on it. */
  @BeanProperty var entryClass: String = _

  @BeanProperty var entryMethod: String = _

  /**
    * Which field of the runtime's `Value` holds the result.
    *
    * It carries one field per erased type and no discriminator, so this is the compiler saying where
    * to look rather than the reader guessing.
    */
  @BeanProperty var valueField: String = _

  /** The frame variables to pass, in the order the entry method takes them. */
  @BeanProperty var parameters: java.util.List[String] = new java.util.ArrayList[String]()
}

object DebugEvalResult {

  def of(answer: Answer): DebugEvalResult = answer match {
    case Answer.Ok(tpe, eff, artifact) =>
      val r = new DebugEvalResult
      r.status = "ok"
      r.tpe = tpe
      r.eff = eff
      artifact.foreach { a =>
        r.artifact = a.classes
          .map { case (name, bytes) => s"$name=${Base64.getEncoder.encodeToString(bytes)}" }
          .mkString(";")
        r.entryClass = a.entryClass
        r.entryMethod = a.entryMethod
        r.valueField = a.valueField
        a.parameters.foreach(r.parameters.add)
      }
      r

    case Answer.Failed(diagnostics) =>
      val r = new DebugEvalResult
      r.status = "failed"
      diagnostics.foreach(r.diagnostics.add)
      r

    case Answer.Rejected(reason) => rejected(reason)
  }

  def rejected(reason: String): DebugEvalResult = {
    val r = new DebugEvalResult
    r.status = "rejected"
    r.reason = reason
    r
  }

  def failure(t: Throwable): DebugEvalResult = {
    val where = t.getStackTrace.headOption.map(f => s" at $f").getOrElse("")
    rejected(s"the compiler failed while evaluating this expression: $t$where")
  }
}
