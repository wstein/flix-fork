# Java Interoperability Decision Log

Decisions taken while making `@Export`ed Flix reachable from the JVM, each with
the evidence behind it and the alternative that was rejected.

A decision recorded here is not a preference; it is a claim that can be checked,
and several entries below exist because an earlier claim was checked and failed.

**Scope.** These decisions concern values returned *outward* through `@Export`
shims. They do not establish correctness of the broader Flix–Java boundary —
generic Java method results, anonymous-class overrides, superclass calls, or
functional-interface adaptation — which is a separate mechanism with its own
open defects. J17 records what is tracked elsewhere and why none of it is
settled here.

Status values are **Settled** (evidence is decisive and in the repository),
**Partial** (the decision holds but the implementation does not yet cover every
case, with the gap named), **Proposed** (our choice, not yet built, reversible),
and **Deferred** (knowingly unresolved, with the blocker named).

Sources referred to throughout:

- `examples/interoperability/calling-flix-from-java` — the worked example and
  the reference for what the boundary promises. Java is the only consumer the
  test suite compiles.
- flix/flix [#2359](https://github.com/flix/flix/issues/2359) *Add Stable JVM
  API (Facade)*, and [#7789](https://github.com/flix/flix/pull/7789) *feat:
  Explicit Export*, merged, which shipped `@Export` instead.
- **The six-language matrix** — Java, Scala 3, Kotlin, Groovy, Clojure, JRuby,
  each run by hand against a `build-jar` artifact of the example at commit
  `5a0ef9272`, on a two-level module. It is *not* checked into the repository
  and is not reproducible from it; entries that rest on it say so. Where a
  language's behavior is quoted below as a diagnostic, it was re-measured
  directly with that language's compiler against class files differing only in
  the attribute under discussion.

---

## J0 — The Flix representation stays private

**Status: Settled.**

A `Some(x)` is a `Tag$Obj` distinguished by an `int ordinal`; the class is named
after the shape of its fields and the backend renames it freely. Nothing outside
the compiler may name it, so a value that has no exact Java form is *converted*
at the boundary rather than exposed.

Every other decision here follows from this one, and it is why the boundary is a
conversion rather than a cast.

**Rejected:** publishing the tag classes as a stable API. That buys direct
access at the cost of freezing names the backend needs in order to change
representation at all.

---

## J1 — Generated classes are named beside their namespace, not beneath it

**Status: Partial.** Shipped for two-level module trees; see the residual.

`mod Acme.Api` compiles to the class `Acme.Api`, and its defs used to be
generated into a *package* of the same name. The JVM permits a class and a
package to share a name and javac accepts it when compiling against a jar, but:

- Scala rejects the whole classpath: *"package Acme contains object and package
  with same name: Api"*.
- Kotlin resolves the package, never sees the class, and reports every exported
  function as `unresolved reference`.

A namespace with an exported def always had both, so **every export was
unreachable from those two languages**. Defs are now `Acme.Api$Def$get` in the
package `Acme`. Namespaces with no parent package have nothing to sit beside and
stay under `dev.flix.gen`.

This matches what the JVM languages we checked do. Observed, not surveyed
exhaustively: Scala 3.8.4 emits `acme.Api$`, Kotlin 2.4.10 `acme.ApiKt`, Groovy
`acme.Api$_use_closure1`, and Clojure names the classes of namespace `acme.api`
`acme.api$get_it`. None of the four creates a package named after a class. We
make no claim about JVM languages beyond these.

**Rejected:** mirroring the namespace under `dev.flix.gen`
(`dev.flix.gen.Acme.Api.Def$get`). It also removes the clash, and it was
implemented first, but it diverges from every neighbouring language for no gain.

### Residual: the clash returns at module depth three

Moving the *defs* was not enough, because the *facade* nests too.
`BackendObjType.Namespace(ns)` is `JvmName(ns.dropRight(1), ns.last)`, so the
facade of `mod Acme.Api.Deep` is the class `Deep` in package `Acme.Api` — and
`Acme.Api` is itself a facade class. The sibling rule moved the clash down one
level rather than removing it.

Reproduced with this compiler: `mod Acme.Api` with an export, plus `mod
Acme.Api.Deep` with an export, emits

```
Acme/Api.class          <- the facade class Acme.Api
Acme/Api/Deep.class     <- inside the package Acme.Api
Acme/Api$Def$get.class
Acme/Api/Deep$Def$deeper.class
```

and scalac 3 fails against the resulting jar with the same message quoted above.
Both exports are accepted by the front end; this is ordinary user code.

So J1's *rule* is right and its *coverage* is not. Until this is closed, an
exported module tree must be two levels deep.

**Remedies, in preference order.** (a) Give the facade a suffix, as Scala does
for its module classes — `Acme.Api$` beside package `Acme.Api`. The log already
cites `acme.Api$` as the neighbouring convention and did not adopt it; it
changes the Java-visible name of every export, so it is a public API decision.
(b) Apply the sibling rule to facades as well, making `mod A.B.C` the class
`A.B$C` in package `A`; leaves two-level names untouched and only renames depth
≥ 3. (c) Reject the configuration with a diagnostic, which keeps names stable
but refuses programs that work from Java today.

**Second residual:** entry points in `mod Acme` *and* a nested `mod Acme.Api`
clash differently — a *top-level* class `Acme` in the unnamed package meets the
package `Acme`. This one is unreachable for exports, since
`checkNonRootNamespace` rejects `@Export` in a one-segment module, so it needs
a `main` or a `@Test`.
javac diagnoses it as *"class Api clashes with package of same name"* only when
both are compiled from source in one run; against a jar it emits no clash
diagnostic and fails at the use site. Flix should diagnose it.

---

## J2 — `@Export` stays an annotation; there is no `export` keyword

**Status: Settled.**

#2359 originally proposed `export def doStuff(f: Int32): Int32`, and Magnus
wrote *"only non-polymorphic functions explicitly marked as `export` (or
whatever)"*. #7789 shipped the annotation instead.

A keyword is defensible in substance — it changes typing rules, which
annotations conventionally do not, and it could imply `pub`, removing today's
redundant `@Export pub def`. It is rejected for a different reason: it forks the
*syntax*. Everything else in this log is an internal change that could go
upstream unaltered; a keyword would mean Flix written for this fork stops
compiling on upstream.

**Rejected:** restoring the #2359 keyword. Revisit only if this fork becomes a
deliberate permanent divergence.

---

## J3 — Conversion is automatic, not a converter library

**Status: Settled.**

The obvious model is Scala's: `scala.jdk.javaapi.CollectionConverters` gives
Java explicit static methods, because Java cannot use Scala's implicit extension
methods.

It does not transfer. Scala's converters work because a `scala.List` *is* a
nameable, stable, public JVM type that Java can receive and convert later. By J0
Flix has no such type, so an exported function cannot hand Java a raw Flix
`Option` for a converter to act on. Building converters would mean publishing
the representation — reversing J0.

The motivating gap is also absent: Scala needs a `javaapi` variant because
implicits are unusable from Java. Flix's boundary is already explicit.

**Rejected:** a `dev.flix.javaapi.Converters` library. Note that the mechanics
are *not* the obstacle — see J11; the obstacle is that it requires J0 to fall.

---

## J4 — One recursive conversion plan, not a special case per type

**Status: Settled.** Shipped.

Exporting `Option` initially touched four places that had to agree: the type the
shim returns, the signature it declares, the instructions that convert, and the
front-end check that permits it. A fifth conversion would have meant a fifth set
of four.

Three of them now read one recursive `ExportPlan`, which says what Java type it
produces, what it contributes as a type argument, and how to convert. The
descriptor a caller compiles against and the bytecode it calls are derived from
the same description and cannot drift.

Recursive because conversions compose: a converted container describes its
element with a plan of its own.

The fourth — the front-end check — could not be folded in. `EntryPoints` works
on `Type` and the backend on `SimpleType`; carrying a plan between them would
touch six ASTs. It keeps its own recognizer, which duplicates
`ExportPlan.isOption` verbatim, and J16 records what that costs.

**Rejected:** threading the plan down from the front end, for the reason above.

---

## J5 — The plan is keyed on the declared type, not the erased one

**Status: Settled.**

Erasure specializes `Option[String]` into an `Option$…` that no longer says
what it holds, so the declared type reaches codegen as `exportedReturnType`.

This is not merely convenient. By J12 every reference type shares one
specialization, so keying the plan on the erased type would conflate
`Option[String]` with `Option[BigInt]` and emit the wrong signature for one of
them.

---

## J6 — Generic signatures are load-bearing, not cosmetic

**Status: Settled.** Shipped.

A descriptor cannot express `Optional<String>`. Without a `Signature` attribute
the element type is lost, and the three static languages do not merely warn.
Measured against two class files with identical descriptors, differing only in
the presence of the attribute:

- **Java** compiles, but only if the use site names the type. `Optional<String>
  o = find(k)` costs an unchecked-conversion warning; `String s = find(k).get()`
  is an **error** — *"Object cannot be converted to String"*.
- **Scala 3** rejects it: *"Found: java.util.Optional[?]#T, Required: String"*.
- **Kotlin** rejects it: *"expected 'String', actual 'Any'"*. The value is
  inferred as `(Optional<Any!>..Optional<*>?)`.

So the asymmetry is not "Java warns, the others degrade". Java alone can be
coaxed into compiling, and only by weakening the use site; both of the other
static languages reject the artifact.

By J12 the signature is also the *only* thing distinguishing `Optional<String>`
from `Optional<File>` for a caller, because both share one class. That makes it
a correctness property of the API, not an ergonomic one.

**What it does not buy:** nullability. Kotlin reads even a signed return as the
platform type `Optional<String!>!`, because the shim emits no nullness
annotations at all. An earlier draft of this entry claimed the signature
restored null-safety and that its absence switched null-safety off silently;
both were wrong, and the measurement above is why the claim is now stated in
terms of the element type only. Emitting JSpecify or Kotlin-readable annotations
is a separate, unexplored decision.

---

## J7 — `Option` converts in return position only

**Status: Settled** for now; the blocker is structural, not semantic.

The element must itself be exportable, so a nested `Option[Option[t]]` is
rejected.

An earlier draft justified the return-only restriction by saying there is "no
answer for a Java caller passing `Optional.empty()` to a function whose Flix
type is not optional". That is not the situation. Input conversion is relevant
exactly where the parameter *is* an `Option[t]`, and there `Optional.empty() →
None` is the obvious mapping. The real reasons are these.

1. **The declared type does not reach codegen for parameters.** J5 carries it as
   `exportedReturnType`, which is return-only by construction. A parameter's
   type is erased by `Eraser.visitParam`, so the backend sees the specialized
   `Option$…` with the type argument already gone and cannot tell what to
   convert from. Parameters need the same mechanism J5 gives returns, extended.
2. **`ExportPlan` only runs one way.** `emit` describes Flix → Java. The reverse
   is a second tree of instructions, and by J16 the gate may not accept a
   parameter type until that tree exists.
3. **Construction is harder than inspection.** Returning reads a tag with
   `GETFIELD`; accepting one means *building* a Flix value — allocating the
   specialized `None` singleton or a `Some` tag with the right ordinal. The
   generated shim can name those classes, so this is work rather than an
   obstacle, but it is where J0's privacy is easiest to violate by accident.
4. **Null has two questions in reverse, not one.** A Java caller can pass a
   `null` `Optional` as well as `Optional.empty()`, and by J9 the forward
   direction has already made `Some(null)` and `None` the same value. An input
   plan has to choose whether `null` is a `None` or an error, and that choice is
   API policy, not an implementation detail.

**Rejected:** accepting `Optional` parameters ahead of (1) and (2). That is J16
exactly — a gate wider than the plan.

---

## J8 — A converted element that is primitive is boxed

**Status: Settled.** Shipped.

`Optional` holds references, so the `int` a `Some` carries becomes an `Integer`
and the signature says `Optional<Integer>`. Flix's own boxing wraps in
`BackendObjType.Value`, which is not a Java box, so the shim emits
`Integer.valueOf` and its peers directly.

---

## J9 — `Some(null)` and `None` both cross as `Optional.empty()`

**Status: Settled** as a known loss, not as a desirable one.

`AsOptional.emit` calls `Optional.ofNullable`, and for a reference element the
element plan is `Identity` — a no-op. A `Some` holding a Java `null` therefore
arrives as `Optional.empty()`, which is exactly what `None` produces. The two
are indistinguishable to the caller.

The payload can genuinely be null: a `Some` built from any Java method that may
return null carries it unchanged, and Flix's type system does not track it.

**Rejected:** `Optional.of`, which raises a `NullPointerException` at the
boundary — the crash lands in the shim, blaming the export for the caller's
data. **Also rejected:** rejecting nullable element types in the gate, which is
not expressible, because Flix does not distinguish a nullable Java reference
from a non-nullable one. A faithful encoding would need something other than
`Optional`, which is the whole reason `Optional` was chosen.

Nothing pins this. `TestExportedShims` inspects bytecode with an ASM visitor and
never invokes a shim, so a regression test needs a load-and-invoke harness that
does not exist yet.

---

## J10 — Collections convert to lazy views, not eager copies

**Status: Proposed.** Not built.

Time complexity alone suggests eager copying is fine, because the common case —
full traversal — is O(n) either way. Allocation is the argument, and it splits
on the element type.

For a **reference** element, whose element plan is `Identity`:

| Java caller does | Eager copy | Lazy view |
| --- | --- | --- |
| full traversal | O(n) time, **O(n) alloc** | O(n) time, **O(1) alloc** |
| `findFirst()` | O(n) | O(1) |
| export called in a loop | **n garbage refs per iteration** | O(1) |
| peak memory | **2×** — chain and copy coexist | 1× — chain shared |

For a **primitive** element the view is not allocation-free and can be worse.
By J8 the element is boxed, and by J11 that boxing is emitted *inside* the
iterator's `next`, so a view re-boxes on every traversal: equal to the copy on
the first pass, strictly worse on each subsequent one, since a copy boxes once.

**Accepted costs:** a view keeps the Flix chain reachable for as long as the
view is, and re-runs the element conversion on repeated access. Memoizing the
boxes would reintroduce per-view state and defeat the O(1) allocation for the
reference case, which is the case the decision is made for.

**Consequence:** adopt the view for reference elements. For primitive elements,
either copy eagerly or accept the re-boxing knowingly; do not present it as
O(1).

**Rejected:** the claim in an earlier draft that the case "rests on allocation
alone" uniformly. It rests on allocation, and allocation depends on the element.

### The contract the view must promise

A view is API the moment it ships, so these are settled before it is built, not
after.

- **Immutable.** Every mutator — `add`, `remove`, `set`, `sort`, `clear`, and
  the `ListIterator` mutators — throws `UnsupportedOperationException`. A Flix
  list is immutable and there is nothing to write through to.
- **A snapshot, not a live window.** The underlying chain is immutable, so the
  distinction is unobservable in practice; it is stated because Scala's views
  make the opposite promise, and a reader coming from `CollectionConverters`
  will otherwise assume mutation visibility is the point. It is not — see the
  note above that Scala's motivation does not apply here.
- **Safe for concurrent readers, with no shared iterator state.** Because there
  is no mutation there is no `ConcurrentModificationException` and no
  `modCount`; each `iterator()` walks the chain from the head independently.
  Two threads may iterate one view. A single `Iterator` is not thread-safe, as
  everywhere else in the JDK.
- **`size()` is O(n) on first call and cached thereafter.** One `int` per view,
  computed lazily. This is the one place per-view state is worth it: without it
  `AbstractCollection.isEmpty()` — which is `size() == 0`, and which
  `AbstractSequentialList` never overrides — is O(n) on a cons chain.
  `isEmpty()` is overridden separately to test the head only, so it stays O(1)
  even before `size()` has been forced.
- **`equals`/`hashCode` follow `java.util.List`.** `AbstractList` already
  defines both by iteration, which is the contract Java callers expect; the cost
  is O(n) and, for a primitive element, re-boxing.
- **Elements are converted on access, not on construction**, so a view holds the
  Flix chain alive as long as it is reachable. This is the accepted cost above,
  and it is the reason a view should not be stored in a long-lived field.

`size()` caching is a deliberate exception to the no-per-view-state rule: it is
O(1) memory and bounded, unlike memoizing every boxed element.

---

## J11 — The view is generated, not hand-written in `dev/flix/runtime`

**Status: Settled** by analysis.

`dev/flix/runtime` holds real Java sources compiled with the compiler, so a view
written there could not name `Tag$Obj$Obj` or the specialized `Nil` — they are
emitted per program. It would need reflection on every access.

A view generated as a `BackendObjType` names them directly. It must be
parameterized by the element plan, because the element conversion is emitted
*inside* the iterator's `next`.

A Flix list is a cons chain with no indexing, so the view extends
`AbstractSequentialList` over a chain-walking iterator rather than
`AbstractList`, whose `get(i)` would make full iteration quadratic.

**Rejected:** the claim in an earlier draft that Scala's `List` converter makes
the same choice. It makes the opposite one —
`scala.collection.convert.JavaCollectionWrappers$SeqWrapper` extends
`java.util.AbstractList`, and `AsJavaConverters.asJava(Seq)` routes `List`
through it; there is no `List`-specific converter, and no use of
`AbstractSequentialList` anywhere in `scala/collection/convert/`. Scala avoids
the quadratic trap by overriding `iterator()` instead. The argument above stands
on its own and does not need the precedent.

---

## J12 — Data representations specialize after erasure; functions do not

**Status: Settled.**

#2359 stalled on the observation that *"Java uses erasure but Flix uses
monomorphization, and the two approaches are not readily compatible."* The
premise is half true, and the interesting half is which one.

**Functions monomorphize on the source type**, as in Rust and C++.
`Specialization` runs at `Flix.scala:667` and keys each def specialization on
`sym.toString :: tpe.toString :: qualifiedNamesOf(tpe)` (`Symbol.scala:112-114`)
— the type as written. It contains no `erase` in the def path. Measured: `def
idf(x: a, n: Int32): a` called at `String` and at `BigInt` — two *reference*
types — emits two def classes, `Def$idf$gxHvfHhkmvN` and
`Def$idf$X9AoBsJRHtW`. No collapse.

**Data representations specialize after erasure.** `Eraser` runs at
`Flix.scala:696`, eight phases later, and `Eraser.visitType`
(`Eraser.scala:322-329`) computes `targs.map(erase)` *before* choosing the
specialized enum or struct name. Measured on four exported `Option` returns:
three classes.

```
Option[String]  ⎫
Option[BigInt]  ⎬─→ Option$LRvYAvhsMeY
Option[Int32]   ──→ Option$XfxNPeZznzG
Option[Float64] ──→ Option$NFgb6xberHg
```

Further reference types add no classes — `String`, `BigInt` and `BigDecimal`
each produce `Option$LRvYAvhsMeY` — because the name hashes the *erased*
arguments.

**Measure this on the export path.** On the ordinary path an earlier phase has
already boxed a polymorphic payload, so every instantiation shares one class for
an uninteresting reason: a probe program using `Option` at all five types emits
a single `Option$LRvYAvhsMeY` and a single `Tag$Obj`. `Eraser.visitDef` leaves
an exported def's return type un-erased (`Eraser.scala:57-60`) precisely so the
shim can present the declared type, which is what J5 consumes, so the export
path is where the distinction is still observable. An earlier draft of this
entry recorded the measurement without that condition, and it does not reproduce
without it.

The distinction is what makes the export boundary tractable, because **only a
data representation crosses it**. A shim hands Java a value, not a function, so
what matters is that `Option[String]` and `Option[BigInt]` are already one class
holding an `Object` — which is exactly what Java's `<T>` erases to. For
reference elements there is no representational mismatch to solve. The gap is
the eight primitive cases, and Java's own answer there is boxing (J8), which the
same measurement confirms end to end: the four shims above declare
`Optional<String>`, `Optional<BigInteger>`, `Optional<Integer>` and
`Optional<Double>`.

The bound is per type parameter: `Eraser` caches on the whole erased argument
list, and there are nine erased types, so a type of arity *k* has at most 9^*k*
representations — nine for `Option[t]`, eighty-one for `Map[k, v]`.

**Rejected:** the summary "Flix erases first and specializes second". It is
false of the compiler as a whole — there is a pass named `Specialization` that
does the opposite, and it runs first — and an earlier draft of this log and
its companion paper both asserted it. The corrected claim is narrower and still
carries the boundary.

---

## J13 — Polymorphic exports need a specialization root, not a codegen change

**Status: Proposed.** Not built. Larger than it first looked.

Given J12, `pub def identity[a](x: a): a` could be exported by declaring `<T> T
identity(T)` over the erased-reference instantiation, with the unchecked cast
Java performs for its own generics. Four things stand in the way, none of them
representation.

1. **The instantiation must be requested, and in `Specialization`, not
   `Eraser`.** An earlier draft said `Eraser`. It cannot: `Eraser.run` is a 1:1
   `parMapValues` over `root.defs` and creates declarations only for enums and
   structs, and by the time it runs `Specialization` has already dropped every
   parametric function. The root belongs in `Specialization.run`, which today
   seeds only `root.defs.filter(_.spec.tparams.isEmpty)`. An unconstrained
   `Kind.Star` variable already defaults to `AnyType`, which erases to `Object`
   — that default is the evidence the instantiation exists.
2. **It is not tree-shaking.** An earlier draft said the def "would be
   tree-shaken". `TreeShaker1` seeds from `root.entryPoints`, which contains
   exports, and runs *before* `Specialization`. The def is never removed; it is
   never created.
3. **It is partly a codegen change.** `shimSignature` builds
   `s"($params)${plan.typeArgument}"` and has no way to emit the formal
   parameter section `<T:Ljava/lang/Object;>` that `<T> T identity(T)` needs.
   The specialization must also retain the original `DefnSym`: the shim
   publishes `defn.sym.name`, `checkValidJavaName` demands `[a-z][a-zA-Z0-9]*`,
   and `specializedDefnSym` folds in a `$` and a base58 hash.
4. **The gate must be relaxed in the same change.** `checkNoTypeVariables`
   (E1069) rejects polymorphic exports outright. By J16 that relaxation ships
   with the plan, not before it — and by J14 it must admit unconstrained
   variables only.

This is the part #2359 never reached.

---

## J14 — Trait-constrained polymorphism has no `Object` instantiation

**Status: Deferred.**

J13 covers an *unconstrained* type variable. A constrained one — `def show[a:
ToString](x: a)` — has no erased-reference instantiation to route to.
`mkInstanceMap` keys instances on `(TraitSym, TypeConstructor)` and
`Type.typeConstructor` is `None` for a variable, so there is nothing to resolve
against; no instance is declared for `java.lang.Object`; and Flix compiles
traits away by specialization rather than by passing dictionaries, so there is
no runtime witness to fall back on.

**Blocker named:** instance resolution is a specialization-time decision. Making
it a runtime one means introducing dictionary passing, which is a language-wide
change and not an interop one.

---

## J15 — Type arguments of Java generics do not reach the backend today

**Status: Deferred.**

`SimpleType` does carry type arguments — `Enum(sym, targs)`, `Struct(sym,
targs)`, `Arrow(targs, result)` — and `ExportPlan` recovers the `String` of
`Option[String]` from exactly those, which is the shipped mechanism of J4 and
J5. But a *Java* class arrives as `SimpleType.Native(clazz: Class[?])`, which
has no argument list, so there is nowhere for `String` to live.

The argument is not destroyed; it is *dropped on the way down*. The front end
knows `ArrayList[String]` — that is what `EntryPoints` type-checks — and there
is no reason in principle it could not be preserved. What is missing is a
carrier. J5 solved the same problem for returns by threading one extra field
through the ASTs; this needs the same, but on a type constructor rather than a
def, so every phase that builds a `Native` has to build the arguments too.

**Blocker named:** an AST-wide change, not a codegen one — which is why a
hand-written `ArrayList[String]` still exports raw while a converted
`Option[String]` does not. The wording of this entry was previously
"irrecoverable", which overstated it: the information is unavailable in the
backend's current type representation, not lost from the compiler.

---

## J16 — The front-end gate may not outrun the backend plan

**Status: Settled.**

`EntryPoints` decides what may be exported; `ExportPlan` decides how. They are
written against different type representations (J4), so they can disagree, and
once they did: a gate extended to accept `List[t]` and nested `Option` was
written before the plans existed. It would have accepted `List[String]`, for
which the shim falls through and emits a method returning the internal
`Tagged$` — publishing the representation J0 exists to protect.

It was reverted rather than committed. The invariant is that anything the gate
accepts, the solver can plan; the gate is extended in the same change as the
plan, never ahead of it.

**Rejected:** landing the gate first as a stepping stone. A gate that permits
what the backend cannot build is worse than no gate, because the failure is
silent and produces a wrong API rather than an error.

The invariant is now a test rather than a convention: `TestExportedShims`
compiles one export per return type the gate admits and asserts that no shim
descriptor or signature names a `dev.flix.gen` class. Re-widening the gate to
`List[t]` makes it fail with `f17 -> ()Ldev/flix/gen/Tagged$;`, which is the
leak stated above, so the test is known to detect the change that motivated it
rather than merely assumed to.

---

## J17 — The inbound boundary is out of scope and is not sound today

**Status: Deferred**, and deferred to other people — these are upstream issues,
not decisions this fork has taken.

Everything above concerns values leaving Flix through an `@Export` shim. Flix
*calling* Java is a different mechanism, and it has open defects that no
decision here addresses or repairs. They are recorded because the natural
misreading of J4 and J12 is that "Flix–Java interop" has been put on a sound
footing, and it has not.

- **flix/flix#12970** — a generic Java method returning a Flix tuple crashes
  with a `VerifyError`, because the erased `Object` result is used without a
  cast. Reproduced here on this compiler, from the seven-line program in the
  issue. It is the exact mirror of what J4 fixes outward.
- **flix/flix#12972** — argues the same rule generalizes: a bytecode reference
  to a Java member must be emitted at the reflective member's type, with the
  value bridged on either side. Cited as an **open risk, not a measurement**:
  it is source-read analysis and its repro shapes have not been executed.
- **flix/flix#8618** and **#5172** — unifying a Flix arrow with a Java
  functional interface is sound in one direction only. This is why the claim
  "an export hands Java a value, never a function" is a property of `@Export`
  (a function type is not exportable) rather than of the boundary at large.
- **flix/flix#8592** — interop logic is duplicated across the compiler.
  `ExportPlan` is a local answer for one direction of one boundary, not the
  compiler-wide abstraction that issue asks for. J4 should be read as evidence
  that the approach generalizes, not as the generalization.

**Rejected:** presenting `ExportPlan` as *the* interop abstraction. It converts
outbound return values and nothing else; the shared conversion helpers #12972
and #8592 call for would subsume it, and that is the right direction of travel.
