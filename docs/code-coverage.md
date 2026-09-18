# Code coverage

Coverage support is being migrated onto the current compiler and test-runner architecture in
independent, reviewable slices. It is not yet a public CLI feature on this branch.

## Runtime foundation

A coverage-enabled compiler API build (`Options.coverage = true`) includes
`dev.flix.runtime.Coverage` in its generated class set. Ordinary builds omit the class. The build
manifest fingerprint includes this policy, so an incremental build cannot reuse ordinary products
for a coverage build or vice versa.

The runtime owns thread-safe counters keyed by a compilation-session identity. Installing one
session cannot reset or contaminate another concurrent compilation. Generated probes carry both the
session identity and probe index. A snapshot is a point-in-time copy; closing a session releases its
counters.

## Function and line probes

Coverage compilation instruments every reachable, non-test definition owned by user source with a
function-entry probe. Bundled library and package definitions are excluded by their source origin,
not by path or namespace conventions. The probe remains source-level pure so it cannot change a
function's declared effect, but the optimizer treats it as a compiler-owned side effect and must not
discard it.

Each instrumented definition also receives executable line probes throughout its expression tree.
There is at most one line probe for each `(qualified definition, source, line)` tuple. The outermost
executable expression on a line owns that probe; nested expressions on the same line share it. This
placement ensures that a line belonging only to an unselected branch remains uncovered. The
instrumentation traverses lambda bodies, local definitions, match and handler rules, Java interop,
collections, channels, parallel expressions, and fixpoint expressions. Every rebuilt or inserted AST
node receives JVM provenance so coverage builds preserve debugger source attribution.

Control-flow probes distinguish true and false `if` outcomes, true and false match-guard
outcomes, and selected rule bodies in matches, restrictable choices, extensible matches, exception
handlers, and effect handlers. A rule or branch probe executes inside the selected body; it cannot be
inferred from a surrounding line probe.

## Report model

A report is rendered from one immutable `CoverageSnapshot`: the ordered probe table, a same-sized
counter vector, whether execution stopped early, and the exact test filters used for the run. JSON,
LCOV, and the terminal summary therefore cannot observe different counter states. Filtered test
runs describe only the selected execution and retain their filters in JSON and in the summary; they
are never presented as an unqualified whole-suite result. Cancellation will set `partial` rather
than discarding the counters collected before cancellation.

JSON format 1 groups functions, executable lines, and individual branch outcomes beneath each
source path, with paths and entries emitted deterministically. LCOV includes zero-hit functions,
lines, and branches and uses `-` for an untaken branch. The serializers are pure; command-specific
publication writes each file through a temporary sibling followed by atomic replacement when the
filesystem supports it.

## CLI

`flix run --coverage` and `flix test --coverage` publish `build/coverage.json` and
`build/coverage.info` after execution. `--coverage-output <path>` and
`--coverage-lcov-output <path>` override those locations; relative paths are resolved against the
project directory. Reports are also published when tests fail, and the original test result remains
the command result. Loose-file `flix test` uses the same behavior. The loaded program's isolated
coverage session is always closed after the final snapshot.

`flix test --filter <regex> --coverage` runs only matching tests and records every supplied regex in
the JSON report and terminal summary. Its counters describe that selected run, not the complete test
suite. Coverage options deliberately belong only to `run` and `test`; compilation-only commands do
not execute probes.

Both monomorphizers lower the probe to the same JVM operation. Loading the compilation installs the
session in the generated program's isolated class loader and returns a handle for taking snapshots
and releasing its counters.

## Test event stream and cancellation

Coverage is a `Tester.TestEvent`, emitted after the last selected test and immediately before the
single `Finished` event. Console, JSON-lines, LSP, and on-disk reports all consume that same immutable
snapshot; no renderer takes a second counter reading. The JSON-lines event contains the complete
hierarchical JSON report under `coverage`.

`flix/test/run` accepts `coverage: true`. Its `flix/test/event` stream then includes a `coverage`
event whose `coverageJson` is JSON format 1. If cooperative cancellation stops the run between test
cases, the event and report set `partial: true`, retain counters collected so far, and the final event
still reports cancellation. The isolated runtime session is closed after the event has been emitted.

IDE gutter rendering from the LSP coverage event remains a client-side integration slice.
