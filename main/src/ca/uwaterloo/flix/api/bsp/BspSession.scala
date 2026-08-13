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

import ca.uwaterloo.flix.api.{Bootstrap, Flix, ProgramRunner, ProjectView, Version}
import ca.uwaterloo.flix.util.{Build, Formatter, Options, Result}

import scala.util.matching.Regex
import ch.epfl.scala.bsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.{ResponseError, ResponseErrorCode}

import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
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
    * Guards [[joinable]] only, and is never held across a build.
    *
    * A separate monitor from [[buildLock]] on purpose: the whole point of the slot is to be readable
    * and claimable *while* a build is running.
    */
  private val gate: AnyRef = new AnyRef

  /**
    * The compile a later request may join instead of queueing another, if there is one.
    *
    * Non-empty means: a caller has claimed the next build and has not started it yet.
    */
  private var joinable: Option[CompletableFuture[Bootstrap.CompileOutcome]] = None

  /**
    * The compiler this session compiles with, kept warm for its whole life.
    *
    * One instance, because that is what makes a second compile incremental: `Bootstrap` keys its
    * record of what it has handed over on the instance it handed it to.
    */
  // A `var` because a reload replaces it. The instance holds the cached ASTs and the change set of one
  // project configuration, so carrying it across a reload would answer questions about the new project
  // from the old one's caches.
  @volatile private var flix: Flix = mkFlix()

  /** Progress notifications. Held here because a test run emits them as its events arrive. */
  private val tasks: BspTasks = new BspTasks(() => liveClient)

  /** Which documents the client has been told about, so the ones that come clean can be cleared. */
  private val ledger: DiagnosticLedger = new DiagnosticLedger

  /**
    * Whether the last compile found an entry point.
    *
    * Remembered so that `jvmRunEnvironment` can name the main class without compiling. It is a query,
    * and a query that compiled would make describing a project as expensive as building it.
    */
  @volatile private var lastCompileHadMain: Boolean = false

  /** Whether `build/exit` arrived, and whether a shutdown preceded it. */
  @volatile private var exitAfterShutdown: Option[Boolean] = None

  /** Everything the compiler would have printed, as client log messages. */
  private val out: PrintStream = new PrintStream(log, true, "UTF-8")

  /** Attaches the client and releases anything logged before it arrived. */
  def connect(c: BuildClient): Unit = {
    client = Some(c)
    log.connect(c)
  }

  /**
    * Returns `true` if this client was offered a target at all.
    *
    * A client that advertised no support for Flix is told about no targets -- and can still *compute*
    * the target's URI, since it is derived from the project path, and ask for a compile with it. The
    * language filter would then be a formality that only shaped one reply. Every target-scoped request
    * asks this, so a target that was never offered cannot be operated on.
    */
  def servesTargets: Boolean = BuildTargets.servesClient(clientLanguageIds)

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
      case State.AwaitingAck | State.Ready => throw invalidRequest("this connection is already initialized")
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
        state = State.AwaitingAck
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
    * Records the client's acknowledgement, after which requests are served.
    *
    * A notification, so there is no reply to put an error in: a duplicate cannot be refused and
    * dropping the connection over one would turn a client's harmless mistake into a broken editor. It
    * is reported to the client's log instead, which is the only channel a notification has, and the
    * state does not move -- so a second acknowledgement cannot resurrect a session that has since been
    * shut down.
    */
  def initialized(): Unit = synchronized {
    state match {
      case State.AwaitingAck =>
        state = State.Ready
      case other =>
        logMessage(s"build/initialized received while $other; ignored. It is sent exactly once, " +
          "after the reply to build/initialize.")
    }
  }

  /**
    * Moves to `ShutDown`, after which no request is served and no notification is published.
    *
    * A request like any other, so it answers to the same state machine: before the handshake it is
    * `ServerNotInitialized`, and a second one is an `InvalidRequest`. Letting it through unchecked made
    * this the one request that could shut down a session that had never started -- and the state model
    * says only `Ready` serves requests, so the exception was silent rather than argued for.
    */
  def shutdown(): Unit = synchronized {
    state match {
      case State.Uninitialized =>
        throw new ResponseErrorException(new ResponseError(
          ResponseErrorCode.ServerNotInitialized, "build/initialize has not been received", null))
      case State.AwaitingAck =>
        throw new ResponseErrorException(new ResponseError(ResponseErrorCode.ServerNotInitialized,
          "build/initialized has not been received; a client may not send requests before it", null))
      case State.ShutDown =>
        throw invalidRequest("this connection has already been shut down")
      case State.Ready => ()
    }

    state = State.ShutDown
    // Work already in flight becomes void as well, not just future work: a compile that was running
    // when this arrived answers into a connection that is closing down, and its result describes a
    // session nobody is using.
    generation.incrementAndGet()
  }

  /** Returns `true` once `build/shutdown` has been received. */
  def isShutDown: Boolean = state == State.ShutDown

  /** Records that `build/exit` arrived, so the process can exit with the status the client earned. */
  def exited(): Unit = synchronized {
    exitAfterShutdown = Some(state == State.ShutDown)
    state = State.ShutDown
  }

  /**
    * The status this process should exit with.
    *
    * The specification is exact about it: after `build/exit`, success only if `build/shutdown` came
    * first, and an error otherwise. A connection that simply ended -- an editor that was killed, a pipe
    * that closed -- asked for nothing and gets 0, since there is no client left to report a failure to.
    */
  def exitStatus: Int = exitAfterShutdown match {
    case Some(true) => 0
    case Some(false) => 1
    case None => 0
  }

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
      case State.AwaitingAck =>
        // Also -32002, and for the same reason: the handshake is not finished until the client
        // acknowledges it, and a client that jumped the gun should retry rather than conclude the
        // server is broken.
        throw new ResponseErrorException(new ResponseError(ResponseErrorCode.ServerNotInitialized,
          "build/initialized has not been received; a client may not send requests before it", null))
      case State.ShutDown =>
        throw invalidRequest("this connection has been shut down")
      case State.Ready =>
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
    *
    * ==Coalescing==
    *
    * Concurrent requests that all arrive before a build starts share it. See [[compileOrJoin]] for the
    * condition that makes that sound; the visible consequence here is that only the request whose
    * build ran publishes the diagnostics.
    */
  def compile(target: BuildTargetIdentifier, originId: Option[String]): BspSession.CompileAnswer = {
    val startedAt = currentGeneration

    val (outcome, ran) = compileOrJoin()

    if (!isCurrent(startedAt)) {
      // Discarded rather than published. The request still answers, because a client is waiting on
      // it, and `CANCELLED` says the answer is not about the project it asked about -- with the origin
      // id it arrived with, since a client correlates every answer by it, cancelled ones included.
      val cancelled = new CompileResult(StatusCode.CANCELLED)
      originId.foreach(cancelled.setOriginId)
      return BspSession.CompileAnswer(cancelled, diagnostics = 0)
    }

    // Only the request whose build actually ran publishes. The ledger is the record of what the client
    // has been told, so a second publication of the same reports would resend every marker and clear
    // nothing -- and every joiner would do it again.
    if (ran) {
      publish(target, outcome)
    }

    val result = new CompileResult(if (outcome.isSuccess) StatusCode.OK else StatusCode.ERROR)
    originId.foreach(result.setOriginId)
    // The count travels with the status because only this side has it: the messages are the compiler's,
    // and a report built from the status alone can say no more than "some" or "none".
    BspSession.CompileAnswer(result, diagnostics = outcome.messages.length)
  }

  /**
    * Compiles, or joins a compile that has been claimed and has not started.
    *
    * ==Why this is sound, and where the line is==
    *
    * An editor compiles on save, and a person saving repeatedly used to queue one whole-program
    * compile per keystroke, each taking seconds and each already obsolete when it started. Collapsing
    * them is only honest under one condition, which is the invariant this maintains: a request may
    * share another's build **only if that build has not started yet**. The claim is registered before
    * the build lock is acquired and released once it is held, so every joiner arrived before the
    * compile it shares began reading the sources -- and `Steps.rescanSources` then sees everything all
    * of them had written. Nobody is told about a compile that predates their edit.
    *
    * A request that arrives while a build is *running* therefore does not join it: that build may have
    * started before the edit the request is about. It claims the next slot instead and waits, which is
    * what makes two concurrent saves cost two builds rather than one, and twenty cost two as well.
    *
    * Only `buildTarget/compile` goes through here. A run and a test have effects their caller asked
    * for, so sharing one between two requests would be answering a question nobody asked.
    *
    * @return the outcome, and whether this caller is the one whose build produced it.
    */
  private def compileOrJoin(): (Bootstrap.CompileOutcome, Boolean) = {
    val (owner, shared) = gate.synchronized {
      joinable match {
        case Some(promise) => (false, promise)
        case None =>
          val promise = new CompletableFuture[Bootstrap.CompileOutcome]()
          joinable = Some(promise)
          (true, promise)
      }
    }

    if (!owner) {
      // Waits for the build claimed ahead of this request, and fails the same way it did: a joiner
      // must not report success for a build that could not happen.
      return (awaiting(shared), false)
    }

    buildLock.synchronized {
      // The build is about to read the sources, so the slot closes here: from now on a new request
      // needs its own build.
      gate.synchronized { joinable = None }
      try {
        val b = requireBootstrapForBuild()
        val outcome = compileWith(b, b.view)
        shared.complete(outcome)
        (outcome, true)
      } catch {
        case t: Throwable =>
          // Every joiner is waiting on this, and a promise nobody completes is a request that never
          // answers.
          shared.completeExceptionally(t)
          throw t
      }
    }
  }

  /** Returns `promise`'s value, rethrowing its failure rather than a wrapper around it. */
  private def awaiting(promise: CompletableFuture[Bootstrap.CompileOutcome]): Bootstrap.CompileOutcome =
    try promise.get()
    catch {
      // `get` wraps everything in an `ExecutionException`, which would reach a client as an internal
      // error with a useless message instead of the refusal the build actually produced.
      case e: java.util.concurrent.ExecutionException => throw Option(e.getCause).getOrElse(e)
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
    liveClient.foreach(c => toSend.foreach(c.onBuildPublishDiagnostics))

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
  def run(target: BuildTargetIdentifier, arguments: List[String], originId: Option[String],
          cancellation: Cancellation,
          workingDirectory: Option[Path] = None,
          environment: Map[String, String] = Map.empty): RunResult = {
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

    if (!outcome.hasMain) {
      showMessage(MessageType.ERROR,
        s"${view.packageName} has no main function, so there is nothing to run.")
      return statusOf(new RunResult(StatusCode.ERROR), originId)
    }

    val result = BspRunner.run(
      view, Build.Development, arguments,
      // `run/printStdout`, which is what this protocol version gives a program's output. It used to go
      // to `build/logMessage` for want of anywhere better, and a client showed a run's output in its
      // build log beside dependency resolution. The two streams are merged before they get here, so a
      // program's writes to standard error arrive on this one -- the price of preserving the program's
      // own interleaving, which is what a reader of the output actually needs.
      line => printStdout(line, originId),
      RunTimeout,
      workingDirectory = workingDirectory,
      environment = environment,
      // A cancelled run stops its program. Dropping the reply and leaving the process running would
      // hold the terminal, the build lock and the output stream until it happened to end, which is not
      // cancellation in any sense a user would recognise.
      onStart = process => cancellation.onCancel(() => ProgramRunner.terminateTree(process, KillGrace)))

    if (cancellation.isCancelled) {
      return statusOf(new RunResult(StatusCode.CANCELLED), originId)
    }

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
  def test(target: BuildTargetIdentifier, filters: List[String], originId: Option[String],
           cancellation: Cancellation,
           environment: Map[String, String] = Map.empty): TestResult = {
    val startedAt = currentGeneration
    val view = requireView()

    val parent = tasks.newTask()
    val sink = new BspTestSink(tasks, target, parent, onOutput = line => logMessage(line))
    tasks.start(parent, s"Testing ${view.packageName}", Some((TaskStartDataKind.TEST_TASK, new TestTask(target))))

    var status: StatusCode = StatusCode.ERROR
    try {
      // Compiled here, and only here: the diagnostics are the server's to publish, and a client must not
      // have to read a test runner's output to learn that its program does not compile. The fork then
      // finds the build current and compiles nothing.
      val outcome = buildLock.synchronized {
        val b = requireBootstrapForBuild()
        compileWith(b, view)
      }

      status =
        if (cancellation.isCancelled || !isCurrent(startedAt)) StatusCode.CANCELLED
        else {
          publish(target, outcome)
          if (!outcome.isSuccess) {
            // No test ran. The diagnostics just published say why.
            StatusCode.ERROR
          } else {
            val result = BspForkedTester.run(
              view, filters, sink,
              // A cancelled test run kills the fork, which is the whole reason the tests are over there:
              // stopping a process is an ordinary operation where stopping a thread is not one at all.
              onStart = process => cancellation.onCancel(() => ProgramRunner.terminateTree(process, KillGrace)),
              TestTimeout,
              environment)(flix)

            if (result.timedOut) {
              showMessage(MessageType.ERROR,
                s"the tests of ${view.packageName} did not finish within ${TestTimeout.toMinutes} minutes and were stopped.")
            } else if (!result.started && !result.passed) {
              // A runner that failed before its first event -- a classpath that does not resolve, a `java`
              // that is not there -- leaves an empty test tree and a red status, which reads as "no tests"
              // rather than "the runner never ran". Its own output went to the server log, so this says
              // where to look rather than repeating it.
              showMessage(MessageType.ERROR,
                s"the test runner of ${view.packageName} produced no events. Its output is in the build server log.")
            }
            if (cancellation.isCancelled) StatusCode.CANCELLED
            else if (result.passed) StatusCode.OK
            else StatusCode.ERROR
          }
        }

      val result = new TestResult(status)
      originId.foreach(result.setOriginId)
      result
    } finally {
      val report = sink.report()
      originId.foreach(report.setOriginId)
      tasks.finish(parent, s"Tested ${view.packageName}", status,
        Some((TaskFinishDataKind.TEST_REPORT, report)))
    }
  }

  /**
    * Reloads the project from `flix.toml` and the directory, atomically.
    *
    * ==Why a whole new session, and why not in place==
    *
    * A reload is not a rescan. `flix.toml` may have gained a dependency, changed a version or dropped
    * one, so the answer to every question this server serves can change -- and a `Bootstrap` mutated
    * halfway through re-resolution would answer some of them from the old project and some from the
    * new. So a fresh `Bootstrap` and a fresh `Flix` are built first, and only a complete one is
    * installed.
    *
    * **A reload that fails changes nothing.** The previous session keeps serving and the request
    * fails, because a typo in a manifest must not leave an editor connected to a dead server. That is
    * the whole reason this is transactional rather than a re-run of what `initialize` does.
    *
    * The generation is bumped, so a compile that was already running finishes into a project that no
    * longer exists and its diagnostics are discarded rather than published.
    *
    * Published markers are cleared before they are forgotten. A file that is no longer part of the
    * project cannot be spoken for by any later compile, so its marker would otherwise stay until the
    * editor restarted; the next compile republishes whatever is still wrong. A momentary clean slate
    * after an explicit user action is the better of the two errors.
    */
  def reload(): Unit = {
    val view = requireView()

    implicit val formatter: Formatter = Formatter.NoFormatter
    implicit val printStream: PrintStream = out

    buildLock.synchronized {
      Bootstrap.bootstrap(projectPath, options.githubToken) match {
        case Result.Err(e) =>
          val message = s"reloading ${view.packageName} failed, so the previous configuration is still " +
            s"in use: ${e.message(Formatter.NoFormatter)}"
          showMessage(MessageType.ERROR, message)
          throw new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError, message, null))

        case Result.Ok(reloaded) =>
          val target = BuildTargets.id(view)
          liveClient.foreach(c => ledger.clearEverything(target).foreach(c.onBuildPublishDiagnostics))

          bootstrap = Some(reloaded)
          flix = mkFlix()
          lastCompileHadMain = false
          generation.incrementAndGet()

          announceTargetChanged(reloaded.view)
      }
    }
  }

  /**
    * Empties the target's output directory and forgets what was cached about it.
    *
    * Scoped to what the target *is*: the development output. `Bootstrap.clean` would also delete
    * `doc/`, `stubs/` and the coverage reports, none of which a client asked about, and would refuse
    * outside project mode for reasons that have nothing to do with a cache.
    *
    * `cleaned = false` on failure rather than a failed request. The protocol has a field for exactly
    * this, and a client that is told the cache was not cleaned can decide what to do; a request error
    * would leave it guessing whether anything was deleted.
    */
  def cleanCache(target: BuildTargetIdentifier): CleanCacheResult = {
    requireView()

    buildLock.synchronized {
      val b = requireBootstrapForBuild()
      b.cleanOutput(flix, Build.Development) match {
        case Result.Ok(()) =>
          // The class files that were just deleted are what the last compile's diagnostics described,
          // and there is now no build to speak for them.
          liveClient.foreach(c => ledger.clearEverything(target).foreach(c.onBuildPublishDiagnostics))
          lastCompileHadMain = false
          new CleanCacheResult(true)

        case Result.Err(e) =>
          val message = e.message(Formatter.NoFormatter)
          showMessage(MessageType.ERROR, s"could not clean the build output: $message")
          val result = new CleanCacheResult(false)
          result.setMessage(message)
          result
      }
    }
  }

  /**
    * Tells the client the target changed, so it re-reads what it cached about the project.
    *
    * Only when the client speaks this server's language: it was told about no target, so telling it one
    * changed would name something it has never seen.
    */
  private def announceTargetChanged(view: ProjectView): Unit = {
    if (!BuildTargets.servesClient(clientLanguageIds)) {
      return
    }
    val event = new BuildTargetEvent(BuildTargets.id(view))
    event.setKind(BuildTargetEventKind.CHANGED)
    liveClient.foreach(_.onBuildTargetDidChange(new DidChangeBuildTarget(List(event).asJava)))
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
    if (lastCompileHadMain) List(new JvmMainClass(ProgramRunner.MainClass, List.empty[String].asJava))
    else Nil

  /**
    * Compiles under an already-held lock, or does nothing if the output is already current.
    *
    * The skip is asked for here and nowhere else in this file, because this is the path whose callers
    * do not need a `CompilationResult`: a compile request needs a status and diagnostics, and a run
    * needs class files on disk and whether there is a main. Running the tests needs the compilation
    * itself -- a test is a function this process reflects and calls -- so `test` goes through
    * `Bootstrap.testWith`, which always compiles.
    *
    * An up-to-date build carries no messages, and it does not have to: a build is only recorded when it
    * succeeded, and a successful compile in this compiler has no diagnostics at all. So the ordinary
    * publishing path clears whatever the client was last told and sends nothing, which is exactly right
    * for a project that now compiles cleanly.
    */
  private def compileWith(b: Bootstrap, view: ProjectView): Bootstrap.CompileOutcome = {
    val configured = flix.options.copy(
      build = Build.Development,
      outputJvm = true,
      outputPath = view.outputDirectories(Build.Development),
      loadClassFiles = false,
      progress = false)
    flix.setOptions(configured)
    val outcome = b.compileProjectOutcome(flix, clean = false, skipIfUpToDate = true)
    lastCompileHadMain = outcome.hasMain
    outcome
  }

  /** Returns a compiler instance configured the way every build in this session needs it. */
  private def mkFlix(): Flix = new Flix().setFormatter(Formatter.NoFormatter).setOptions(options)

  /** Attaches `originId` to `result`, which is how a client correlates it with its request. */
  private def statusOf(result: RunResult, originId: Option[String]): RunResult = {
    originId.foreach(result.setOriginId)
    result
  }

  /** The attached client, for a helper that must tolerate not having one yet. */
  def currentClient: Option[BuildClient] = liveClient

  /**
    * The client, if there is one and it may still be told things.
    *
    * Every notification goes through here, and that is the point: after `build/shutdown` a client has
    * said it wants nothing more, and work that was already running would otherwise finish into a
    * connection that has been closed down -- publishing diagnostics or a task finish for a build
    * nobody is waiting for. Requests are dispatched off the connection's thread, so that window is
    * real rather than theoretical.
    */
  private def liveClient: Option[BuildClient] = if (isShutDown) None else client

  /** Sends a line of a running program's output, which is not a message about the build. */
  private def printStdout(line: String, originId: Option[String]): Unit =
    liveClient.foreach { c =>
      val params = new PrintParams(originId.getOrElse(""), line + System.lineSeparator())
      c.onRunPrintStdout(params)
    }

  /** Sends `message` to the client's log, if there is a client. */
  def logMessage(message: String): Unit =
    liveClient.foreach(_.onBuildLogMessage(new LogMessageParams(MessageType.LOG, message)))

  /** Shows `message` to the user, for something they have to know about rather than look up. */
  def showMessage(kind: MessageType, message: String): Unit =
    liveClient.foreach(_.onBuildShowMessage(new ShowMessageParams(kind, message)))

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
    * What a compile answers with: the result a client waits for, and how much the compiler said.
    *
    * The two travel together because they come from different places. The status is a fact about the
    * build; the count is a fact about the diagnostics, which only the session sees -- and a report built
    * from the status alone could distinguish nothing finer than "some" from "none", which is what a
    * client then puts in front of a user who has forty errors.
    *
    * @param diagnostics how many diagnostics the compile reported. Diagnostics, not files with
    *                    diagnostics: a client summing across targets wants the former, and Flix has no
    *                    warnings, so every one of them is an error.
    */
  case class CompileAnswer(result: CompileResult, diagnostics: Int)

  /**
    * How long a run may take before the server stops it.
    *
    * A server that waited forever would be held open by any program that does not end, and a client
    * has no way to cancel a process it cannot see. Generous, because a legitimate program may be slow.
    */
  private val RunTimeout: java.time.Duration = java.time.Duration.ofMinutes(10)

  /**
    * How long to wait for a killed program and its children to be gone.
    *
    * A cancelled request has already answered, so this is only about not leaving processes behind. Short,
    * because a process that ignores a forcible kill for five seconds is not going to be reasoned with.
    */
  private val KillGrace: java.time.Duration = java.time.Duration.ofSeconds(5)

  /**
    * How long a test run may take before the server stops it.
    *
    * Longer than a run's, because a suite legitimately takes longer than a program, and bounded for the
    * same reason: a client cannot cancel a process it cannot see, and one test that never returns must
    * not hold a session open indefinitely. The fork is what makes stopping it possible at all.
    */
  private val TestTimeout: java.time.Duration = java.time.Duration.ofMinutes(30)

  /** What this server calls itself in the initialize result and in `.bsp/flix.json`. */
  val ServerName: String = "flix"

  /** The states a connection passes through, in order. */
  private sealed trait State

  private object State {
    /** No `build/initialize` yet. Requests are refused with `ServerNotInitialized`. */
    case object Uninitialized extends State

    /**
      * `build/initialize` answered, `build/initialized` not yet received.
      *
      * A state of its own rather than a detail of being initialized, because the specification puts a
      * rule here: a client may not send requests until it has acknowledged the handshake. Collapsing
      * this into `Ready` makes the server accept a sequence no client is allowed to send, which is the
      * kind of leniency that lets a real client's bug reach production undetected.
      */
    case object AwaitingAck extends State

    /** Serving. */
    case object Ready extends State

    /** `build/shutdown` received. Requests are refused and nothing is published. */
    case object ShutDown extends State
  }
}
