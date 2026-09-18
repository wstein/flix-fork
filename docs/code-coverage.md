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

## Function probes

Coverage compilation instruments every reachable, non-test definition owned by user source with a
function-entry probe. Bundled library and package definitions are excluded by their source origin,
not by path or namespace conventions. The probe remains source-level pure so it cannot change a
function's declared effect, but the optimizer treats it as a compiler-owned side effect and must not
discard it.

Both monomorphizers lower the probe to the same JVM operation. Loading the compilation installs the
session in the generated program's isolated class loader and returns a handle for taking snapshots
and releasing its counters.

The migration still deliberately provides no `--coverage` CLI flag. Line and branch probes, report
formats, filtered-run semantics, cancellation, and LSP events remain separate later slices.
