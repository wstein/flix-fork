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
  * @param kind probe kind (function, line, branch-true, branch-false)
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
    * Global registry of probe hit counts.
    * Indexed by probe ID, each entry tracks how many times that probe was hit.
    */
  private var probes: AtomicLongArray = new AtomicLongArray(0)

  /**
    * Probe metadata: maps probe ID to ProbeMetadata.
    * Includes source file, line number, probe kind, and qualified function name.
    */
  private val probeMetadata: mutable.Map[Int, ProbeMetadata] = mutable.Map()

  /**
    * Register a new probe with enhanced metadata.
    *
    * @param probeId the unique probe identifier.
    * @param source  the source file path.
    * @param line    the line number (1-based).
    * @param kind    the probe kind.
    * @param qualifiedName the fully-qualified function name.
    */
  def registerProbe(probeId: Int, source: String, line: Int, kind: ProbeKind, qualifiedName: String): Unit = synchronized {
    if (probeId >= probes.length()) {
      // Grow the array as needed
      val newSize = Math.max(probeId + 1, probes.length() * 2)
      val newProbes = new AtomicLongArray(newSize)
      for (i <- 0 until probes.length()) {
        newProbes.set(i, probes.get(i))
      }
      probes = newProbes
    }
    probeMetadata(probeId) = ProbeMetadata(source, line, kind, qualifiedName)
  }



  /**
    * Record a hit on a probe.
    *
    * @param probeId the probe identifier.
    */
  def hit(probeId: Int): Unit = {
    if (probeId >= 0 && probeId < probes.length()) {
      probes.incrementAndGet(probeId)
    }
  }

  /**
    * Get the hit count for a probe.
    *
    * @param probeId the probe identifier.
    * @return the number of times the probe was hit, or 0 if not found.
    */
  def getHitCount(probeId: Int): Long = {
    if (probeId >= 0 && probeId < probes.length()) {
      probes.get(probeId)
    } else {
      0L
    }
  }

  /**
    * Get all probe metadata with enhanced information.
    *
    * @return a map from probe ID to ProbeMetadata.
    */
  def getProbeMetadata: Map[Int, ProbeMetadata] = synchronized {
    probeMetadata.toMap
  }



  /**
    * Reset all probe counters to zero.
    */
  def reset(): Unit = synchronized {
    for (i <- 0 until probes.length()) {
      probes.set(i, 0)
    }
  }

  /**
    * Take a snapshot of all current probe hit counts.
    *
    * @return a map from probe ID to hit count.
    */
  def snapshot(): Map[Int, Long] = {
    val result = mutable.Map[Int, Long]()
    for (i <- 0 until probes.length()) {
      val count = probes.get(i)
      if (count > 0) {
        result(i) = count
      }
    }
    result.toMap
  }

  /**
    * Clear all probes and metadata.
    */
  def clear(): Unit = synchronized {
    probes = new AtomicLongArray(0)
    probeMetadata.clear()
  }
}
