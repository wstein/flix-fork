/*
 * Copyright 2024
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
package ca.uwaterloo.flix.runtime

import java.util.concurrent.atomic.AtomicLongArray
import scala.collection.mutable

/**
  * Probe metadata entry with enhanced information for coverage reporting.
  *
  * @param source source file path
  * @param line line number (1-based)
  * @param kind probe kind (function, line, if branch, or match/choose rule)
  * @param qualifiedName fully-qualified function name where the probe is located
  */
case class ProbeMetadata(
  source: String,
  line: Int,
  kind: ProbeKind,
  qualifiedName: String
)

/**
  * Thread-safe coverage registry for tracking source-level probe hits.
  *
  * This registry maintains a global set of probe counters indexed by probe ID.
  * Each probe is executed at specific source locations (function entry, line, branch).
  * Counters are thread-safe using AtomicLongArray.
  *
  * Metadata is stored with enhanced information including probe kind as a sealed ADT
  * and qualified function names for better reporting.
  */
object Coverage {

  /**
    * Represents an isolated coverage session holding metadata and probe hit counters.
    */
  class Session(val sessionId: Long) {
    @volatile private[Coverage] var probes: AtomicLongArray = new AtomicLongArray(0)
    private[Coverage] val probeMetadata: mutable.Map[Int, ProbeMetadata] = mutable.Map()

    def registerProbe(probeId: Int, source: String, line: Int, kind: ProbeKind, qualifiedName: String): Unit = synchronized {
      if (probeId >= probes.length()) {
        val newSize = Math.max(probeId + 1, probes.length() * 2)
        val newProbes = new AtomicLongArray(newSize)
        for (i <- 0 until probes.length()) {
          newProbes.set(i, probes.get(i))
        }
        probes = newProbes
      }
      probeMetadata(probeId) = ProbeMetadata(source, line, kind, qualifiedName)
    }

    def hit(probeId: Int): Unit = {
      val currentProbes = probes
      if (probeId >= 0 && probeId < currentProbes.length()) {
        currentProbes.incrementAndGet(probeId)
      }
    }

    def getHitCount(probeId: Int): Long = {
      val currentProbes = probes
      if (probeId >= 0 && probeId < currentProbes.length()) {
        currentProbes.get(probeId)
      } else {
        0L
      }
    }

    def reset(): Unit = synchronized {
      for (i <- 0 until probes.length()) {
        probes.set(i, 0)
      }
    }

    def clear(): Unit = synchronized {
      probes = new AtomicLongArray(0)
      probeMetadata.clear()
    }

    /**
      * Take an immutable atomic snapshot of this session's metadata and hit counts.
      * All artifacts (JSON, LCOV, summary) must be rendered from a single call to this method.
      *
      * @return a tuple of (metadata map, hits map) where hits only includes probes with count > 0.
      */
    def reportSnapshot(): (Map[Int, ProbeMetadata], Map[Int, Long]) = synchronized {
      val currentProbes = probes
      val hits = mutable.Map[Int, Long]()
      for (i <- 0 until currentProbes.length()) {
        val count = currentProbes.get(i)
        if (count > 0) {
          hits(i) = count
        }
      }
      (probeMetadata.toMap, hits.toMap)
    }
  }

  private val nextSessionId = new java.util.concurrent.atomic.AtomicLong(1L)
  private val sessions = new java.util.concurrent.ConcurrentHashMap[Long, Session]()

  @volatile private var activeSession: Session = createSession()

  /**
    * Create and activate a fresh coverage session for a new compilation lifecycle.
    */
  def createSession(): Session = synchronized {
    val id = nextSessionId.getAndIncrement()
    val s = new Session(id)
    sessions.put(id, s)
    activeSession = s
    s
  }

  /**
    * Returns the currently active coverage session.
    */
  def getSession: Session = activeSession

  /**
    * Register a new probe with enhanced metadata in the active session.
    */
  def registerProbe(probeId: Int, source: String, line: Int, kind: ProbeKind, qualifiedName: String): Unit = {
    activeSession.registerProbe(probeId, source, line, kind, qualifiedName)
  }

  /**
    * Record a hit on a probe in the session specified by sessionId.
    * If the session is unknown or has been evicted, this call is a safe no-op.
    */
  def hit(sessionId: Long, probeId: Int): Unit = {
    val s = sessions.get(sessionId)
    if (s != null) {
      s.hit(probeId)
    }
  }

  /**
    * Evict and close a coverage session by its ID.
    */
  def closeSession(sessionId: Long): Unit = {
    sessions.remove(sessionId)
  }

  /**
    * Record a hit on a probe in the active session.
    */
  def hit(probeId: Int): Unit = {
    activeSession.hit(probeId)
  }

  /**
    * Get the hit count for a probe in the active session.
    */
  def getHitCount(probeId: Int): Long = {
    activeSession.getHitCount(probeId)
  }

  /**
    * Get all probe metadata from the active session.
    */
  def getProbeMetadata: Map[Int, ProbeMetadata] = synchronized {
    activeSession.probeMetadata.toMap
  }

  /**
    * Reset all probe counters to zero in the active session.
    */
  def reset(): Unit = synchronized {
    activeSession.reset()
  }

  /**
    * Take a snapshot of all current probe hit counts in the active session.
    */
  def snapshot(): Map[Int, Long] = {
    val currentProbes = activeSession.probes
    val result = mutable.Map[Int, Long]()
    for (i <- 0 until currentProbes.length()) {
      val count = currentProbes.get(i)
      if (count > 0) {
        result(i) = count
      }
    }
    result.toMap
  }

  /**
    * Returns probe metadata and hit counts from the current active session state.
    */
  def reportSnapshot(): (Map[Int, ProbeMetadata], Map[Int, Long]) = synchronized {
    val currentProbes = activeSession.probes
    val hits = mutable.Map[Int, Long]()
    for (i <- 0 until currentProbes.length()) {
      val count = currentProbes.get(i)
      if (count > 0) {
        hits(i) = count
      }
    }
    (activeSession.probeMetadata.toMap, hits.toMap)
  }

  /**
    * Clear all probes and metadata in the active session.
    */
  def clear(): Unit = synchronized {
    activeSession.clear()
  }
}
