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
parameters and closure captures the same way. Closure conversion retains a capture's
source name through lowering, so a debugger sees `prefix`, not a generated `arg0$…`
temporary. This metadata is omitted from release builds.

Source `let` bindings -- including one bound after a suspension point, inside an
effectful frame -- are not attempted as real JVM locals this way; they are intentionally
not guessed from lowered ANF names, since the optimizer is free to inline, substitute, or
drop them before a frame's fields are assigned. Instead, the compiler captures every
source parameter and `let` binding's identity, name, and location into a
compilation-local snapshot before typed bodies are released, keyed only by the
*declaring def's* own symbol. This snapshot is joined to the def's stable generated class
name once known and exposed on the in-memory compilation result (and the `debug-scopes`
sidecar) for debug builds, independent of what the optimizer or ANF lowering did to the
binding afterward: a `let` after a suspension point is reported exactly as reliably as
one before it, since neither ever needs to survive as an AST node for its name and
pre-erasure Flix type to reach the snapshot. It retains that type and the emitted method
(`staticApply` or `applyFrame`) but does not duplicate JVM slots or liveness ranges --
this is metadata a tool can look up, not a real local a standard Java debugger's
`LocalVariableTable` will show while stepping.

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

What that metadata does not yet give is a *real* JVM local: the frame fields a live
continuation-local is actually stored in (`GenFunAndClosureClasses.nameFrameSlots`'s
`lparams`) are still named from their ANF-lowered symbol, not their source name, unlike
the formal-parameter and closure-capture fields beside them. Closing that gap needs the
binding's identity threaded through every phase that can rename or substitute it --
`Inliner`, `ClosureConv`/`LambdaLift`, and the ANF pass in `EffectBinder` -- since
attaching it after the fact is too late, and forcing the binding to survive as an AST
node fights the inliner's own substitution invariants. Two attempts at this were reverted
for exactly that second reason; a viable design would propagate a stable key through each
substitution instead of trying to keep the binding's shape.

Complete lexical scopes (live ranges, not just names), expression evaluation at a
breakpoint, and JetBrains IDE integration remain later milestones. Release builds remain
subject to the normal optimizer policy.
