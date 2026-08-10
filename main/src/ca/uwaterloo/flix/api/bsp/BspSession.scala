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
package ca.uwaterloo.flix.api.bsp

import ca.uwaterloo.flix.api.{Bootstrap, ProjectView, Version}
import ca.uwaterloo.flix.util.{Formatter, Options, Result}
import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{ResponseError, ResponseErrorCode}

import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
  * One client's connection: what state it is in, what project it is about, and who may ask what.
  *
  * ==Why the state machine is explicit==
  *
  * The lifecycle is the part of BSP a server gets wrong invisibly. A request answered before
  * `build/initialize` is answered about a project nobody has loaded; a request answered after
  * `build/shutdown` is answered by a server that has promised to stop. Both look like they work, and
  * both leave a client acting on a reply it should never have received. So the states are named, the
  * transitions are in one place, and `TestBspLifecycle` walks them.
  *
  * The generation counter is here for the same reason, one phase early: work that outlives a reload
  * must not publish against the project it was started under. Nothing produces such work yet, and
  * the counter costs one field.
  */
class BspSession(val projectPath: Path, options: Options, log: BspLogStream) {

  import BspSession.*

  /** What the connection is allowed to do. */
  @volatile private var state: State = State.Uninitialized

  /** The client, once `connect` has been called. */
  @volatile private var client: Option[BuildClient] = None

  /** The languages the client said it supports, from `build/initialize`. */
  @volatile private var clientLanguageIds: List[String] = Nil

  /** The loaded project. `None` until `build/initialize` succeeds. */
  @volatile private var bootstrap: Option[Bootstrap] = None

  /** Bumped whenever the project is reloaded, so stale work can be recognised as stale. */
  private val generation: AtomicLong = new AtomicLong(0)

  /** Everything the compiler would have printed, as client log messages. */
  private val out: PrintStream = new PrintStream(log, true, "UTF-8")

  /** Attaches the client and releases anything logged before it arrived. */
  def connect(c: BuildClient): Unit = {
    client = Some(c)
    log.connect(c)
  }

  /** The current generation. Work started under one generation is void under any other. */
  def currentGeneration: Long = generation.get()

  /** Returns `true` if `g` is still the generation the caller started under. */
  def isCurrent(g: Long): Boolean = g == generation.get()

  /**
    * Loads the project and moves to `Initialized`.
    *
    * The project is loaded here rather than lazily on the first request, because this is the request
    * whose reply a client waits for before doing anything, and because a project that cannot load
    * should be reported as a failed initialize rather than as a failed compile.
    *
    * `rootUri` is checked against the directory this server was started in. A client that asks about
    * a different project is refused: serving it would mean answering about a project whose
    * dependencies were never resolved, and silently substituting one project for another is worse
    * than saying no.
    */
  def initialize(params: InitializeBuildParams): InitializeBuildResult = synchronized {
    state match {
      case State.Initialized => throw invalidRequest("this connection is already initialized")
      case State.ShutDown => throw invalidRequest("this connection has been shut down")
      case State.Uninitialized => ()
    }

    requireSameProject(params.getRootUri)

    clientLanguageIds =
      Option(params.getCapabilities).flatMap(c => Option(c.getLanguageIds)).map(_.asScala.toList).getOrElse(Nil)

    // Dependency resolution can reach the network and narrates while it does. Its output goes to the
    // client's log, never to standard output, which belongs to the protocol.
    implicit val formatter: Formatter = Formatter.NoFormatter
    implicit val printStream: PrintStream = out
    Bootstrap.bootstrap(projectPath, options.githubToken) match {
      case Result.Ok(b) =>
        bootstrap = Some(b)
        state = State.Initialized
        new InitializeBuildResult(
          ServerName, Version.CurrentVersion.toString, Bsp4j.PROTOCOL_VERSION,
          BspCapabilities.mkServerCapabilities())

      case Result.Err(e) =>
        // The connection stays uninitialized: a client that retries gets another honest attempt,
        // where a half-initialized session would answer questions about nothing.
        throw new ResponseErrorException(
          new ResponseError(ResponseErrorCode.InternalError, s"cannot load the project: ${e.message(Formatter.NoFormatter)}", null))
    }
  }

  /**
    * Records the client's acknowledgement.
    *
    * Idempotent on purpose: a second one is a client bug, and dropping the connection over it would
    * turn a harmless mistake into a broken editor.
    */
  def initialized(): Unit = ()

  /** Moves to `ShutDown`, after which no request is served and no notification is published. */
  def shutdown(): Unit = synchronized {
    state = State.ShutDown
  }

  /** Returns `true` once `build/shutdown` has been received. */
  def isShutDown: Boolean = state == State.ShutDown

  /**
    * Returns the view a request should be answered from, or fails.
    *
    * Every request that is about the project goes through here, which is what makes the two
    * lifecycle rules unavoidable rather than remembered.
    */
  def requireView(): ProjectView = synchronized {
    state match {
      case State.Uninitialized =>
        // -32002. The code matters: a client distinguishes "not ready" from "broken" by it, and
        // retries the first.
        throw new ResponseErrorException(
          new ResponseError(ResponseErrorCode.ServerNotInitialized, "build/initialize has not been received", null))
      case State.ShutDown =>
        throw invalidRequest("this connection has been shut down")
      case State.Initialized =>
        bootstrap match {
          case Some(b) => b.view
          case None => throw invalidRequest("the project is not loaded")
        }
    }
  }

  /**
    * Returns the project's targets, filtered by what the client said it understands.
    *
    * The filter is required rather than polite: a server must not answer with targets for a language
    * the client did not advertise.
    */
  def buildTargets(): List[BuildTarget] = {
    val view = requireView()
    if (BuildTargets.servesClient(clientLanguageIds)) {
      List(BuildTargets.target(view, BspCapabilities.mkTargetCapabilities()))
    } else {
      Nil
    }
  }

  /** Sends `message` to the client's log, if there is a client. */
  def logMessage(message: String): Unit =
    client.foreach(_.onBuildLogMessage(new LogMessageParams(MessageType.LOG, message)))

  /** Shows `message` to the user, for something they have to know about rather than look up. */
  def showMessage(kind: MessageType, message: String): Unit =
    client.foreach(_.onBuildShowMessage(new ShowMessageParams(kind, message)))

  /**
    * Fails unless `rootUri` names the directory this server was started in.
    */
  private def requireSameProject(rootUri: String): Unit = {
    if (rootUri == null) {
      throw invalidParams("build/initialize carried no rootUri")
    }
    BspUri.toPath(rootUri) match {
      case None =>
        throw invalidParams(s"build/initialize carried a rootUri that is not a file uri: $rootUri")
      case Some(asked) =>
        if (canonical(asked) != canonical(projectPath)) {
          throw invalidParams(
            s"this server serves '${projectPath.toAbsolutePath.normalize()}', but build/initialize asked for " +
              s"'${asked.toAbsolutePath.normalize()}'. Start a server in that directory instead.")
        }
    }
  }

  /**
    * Returns `p` with symbolic links resolved, falling back to normalising when it cannot be.
    *
    * Comparing normalised paths is not enough, and the difference is not exotic: on macOS a temporary
    * directory is under `/var`, which is a link to `/private/var`, and the working directory the JVM
    * reports is the resolved one while the `rootUri` an editor sends is whatever the user opened. Two
    * spellings of one directory would then look like two projects, and a correct client would be
    * refused. `normalize` cannot see that -- it is a purely textual operation.
    */
  private def canonical(p: Path): Path =
    try p.toRealPath()
    catch {
      // The directory may not exist -- a client can ask about one that was deleted -- and then the
      // textual answer is the best available.
      case _: java.io.IOException => p.toAbsolutePath.normalize()
    }

  private def invalidRequest(message: String): ResponseErrorException =
    new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidRequest, message, null))

  private def invalidParams(message: String): ResponseErrorException =
    new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidParams, message, null))
}

object BspSession {

  /** What this server calls itself in the initialize result and in `.bsp/flix.json`. */
  val ServerName: String = "flix"

  /** The states a connection passes through, in order. */
  private sealed trait State

  private object State {
    /** No `build/initialize` yet. Requests are refused with `ServerNotInitialized`. */
    case object Uninitialized extends State

    /** Serving. */
    case object Initialized extends State

    /** `build/shutdown` received. Requests are refused and nothing is published. */
    case object ShutDown extends State
  }
}
