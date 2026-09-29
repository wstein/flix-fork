# 3. JVM interop as a typed boundary, not a bytecode conversion layer

## Status

Proposed. Scoped to how Flix code is *called from* the JVM -- Java, Kotlin, Scala -- and how
values cross that boundary. Calling Java *from* Flix (`import`, `new`, method calls) is unchanged.
Numbered 3 to follow ADRs 1 and 2 on `feat/stable-specialization-names-rewrite`.

Revision 8 records the [experimental Phase 1 integration](../interop/JAVA-BOUNDARY-PHASE1.md):
packaged deep boundary instances, source contracts and CLI/LSP diagnostics, syntax-only
bootstrap stubs with a pre-codegen ABI check, entry-point effect policy and default handlers,
type-tagged opaque handles, transitive region rejection, and staged Java/Kotlin/Scala callers.
The explicit contract and final generic-ready opaque handle incorporate the agreed design
refinements. The rollout and upstream discussion draft are local; publication and submitting
the proposal still require a separate go-ahead. The overall ADR remains Proposed.

Revision 7 records [automatic wrapper orchestration](../interop/AUTOMATIC-BOUNDARY-WRAPPERS.md):
programmatic declarations select original definitions; checked instances derive Java argument
and result types and conversion effects; generated ordinary definitions are rechecked before
facade emission. Repeated calls remove their owned virtual source and rebuild from current
instance information. Missing directional evidence and unsupported outputs fail at the supplied
member location. Source syntax, packaged traits, cyclic-build stubs, handlers, opaque handles,
and Kotlin/Scala callers still remain; the status stays Proposed.

Revision 6 records the [named boundary API slice](../interop/NAMED-BOUNDARY-API.md): an opt-in
programmatic declaration exposes checked concrete wrappers through a chosen Java class, records
generic signatures, retains wrapper entry points, and passes a staged Java caller against the
replacement facade. API-only stubs use a checked contract; they do not solve cyclic pre-type-check
bootstrapping. Source declarations, automatic wrapper orchestration, handled effects, opaque
handles, and Kotlin/Scala callers remain phase-1 work. The status stays Proposed.

Revision 5 records the [concrete boundary-type proof](../interop/CONCRETE-BOUNDARY-TYPES.md):
checked instances derive `List<Integer>` and conversion effects internally without relaxing
source associated-type rules. Concrete wrapper signatures require a validated typed root, so
section 3 replaces early concrete desugaring with staged elaboration and ordinary wrapper
checking. This does not implement the new declaration, facade, signatures, or joint-compilation
stubs; the status remains Proposed.

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

Model the boundary as typed Flix code synthesized and checked through a staged frontend, with a
narrow phase 1 that needs no new type-level machinery, and one small piece of codegen: a forwarding
facade for an explicitly declared API.

### 1. Boundary traits, distinct from `ToJava`/`ToFlix`

Add `JavaResult[t]` and `JavaArgument[t]` in a new `Java.Boundary` module rather than changing
`ToJava`/`ToFlix`. They differ from those in the two ways the boundary needs:

- **Deep.** A container instance converts its elements through the element's own boundary
  instance.
- **Positional boxing.** The facade passes a primitive, `String`, or a Java type unchanged at the
  top level of a signature; the boundary traits are consulted only for other types, and a
  container asks for its element *boxed* through `JavaResult.Out[a]`.

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

Leaf instances reuse the existing boxing/unboxing operations without changing `ToJava`/`ToFlix`. The
`flix-lab` prototype established that `JList[JavaResult.Out[a]]` and its recursive conversion
work within an instance constrained by `JavaResult[a]`. It did **not** establish that a concrete
application such as `JavaResult.Out[List[Int32]]` works: Flix rejects it because an associated
type may only be applied to a type variable. The prototype used an explicit `JList[Integer]`
export signature. `BoundaryTypeElaborator` now proves concrete type and effect selection from
the checked trait and equality environments, including recursive containers and user instances.
Its internal projections are not new source syntax. Missing evidence, unresolved projections,
cycles, unbound variables, and invalid equality constraints are rejected. A two-pass test renders
the selected native type into a wrapper, rechecks it with the ordinary frontend, and executes
the conversion. The named facade and source-contract suites now also validate emitted generic
classfile signatures and the pre-codegen recorded-ABI gate.
A resolver-only spike had admitted unresolved and self-referential applications; it was reverted.
Simply removing the resolver restriction is not a safe phase-1 implementation.

### 2. An explicit, named API declaration

```text
export mod Acme.Api as "com.acme.Api" {
    def price: () -> int;
    def quote: (java.lang.String) -> java.util.List[java.lang.Integer];
}
```

The Java class name is chosen, not derived from facade layout, and the members are listed in one
place. Every listed def must be monomorphic, declare its full signature, and have no region
parameter: a region-bound value (`Array[t, r]`, a mutable struct) never crosses.

Phase 1 uses this declaration in a separate `.flix-api` sidecar. Its signatures explicitly
record the Java bootstrap ABI; they do not choose conversions. An optional `= target` before
the colon aliases a Flix function. Ordinary `.flix` grammar is unchanged. Java generic
arguments use `[...]`, fully qualified reference names, and boxed primitives. This is an
experimental source format, not a promise of final upstream declaration syntax.

### 3. Concrete elaboration precedes ordinary wrapper checking

For each listed def `f(x1: a1, ...): r \ e`, the compiler must first elaborate the declared
signature to concrete boundary types `JArg[a1]`, ..., `JResult[r]` and conversion effects.
This cannot happen in `Desugar`: instance selection and associated-type definitions require
the typed environments and successful `Instances` validation. Early processing can reserve
API names and retain declarations, but cannot yet choose concrete wrapper signatures.

The proof uses two frontend passes: check the original program, elaborate internal projections
from the validated root, then synthesize an ordinary def and recheck the augmented program:

```flix
def f$java(x1: JArg[a1], ...): JResult[r] \ e + (conversion effects) =
    JavaResult.toJava(f(JavaArgument.toFlix(x1), ...))
```

`JavaBoundaryWrappers.compile` orchestrates these passes for a programmatic declaration
and explicitly selected result/argument traits. `compileContract` selects the packaged
boundary traits from a parsed source contract. The caller selects original definitions, not
hand-written Java-shaped wrappers. It sums checked conversion effects, rebuilds wrappers after
instance edits, reports member-specific failures at the supplied declaration location, and
removes its generated virtual source on both success and failure. The CLI's `java-api` command
exposes the source-contract path; frontend-only `checkContract` serves editor diagnostics.

Here `JArg` and `JResult` denote compiler-elaborated types, **not** Flix associated-type
applications in generated source. In particular `JResult[List[Int32]]` is `JList[Integer]`.
Parameters and results that are primitives, `String`, or Java types are left unwrapped. Because it
is submitted to the ordinary frontend in the second pass:

- it is resolved, kinded, and type-checked like every other def, including its actual conversion
  calls; missing boundary evidence is rejected by elaboration before generation;
- member-specific synthesis/checking errors map to the member's line in the source contract,
  rather than leaving the primary diagnostic on an invisible generated definition;
- it is registered as an entry point, so `TreeShaker1`/`TreeShaker2` retain it;
- incremental compilation keys it on the declaration, `f`'s declared signature, and the selected
  instance definitions, whose changes can alter the Java ABI or effects.

Production integration must preserve this ordering. A two-pass frontend is the implemented
experimental mechanism; a dedicated entry point for checking generated typed definitions could
avoid replay, but must be demonstrated separately. The syntax-only `java-api-stubs` command
uses the explicit contract, not inferred instances. After instance validation, the compiler
derives the actual ABI independently and rejects any class/name/descriptor/generic-signature
difference before code generation. This one gate checks both cyclic-build stubs and recorded ABI.

### 4. The backend emits a forwarding facade, with a recorded signature

After monomorphization the wrapper's types are exact JVM types. The backend emits `com.acme.Api`
with one `public static` method per member forwarding to `f$java`. The wrapper's typed signature
-- the only place Java generics still exist -- is recorded on it before simplification and
written as the facade method's `Signature` attribute. This is the entire new codegen.

The experimental `Flix.codeGenWithJavaApi` entry point now implements this forwarding contract
for already checked concrete wrappers, selected by a programmatic `JavaBoundaryApi.Declaration`.
It preserves generic signatures and primitive descriptors, retains the selected definitions, and
rejects exact/case-folded generated-class collisions. Low-level forwarding accepts only ground,
finite primitive effects; the source wrapper path combines conversion/target effects and installs
default handlers before forwarding. This follows the effect policy of entry points.

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
`Java.Boundary.Opaque[t]`. In a top-level boundary position the compiler synthesizes tagged
source `pack`/`unpack` calls. Java holds `dev.flix.runtime.OpaqueHandle<Object>` and can return
it, but the supported handle API cannot inspect the payload. The final handle carries a semantic
`JvmTypeKey` and a display type; unwrapping checks the key before casting and fails with an
`IllegalArgumentException` naming expected and actual types. `toString` shows the type only,
equals/hashCode are identity-based, and handles are not Serializable or persistent across builds.
The unused generic parameter reserves later generated marker types. This is an explicit compiler
capability, not a blanket trait instance: containers of opaque values require user instances.
The compiler-internal bridge is API encapsulation, not a security boundary.

A type with only one direction's instance may appear only in
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
| 1 | `Java.Boundary` traits; `export mod ... as`; staged elaboration and checked wrappers; forwarding facade with `Signature`; `Opaque[t]`; ABI gate; a new stub generator for joint compilation | the v0.77.0 compiler and its non-export fork features | nothing yet |
| 2 | synthetic-type provider, so instances can target generated tuple, record, and enum classes; recover the archived conversion semantics as trait instances | stubs | nothing from the old export backend: it is already gone |
| 3 | `export instance com.acme.Service = mod Acme.Impl`: Flix implements a Java interface Java compiles first | stubs for Flix-owned APIs only | stubs for Java-first projects |
| 4 | effects as Java handler interfaces; collection views (O(1) at the boundary) | -- | copies where they are too expensive |

**A pre-type-check stub path is required in phase 1.** A Flix module that calls a Java class
Java has not compiled yet cannot be type-checked, so a typed `--emit-java-api` cannot break the
joint-compilation cycle. The old `ExportStubs` is gone; the new generator must read
`export mod ... as` declarations before resolution, using the declared Java name. Syntax alone
does not provide validated user-instance reductions. The agreed Phase 1 choice is an explicit
concrete API contract: syntax-only stubs use its signatures, and checked instance reductions
must match class names, descriptors, and generic signatures before runnable code is generated.
Phase 3 removes the cycle itself for projects whose contract is a Java interface.

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
  the replacement adds a trait module, staged elaboration and wrapper checking, a stub path, and a
  forwarding facade.
- **Extensible.** A user converts their own types by writing instances.
- **Errors are type errors**, located at the export's member line.
- **Names are stable by construction.** The `as` name fixes the Java class; the facade layout is
  free to change, and the companion-module merge and `IllegalExportEnumMember` go away.
- **`ToJava`/`ToFlix` are untouched**, and their callers with them.
- **Phase 1 converts less than the archived fork did.** Tuples, records, and enums need phase 2's
  synthetic types or a user's own Java class. Existing export consumers stay on an archived build
  until their required shapes are implemented.
- **Upstreamable in pieces.** Phase 1 is one declaration form, staged wrapper checking, a library module,
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
| **Typed boundary: boundary traits, declared API, staged checked wrappers** | ★★★★★ | medium | ★★★★★ | **Proposed** |

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

- **Production frontend performance.** The experimental two-pass frontend preserves checked
  instances and ABI invalidation; how should production integration avoid redundant work?
  Bootstrap stubs now come from the explicit contract and are checked before real code generation.
- **The synthetic-type provider.** Where it runs, how it declares classes to the Java resolver,
  and how it names them without the layout leaks of the current design.
- **Typed opaque markers.** Phase 1 fixes the runtime handle name and type-tag checks. Phase 2
  may supply generated marker types to its reserved generic parameter.
- **Production declaration syntax.** Phase 1's `.flix-api` sidecar does not disturb ordinary
  `mod` parsing. Upstream may prefer another explicit-name source form.
- **Effects as Java interfaces (phase 4).** How a Java handler object maps to Flix's resumption
  semantics, and which effects it may implement at all.
