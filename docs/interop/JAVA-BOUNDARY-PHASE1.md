# Typed JVM boundary: experimental Phase 1

The replacement is opt-in. It does not restore `@Export`, change ordinary Java imports,
or alter the existing `ToJava`/`ToFlix` traits. ADR 3 remains Proposed; this implements its
first experimental milestone, not generated tuple/record/enum classes or Java effect handlers.

## Declare the bootstrap and recorded API

An API is a separate `.flix-api` source, not syntax inserted into an ordinary `.flix` file:

```text
export mod Acme.Api as "com.acme.Api" {
    def values: () -> java.util.List[java.lang.Integer];
    def total = sum: (java.util.List[java.lang.Integer]) -> int;
    def model: () -> dev.flix.runtime.OpaqueHandle[java.lang.Object];
}
```

The members select `Acme.Api.values`, `Acme.Api.sum`, and `Acme.Api.model`; an alias may
instead name a fully qualified target. Each line records the Java-facing signature explicitly.
Use Java primitive names (`boolean`, `char`, `byte`, `short`, `int`, `long`, `float`, `double`),
`void` for results, fully qualified reference types, and `[...]` for generic arguments.
Generic arguments must be references. Overloads, wildcards, arrays, and generated Java types
are not part of this first contract grammar. Comments use `//`. Members end with semicolons.

This contract is an assertion, **not** a conversion recipe. Successfully checked
`Java.Boundary.JavaResult` and `JavaArgument` instances independently determine the real ABI.
The compiler compares the class name, method names, descriptors, and generic signatures
before generating runnable code. Any mismatch fails at the declaration and prints expected
and actual values. Changing an instance can therefore require updating the recorded API.

## Break a Java-first compilation cycle

```console
java -jar flix.jar java-api-stubs src/Api.flix-api --out build/api-stubs
javac -cp build/api-stubs -d build/java src/java/JavaConsumer.java
jar --create --file build/java.jar -C build/java .
java -jar flix.jar java-api src/Api.flix-api --lib build/java.jar --out build/flix src/Api.flix
java -cp build/flix:build/java example.JavaConsumer
```

Use `;` instead of `:` in a Windows runtime classpath. Stubs depend only on the JDK and the
included opaque-handle API. The first command does not resolve Java imports, type-check Flix,
or guess user-instance conversions. Stub methods throw if accidentally used at runtime.
Never package `build/api-stubs` or put it on the runtime classpath.

Without explicit `.flix` arguments, `java-api` loads the project through normal `Bootstrap`,
including its manifest dependencies. `--lib` is repeatable. The build tool owns output
directories and stale-class cleanup; this command never deletes them and refuses to overwrite
a non-class file. Enable `--diagnostics-json` for the existing build-protocol document,
with LSP wire ranges and source paths.

Language servers validate opened `.flix-api` buffers and discover contracts under `src/`.
They retain the original typed root for editor queries, synthesize/check wrappers without
code generation, and publish declaration-located diagnostics. Unsaved buffers shadow disk
contents; closing a contract restores the disk version or removes an in-memory-only contract.

## Conversion and effect policy

Top-level primitives, `String`, `BigInt`/`BigDecimal`, and Java native types pass through.
Inside supported containers, primitive instances box and element instances recurse. Packaged
instances support List, Optional/Option, Vector, Chain/Collection, and result-only Set and Map.
Sets/maps are copies using Java equality; conversions that collapse distinct keys/elements
have Java collection semantics. Set/Map arguments deliberately require user instances.
Types without the required directional instance fail; no implicit opaque fallback exists.
User instances can target Java classes already present on the type-checking classpath.

The target effect and all conversion effects are combined. Only ground, finite sets of
primitive effects and effects with default handlers are accepted. The checked source wrapper
installs default handlers around the entire conversion/call sequence, following entry-point
handler order. Existing unchecked exceptions and errors reach the Java caller unchanged.

## Explicit opaque values

Use `Java.Boundary.Opaque[t]` in the original Flix signature and construct
`Java.Boundary.Opaque.Opaque(value)`. At a top-level boundary position the compiler synthesizes
tagged `pack`/`unpack` calls and emits `dev.flix.runtime.OpaqueHandle<Object>`.
This is an explicit compiler capability, not an unconstrained blanket trait instance.
Containers of opaque values require a user instance; they do not silently erase element types.

The final handle has no public constructor or payload accessor. It carries the semantic
`JvmTypeKey` of `t`, independent of aliases and generated JVM naming mode. Unwrapping checks
the tag before casting and rejects a mismatched or null handle with `IllegalArgumentException`
at the boundary, naming the expected and actual types. `toString` shows the Flix type only.
Equality and hashing retain Object identity semantics; the handle is not Serializable.
Its unused generic parameter reserves space for generated marker types in a later phase.
Handles belong to their generated program/runtime; cross-build persistence is unsupported.

The bridge methods are compiler-internal, not a supported public API or a security boundary.
The compiler exposes only the two support classes to its isolated Java metadata loader and
packages their standalone bytes with both bootstrap APIs and real output, not the compiler jar.

Region-bound arrays, regions, and mutable structs are rejected even inside opaque values
and recursively inside nominal enum payloads. The nominal scan is conservative: a type with
a potentially regional case is refused even if a particular value takes a different case.

## Validation and rollout

From the matching `flix-lab` feature branch, with a JDK 21 available to Gradle:

```console
FLIX_FORK_ROOT=/path/to/compiler scripts/check-java-boundary-phase1
```

This runs only focused boundary suites, then isolated Java, Kotlin, and Scala callers.
All three compile against API-only output and run with real generated classes, never stubs
or the compiler jar. The compiler suites additionally test a genuine Java-first cycle,
ABI mismatch rejection, CLI JSON diagnostics, LSP edit/close behavior, opaque type checks,
default handlers, generic collection signatures, and transitive region rejection.
Temporary artifacts are retained for inspection; the script prints their directory.
The full compiler suite and native-image build are not run; opaque class-file resources are
registered in native-image metadata, but native execution is not part of this validation claim.

Start with a new consumer and a pinned experimental compiler build. Existing generated
tuple/record/enum consumers remain on the archived export-enabled build until phase 2
supports their shapes. Publication and remote migration are separate, explicit actions.
The [upstream proposal draft](UPSTREAM-TYPED-BOUNDARY-PROPOSAL.md) is ready for discussion;
no upstream issue/PR is opened automatically.
