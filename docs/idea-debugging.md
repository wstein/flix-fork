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
for source formal parameters. This makes their names and JVM descriptors available to a
native JVM debugger without exposing compiler-generated temporaries. Source `let`
bindings, captures, and continuation-frame values require the separate pre-erasure
debug-provenance snapshot; they are intentionally not guessed from lowered ANF names.

The compiler now captures source parameter and `let` binding identities, names, and
locations in that compilation-local snapshot before typed bodies are released. This is
finalized against stable generated class names and exposed on the in-memory compilation
result for debug builds. It is not yet emitted as a debug sidecar or joined to JVM
slots, captures, or continuation fields.

## Current limits

The debug policy does not promise a bindable location for every lexical line, preserve
unused definitions removed by reachability analysis, or preserve local/lambda bodies.
Line-table attribution, scope metadata, source-to-class indexing, and JetBrains IDE
integration are delivered in later milestones. Release builds remain subject to the
normal optimizer policy.
