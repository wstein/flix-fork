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
package ca.uwaterloo.flix.api.lsp.provider

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.util.Options

import java.nio.file.Path

import scala.collection.mutable

/**
  * The compiler a debug session evaluates expressions with, kept between requests.
  *
  * ==What it is for==
  *
  * Evaluating one expression means compiling the project: an expression can mention anything the
  * program declares, so the program has to be there to mention. Doing that from scratch each time
  * made a watch cost two whole compilations — one to find the expression's type and one to produce
  * the classes that run it — on every step.
  *
  * A [[Flix]] instance is incremental. Re-adding an input marks *that* input changed and leaves the
  * rest of the cached typing in place, which is what makes the second compilation cheap: between two
  * requests the only thing that differs is the wrapper holding the expression. This is the same
  * arrangement the REPL uses, and for the same reason.
  *
  * ==Why one project at a time==
  *
  * A debug session is a session of one program. Keeping a compiler per project would hold the typed
  * AST of every project ever debugged for the life of the server, and the case it would serve —
  * two debug sessions in two projects, alternating expression by expression — is not one anybody
  * has. When the project changes, the previous compiler is dropped whole.
  *
  * ==Why the source set is checked and the file contents are not==
  *
  * A file whose *contents* changed is handled by the compiler itself: its input is re-read and the
  * change set narrows the work. A file that has *appeared or vanished* is not, because nothing has
  * told the instance about it — so the source set is compared, and a difference discards the
  * compiler rather than compiling against a project that no longer exists.
  *
  * ==Why it is synchronised==
  *
  * A [[Flix]] instance holds the compilation's state and is not safe to use from two threads. The
  * requests are rare and the work is long, so a lock is the right shape: two evaluations of the same
  * project queue rather than corrupt each other.
  */
object DebugEvalSidecar {

  /** Maximum compiled/type answers retained for the active debug session. */
  private[provider] val MaxAnswers: Int = 32

  /** One build's compiler and the answers already computed with it. */
  private case class Entry(
                            projectRoot: Path,
                            sources: Set[Path],
                            sourcesDigest: String,
                            flix: Flix,
                            answers: mutable.LinkedHashMap[Key, Any] = mutable.LinkedHashMap.empty,
                          )

  /**
    * What makes two requests the same question.
    *
    * Not the frame's *values*: an artifact is code, and the code that reads `n` is the same code
    * whatever `n` currently holds. That is what makes caching worth doing at all — a watch is
    * re-evaluated on every step, and every one of those steps asks this same question again.
    *
    * The sources' digest is in the key because the answer is about a *running program*. A rebuild
    * gives a different one, and an artifact compiled against the old sources would call into classes
    * the new program does not have. It is the manifest's `sourcesDigest` rather than its
    * `fingerprint`: measured, only the first changes when a source file does.
    */
  private case class Key(sourcesDigest: String, scope: String, expression: String, policy: String, withArtifact: Boolean)

  private var entry: Option[Entry] = None

  /**
    * Runs `body` with the in-memory compiler for this exact project build.
    *
    * The compiler already holds the project's sources; a caller replaces the in-memory wrapper and
    * then checks or generates. No evaluation output directory exists.
    *
    * `sources` is passed rather than discovered here so the caller keeps one rule for what a
    * project's sources are.
    */
  def withCompiler[A](projectRoot: Path, sources: List[Path], sourcesDigest: String)(body: Flix => A): A =
    withCompiler(projectRoot, sources, sourcesDigest, () => standaloneCompiler(sources))(body)

  /** As [[withCompiler]], creating the compiler with the project's dependency configuration. */
  def withCompiler[A](projectRoot: Path, sources: List[Path], sourcesDigest: String,
                      freshCompiler: () => Flix)(body: Flix => A): A = synchronized {
    body(current(projectRoot, sources, sourcesDigest, freshCompiler).flix)
  }

  /** Replaced when the project, source set, or launched build digest changes. */
  private def current(projectRoot: Path, sources: List[Path], sourcesDigest: String,
                      freshCompiler: () => Flix): Entry = entry match {
    case Some(e) if e.projectRoot == projectRoot && e.sources == sources.toSet && e.sourcesDigest == sourcesDigest => e
    case _ =>
      entry.foreach(_.flix.close())
      val fresh = Entry(projectRoot, sources.toSet, sourcesDigest, freshCompiler())
      entry = Some(fresh)
      fresh
  }

  /**
    * The answer to a question already asked, or `compute`'s answer, remembered.
    *
    * Keyed by everything that decides the answer and nothing that does not — see [[Key]]. A watch
    * that is re-evaluated on every step asks the same question every time, and the expensive half of
    * answering it is generating classes, which are the same classes each time.
    *
    * `compute` is trusted to be a pure function of the key. It is called under the same lock as
    * everything else here, so two requests for one question compile it once.
    */
  def cached[A](projectRoot: Path, sources: List[Path], sourcesDigest: String, scope: String,
                expression: String, policy: String, withArtifact: Boolean)(compute: => A): A = synchronized {
    cached(projectRoot, sources, sourcesDigest, scope, expression, policy, withArtifact,
      () => standaloneCompiler(sources))(compute)
  }

  /** As [[cached]], creating a replacement compiler with the project's dependency configuration. */
  def cached[A](projectRoot: Path, sources: List[Path], sourcesDigest: String, scope: String,
                expression: String, policy: String, withArtifact: Boolean,
                freshCompiler: () => Flix)(compute: => A): A = synchronized {
    val key = Key(sourcesDigest, scope, expression, policy, withArtifact)
    val answers = current(projectRoot, sources, sourcesDigest, freshCompiler).answers
    answers.remove(key) match {
      case Some(answer) =>
        // Reinsert on a hit: LinkedHashMap order is then least-recently-used first.
        answers.put(key, answer)
        answer.asInstanceOf[A]
      case None =>
        val answer = compute
        // Re-read rather than reused: `compute` may have replaced the entry, and remembering an
        // answer against a compiler that has been dropped would outlive what it was computed from.
        val currentAnswers = current(projectRoot, sources, sourcesDigest, freshCompiler).answers
        currentAnswers.put(key, answer)
        while (currentAnswers.size > MaxAnswers) {
          currentAnswers.remove(currentAnswers.head._1)
        }
        answer
    }
  }

  /** How many answers are being remembered for `projectRoot`. Pinned by tests. */
  def cachedAnswers(projectRoot: Path): Int = synchronized {
    entry.filter(_.projectRoot == projectRoot).map(_.answers.size).getOrElse(0)
  }

  /** Whether a compiler for `projectRoot` is being kept. Pinned by tests; not used in anger. */
  def isCaching(projectRoot: Path): Boolean = synchronized {
    entry.exists(_.projectRoot == projectRoot)
  }

  /** Drops whatever is cached. For tests, and for a caller that knows the world has changed. */
  def evict(): Unit = synchronized {
    entry.foreach(_.flix.close())
    entry = None
  }

  /** A dependency-free compiler used by API callers that do not own a Bootstrap project. */
  def standaloneCompiler(sources: List[Path]): Flix = {
    implicit val sctx: SecurityContext = SecurityContext.Unrestricted
    val flix = new Flix().setOptions(
      // The same debug policy as the launched build, with disk output explicitly disabled.
      Options.DefaultTest.copy(xdebug = true, inMemory = true),
    )
    sources.foreach(path => flix.addFile(path, sctx))
    flix
  }
}
