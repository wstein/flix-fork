# Stable JVM names

## Implementation status

The `feat/stable-jvm-name-table` branch starts at exactly `v0.76.0`.
The implementation provides key encoding, an immutable name table, a
compilation-local provenance registry, typed-AST source-origin capture, and
provenance propagation through both monomorphizers, optimization, lifting, and
erasure. JVM generation freezes the surviving JVM-visible symbols before emitting
or aggregating classes. Definitions and references use that same immutable table.
Internal symbol allocation, equality, and counters also remain unchanged.

## Table contract

`GeneratedJvmKey` contains a generated-symbol family and ordered semantic fields.
The binary encoding frames a format version, domain, field count, and UTF-8 byte
lengths. Delimiters inside names cannot alias field boundaries. Malformed Unicode
is rejected instead of silently replaced. A Unicode golden vector pins the exact
version-one bytes and suffix, so encoding changes require an explicit review.
Callers must
provide canonical semantic fields: rendered types, internal counters, source
positions, unordered collections, and optimized body hashes are unsuitable.

`JvmNameTable` assigns twelve lowercase base-36 characters from SHA-256 modulo
36^12, including leading zeros. Registration order does not affect names. The
table rejects conflicting keys for one symbol, one key assigned to distinct
symbols, and different keys producing the same suffix. Repeated registration
of the same symbol and key is allowed. Missing lookups fail with an internal
compiler error; there is no counter fallback.

The collision check is deliberately global across the table, including families.
This is stricter than checking final JVM class names and avoids relying on
generator-specific prefixes to conceal ambiguous provenance.

## Semantic type keys

`JvmTypeKey` encodes types structurally, retaining argument order and effects.
Type variables use their declaration's parameter order, so renaming a parameter
or changing its internal allocation id preserves its key. Row labels and
commutative set operands use canonical order. Generated nominal types require
their recorded origins. Simplified types have a separate encoding for erasure;
that encoding cannot recover already-erased effects.

Java generic parameters use bound indices when their declaration parameter list
is available. `JvmConstructor` does not carry its owning class's parameter list;
class variables in constructor signatures therefore retain their declaring class
and variable name. Renaming those Java class parameters can change a constructor
key. Constructor-declared parameters still use bound indices. This boundary also
applies to class variables in field signatures, which lack that parameter list.

The encoder is not a Boolean solver. Callers must normalize other algebraic
equivalences and resolve associated types before comparing keys across those
reductions. Unbound variables, erroneous types, and missing origins fail closed.

## Declaration origins

`JvmDeclarationOrigins.capture` records declarations from the typed AST, while
instance ownership and generic parameters are still available. Instance and
derived members use the trait, canonical instance head, and member name; defaults
use a separate implementation family. Definition bodies do not participate in
declaration identity. Capture does not change internal symbols or their counters.

Tests check unrelated declarations, body edits, instance reordering, generic
parameter renaming, default implementations, and derived standard-library members.

## Lexical origins

`JvmSourceOrigins.capture` combines declaration capture with lexical capture for
ordinary definitions, instance members, and default implementations. It must run
before monomorphization or optimization discards source structure. It records
anonymous-class origins in the symbol registry while their owning source bodies
are still available.

`JvmLexicalOrigins` records source-level structure, enclosing scope, binding
identity, and an ordinal within each identical sibling group. Parameters and
local bindings use lexical identities rather than allocation counters or spelling.
Regions and nested pattern scopes retain distinct binding identities. Source
positions and comments do not participate. Structural fingerprints are bounded
SHA-256 digests of framed fields, not hashes of optimized bodies.

Let-expression identity uses the binding's right-hand side rather than its
remaining statement chain. Fingerprints are memoized by expression identity,
lexical environment identity, and binding depth; nonbinding edges reuse the same
depth. Sequential let and nonbinding chains have operation-count regression tests
at increasing sizes. Distinct binding contexts require distinct fingerprints;
this is not a general linear-time guarantee for arbitrary nested scopes. Role
paths use bounded framed hashes. Error expressions are rejected with an explicit
error-free typed-AST contract diagnostic.

Fingerprint traversal alpha-normalizes nested binders; origin traversal binds
them to recorded lexical sites. Shared scoped combinators enumerate lambda,
local-definition, and anonymous-class bodies and prepare their binding
environments. Explicit alpha and site contexts preserve their different scope
keys without duplicating the traversal. Local-definition continuations have lazy
environment preparation: site fingerprinting excludes the continuation, while
full-expression fingerprinting and origin visitation include it. Anonymous-class
fingerprints sort method keys; visitation retains source order. Fingerprint-only
type and annotation metadata is encoded by the fingerprint consumer, not by the
shared traversal.
Golden fixtures pin the pre-refactor keys, traversal order, and fingerprint
evaluation counts for nested lambdas, recursive local definitions, and anonymous
classes with explicit constructors. Reversing anonymous-class methods changes
visitation order but not their semantic origins or the enclosing class key.

Lexical capture requires explicit type and declaration-origin callbacks. There
is no nominal-symbol fallback: a default implementation and an ordinary
definition can have the same symbol shape but different declaration families.
All callers must use the authoritative declaration registry for symbol origins.

Named holes retain their source labels; anonymous holes omit their generated
names. `HoleSym.isAnonymous` records that distinction at creation time without
changing allocation, equality, or hashing. Inferring anonymity from a name such
as `h123` would incorrectly classify a user-written hole.

Lexical type shapes use a separate encoding domain. Declared type parameters,
concrete types, and bound regions retain semantic identity. Residual inference
variables have an opaque, kind-specific marker: neither their counters nor their
inference-dependent sharing or traversal order define a source site. This encoding
is **not** suitable for specialization identity; specialization uses the strict
semantic encoder, which rejects unbound variables.
Inferred call-site type arguments do not define lexical identity; complete
specialization arguments must be captured separately during monomorphization.

Temporary expression lookups use object identity only for lookup, never as key
material. `entries` contains lambda, local-definition, and anonymous-class sites;
`allEntries` also contains expression and call sites needed for clone provenance.
Call `releaseBodies()` after transformations transfer those origins. It releases
the body maps without removing origins already copied to the symbol registry.
Compilation imports these lookups into `JvmCompilationOrigins` and immediately
releases the source-body wrappers.

## Transformation provenance

Each code-generation invocation owns one `JvmCompilationOrigins`. Both
monomorphizers capture specialization identity before lowering loses type
arguments. Generated enum, struct, case, field, anonymous-class, and lifted
definition symbols receive origins at creation time. Internal counters are never
used as origin fields.

Expression transfers preserve source identities; eliminated expressions retain
the returned child's identity. Inlining and other duplication include the source
site and destination clone context. Newly synthesized children use explicit
semantic roles and structural field positions within the generated expansion.
Map branches require explicit attribution rather than iteration-order identities.
Parent keys are digested into bounded fields instead of retaining parent ASTs.

Suspended inliner expressions retain their immutable definition-site context:
variable renaming, substitutions, in-scope bindings, clone provenance, and the
inline-expansion guard. Forcing a caller argument restores that context instead
of inheriting the callee's guard or variable bindings. This lets nested caller
arguments inline through forwarding wrappers without spending an optimizer round
per wrapper. Expressions suspended inside a callee retain the guard; recursive
body expansion is still bounded. This does not promise an optimization fixpoint
for every program, and the optimizer's round limit is unchanged.

Moving an original expression to its sole use preserves its source identity,
independently of changes to the consuming expression. A suspension created while
cloning instead receives destination-specific provenance when materialized.
Restoring its captured bindings and guard must not give two materializations the
same lambda identity. The lazy `DelayList.flatMap` assertion regression exercises
this distinction; attributing every original suspension to its use site would
reintroduce name churn when a trailing expression changes.

Argument evaluation uses the call-site context, while a local callee's free
variables use its saved definition context. The new body clone is attributed to
the actual call site. Lambda-eligibility checks follow captured suspension
contexts too. Already visited copy-propagated expressions keep their recorded
source provenance and receive destination-specific clone identities; they do not
retain an unused definition-context snapshot. These environments are local to
the inliner traversal and are not stored in the frozen JVM name table.

Lambda dropping gives its synthetic local-definition wrapper a separate origin
from the retained body. Closure conversion preserves origins during captured
local-call rewrites. Tuple-switch lowering shares its wildcard body through a
single branch target rather than duplicating closures under the same source key.
The switch itself is a mapped branch reached by a jump, preserving tail-position
marking in successful cases as well as in the shared fallback.

Pruning runs after source tree shaking, monomorphization, lambda dropping, each
inlining round, simplification, closure conversion, lifting, and subsequent
lowering phases. Source pruning uses surviving declarations, not stale module
membership lists. Virtual default-implementation symbols remain available while
their signature bodies survive. After lifting, expression tables are released;
only live symbol origins remain. Pruning occurs after parallel workers join.

Compiler cleanup releases the context on success and failure, including failed
or already completed freezes. A later compilation on the same compiler receives
a fresh context. Access outside code generation and nested context reuse fail.

## Registry lifecycle

Create one `JvmProvenance` per code-generation invocation, including recompiles
on the same compiler object. Its synchronized registration and origin lookup
support parallel compiler phases. Conflicting registration fails immediately;
identical registration is idempotent.
Registration and direct name-table construction share the same consistency check
and conflict diagnostic. The check does not mutate either owner's map; rejected
registration preserves the prior origin, including for equal symbol instances.

After a phase discards symbols, call `retainLive(live)` with the symbols whose
origins later phases still need. This removes dead entries and replaces the map's
backing storage, so neither dropped symbols nor peak table capacity are retained.
Call it after parallel workers have joined and after copying any parent origin
needed by a surviving specialization or lifted construct into its own key. Keys
contain semantic strings, not references to discarded ASTs or parent symbols.
Pruning can run repeatedly; registration remains open for later phases.

After tree shaking, `freeze(required)` checks every surviving generated symbol
and builds the immutable table using only those symbols. Dead generated symbols
do not affect collision claims. Freezing is a one-way transition: subsequent
writes, pruning, and repeated freezes fail, including after an unsuccessful freeze.
Every freeze attempt releases all registry entries and backing storage, whether it
succeeds or throws. The returned immutable table owns only the required symbols
and their suffixes; it retains no provenance keys. Release that table after bytecode
generation as well. No partially validated table is published. Registry instances
share no mutable state.

## Integration sequence

1. Record declaration origins from the typed AST, including instance and derived
   members, at the start of each code-generation invocation.
2. Capture specialization arguments in both monomorphizers. Erased arrow types
   alone are insufficient: distinct effect arguments can share that type.
3. Preserve lexical site, synthetic role, and clone provenance through lowering,
   inlining, closure conversion, and lambda lifting. Anonymous-class origins must
   include the enclosing specialization. Absolute locations and traversal
   counters cannot provide edit-resistant lexical identity.
4. Record enum specialization provenance before erasure removes its arguments.
   Struct classes and payload tags in v0.76.0 already use layout-based names;
   their semantic types still participate in specialization keys.
5. Build the table before JVM generators aggregate classes. Route definitions,
   references, nullary tags, anonymous classes, and non-export namespace shims
   through the same table. Preserve exported method names.

All five steps are wired into compilation. Before emission, `CodeGen` freezes
every surviving definition, enum owner of an emitted nullary case, and anonymous
class. Payload-only enums and layout-based structs need no JVM-name entry.
The required set follows emitted declarations, not just expression references.

Function and closure descriptors, anonymous-class descriptors, nullary singleton
descriptors, and non-export namespace shims use the frozen table for both
definitions and references. Generated definitions and enums replace only their
counter suffix; source declaration spelling and exported method names retain
their existing ABI. Java overrides, effects, and structural runtime class names
are unchanged. Missing mappings fail rather than recovering a counter-based name.

Freezing releases all remaining expression and symbol provenance, including on
failure. No more registration or pruning is permitted. The compilation owns the
immutable table until cleanup, which releases it on success and failure. Emission
listeners may read the frozen table, but raw provenance is no longer available.
This guarantees stable linkage names, not byte-for-byte identical class files:
source locations and diagnostic strings are outside the naming contract.

## Identical unnamed siblings

Inserting, deleting, or reordering identical unnamed lambdas may renumber that
identical sibling group. Distinguishable constructs must retain their identities.
Persistent identities across edits are not required for identical siblings.

The lexical identity must include the enclosing semantic scope, a canonical
source-level identity for the construct, and an occurrence ordinal within only
that identical group. Assign ordinals in deterministic source traversal order,
before parallel transformations. Do not use a counter shared by all lambdas in
the scope: inserting a distinguishable lambda must not renumber existing groups.
The ordinal distinguishes identical sites; absolute source positions are not
part of the key.

Integration tests must cover both allowed renumbering within an identical group
and preserved identities for other groups when identical or distinguishable
siblings are inserted. This policy settles the identity requirement for source
capture and propagation.

## Verification

Run the following on Java 21 or newer:

```sh
./mill --no-server flix.test.testOnly 'ca.uwaterloo.flix.language.phase.jvm.TestJvm*'
./mill --no-server flix.test.testOnly 'ca.uwaterloo.flix.StandardLibrarySuite'
./mill --no-server flix.testExamples
```
Tests cover counter independence, framing, family separation, ordering, width,
leading zeros, duplicate identity, conflicting provenance, collisions, and missing
lookups. Digest injection is package-private and used only to force collisions.
Registry tests cover parallel registration, required-symbol completeness,
phase pruning, release after successful and failed freezes, tree-shaken symbols,
freeze lifecycle, and isolation between compilations.
Pipeline tests exercise both monomorphizers, identical inlined lambda clones,
pattern branches, anonymous classes, unrelated declaration edits, parallel builds,
captured recursive local calls, lambda-drop wrappers, and compiler-context cleanup.
Tuple-switch tests also execute successful and fallback paths with captured
parameters and 100,000 recursive calls to guard tail-call preservation.
Inliner tests execute nested forwarding, caller parameter reuse, local captures,
lambda aliases, and recursive callee-created suspensions under both
monomorphizers. Forwarding chains exceed the optimizer round limit and must
eliminate their private helper definitions. A standard-library pipeline test
checks runtime results and preservation of emitted names when adding an Int8
`List.map` specialization beside existing list and vector pipelines.
Descriptor tests change allocation counters while retaining semantic origins and
check every counter-bearing naming family. Missing frozen mappings and access
before freezing or after cleanup are rejected. Pipeline checks compare actual
emitted class names and verify classfile names against their descriptor keys,
not a separately reconstructed provenance table. Namespace-shim tests preserve
exported names and reject unmapped exports as well as generated definitions.
Runtime fixtures exercise specialized nullary cases, captured anonymous methods,
anonymous constructors, and superclass bridges under both monomorphizers.

This revision has no configured Scala formatter or source-linter command.
Validation uses the existing warnings-as-errors Scala compilation and
`git diff --check`. The companion lab supplies strict emitted-name stability
checks in addition to the compiler tests. Passing compiler tests alone does not
establish edit stability.

### Suspended-argument verification (2026-09-16)

Compiler revision `2e1743823` passes the full suite: 16,910 tests in 72 suites,
zero failures or aborted suites, and eight ignored tests. The focused JVM suite
passes all 139 tests; the previously failing standard-library suite also passes
all 14,223 tests when run separately. The forwarding regression fails if caller
arguments inherit the callee's expansion guard. The lazy flatMap regression
fails if cloned suspensions reuse their captured provenance unchanged. Conversely,
attributing every suspension to its destination fails the trailing-expression
and list-specialization stability regressions under both monomorphizers.

The companion lab at `ef52883` was run against assembly SHA-256
`f9ecd1a5a11b2cf4f1a20a05628e8168696434eb12b62bf421ec0aedb102d11c`,
with both `--assert-stable-names --verbose-names` and those options plus
`--new-monomorphizer`. Both modes preserve all names on identical rebuilds,
12-thread rebuilds, comment and blank-line edits, local-variable renaming,
unrelated-definition insertion, lifted-closure insertion, and addition of a
`List.map` specialization. The specialization edit adds five classes and removes
none, including no loss of the existing vector pipeline's lifted definitions.

Both strict sweeps still exit nonzero: prepending a list pipeline removes seven
names and adds ten. This is a separate unresolved edit-stability failure, not a
successful verification. Lexical capture currently groups identical argument
wrappers by declaration scope and child role rather than by their distinguishable
containing call sites; the seven existing `List.length` wrappers are a candidate
explanation that requires suffix-to-origin tracing. No missing names are
whitelisted. Parallel rebuilds preserve names but not every classfile byte;
the observed byte preservation is 99.23% (classic) and 99.27% (new monomorphizer).
