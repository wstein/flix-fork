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

import java.util.concurrent.atomic.AtomicBoolean

/**
  * Whether the client has given up on a request, and what to do about it.
  *
  * ==Why dropping the reply is not cancelling==
  *
  * `$/cancelRequest` marks a JSON-RPC future cancelled, and lsp4j then answers `RequestCancelled` and
  * discards whatever the handler eventually produces. For a compile that is the whole story: the work
  * has to finish anyway, because the compiler's pool and `JvmWriter`'s writes are not interrupt-safe and
  * a half-reconciled class directory is worse than a late answer.
  *
  * For a request that started something *else*, it is not the story at all. A cancelled
  * `buildTarget/run` whose program keeps running has not been cancelled in any sense a user would
  * recognise: the process holds the terminal, the build lock and the output stream until it happens to
  * end. Something has to reach the work, and this is what carries the signal to it.
  *
  * ==What it promises==
  *
  * Registered actions run once, on the thread that cancels, and the flag is readable by work that can
  * check it between steps. Nothing here interrupts a thread: a test running in this process finishes,
  * because a JVM cannot safely stop a method in the middle, and the honest guarantee is that no *further*
  * test starts.
  */
class Cancellation {

  private val cancelled: AtomicBoolean = new AtomicBoolean(false)

  /** What to run when the client gives up. Guarded by `this`. */
  private var actions: List[() => Unit] = Nil

  /** Returns `true` once the client has given up on the request. */
  def isCancelled: Boolean = cancelled.get()

  /**
    * Registers `action`, to run when the request is cancelled.
    *
    * Runs immediately if the cancellation already happened, which is the race that matters: the client
    * can give up while a process is starting, and an action registered a moment too late would leave it
    * running for the length of its timeout.
    */
  def onCancel(action: () => Unit): Unit = {
    val alreadyCancelled = synchronized {
      if (!cancelled.get()) {
        actions = action :: actions
      }
      cancelled.get()
    }
    if (alreadyCancelled) {
      run(action)
    }
  }

  /** Marks the request cancelled and runs everything registered. */
  def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      val pending = synchronized {
        val current = actions
        actions = Nil
        current
      }
      pending.foreach(run)
    }
  }

  /** Runs `action`, swallowing its failure: cancelling must not fail, and there is nobody to tell. */
  private def run(action: () => Unit): Unit =
    try action()
    catch { case _: Exception => () }
}
