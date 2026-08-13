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

import ch.epfl.scala.bsp4j.*

import java.util.concurrent.atomic.AtomicLong

/**
  * The progress notifications that bracket a long request.
  *
  * A compile takes seconds, and a client that is told nothing shows nothing -- so the work looks
  * like a hang. `taskStart` and `taskFinish` are what put a progress indicator on screen and take it
  * off again.
  *
  * ==Every start has exactly one finish==
  *
  * That is the invariant worth stating, because the failure is asymmetric: a missing `taskFinish`
  * leaves a spinner turning forever, and no error is reported anywhere. [[bracket]] exists so the
  * finish cannot be forgotten -- including when the body throws, which is precisely when a hand-written
  * pair is skipped.
  */
class BspTasks(client: () => Option[BuildClient]) {

  /** Task ids only have to be unique within a connection. */
  private val nextId: AtomicLong = new AtomicLong(0)

  /**
    * Runs `body`, bracketed by a start and a finish notification.
    *
    * `statusOf` decides the finish's status from the body's result, so a compile that reports errors
    * finishes as `ERROR` while still being a request that succeeded.
    */
  def bracket[T](message: String,
                 startData: TaskId => Option[(String, Object)],
                 finishData: (TaskId, T) => Option[(String, Object)],
                 statusOf: T => StatusCode)(body: TaskId => T): T = {
    val id = new TaskId(nextId.incrementAndGet().toString)

    val start = new TaskStartParams(id)
    start.setEventTime(System.currentTimeMillis())
    start.setMessage(message)
    startData(id).foreach { case (kind, data) =>
      start.setDataKind(kind)
      start.setData(data)
    }
    notify(_.onBuildTaskStart(start))

    // The finish is in a `finally`-shaped position deliberately: a body that throws must still take
    // the client's progress indicator down, or the connection looks wedged.
    var status: StatusCode = StatusCode.ERROR
    var data: Option[(String, Object)] = None
    try {
      val result = body(id)
      status = statusOf(result)
      data = finishData(id, result)
      result
    } finally {
      val finish = new TaskFinishParams(id, status)
      finish.setEventTime(System.currentTimeMillis())
      finish.setMessage(message)
      data.foreach { case (kind, d) =>
        finish.setDataKind(kind)
        finish.setData(d)
      }
      notify(_.onBuildTaskFinish(finish))
    }
  }

  /** Returns a fresh id for a task nested under `parent`, which is how a client builds a tree. */
  def child(parent: TaskId): TaskId = {
    val id = new TaskId(nextId.incrementAndGet().toString)
    id.setParents(java.util.List.of(parent.getId))
    id
  }

  /** Returns a fresh top-level task id. */
  def newTask(): TaskId = new TaskId(nextId.incrementAndGet().toString)

  /**
    * Opens a task explicitly.
    *
    * Paired with [[finish]] by the caller, for work whose start and end are not one block -- a test
    * run reports each test as it happens, so the pairs interleave with the events driving them.
    * [[bracket]] is what to use when the work *is* a block.
    */
  def start(id: TaskId, message: String, data: Option[(String, Object)]): Unit = {
    val params = new TaskStartParams(id)
    params.setEventTime(System.currentTimeMillis())
    params.setMessage(message)
    data.foreach { case (kind, d) =>
      params.setDataKind(kind)
      params.setData(d)
    }
    notify(_.onBuildTaskStart(params))
  }

  /** Closes a task opened by [[start]]. */
  def finish(id: TaskId, message: String, status: StatusCode, data: Option[(String, Object)]): Unit = {
    val params = new TaskFinishParams(id, status)
    params.setEventTime(System.currentTimeMillis())
    params.setMessage(message)
    data.foreach { case (kind, d) =>
      params.setDataKind(kind)
      params.setData(d)
    }
    notify(_.onBuildTaskFinish(params))
  }

  /** Reports how far along a task is, for a client that draws a bar rather than a spinner. */
  def progress(id: TaskId, message: String): Unit = {
    val params = new TaskProgressParams(id)
    params.setEventTime(System.currentTimeMillis())
    params.setMessage(message)
    notify(_.onBuildTaskProgress(params))
  }

  /** Delivers a notification, if a client is attached. */
  private def notify(send: BuildClient => Unit): Unit = client().foreach { c =>
    try send(c)
    catch {
      // A client that has gone away must not turn a build into a failure: the work is done and there
      // is nobody left to tell.
      case _: Exception => ()
    }
  }
}
