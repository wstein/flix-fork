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

Lambda parameters are captured from the typed source and joined by their source name
and declaration location when lifted. Parameterized types such as `Option[Int32]`
therefore retain their Flix spelling; generated parameters with no source binding are
omitted rather than assigned a guessed type from an erased backend representation.

Pattern variables are captured with their source types and use the same initialized
JVM ranges as source `let` bindings. When a lambda is lifted, the compiler joins the
locals that remain in that lambda body to the lifted class; locals in nested lambdas or
anonymous-class methods stay with their own emitted methods. Bindings with the same
source name in distinct branches retain distinct lexical identities and local-variable
ranges. Flix itself rejects lexical shadowing.

Specialized definitions inherit their source binding snapshot in both monomorphizers.
These are source types, not reconstructed specialization types: a generic parameter
may still be recorded as `a`. An evaluator must resolve that type in its source context
or reject it; it must not silently treat it as a JVM `Object` or an unrelated type.

The sidecars are produced only after class emission succeeds. A following non-debug
build removes them, so an IDE cannot accidentally consume metadata from an earlier
debug build. Clients that do not understand a sidecar format must ignore it and use
their ordinary debugger fallback.

## Current limits

## Expression typing

The language server implements `flix/debugEval/compile`. A request identifies the
paused JVM class and method, supplies a Flix expression, and chooses `pure` or
`allowEffects`. The compiler reads the exact format-2 scope sidecar produced by the
debug build, declares only the referenced debugger-visible bindings in an in-memory
wrapper, and returns the compiler's type, effect, or diagnostics. It writes no project
artifact during this typing pass.

Referenced bindings are selected lexically rather than by substring: a frame variable
named `at` is not declared for the literal `"at"` (which would make the generated wrapper
fail its unused-parameter check), but is declared for interpolation code such as
`"${at}"`. Comments, character literals, nested braces, and escaped string content are
skipped by the same scan; parsing and name resolution remain the compiler's authority.

The build sidecar is authoritative for frame types. At launch the debugger records a
build ID made from both the manifest fingerprint (compiler options and dependencies)
and `sourcesDigest` (source contents), and sends it with every evaluation request. The
language server rejects the request if the on-disk manifest now names another build.
Thus rebuilding while an older JVM is paused cannot compile an expression against the
new sidecars and inject it into the old program. The launcher passes the ID as
`-Dflix.debug.buildId`; `DebugEvalHost.BUILD_ID` snapshots it at class load and exposes
it as a read-only static field, so JDI can read the identity without invoking code in
the paused process. Class names are accepted in both JDI
binary form and JVM internal form. Repeated names with conflicting source types are
omitted from the sidecar rather than resolved arbitrarily; an expression that needs one
receives an ordinary unknown-name diagnostic.

Pure evaluation is the default policy. `allowEffects` permits compilation only; the
debugger still applies its explicit user setting before it executes effectful code.

When an artifact is requested, the compiler performs a second, in-memory code-generation
pass with the inferred return type. It returns only classes absent from the development
build manifest, so the debuggee continues to resolve program classes from the running
build. A missing, malformed, or non-format-4 manifest is refused; the class directory is
never used as a fallback because it may contain a newer or partial build. No temporary
class directory or project output is modified. Raw artifact class bytes are capped at
16 MiB before Base64 expansion, preventing an expression from creating an unbounded
JSON-RPC/JDI payload; a larger specialization set is rejected with a specific remedy.

Debug builds include `dev.flix.runtime.DebugEvalHost` and its private child loader as
ordinary compilation products; release builds omit them. Generated `Main` loads the host
before executing user code so JDI can invoke it at the first source breakpoint. Each
evaluation uses a fresh parent-first loader, allowing old artifacts to become collectible
while preserving object identity for values owned by the running program. The host runs
the Flix trampoline, returns the compiler-selected `Value` field, and refuses suspended
effects rather than attempting to resume the program's handlers while it is paused.

The language server keeps one in-memory evaluation compiler for the active project and
caches answers by build source digest, frame, expression, policy, and artifact request.
Repeated watches therefore reuse both the compiler and the emitted artifact. At most 32
answers are retained for the active session, with the least recently used discarded
first. A changed
source set, a new build digest, or a different project closes and replaces the compiler;
no cache entry is allowed to cross the identity of the launched build.

That compiler is created by the language server project's existing `Bootstrap`, not by
scanning `.flix` files into a bare compiler. It therefore receives the same packages and
JARs declared by `flix.toml` as the paused program. Evaluation remains in-memory, and a
dependency change still replaces the language server project before the next request.

The debug policy does not promise a bindable location for every lexical line, preserve
unused definitions removed by reachability analysis, or preserve local/lambda bodies.
Line-table attribution and the source/class and binding-type sidecars are available,
including name and type metadata for a `let` bound anywhere in a def, before or after a
suspension point.

The earlier attempts to retain source locals failed because they reused the ordinary
let-binding state, whose invariants assume substitution has already happened. The
explicit `DebugLocal` state resolves that distinction; it does not pretend an optimized
expression has a recoverable slot.

Final JetBrains IDE qualification remains open.
Release builds remain subject to the normal optimizer policy.
