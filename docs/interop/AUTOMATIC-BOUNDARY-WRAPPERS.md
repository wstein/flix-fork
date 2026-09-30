# Automatic boundary wrappers (experimental)

For the complete source-contract path and current validation, see
[experimental Phase 1](JAVA-BOUNDARY-PHASE1.md). This page describes the lower-level API.

`JavaBoundaryWrappers.compile` now joins the concrete elaboration proof to the named facade API.
Callers select original Flix definitions and boundary trait symbols; they do not write Java-shaped
wrapper signatures or conversion bodies. No surface associated-type rule is changed.

```scala
val api = JavaBoundaryWrappers.Declaration("com.acme.Api", List(
  JavaBoundaryWrappers.Member("values", Symbol.mkDefnSym("Acme.values"), declarationLocation)))
val traits = JavaBoundaryWrappers.Traits(
  Symbol.mkTraitSym("Acme.JavaResult"), Symbol.mkTraitSym("Acme.JavaArgument"))
val result = JavaBoundaryWrappers.compile(flix, api, traits, securityContext)
```

The caller has already added its original sources to `flix`. The traits must define
`Out`/`In`, `Aef`, and `toJava`/`toFlix` in the ADR's shapes. The packaged traits are
`Java.Boundary.JavaResult` and `Java.Boundary.JavaArgument`; explicit symbols remain useful for
isolated prototypes. Existing `ToJava` and
`ToFlix` are untouched. The declaration location is supplied by the caller and is the primary
location reported for member-specific elaboration and wrapper-checking failures.

## Implemented pipeline

1. Check the original sources through instance validation.
2. Reject non-public, polymorphic, or constrained targets. Reject arrays and mutable structs
   appearing in their signature types, and reject unsupported boundary representations.
3. Keep top-level primitives, `String`, Java native types, and `Unit` unchanged. For other types,
   derive argument `In` and result `Out` plus their `Aef` from checked instances. Missing
   directional evidence is an error, never an opaque or `Object` fallback.
4. Sum the original effect and all conversion effects. Admit ground, finite primitive effects
   and default-handled effects; wrap the entire conversion/call sequence in required handlers.
5. Generate ordinary source definitions calling argument conversions, the original function,
   and the result conversion. Native imports and concrete signatures come from the derived types.
6. Recheck that augmented program with the ordinary frontend, retain the wrappers as entry points,
   and emit the named forwarding facade using the recorded generic types.
7. Remove the owned virtual generated source in `finally`, on success and failure.

The wrapper module name is a deterministic hash of the chosen Java class name, not a global
counter or random identifier. A caller-owned module or source at that name is rejected without
being overwritten or removed. Repeated compilation reconstructs the wrappers from current typed
instance information: an instance edit can change the Java ABI, even if the target signature is
unchanged. The prototype intentionally replays the frontend; it does not introduce a new
dependency-graph format or claim optimal incremental compilation performance.

`WrapperErrors.loc` maps an ordinary generated-source failure to the selected member's supplied
location. Its nested compiler messages retain the generated source for inspection. Errors in
original sources retain their own locations. `JavaBoundary` now converts these errors into
shared CLI/LSP messages for `.flix-api` source contracts.

## Validation

All 31 replacement tests pass (18 type-elaboration, 5 facade, and 8 wrapper tests), along with
the harness's shell syntax and missing-configuration guards. The full compiler suite is not run.

```console
FLIX_FORK_ROOT=/path/to/compiler ./scripts/check-java-boundary-wrappers
```

The new suite derives nested list results, recursively converts Java list arguments, converts
user enums through user instances in both directions, checks conversion-effect sums, and runs
a staged Java caller compiled against API-only stubs in a separate JVM with real classes alone.
Other tests cover missing directional evidence, polymorphism, array rejection, unsupported
associated outputs, primary diagnostic locations, cleanup after failed rechecking, stable repeated
facade bytes, instance-driven ABI rebuilding, and preservation of caller-owned virtual sources.

## Current integration

Phase 1 adds source contracts, packaged instances, effect handlers, type-tagged opaque crossing,
transitive region checks, cyclic-build bootstrap stubs, and Kotlin/Scala callers. The separate
`java-api-stubs` command reads the explicit contract without type-checking; the real compile
checks its ABI against derived instances before code generation. See the Phase 1 guide for
the 43-test boundary corpus and rollout limits. ADR 3 remains Proposed.
