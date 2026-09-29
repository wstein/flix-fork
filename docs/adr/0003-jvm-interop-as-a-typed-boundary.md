# 3. JVM interop as a typed boundary, not a bytecode conversion layer

## Status

Proposed. Scoped to how Flix code is *called from* the JVM -- Java, Kotlin, Scala -- and how
values cross that boundary. Calling Java *from* Flix (`import`, `new`, method calls) is unchanged.
Numbered 3 to follow ADRs 1 and 2 on `feat/stable-specialization-names-rewrite`.

Revision 4 records the `flix-lab` typed-boundary probe: recursive element conversion, a
`List<Integer>` JVM signature, and a staged Java caller work through the archived `@Export`
path, but `JavaResult.Out[List[Int32]]` is rejected. The proposed wrapper therefore needs an
explicit boundary-type elaboration step; the probe does not validate the new declaration or
facade. Revision 3 records the fork's cleanup before its v0.77.0 merge: `@Export`, its conversion
backend, and the old stub generator have been removed. Phase 1 must introduce stubs for the
new declaration rather than adapt an existing command. Revision 2 narrowed phase 1 to what
can be built without new language machinery and identified the existing `ToJava`/`ToFlix`
traits, the early-class problem, and the need for an explicit opaque boundary.

## Context

### Upstream removed `@Export`

flix/flix v0.77.0 removed the `@Export` annotation outright (`0f4fe4b37`, "refactor: remove the
`@Export` annotation (#13411)", closing flix/flix#8036), because it was undocumented, allowed only
primitives and `java.lang.Object`, and had been broken for single-level modules since it was
added. Nothing replaces it, and further cleanups of the entry-point machinery -- test-only
namespace classes, dropping `Root.entryPoints` -- are announced.

### The fork's export experiment and its removal

The export experiment came from `feat/jvm-language-interop` (merged into `dev0.76.2` in
`0177278d6`). It converted `Option`, `List`, `Vector`, `Chain`, `Set`, `Map`, tuples, records, and
enums -- as Java enums and as sealed interfaces of records -- in both directions and nested.
Its staged tests proved Java could link against the generated API. Before merging v0.77.0, this
fork removed that implementation. Local archive tags `archive/export-v1` and
`archive/dev0.77.0-pre-cleanup` retain the old trees; they must be published before a remote
branch rewrite so pinned consumer commits remain fetchable.

What it cost is the reason for this ADR:

| Piece | Size | What it duplicates |
|---|---|---|
| `ExportPlan.scala` (results) | 885 lines | Flix's own Java interop, as hand-written ASM |
| `ArgumentPlan.scala` (parameters) | 473 lines | building Flix tags, tuples, and records by hand |
| `ExportStubs.scala` (joint compilation) | 601 lines | name resolution, from syntax alone |

Every hard problem came from deciding the boundary *after erasure*:

1. **Declared types are smuggled past the Eraser**: `exportedReturnType`, `exportedParamTypes`,
   `exportedEnumFields`, and `enumSpecializations` exist on `ErasedAst` and `JvmAst` only so
   codegen can recover what `Eraser` threw away.
2. **Runtime layouts are recomputed by hand**: tag field types, case ordinals, `RedBlackTree`
   node shapes, which specialization's nullary singleton to use.
3. **Names are derived, so they leak**: an exported enum's Java class is its companion module's
   facade, which forced a merge of the two and a reserved-name list (`IllegalExportEnumMember`);
   the facade layout change `f4dac093c` later broke debug evaluation and the reachability tests
   (`49a23aa01`, `7408fdec6`).
4. **Stubs re-resolve names without a resolver**, and broke as soon as 0.76.2 added
   package-qualified `use`.
5. **Everything is copied** on every call.

Keeping it also made upstream changes to `EntryPoints`, `GenNamespace`, or `Root.entryPoints`
conflict with code only the fork maintained. The cleanup removes that ongoing merge cost.

### What already exists in the library

The standard library already marshals values: `ToJava[t]` and `ToFlix[t]` (`ToJava.flix`,
`ToFlix.flix`), shaped like `Coerce[t]` -- an associated type `Out`/`In` and an associated effect
`Aef` defaulting to `{}`. Two of their choices do not fit an export boundary:

- `ToJava[Int32]` has `type Out = Integer`: it always boxes. A boundary wants `int` for a result
  and `Integer` only inside a container.
- `ToJava[List[a]]` has `type Out = JList[a]` and `type Aef = IO`: the elements are *not*
  converted, so a `List[Option[Int32]]` becomes a `JList` of Flix `Option` values.

Both are right for what they are: shallow, general-purpose marshaling. Changing them would break
their existing callers.

### Two facts that bound any design

- **Java types carry generic arguments only while typed.** `TypeConstructor.Native(desc, arity)`
  gives `JList[a]` its argument during type-checking; `SimpleType.Native(clazz)` has none. A
  facade's generic `Signature` attribute (`List<Integer>`) must therefore be decided before
  simplification, not in codegen.
- **A trait instance can only name classes that exist while Flix type-checks.** Java types are
  resolved from the classpath. Classes the compiler generates in codegen -- today's
  `dev.flix.gen.Tuple$int$String`, the exported enum classes -- cannot appear in an instance's
  `Out`.

## Decision

Model the boundary as typed Flix code synthesized before resolution, with a narrow phase 1 that
needs no new type-level machinery, and one small piece of codegen: a forwarding facade for an
explicitly declared API.

### 1. Boundary traits, distinct from `ToJava`/`ToFlix`

Add `JavaResult[t]` and `JavaArgument[t]` in a new `Java.Boundary` module rather than changing
`ToJava`/`ToFlix`. They differ from those in the two ways the boundary needs:

- **Deep.** A container instance converts its elements through the element's own boundary
  instance.
- **Positional boxing.** The facade passes a primitive, `String`, or a Java type unchanged at the
  top level of a signature; the boundary traits are consulted only for other types, and a
  container asks for its element *boxed* through `JavaResult.Boxed[a]`.

```flix
pub trait JavaResult[t: Type] {
    type Out[t]: Type
    type Aef[t]: Eff = {}
    pub def toJava(x: t): JavaResult.Out[t] \ JavaResult.Aef[t]
}

instance JavaResult[List[a]] with JavaResult[a] {
    type Out = JList[JavaResult.Out[a]]
    type Aef = IO + JavaResult.Aef[a]
    pub def toJava(l: List[a]): JList[JavaResult.Out[a]] \ IO + JavaResult.Aef[a] =
        Adaptor.toList(List.map(JavaResult.toJava, l))
}
```

Leaf instances delegate to `ToJava`/`ToFlix` where those already do the right thing. The
`flix-lab` prototype established that `JList[JavaResult.Out[a]]` and its recursive conversion
work within an instance constrained by `JavaResult[a]`. It did **not** establish that a concrete
application such as `JavaResult.Out[List[Int32]]` works: Flix rejects it because an associated
type may only be applied to a type variable. The prototype used an explicit `JList[Integer]`
export signature. Phase 1 must elaborate concrete boundary types before generating wrappers;
that elaboration, including generic signatures and effect sums, needs its own compiler proof.

### 2. An explicit, named API declaration

```flix
export mod Acme.Api as "com.acme.Api" {
    def price
    def quote
}
```

The Java class name is chosen, not derived from facade layout, and the members are listed in one
place. Every listed def must be monomorphic, declare its full signature, and have no region
parameter: a region-bound value (`Array[t, r]`, a mutable struct) never crosses. (The syntax is
illustrative; any form that names the Java class and the members will do.)

### 3. Wrappers are synthesized before resolution

For each listed def `f(x1: a1, ...): r \ e`, the compiler must first elaborate the declared
signature to concrete boundary types `JArg[a1]`, ..., `JResult[r]` and conversion effects.
It then synthesizes, in `Desugar` or earlier,

```flix
def f$java(x1: JArg[a1], ...): JResult[r] \ e + (conversion effects) =
    JavaResult.toJava(f(JavaArgument.toFlix(x1), ...))
```

Here `JArg` and `JResult` denote compiler-elaborated types, **not** Flix associated-type
applications in generated source. In particular `JResult[List[Int32]]` is `JList[Integer]`.
Parameters and results that are primitives, `String`, or Java types are left unwrapped. Because it
is synthesized before `Resolver`:

- it is resolved, kinded, and type-checked like every other def, so a type that cannot cross is an
  ordinary *missing instance* error;
- every synthesized node carries the source location of the member's line in the `export`
  declaration, so that error points at the export, not at invisible code;
- it is registered as an entry point, so `TreeShaker1`/`TreeShaker2` retain it;
- incremental compilation keys it on the declaration and on `f`'s declared signature, both of
  which it is a pure function of.

### 4. The backend emits a forwarding facade, with a recorded signature

After monomorphization the wrapper's types are exact JVM types. The backend emits `com.acme.Api`
with one `public static` method per member forwarding to `f$java`. The wrapper's typed signature
-- the only place Java generics still exist -- is recorded on it before simplification and
written as the facade method's `Signature` attribute. This is the entire new codegen.

### 5. Effects

The wrapper's effect is `e` plus every conversion's `Aef`. It is checked where the wrapper is
type-checked, against the rule `EntryPoints` applies today: primitive effects and effects with a
`@DefaultHandler`, whose handlers wrap the call the way they wrap `main` and `@Test` defs. The
collection conversions are `IO`, which is primitive. A failure inside Flix -- a match error, a
`bug!`, a Java exception the Flix code did not catch -- reaches the Java caller as the same
unchecked exception it is inside Flix; no effect can reach Java unhandled, because the check
refuses the export first.

### 6. Opaque crossing is explicit

A type with no boundary instance is a missing-instance error; there is no silent fallback. To
pass a Flix value through Java without converting it, the export says so with the type
`Opaque[t]`, whose instances in both directions wrap and unwrap the value in a Java handle Java can
hold and pass back but not inspect. A type with only one direction's instance may appear only in
that position: a `JavaResult`-only type as a result, a `JavaArgument`-only type as a parameter,
and the error for the other says which instance is missing.

### 7. Phase 1 represents values with classes that already exist

Phase 1 converts only to Java types present on the classpath while Flix type-checks: primitives,
`String`, `Optional`, `java.util.List`, `Collection`, `Set`, `Map`, and the user's own Java types
(a user's record or enum, targeted by a hand-written instance). Tuples, records, and enums without
such an instance cross as `Opaque` or are refused.

Generating Java classes for Flix tuples, records, and enums -- what the archived fork did -- needs
a synthetic-type provider: an early phase that declares those classes to the Java resolver before
type-checking, so an instance can name them. That is phase 2.

## Phases

| Phase | Adds | Keeps | Removes |
|---|---|---|---|
| 1 | `Java.Boundary` traits; `export mod ... as`; wrappers before resolution; forwarding facade with `Signature`; `Opaque[t]`; ABI gate; a new syntax-only stub generator for joint compilation | the v0.77.0 compiler and its non-export fork features | nothing yet |
| 2 | synthetic-type provider, so instances can target generated tuple, record, and enum classes; recover the archived conversion semantics as trait instances | stubs | nothing from the old export backend: it is already gone |
| 3 | `export instance com.acme.Service = mod Acme.Impl`: Flix implements a Java interface Java compiles first | stubs for Flix-owned APIs only | stubs for Java-first projects |
| 4 | effects as Java handler interfaces; collection views (O(1) at the boundary) | -- | copies where they are too expensive |

**A syntax-only stub generator is required in phase 1.** A Flix module that calls a Java class
Java has not compiled yet cannot be type-checked, so a typed `--emit-java-api` cannot break the
joint-compilation cycle. The old `ExportStubs` is gone; the new generator must read
`export mod ... as` declarations before resolution, using the declared Java name. Phase 3 removes
the cycle itself for projects whose contract is a Java interface.

## The ABI gate, in phase 1

Whether existing callers still link depends on the class name, each method's descriptor, and its
generic `Signature`, so these are fixed rules, checked in phase 1 rather than by a later lockfile:

- the Java class is exactly the `as` name;
- a primitive, `String`, or Java type crosses unchanged at the top level (`int`, not `Integer`);
- a container's element is boxed (`List<Integer>`);
- the facade method carries the generic `Signature` of its wrapper's typed signature.

A phase-1 test records, for a fixed corpus of declarations, the facade's class names, descriptors,
and `Signature` attributes, and fails on any difference from the recorded API. The archived
`@Export` corpus supplies compatibility fixtures where the new path supports the same types; a
Java caller compiled against those recorded descriptors must keep linking.

## Consequences

- **Smaller compiler, larger library.** The old boundary-specific Scala is already removed;
  the replacement adds a trait module, one desugaring, a syntax-only stub path, and a forwarding
  facade.
- **Extensible.** A user converts their own types by writing instances.
- **Errors are type errors**, located at the export's member line.
- **Names are stable by construction.** The `as` name fixes the Java class; the facade layout is
  free to change, and the companion-module merge and `IllegalExportEnumMember` go away.
- **`ToJava`/`ToFlix` are untouched**, and their callers with them.
- **Phase 1 converts less than the archived fork did.** Tuples, records, and enums need phase 2's
  synthetic types or a user's own Java class. Existing export consumers stay on an archived build
  until their required shapes are implemented.
- **Upstreamable in pieces.** Phase 1 is one declaration form, one desugaring, a library module,
  and a forwarding facade -- the shape a maintainer who just deleted `@Export` can review.

## Alternatives considered

Rated for value, effort, and fit with upstream (★ low to ★★★★★ high).

| Alternative | Value | Effort | Upstream fit | Verdict |
|---|---|---|---|---|
| Keep the fork's bytecode conversions and polish them | ★★ | large, ongoing | ★ | Rejected: a second compiler for the boundary, the shape upstream removed |
| Evolve `ToJava`/`ToFlix` to be deep and positional | ★★★ | medium | ★★ | Rejected: breaks their existing callers' types |
| Per-def `@Export` with trait-based conversions | ★★★ | medium | ★★★ | Rejected: keeps derived Java names and scatters the API |
| Implicit opaque fallback for any type without an instance | ★★★ | small | ★★ | Rejected: a forgotten instance silently changes the ABI |
| Generate Java classes for tuples, records, and enums in phase 1 | ★★★★ | large | ★★★ | Deferred to phase 2: needs a synthetic-type provider |
| Generated Java source facades compiled by `javac` | ★★★ | medium | ★★ | Deferred: better IDE and Javadoc story, but a Java toolchain inside the Flix build |
| Reflective scripting API (JSR-223) | ★★ | small | ★★ | Out of scope: embedding, not a typed contract |
| **Typed boundary: boundary traits, declared API, wrappers before resolution** | ★★★★★ | medium | ★★★★★ | **Proposed** |

## Migration from the fork's `@Export`

1. Keep existing consumers on an archived export-enabled compiler while phase 1 is built. The
   cleaned v0.77.0 branch does not accept `@Export` and advertises `exportStubs: false`.
2. Recover the *contract* of the archived staged tests, not their code: `TestExportStubs` called
   the removed `ExportStubs` directly. What stays is its assertion -- a Java
   caller compiled against the emitted API alone links and runs against the real classes -- plus
   new tests for the `as` class name and for collisions between it and generated classes.
3. In phase 2, port each archived conversion's *semantics* -- not its ASM -- into boundary instances:
   `Chain` as `Collection`, records in label order, data-carrying enums as sealed interfaces of
   records, `Set` and `Map` refused as parameters. The fork's tests pin how each behaves.
4. Migrate consumers to the new declaration only after the staged caller tests pass for the
   shapes they use. Publish the archive tags before rewriting the remote branch, then keep the old
   compiler and tests reachable while those migrations proceed.
5. Propose phase 1 upstream on its own. `f4dac093c`, the facade-placement fix, is not needed by
   this design -- the `as` name, not the namespace facade, is the Java-facing class -- and is
   worth proposing only if upstream keeps Java-visible namespace classes for another reason.

## Open questions

- **Concrete boundary-type elaboration.** The library instance can write
  `JList[JavaResult.Out[a]]`, but Flix rejects `JavaResult.Out[List[Int32]]`. How does the compiler
  derive `JList[Integer]` and conversion effects from a declared concrete type, including
  user-defined instances, without a second hard-coded conversion table? This is the phase-1 gate.
- **The synthetic-type provider.** Where it runs, how it declares classes to the Java resolver,
  and how it names them without the layout leaks of the current design.
- **The `Opaque[t]` handle.** A generic `FlixValue<T>` wrapper, or the erased Flix value itself
  behind a marker interface.
- **Declaration syntax.** `export mod ... as "..."` is illustrative; it must parse without
  disturbing `mod` and must say the Java name explicitly.
- **Effects as Java interfaces (phase 4).** How a Java handler object maps to Flix's resumption
  semantics, and which effects it may implement at all.
