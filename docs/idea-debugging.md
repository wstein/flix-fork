# JVM Debug Builds

`flix build --Xdebug` selects the experimental JVM debug-build policy. It is the
compiler foundation for source-level JVM debugging; it does not by itself provide an
IDE debugger or expression evaluation. Debug sidecars are published with the build.

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
parameters and closure captures the same way. Closure conversion retains a capture's
source name through lowering, so a debugger sees `prefix`, not a generated `arg0$…`
temporary. This metadata is omitted from release builds.

User-source `let` bindings are materialized in debug builds. The inliner registers them
as `DebugLocal`, an explicit non-substitutable binding state, even when occurrence
analysis says a pure binding is used once. This preserves the inliner's substitution
invariants while allowing the existing symbol-renaming pipeline to carry the binding
into JVM lowering. Library bindings still follow the normal optimizer policy.

The JVM emitter records a local's range only after its initializer stores the value,
through the emitted continuation of the binding. This also works for locals before and
after effect suspension: JDI reads the actual restored slot, not a value reconstructed
from sidecar text. Generated ANF slots are hidden. Frame parameters become visible only
after their restore instructions. Internal continuation field names remain `l0`, `l1`,
etc.; debugger variable names come from the LVT.

Nested initializer scopes are kept inside their own ANF initializer in debug builds.
Their locals stop being visible before the enclosing binding is initialized, instead
of leaking into the enclosing scope through ANF hoisting. Release lowering is unchanged.

The compiler also captures source binding identity, name, location, and pre-erasure type
before lowering, joining them to the emitted class and method for the `debug-scopes`
sidecar. That method-wide snapshot is not a list of variables live at a particular
instruction: clients must use JDI/LVT visibility when selecting evaluation parameters.

Regression coverage includes both monomorphizers, non-overlapping two-slot `Int64`
locals, release-mode omission, sequential-mode pruning, and a real JDWP/JDI breakpoint
that reads locals after a handler resumes a suspended function.

## Build sidecars

A successful `flix build --Xdebug` writes two deterministic sidecars beside
`build/development/build.json`:

- `debug-index.json` format 1 maps each source identity recorded in emitted
  `SourceFile`/SMAP attributes to the generated binary classes carrying its code.
- `debug-scopes.json` format 2 maps emitted class, method, and source binding name
  to its pre-erasure Flix type, including lifted closure captures and lambda
  parameters in `applyFrame`. JVM local-variable tables remain authoritative for
  slots and live ranges.

The sidecars are produced only after class emission succeeds. A following non-debug
build removes them, so an IDE cannot accidentally consume metadata from an earlier
debug build. Clients that do not understand a sidecar format must ignore it and use
their ordinary debugger fallback.

## Current limits

The debug policy does not promise a bindable location for every lexical line, preserve
unused definitions removed by reachability analysis, or preserve local/lambda bodies.
Line-table attribution and the source/class and binding-type sidecars are available,
including name and type metadata for a `let` bound anywhere in a def, before or after a
suspension point.

The earlier attempts to retain source locals failed because they reused the ordinary
let-binding state, whose invariants assume substitution has already happened. The
explicit `DebugLocal` state resolves that distinction; it does not pretend an optimized
expression has a recoverable slot.

Complete lexical scopes (including shadowing and pattern bindings),
expression evaluation at a breakpoint, and JetBrains IDE qualification remain open.
Release builds remain subject to the normal optimizer policy.
