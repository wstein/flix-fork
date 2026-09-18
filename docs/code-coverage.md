# Code coverage

Coverage support is being migrated onto the current compiler and test-runner architecture in
independent, reviewable slices. It is not yet a public CLI feature on this branch.

## Runtime foundation

A coverage-enabled compiler API build (`Options.coverage = true`) includes
`dev.flix.runtime.Coverage` in its generated class set. Ordinary builds omit the class. The build
manifest fingerprint includes this policy, so an incremental build cannot reuse ordinary products
for a coverage build or vice versa.

The runtime owns thread-safe counters keyed by a compilation-session identity. Installing one
session cannot reset or contaminate another concurrent compilation. The instrumentation slice will
make generated probes carry both the session identity and probe index. A snapshot is a point-in-time
copy; closing a session releases its counters.

This first migration slice deliberately provides no `--coverage` CLI flag and inserts no probes.
The next slice adds compiler-owned source instrumentation and installs its session before executing
tests. Report formats, filtered-run semantics, cancellation, and LSP events remain separate later
slices.
