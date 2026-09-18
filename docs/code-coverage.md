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

Both monomorphizers lower the probe to the same JVM operation. Loading the compilation installs the
session in the generated program's isolated class loader and returns a handle for taking snapshots
and releasing its counters.

The migration still deliberately provides no `--coverage` CLI flag. Report formats, filtered-run
semantics, cancellation, and LSP events remain separate later slices.
