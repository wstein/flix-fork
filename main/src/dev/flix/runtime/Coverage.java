/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.flix.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/** Thread-safe counters used by compiler-inserted source-coverage probes. */
public final class Coverage {

    private static final ConcurrentHashMap<Long, AtomicLongArray> SESSIONS = new ConcurrentHashMap<>();

    private Coverage() {
    }

    /** Installs a fresh counter array, replacing any earlier session with the same identity. */
    public static void install(long sessionId, int probeCount) {
        if (probeCount < 0) {
            throw new IllegalArgumentException("probeCount must be non-negative");
        }
        SESSIONS.put(sessionId, new AtomicLongArray(probeCount));
    }

    /** Records one probe execution. Unknown sessions and invalid probe IDs are safe no-ops. */
    public static void hit(long sessionId, int probeId) {
        AtomicLongArray counters = SESSIONS.get(sessionId);
        if (counters != null && probeId >= 0 && probeId < counters.length()) {
            counters.incrementAndGet(probeId);
        }
    }

    /** Returns an immutable point-in-time copy of a session's counters. */
    public static long[] snapshot(long sessionId) {
        AtomicLongArray counters = SESSIONS.get(sessionId);
        if (counters == null) {
            return new long[0];
        }
        long[] result = new long[counters.length()];
        for (int i = 0; i < counters.length(); i++) {
            result[i] = counters.get(i);
        }
        return result;
    }

    /** Discards one completed compilation session. */
    public static void close(long sessionId) {
        SESSIONS.remove(sessionId);
    }
}
