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
  class Session {
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

    def reset(): Unit = synchronized {
      for (i <- 0 until probes.length()) {
        probes.set(i, 0)
      }
    }

    def clear(): Unit = synchronized {
      probes = new AtomicLongArray(0)
      probeMetadata.clear()
    }
  }

  @volatile private var session: Session = new Session()

  /**
    * Create and activate a fresh coverage session for a new compilation lifecycle.
    */
  def createSession(): Session = synchronized {
    session = new Session()
    session
  }

  /**
    * Returns the currently active coverage session.
    */
  def getSession: Session = session

  /**
    * Register a new probe with enhanced metadata in the active session.
    */
  def registerProbe(probeId: Int, source: String, line: Int, kind: ProbeKind, qualifiedName: String): Unit = {
    session.registerProbe(probeId, source, line, kind, qualifiedName)
  }

  /**
    * Record a hit on a probe in the active session.
    */
  def hit(probeId: Int): Unit = {
    val currentProbes = session.probes
    if (probeId >= 0 && probeId < currentProbes.length()) {
      currentProbes.incrementAndGet(probeId)
    }
  }

  /**
    * Get the hit count for a probe in the active session.
    */
  def getHitCount(probeId: Int): Long = {
    val currentProbes = session.probes
    if (probeId >= 0 && probeId < currentProbes.length()) {
      currentProbes.get(probeId)
    } else {
      0L
    }
  }

  /**
    * Get all probe metadata from the active session.
    */
  def getProbeMetadata: Map[Int, ProbeMetadata] = synchronized {
    session.probeMetadata.toMap
  }

  /**
    * Reset all probe counters to zero in the active session.
    */
  def reset(): Unit = synchronized {
    session.reset()
  }

  /**
    * Take a snapshot of all current probe hit counts in the active session.
    */
  def snapshot(): Map[Int, Long] = {
    val currentProbes = session.probes
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
    val currentProbes = session.probes
    val hits = mutable.Map[Int, Long]()
    for (i <- 0 until currentProbes.length()) {
      val count = currentProbes.get(i)
      if (count > 0) {
        hits(i) = count
      }
    }
    (session.probeMetadata.toMap, hits.toMap)
  }

  /**
    * Clear all probes and metadata in the active session.
    */
  def clear(): Unit = synchronized {
    session.clear()
  }
}
