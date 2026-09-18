# JVM Debug Builds

`flix build --Xdebug` selects the experimental JVM debug-build policy. It is the
compiler foundation for source-level JVM debugging; it does not by itself provide an
IDE debugger, debug sidecar metadata, or expression evaluation.

## Function bodies

In a debug build, the optimizer retains user top-level definitions instead of folding
their bodies into callers. This gives a single-expression helper its own generated JVM
class and bytecode location, which a source breakpoint can bind to.

The policy deliberately permits one narrow exception: the compiler-owned standard
library switches for Datalog execution, collection evaluation, and lock elision may be
inlined after the compiler has rewritten them to constants. Those switches are not user
source locations. Keeping this established constant-folding and tree-shaking path is
required for `--Xsequential` to remove disabled parallel and locking implementations.

Consequently, `flix build --Xdebug --Xsequential` retains user function bodies while
still producing a single-threaded build. This contract is checked under both
monomorphization pipelines.

## Source locations

Debug builds record locations at real source-expression boundaries. In particular,
successive `let` bindings and calls in a retained function expose their own JVM line
numbers for breakpoint binding and stepping. Release builds retain their existing,
narrower line-table policy because optimization can move, merge, or remove expressions.

## Variables

For control-pure static methods, debug builds emit standard JVM local-variable entries
for source formal parameters. Effectful frames also expose their restored formal
parameters, continuation locals, and closure captures. Closure conversion retains a
capture's source name through lowering, so a debugger sees `prefix`, not a generated
`arg0$…` temporary. This metadata is omitted from release builds. Source `let`
bindings still require the separate pre-erasure debug-provenance snapshot; they are
intentionally not guessed from lowered ANF names.

The compiler now captures source parameter and `let` binding identities, names, and
locations in that compilation-local snapshot before typed bodies are released. This is
finalized against stable generated class names and exposed on the in-memory compilation
result for debug builds. It retains the pre-erasure Flix type and the emitted method
(`staticApply` or `applyFrame`) but does not duplicate JVM slots or liveness ranges.

## Build sidecars

A successful `flix build --Xdebug` writes two deterministic sidecars beside
`build/development/build.json`:

- `debug-index.json` format 1 maps each source identity recorded in emitted
  `SourceFile`/SMAP attributes to the generated binary classes carrying its code.
- `debug-scopes.json` format 2 maps emitted class, method, and source binding name
  to its pre-erasure Flix type. JVM local-variable tables remain authoritative for
  slots and live ranges.

The sidecars are produced only after class emission succeeds. A following non-debug
build removes them, so an IDE cannot accidentally consume metadata from an earlier
debug build. Clients that do not understand a sidecar format must ignore it and use
their ordinary debugger fallback.

## Current limits

The debug policy does not promise a bindable location for every lexical line, preserve
unused definitions removed by reachability analysis, or preserve local/lambda bodies.
Line-table attribution and the initial source/class and binding-type sidecars are
available. Continuation fields, complete lexical scopes, evaluation, and JetBrains IDE
integration remain later milestones. Release builds remain subject to the normal
optimizer policy.
