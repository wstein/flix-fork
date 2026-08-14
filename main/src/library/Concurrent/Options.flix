/*
 * Copyright 2026 Magnus Madsen, Werner Stein
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

pub mod Concurrent.Options {

    ///
    /// Returns `true` if pure operations on large collections may be evaluated in parallel.
    ///
    /// Rewritten by the compiler before optimization. When it is `false`, every parallel branch
    /// guarded by it becomes dead code, and the tree shaker removes the `par` evaluation it kept
    /// alive. See `docs/datalog-execution-mode.md`.
    ///
    pub def enableParallelEvaluation(): Bool = true

}
