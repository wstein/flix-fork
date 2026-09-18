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
package ca.uwaterloo.flix.runtime

import java.util.concurrent.atomic.AtomicLong

sealed trait CoverageProbeKind {
  def wireName: String

  final def isBranch: Boolean = this match {
    case CoverageProbeKind.BranchTrue | CoverageProbeKind.BranchFalse | CoverageProbeKind.BranchRule => true
    case CoverageProbeKind.Function | CoverageProbeKind.Line => false
  }
}

object CoverageProbeKind {
  case object Function extends CoverageProbeKind { val wireName: String = "function" }
  case object Line extends CoverageProbeKind { val wireName: String = "line" }
  case object BranchTrue extends CoverageProbeKind { val wireName: String = "branch-true" }
  case object BranchFalse extends CoverageProbeKind { val wireName: String = "branch-false" }
  case object BranchRule extends CoverageProbeKind { val wireName: String = "branch-rule" }
}

case class CoverageProbe(id: Int, source: String, line: Int, kind: CoverageProbeKind, qualifiedName: String)

case class CoverageSession(sessionId: Long, probes: Vector[CoverageProbe])

/** One coherent counter snapshot and the execution context which produced it. */
case class CoverageSnapshot(session: CoverageSession,
                            counts: Vector[Long],
                            partial: Boolean,
                            testFilters: List[String]) {
  require(session.probes.map(_.id) == session.probes.indices, "Coverage probe ids must be dense and ordered.")
  require(counts.size == session.probes.size, "Coverage counters must match the probe table.")

  def count(probe: CoverageProbe): Long = counts(probe.id)
}

object CoverageSession {
  private val NextId = new AtomicLong(1L)

  private[flix] def freshId(): Long = NextId.getAndIncrement()

  def fresh(probes: Vector[CoverageProbe]): CoverageSession =
    CoverageSession(freshId(), probes)
}
