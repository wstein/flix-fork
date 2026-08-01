/*
 * Copyright 2021 Magnus Madsen
 * Copyright 2025 Casper Dalgaard Nielsen
 *                Adam Yasser Tallouzi
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

pub mod Fixpoint3.Options {

    ///
    /// Prints the Datalog program, as the solver sees it, before the fixpoint computation.
    ///
    /// The compiler rewrites this function when `--Xdatalog-debug=rules` is passed. Because the
    /// result is a constant, the optimizer removes the guarded tracing code entirely when the
    /// flag is absent, so tracing costs nothing unless it is asked for.
    ///
    pub def enableDebugRules(): Bool = false

    ///
    /// Prints the input facts and the minimal model of the fixpoint computation.
    ///
    /// The compiler rewrites this function when `--Xdatalog-debug=facts` is passed. See
    /// `enableDebugRules` for why the default must stay a constant.
    ///
    pub def enableDebugFacts(): Bool = false

    ///
    /// Prints the relation algebra machine program after each compilation phase, together with
    /// the selected indexes. This traces how the solver executes the rules rather than what the
    /// rules mean, and is intended for solver developers.
    ///
    /// The compiler rewrites this function when `--Xdatalog-debug=ram` is passed. See
    /// `enableDebugRules` for why the default must stay a constant.
    ///
    pub def enableDebugRam(): Bool = false

    ///
    /// Prints the trace to the file given by `debugFileName` instead of to standard out.
    ///
    /// Off by default, so that a trace requested on the command line is reported where the caller
    /// is looking rather than written to a file they did not ask for.
    ///
    pub def enableDebugToFile(): Bool = false

    ///
    /// Returns the name of the file the trace is written to.
    ///
    pub def debugFileName(): String = "fixpoint_debug.txt"

    ///
    /// The arity of the B+ trees in the interpreter.
    ///
    pub def usedArity(): Int32 = 64

    ///
    /// The maximum depth of recursively spawned threads in the interpreter.
    ///
    /// To be more precise this value is the number of times the interpreter can recursively
    /// split a `RelOp.Search` into smaller workloads for threads running in parallel.
    ///
    /// Consider the program:
    /// ```
    /// search a ∈ A do
    ///     search b ∈ B do
    ///         search c ∈ C do
    ///             ...
    ///         end
    ///     end
    /// end
    /// ```
    /// Spawning `k` threads in every layer with a `RelOp.Search`s undesirable, since this
    /// leads to `k^d` threads for a nesting of depth `d`. Therefore, the interpreter is
    /// limited to only spawn threads for the `MaxParallelNesting` highest `search` `RelOp`s.
    ///
    /// For the example, if we allow a maximum of 2 parallel nestings, we would not spawn
    /// threads when evaluating `search c ∈ C do`.
    ///
    pub def parLevel(): Int32 = 2

}
