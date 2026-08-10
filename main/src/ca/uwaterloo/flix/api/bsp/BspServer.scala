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

import ca.uwaterloo.flix.util.Options
import ch.epfl.scala.bsp4j.BuildClient
import org.eclipse.lsp4j.jsonrpc.Launcher

import java.io.{FileDescriptor, FileOutputStream, InputStream, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ExecutorService, Executors}

/**
  * The Build Server Protocol endpoint: `flix bsp`.
  *
  * ==Why this is not in the language server==
  *
  * Because a build is not a language feature. `docs/TOOLING-CONTRACT.md` argues the point and this
  * respects it: the language server answers questions about a document, this answers questions about
  * a build, and they are separate endpoints with separate lifetimes. What that document also
  * concluded -- that BSP belongs to some other build tool -- does not hold for a plain `flix.toml`
  * project, where `flix` is the build tool: it resolves the dependencies, owns `build/`, packages the
  * jar and runs the tests. There is nothing above it to leave this to.
  *
  * ==Standard output belongs to the protocol==
  *
  * The single most important line of code here is the one that takes `FileDescriptor.out` before
  * anything else can write to it, and then points `System.out` somewhere harmless. The compiler
  * prints from more places than can be audited once and trusted forever -- a crash report, a progress
  * bar, a test program's own output, dependency resolution -- and one stray line between two frames
  * ends the connection. Redirecting to the client's log rather than to nothing is deliberate: a
  * crash report that vanishes is worse than one that arrives somewhere unexpected.
  *
  * @see [[BspLogStream]] for where that output goes, and [[BspSession]] for the lifecycle.
  */
object BspServer {

  /**
    * Serves one client on standard input and output until it disconnects.
    *
    * @param options     the compiler options; progress reporting is forced off, since a progress bar
    *                    draws on the channel the protocol needs.
    * @param projectPath the project this server is about. A client asking about another is refused.
    */
  def run(options: Options, projectPath: Path): Unit = {
    // Before anything else. `System.out` is still the real descriptor at this point, and this is the
    // last moment at which taking it is guaranteed to be safe.
    val protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8)
    val protocolIn = System.in

    val log = new BspLogStream()
    val quarantined = new PrintStream(log, true, StandardCharsets.UTF_8)
    System.setOut(quarantined)

    // Announcing on stderr, the way the language server does: stderr is not the protocol channel, and
    // a client that captures it gets something useful when a handshake never completes.
    System.err.println("Starting Flix BSP Server...")

    val executor = Executors.newFixedThreadPool(4, (r: Runnable) => {
      val t = new Thread(r, "flix-bsp")
      // Daemon, so a client that vanishes without `build/exit` cannot keep the process alive.
      t.setDaemon(true)
      t
    })

    try {
      serve(options.copy(progress = false), projectPath, log, protocolIn, protocolOut, executor)
    } finally {
      executor.shutdownNow()
      // Put the real descriptor back, so anything that runs after this -- an exit hook, a crash
      // handler -- prints where a person can see it rather than into a closed connection.
      System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
      System.err.println("BSP Server Terminated.")
    }
  }

  /**
    * Wires a session to a launcher on the given streams and listens until the stream closes.
    *
    * Separate from [[run]] so that a test can drive a real connection over pipes without a process
    * and without touching this JVM's `System.out`.
    */
  private[bsp] def serve(options: Options,
                         projectPath: Path,
                         log: BspLogStream,
                         in: InputStream,
                         out: OutputStream,
                         executor: ExecutorService): Unit = {
    val session = new BspSession(projectPath, options, log)

    // `build/exit` has to stop the listener, and the listener does not exist until after the server
    // object is built, which is the cycle this reference breaks.
    //
    // An `AtomicReference` and not a local `var`: the write happens on this thread and the read
    // happens on whichever thread dispatches `build/exit`, and `@volatile` on a local is not a thing
    // in Scala -- it is captured in a plain holder and the annotation does nothing. A client that
    // sends `exit` in the window before the assignment would then find nothing to cancel and the
    // server would keep listening until its input closed.
    val listening = new AtomicReference[java.util.concurrent.Future[Void]]()
    val server = new FlixBuildServer(session, () => Option(listening.get()).foreach(_.cancel(true)))

    val launcher = new Launcher.Builder[BuildClient]()
      .setLocalService(server)
      .setRemoteInterface(classOf[BuildClient])
      .setInput(in)
      .setOutput(out)
      .setExecutorService(executor)
      .create()

    session.connect(launcher.getRemoteProxy)
    val future = launcher.startListening()
    listening.set(future)

    try {
      future.get()
    } catch {
      // A cancelled listener is how `build/exit` returns, and an interrupt is how a shutdown does.
      // Neither is a failure worth a stack trace.
      case _: java.util.concurrent.CancellationException => ()
      case _: InterruptedException => ()
    }
  }
}
