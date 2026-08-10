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

import ca.uwaterloo.flix.api.{Bootstrap, Flix, ProjectView, Version}
import ca.uwaterloo.flix.util.{Build, Formatter, Options, Result}

import scala.util.matching.Regex
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

  /**
    * Held for the duration of a build.
    *
    * One `Flix` holds the cached ASTs and a change set, so two concurrent compiles corrupt them. A
    * separate object rather than the session itself, so that a query answered from a snapshot is not
    * blocked behind a compile.
    */
  private val buildLock: AnyRef = new AnyRef

  /**
    * The compiler this session compiles with, kept warm for its whole life.
    *
    * One instance, because that is what makes a second compile incremental: `Bootstrap` keys its
    * record of what it has handed over on the instance it handed it to.
    */
  private val flix: Flix = new Flix().setFormatter(Formatter.NoFormatter).setOptions(options)

  /** Progress notifications. Held here because a test run emits them as its events arrive. */
  private val tasks: BspTasks = new BspTasks(() => client)

  /** Which documents the client has been told about, so the ones that come clean can be cleared. */
  private val ledger: DiagnosticLedger = new DiagnosticLedger

  /**
    * Whether the last compile found an entry point.
    *
    * Remembered so that `jvmRunEnvironment` can name the main class without compiling. It is a query,
    * and a query that compiled would make describing a project as expensive as building it.
    */
  @volatile private var lastCompileHadMain: Boolean = false

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

  /**
    * Compiles the project once, and publishes what the compiler said.
    *
    * ==Serialised, and not on the caller's thread==
    *
    * One `Flix` instance holds the cached ASTs and a change set, so two concurrent compiles would
    * corrupt them; a second instance would double the memory and lose the incrementality, since
    * `Bootstrap` keys its bookkeeping on the instance it was last given. So compiles hold a lock, and
    * they run on the build thread rather than the thread that dispatched the request -- the language
    * server compiles on its RPC thread, which is why a slow check there blocks every other request.
    *
    * ==Generation==
    *
    * The generation is read before the work and checked after it. A compile that finishes after a
    * reload describes a project that no longer exists, and publishing its diagnostics would put
    * markers on a file from a different configuration.
    *
    * Returns the status a client should see: `OK` when the program compiled, `ERROR` when it did not.
    * Diagnostics are published either way, which is the case a `Result` cannot express -- a compile
    * can succeed and still have something to say.
    */
  def compile(target: BuildTargetIdentifier, originId: Option[String]): CompileResult = {
    val startedAt = currentGeneration

    val outcome = buildLock.synchronized {
      val b = requireBootstrapForBuild()
      compileWith(b, b.view)
    }

    if (!isCurrent(startedAt)) {
      // Discarded rather than published. The request still answers, because a client is waiting on
      // it, and `CANCELLED` says the answer is not about the project it asked about.
      return new CompileResult(StatusCode.CANCELLED)
    }

    publish(target, outcome)

    val result = new CompileResult(if (outcome.isSuccess) StatusCode.OK else StatusCode.ERROR)
    originId.foreach(result.setOriginId)
    result
  }

  /**
    * Publishes the diagnostics of `outcome`, and clears the documents that no longer have any.
    *
    * Clearing is only safe when the compiler spoke for everything it spoke for last time, which is
    * what a successful compile means. After a failure a document left unmentioned has not been shown
    * to be clean -- the compiler may simply not have reached it -- so its markers stay.
    */
  private def publish(target: BuildTargetIdentifier, outcome: Bootstrap.CompileOutcome): Unit = {
    val reports = BspDiagnostics.reportsFor(target, outcome.messages, outcome.root)
    val toSend = ledger.publishFor(reports, target, reachedEverySource = outcome.isSuccess)
    client.foreach(c => toSend.foreach(c.onBuildPublishDiagnostics))

    // A diagnostic on a source the client cannot open would otherwise be invisible: it is reported,
    // but against a `flix-lib:` or `jar:` URI that no editor will show. One aggregate message, not
    // one per diagnostic, so it cannot become noise.
    val unopenable = reports.count(r => !BspUri.isOpenable(r.getTextDocument.getUri))
    if (unopenable > 0) {
      showMessage(MessageType.WARNING,
        s"$unopenable file(s) with problems are inside the standard library or a package dependency, " +
          "so your editor may not be able to open them.")
    }

    outcome.error.foreach(e => showMessage(MessageType.ERROR, e.message(Formatter.NoFormatter)))
  }

  /** Returns the loaded project for a build, or fails the way a request expects. */
  private def requireBootstrapForBuild(): Bootstrap = {
    requireView()
    bootstrap.getOrElse(throw invalidRequest("the project is not loaded"))
  }

  /**
    * Compiles, then runs the program in a JVM of its own.
    *
    * Compiled first because `buildTarget/run` is defined to build what it runs -- a client that had to
    * compile separately could run a stale program and would have no way to know.
    *
    * A project with no `main` is refused rather than treated as a run that did nothing.
    * `Bootstrap.run` silently returns in that case, which is defensible for a command and useless to a
    * client: "nothing happened" and "there was nothing to happen" call for different messages.
    *
    * The compile lock is released before the program starts. A user program may run for minutes, and
    * holding the lock across it would block every later compile behind it.
    */
  def run(target: BuildTargetIdentifier, arguments: List[String], originId: Option[String]): RunResult = {
    val startedAt = currentGeneration

    val (outcome, view) = buildLock.synchronized {
      val b = requireBootstrapForBuild()
      val v = b.view
      (compileWith(b, v), v)
    }

    if (!isCurrent(startedAt)) {
      return statusOf(new RunResult(StatusCode.CANCELLED), originId)
    }

    publish(target, outcome)

    if (!outcome.isSuccess) {
      // Not run. A program that did not compile cannot be run, and the diagnostics just published say
      // why, so there is nothing to add.
      return statusOf(new RunResult(StatusCode.ERROR), originId)
    }

    val hasMain = outcome.root.exists(_.mainEntryPoint.isDefined)
    if (!hasMain) {
      showMessage(MessageType.ERROR,
        s"${view.packageName} has no main function, so there is nothing to run.")
      return statusOf(new RunResult(StatusCode.ERROR), originId)
    }

    val result = BspRunner.run(
      view, Build.Development, arguments,
      // The program's own output, as log messages. A client shows these in its run console; they are
      // not diagnostics and must not be mistaken for them.
      line => logMessage(line),
      RunTimeout)

    if (result.timedOut) {
      showMessage(MessageType.ERROR, s"${view.packageName} did not finish within ${RunTimeout.toMinutes} minutes and was stopped.")
    }
    statusOf(new RunResult(if (result.isSuccess) StatusCode.OK else StatusCode.ERROR), originId)
  }

  /**
    * Compiles, then runs the project's tests, reporting each one as it happens.
    *
    * ==In this process, deliberately for now==
    *
    * A test is a compiled function reflected and called, which is what `Tester` does, so the tests run
    * in the server's JVM rather than a forked one. The consequence has to be stated rather than
    * discovered: a test that calls `System.exit` takes the server with it, and one that loops forever
    * holds the build lock until the client gives up. `jvmTestEnvironment` is the way out -- a client
    * that wants isolation forks with that classpath. Forking here needs a test-runner entry point in
    * the compiled program, which does not exist.
    *
    * The console rendering is *not* attached: it builds a system terminal and writes to the real file
    * descriptor, which here carries the protocol. The events go to a [[BspTestSink]] instead, and both
    * renderings agree about pass and fail because both watch the same runner.
    */
  def test(target: BuildTargetIdentifier, filters: List[Regex], originId: Option[String]): TestResult = {
    val startedAt = currentGeneration
    val parent = tasks.newTask()

    val view = requireView()
    tasks.start(parent, s"Testing ${view.packageName}", Some((TaskStartDataKind.TEST_TASK, new TestTask(target))))

    val sink = new BspTestSink(tasks, target, parent)
    val (outcome, ran) = buildLock.synchronized {
      val b = requireBootstrapForBuild()
      b.testWith(flix, filters, sink)
    }

    val status =
      if (!isCurrent(startedAt)) StatusCode.CANCELLED
      else {
        publish(target, outcome)
        ran match {
          // The program did not compile, so no test ran. The diagnostics just published say why.
          case None => StatusCode.ERROR
          case Some(succeeded) => if (succeeded) StatusCode.OK else StatusCode.ERROR
        }
      }

    tasks.finish(parent, s"Tested ${view.packageName}", status,
      Some((TaskFinishDataKind.TEST_REPORT, sink.report())))

    val result = new TestResult(status)
    originId.foreach(result.setOriginId)
    result
  }

  /** Returns the environment a client needs to run this project's program itself. */
  def jvmEnvironment(target: BuildTargetIdentifier): JvmEnvironmentItem = {
    val view = requireView()
    val item = new JvmEnvironmentItem(
      target,
      view.runtimeClasspath(Build.Development).map(BspUri.ofFile).asJava,
      // No options. The compiler's own `Enable-Native-Access` is about the compiler, not about a
      // program it produced, and inventing flags here would silently change how a client runs one.
      List.empty[String].asJava,
      view.projectPath.toAbsolutePath.toString,
      // Empty rather than this process's environment. A client that wants the parent environment
      // already has it, and a server that quietly injected its own `PATH` would make a run
      // unreproducible.
      java.util.Collections.emptyMap[String, String]())
    item.setMainClasses(mainClasses().asJava)
    item
  }

  /**
    * Returns the program's entry class, if it has one.
    *
    * Answered from the *last* compile rather than by compiling: `jvmRunEnvironment` is a query, and a
    * query that compiled would make a client's attempt to describe a project as expensive as building
    * it. A project that has not been compiled yet reports no main class, which is true -- nothing is
    * known about it yet.
    */
  private def mainClasses(): List[JvmMainClass] =
    if (lastCompileHadMain) List(new JvmMainClass(BspRunner.MainClass, List.empty[String].asJava))
    else Nil

  /** Compiles under an already-held lock. */
  private def compileWith(b: Bootstrap, view: ProjectView): Bootstrap.CompileOutcome = {
    val configured = flix.options.copy(
      build = Build.Development,
      outputJvm = true,
      outputPath = view.outputDirectories(Build.Development),
      loadClassFiles = false,
      progress = false)
    flix.setOptions(configured)
    val outcome = b.compileProjectOutcome(flix, clean = false)
    lastCompileHadMain = outcome.root.exists(_.mainEntryPoint.isDefined)
    outcome
  }

  /** Attaches `originId` to `result`, which is how a client correlates it with its request. */
  private def statusOf(result: RunResult, originId: Option[String]): RunResult = {
    originId.foreach(result.setOriginId)
    result
  }

  /** The attached client, for a helper that must tolerate not having one yet. */
  def currentClient: Option[BuildClient] = client

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

  /**
    * How long a run may take before the server stops it.
    *
    * A server that waited forever would be held open by any program that does not end, and a client
    * has no way to cancel a process it cannot see. Generous, because a legitimate program may be slow.
    */
  private val RunTimeout: java.time.Duration = java.time.Duration.ofMinutes(10)

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
