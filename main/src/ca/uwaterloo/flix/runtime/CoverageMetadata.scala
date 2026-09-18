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

sealed trait CoverageProbeKind

object CoverageProbeKind {
  case object Function extends CoverageProbeKind
  case object Line extends CoverageProbeKind
  case object BranchTrue extends CoverageProbeKind
  case object BranchFalse extends CoverageProbeKind
  case object BranchRule extends CoverageProbeKind
}

case class CoverageProbe(id: Int, source: String, line: Int, kind: CoverageProbeKind, qualifiedName: String)

case class CoverageSession(sessionId: Long, probes: Vector[CoverageProbe])

object CoverageSession {
  private val NextId = new AtomicLong(1L)

  private[flix] def freshId(): Long = NextId.getAndIncrement()

  def fresh(probes: Vector[CoverageProbe]): CoverageSession =
    CoverageSession(freshId(), probes)
}
