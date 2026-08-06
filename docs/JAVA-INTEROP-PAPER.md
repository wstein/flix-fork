# Exporting a Monomorphizing Functional Language to an Erased JVM

*A design report on Flix's `@Export` boundary.*

## Abstract

Languages that monomorphize generics are held to interoperate badly with
platforms that erase them. Rust and C++ concede the point: neither exposes a
generic across its ABI, only instantiations. Flix monomorphizes and targets the
JVM, and the corresponding issue in its tracker stalled for years on precisely
this reasoning.

We report that the premise holds for one half of the language and not the other,
and that the export boundary lives entirely in the half where it fails. Flix
monomorphizes *functions* on the source type, exactly as Rust and C++ do; but it
generates the classes that *represent* polymorphic data after erasure, so
`Option[String]` and `Option[BigInt]` share a single class. We measure four
instantiations of `Option` collapsing to three classes, against two reference
instantiations of a polymorphic function that do not collapse at all.
Because an exported function hands Java a value rather than a function, only the
data side crosses, and there the representations already coincide: what remains
is eight primitive cases, for which the platform's own answer, boxing, suffices.

We describe the boundary this permits: a recursive *conversion plan* from which
the Java descriptor, the generic signature, and the emitted bytecode are all
derived, so that the API a caller compiles against cannot drift from the code it
calls. We report two defects found by testing across six JVM languages that a
Java-only test suite cannot detect, one of which made every export unreachable
from Scala and Kotlin — and whose first repair fixed the depth we happened to
test and moved the defect one level down.

**Scope.** This report concerns values returned *outward* through `@Export`
shims. It does not establish correctness of the broader Flix–Java boundary —
generic Java method results, anonymous-class overrides, superclass calls, or
functional-interface adaptation — which is a separate mechanism with its own
open defects, tracked separately and summarized in §7.2. Read every claim below
as scoped to the export direction unless it says otherwise.

## 1. Introduction

Flix is a functional language with algebraic data types, a type-and-effect
system, and first-class Datalog, compiled to JVM bytecode. Its values are
represented in a manner private to the compiler: a `Some(x)` is an instance of a
class named after the *shape* of its fields, `Tag$Obj`, discriminated by an
integer `ordinal`.

That privacy is deliberate and it is the premise of everything below. A
representation the compiler may change is a representation no external caller
may name. The question a JVM export mechanism must answer is therefore not "how
does Java reach a Flix value" but "what does a Flix value become when it
leaves".

Two forces shape the answer. The first is that the JVM erases generics, while
Flix monomorphizes them — the subject of §4, and the reason the feature was
believed hard. The second is that "the JVM" is not one language: a jar is
consumed by Java, Scala, Kotlin, Groovy, Clojure and others, whose tolerance for
an imprecise artifact differs sharply. §5 reports what that difference costs.

## 2. Background

### 2.1 The export surface

Flix exposes a function to the JVM with an `@Export` annotation. The compiler
emits a *shim*: an ordinary `public static` method on a class named after the
enclosing module, whose body constructs the internal closure object, forces it,
and returns the result.

```flix
mod Acme.Api {
    @Export
    pub def greet(name: String): String = "Hello, ${name}!"
}
```

```java
public final class Acme.Api {
  public static final java.lang.String greet(java.lang.String);
}
```

Exportability is restricted to types with an exact JVM representation, or to
types this work gives one. Anything whose encoding remains an implementation
detail — records and closures, and tuples and enums (including those with
data-carrying cases) until this work — is rejected rather than exposed.

### 2.2 What erasure costs a signature

A JVM method descriptor cannot express type arguments. `Optional<String>` and
`Optional<File>` share the descriptor `Ljava/util/Optional;`; the argument
survives only in the optional `Signature` attribute (JVMS §4.7.9), which the
virtual machine ignores for linkage and verification but which compilers and
core reflection read. A producer that omits it emits an API that is *correct*
but *untyped*, and §5 shows that the three static languages do not merely differ
in how loudly they complain — two of them refuse it outright.

## 3. Conversion as the boundary

Because the representation is private, an exported value that has no exact Java
form must be converted where it crosses. Our first implementation, for `Option`,
placed that knowledge in four separate places: the type the shim returns, the
signature it declares, the instructions it emits, and the front-end predicate
deciding what may be exported. Each had to agree with the other three, and
nothing enforced it. A second convertible type would have required a second set
of four.

We replaced three of them with a single recursive description:

```scala
sealed trait ExportPlan {
  def javaType: BackendType   // what the descriptor says
  def typeArgument: String    // what the Signature says
  def emit(...): Unit         // what the bytecode does
}
```

with cases for identity, boxing, and container conversion. Those three faces of
the boundary are now projections of one value, so the type a caller compiles
against, the type argument it sees, and the code it invokes are derived from the
same source and cannot disagree *with each other*. The guarantee is exactly that
wide: it covers the three backend projections and says nothing about the fourth
consumer.

Recursion matters because conversions compose. `AsOptional` holds an
`element: ExportPlan` and calls `element.emit`, so a nested conversion is a new
case rather than a redesign.

The fourth consumer, the front-end predicate, could not be folded in: it runs on
the typed AST, where the plan's type representation does not yet exist, and
carrying a plan between them would touch six intermediate ASTs. It therefore
keeps its own copy of the recognizer. §3.1 is the cost of that residual, and we
state it as a limitation rather than a completed unification.

### 3.1 A structural hazard

Because the predicate deciding *what may cross* and the plan deciding *how* are
written against different intermediate representations, they can disagree — and
during this work they did: a predicate extended to admit lists was written
before the corresponding plans existed. It would have admitted a type for which
the shim falls through to returning the internal tag class, publishing exactly
what the design exists to hide.

The failure mode is instructive because it is *silent*. It does not crash; it
emits a working method with a wrong type. We record the invariant explicitly:
the predicate is extended in the same change as the plan, never before it.

An invariant a reviewer has to remember is weaker than one a build enforces, so
the invariant is also a test: every return type the predicate admits is compiled
and its shim inspected, asserting that no exported method mentions an internal
representation class. It fails on precisely the change that motivated it.

## 4. Erasure versus monomorphization

The tracked issue proposing a stable JVM API stalled on the observation that
*"Java uses erasure but Flix uses monomorphization, and the two approaches are
not readily compatible."* Taken at face value this bounds the feature to
non-generic signatures forever.

The compiler does both things, in different places, and the distinction is the
result of this section.

**Functions monomorphize on the source type.** The `Specialization` pass keys
each specialization on the type as written, not on its erasure. Compiling
`def idf(x: a, n: Int32): a` at two *reference* types emits two classes:

```
idf[String]  ──→ Def$idf$gxHvfHhkmvN     two reference types,
idf[BigInt]  ──→ Def$idf$X9AoBsJRHtW     two instantiations
```

This is Rust's and C++'s behavior, and for functions the folklore is correct.

**Data representations specialize after erasure.** Eight phases later the
`Eraser` computes the erasure of each type argument *before* selecting the class
that will represent a polymorphic enum or struct. Exporting `Option` at four
element types emits three classes:

```
Option[String]  ⎫
Option[BigInt]  ⎬─→ Option$LRvYAvhsMeY      three classes,
Option[Int32]   ──→ Option$XfxNPeZznzG      four instantiations
Option[Float64] ──→ Option$NFgb6xberHg
```

Adding further reference types does not add classes: the name is a hash of the
*erased* arguments, and we obtained the same `Option$LRvYAvhsMeY` from `String`,
`BigInt` and `BigDecimal` independently.

There are nine erased types, so a polymorphic data type of arity *k* has at most
9^*k* representations — nine for `Option[t]`, eighty-one for `Map[k, v]` —
irrespective of how many types a program mentions.

The measurement has to be taken on the export path, and that is not an artifact:
on the ordinary path an earlier phase has already boxed a polymorphic payload,
so every instantiation looks alike for an uninteresting reason. An exported
def's return type is deliberately left un-erased so its shim can present the
declared type, which is also what §3's plan consumes. The boundary is precisely
the place where the distinction is still observable.

The consequence is the central result of this report, and it turns on *which
side of the language crosses the boundary*. An `@Export` shim hands Java a
**value**, never a function — a Flix function type is not an exportable return
type, so this is a property of the mechanism rather than an observation about
programs. (Functions do cross the *inbound* boundary, where a Java functional
interface is adapted to a Flix arrow; that path is a different mechanism and
§7.2 records that it is not sound today.) Only the data representation crosses
outward, and there — **for reference types there is no mismatch to solve.** Flix
holds one class with an `Object` payload; that is precisely what a Java `<T>`
erases to. The two models already agree. What remains is eight primitive cases,
for which Java's own answer — boxing at the boundary — applies unchanged.

Had the collapse been on the function side instead, it would have bought
nothing: an exported `identity` is reached through a shim that is generated per
export, not per instantiation.

This also inverts a design detail. Because reference types share a class, the
`Signature` attribute is not decoration: it is the only thing distinguishing
`Optional<String>` from `Optional<File>` for a consumer. A conversion plan must
therefore be keyed on the *declared* type; keying it on the erased type would
conflate the two and emit a signature that is wrong for one of them.

### 4.1 Polymorphic exports

The result above predicts that a polymorphic function can be exported, and it
can: `pub def id(x: t): t` is `public static Object id(Object)`. The obstacle
was never representation but *existence* — a compiler that emits only the
instantiations a program uses never creates a function Flix never calls — so
`@Export` has to act as a specialization root. Because functions specialize on
the source type, that root belongs in the monomorphizer rather than the erasure
pass, an ordering we initially had backwards.

What the implementation cost was two lines, and the reason is §4 again. The
monomorphizer already defaults an unconstrained variable to a type that the
backend represents as `Object`, so seeding an exported parametric def with the
*empty* substitution specializes it at precisely the erased-reference
instantiation. Seeding it there also keeps its symbol, which is required rather
than convenient: a shim is emitted only for a def the compiler still recognizes
as an entry point, and a specialization requested at a call site is renamed with
a hash.

We had also predicted that a signature encoder would have to learn to emit a
formal type-parameter section, for `<T> T id(T)`. Building it changed our mind
about wanting that. By parametricity such a function can only shuffle, drop or
duplicate its argument, so `<T>` buys inference at the call site while implying
a guarantee the shim does not enforce — the cast is unchecked either way. The
honest signature is the one the representation supports.

The construction stops exactly at trait constraints, and stops hard. A
constrained variable has no `Object` instantiation because instance resolution
here is a specialization-time decision keyed on a concrete type constructor: no
instance exists for the defaulted variable, none can be declared for it, and the
language admits no blanket instances. Nothing is passed at run time because
there is nothing to pass — the backend emits no artifact per instance at all.
Left ungated this does not fail, it crashes the compiler on a map lookup, which
makes the front-end rejection a correctness requirement rather than a policy.
Users write a monomorphic wrapper, which needs no compiler support.

## 5. Six languages, two defects

A JVM artifact is not consumed by one language, and the others are less
forgiving than Java. We exercised each export from Java, Scala 3, Kotlin,
Groovy, Clojure and JRuby against a real build artifact. Two defects appeared
that a Java-only suite cannot detect.

**A class and a package of the same name.** A module `Acme.Api` compiles to a
class of that name, and its definitions were generated into a *package* of that
name. The JVM permits both; javac accepts both.

- Scala rejects the classpath outright: *"package Acme contains object and
  package with same name: Api"*.
- Kotlin resolves the package, never observes the class, and reports every
  exported function as an unresolved reference.

Since a module with an exported definition necessarily has both, **every export
was unreachable from both languages** — a total interoperability failure that
the Java tests could not see. The remedy follows what neighbouring languages
already do: emit generated classes as siblings (`Acme.Api$Def$get`), never as a
package named after a class. Scala emits `acme.Api$`, Kotlin `acme.ApiKt`,
Clojure `acme.api$get_it`; none creates such a package.

Our first attempt at that remedy was itself instructive. Putting a module's
classes in its *parent* package fixes `Acme.Api` and moves the clash to
`Acme.Api.Deep`, whose facade is then `Deep` in a package named after the facade
class `Acme.Api` — the same defect one level down, and invisible to a test suite
whose fixtures were all two levels deep. The rule has to be stated on the whole
namespace rather than on its parent: only the first segment becomes a package,
and everything below it joins the class name, so `mod A.B.C` is the class
`A.B$C` in package `A`. That leaves two-level names — the ones callers already
write — untouched, and it makes the property hold at every depth rather than at
the depth that happened to be tested.

**A missing signature.** Omitting the `Signature` attribute does not degrade
gracefully anywhere except Java, and there only conditionally:

| Consumer | Effect |
| --- | --- |
| Java | compiles if the use site names the type, at an unchecked-conversion warning; a direct use is an error |
| Scala 3 | error: `Optional[?]` is not `String` |
| Kotlin | error: expected `String`, actual `Any` |
| Groovy, Clojure, JRuby | unaffected; resolution is dynamic |

We initially recorded this as "Java warns, Scala errors, Kotlin silently loses
null-safety", and measurement contradicted all three of the interesting parts.
Kotlin does not silently accept a raw return; it rejects it. And the signature
does not restore null-safety even when present — Kotlin reads a signed return as
the platform type `Optional<String!>!`, because the shim emits no nullness
annotations at all. What the attribute restores is the element type, nothing
more.

The pattern that survives is that **dynamic consumers tolerate an imprecise
artifact and static ones do not**, in both defects and in the same direction. A
producer tested only against Java systematically under-constrains its output —
and, as the correction above shows, a producer *reasoning* about the other
languages rather than running them will get the details wrong in their favour.

## 6. Related work

**Scala's converters.** `scala.jdk.javaapi.CollectionConverters` offers Java
explicit conversion methods, existing because Java cannot use Scala's implicit
extension methods. The model does not transfer: it works because a `scala.List`
is a nameable, stable, public JVM type that Java may hold and convert later. A
language whose representation is private has nothing to hand over, so conversion
must occur at the boundary rather than after it. Scala's views are also
motivated by *mutation visibility*, which does not arise for immutable values;
the argument for laziness reduces to allocation.

**Rust and C++.** Neither exposes a generic across a stable ABI. In Rust a
generic function has no single symbol until instantiated, and `extern "C"`
requires concrete types; the reference manual's type-layout guarantees apply to
`repr(C)` types, not to generic ones. In C++ a template is not a symbol either,
and the usual advice is to instantiate explicitly at the boundary. Flix behaves
the same way for functions. What differs is that its polymorphic *data* is
represented after erasure, and that is the side an export boundary needs — the
observation of §4. We do not claim these languages could not do otherwise, only
that they do not.

**Reified generics.** Platforms that retain type arguments at runtime — the CLR
being the usual comparison — avoid the question. On the JVM this is
unavailable. The `Signature` attribute is the substitute, and it is worth being
precise about its status: JVMS §4.7.9 makes it optional metadata, ignored for
linkage and verification, so two methods differing only in `Signature` link and
run identically. It is nonetheless consulted by every compiler that reads the
class and by core reflection (`getGenericReturnType` and its peers). "Advisory
to the VM, authoritative to compilers" is a fair summary only if *authoritative*
is read as "the only source available", not as "enforced": nothing checks that a
`Signature` is consistent with its descriptor, which is precisely why deriving
both from one plan (§3) matters.

## 7. Limitations

### 7.1 Of the export boundary

One residual of the naming repair remains, at the top rather than the bottom of
a module tree: a one-segment module has no parent to sit beside, so its facade
stays in the unnamed package, and `mod Acme` with a `main` alongside a `mod
Acme.Api` still puts a class `Acme` next to a package `Acme`. Exports cannot
reach it — a one-segment module may not export — so it needs a `main` or a
`@Test`, and the right fix is a diagnostic rather than another rename.

The type arguments of a Java generic reach the backend in both positions. They
are carried the same way an enum's are, and an exported `ArrayList[String]`
declares them whether it is returned or accepted. We had recorded parameters as
a residual on the reasoning that the declared type is threaded only for the
return; that was true of the mechanism J5 added and irrelevant, because the
ordinary type-visiting path already carried the arguments into the parameters.
The residual was a claim about the code that had not been checked against it.

One thing about that change is worth stating as a limitation rather than an
achievement: the arguments deliberately do not participate in type equality — a
Java class is one class however it was applied, and the compiler reaches the
same one down paths that erase the arguments differently — which makes this the
one place a backend type ignores a field it carries.

Carrying the arguments also exposed an older hole, and the way it hid is the
point. The predicate deciding what may be exported recursed into the *head* of a
type application and never looked at the argument, so a Flix container of Flix
values was rejected while a *Java* container of the same values was accepted.
The descriptor of the accepted one mentions nothing of Flix — it is
`java.util.ArrayList` — and the type-level checks §3.1 describes all pass. Only
the values crossing were wrong: generated class names, the thing the boundary
exists to hide. Erasure is what made a leak look like a well-typed API, which is
a variant of §5's lesson pointed inward rather than outward: a check that reads
only types cannot see a leak that lives only in values.

Conversion is one-way, and not for a semantic reason. Mapping
`Optional.empty()` to `None` in parameter position is unproblematic; what is
missing is machinery. The declared type is carried to the backend only for
returns, so a parameter arrives with its type argument already erased; the plan
describes conversion in one direction only; and the reverse direction has to
decide what a `null` `Optional` means, which is API policy rather than
implementation. Accepting `Optional` parameters before that exists would be the
§3.1 hazard again.

The `Option` conversion is not injective. The shim uses `Optional.ofNullable`,
so a `Some` whose payload is a Java `null` is delivered as `Optional.empty()` —
indistinguishable from `None`. `Optional.of` would instead raise a
`NullPointerException` inside the shim, blaming the export for the caller's
data; rejecting nullable elements is not expressible, since Flix does not
distinguish a nullable Java reference from a non-nullable one. For a report
whose §3.1 makes silent non-crashing failure a named result, this is the one
place the boundary itself has one.

The measurements are of one compiler on one program shape. Appendix B gives the
commands and expected output for every number quoted here; what it does not give
is CI coverage for the non-Java consumers, which remains hand-run. The 9^*k*
bound follows from reading the erasure pass, not from exhaustive measurement.

### 7.2 Of everything this report does not cover

The `@Export` boundary is one direction of one mechanism. The *inbound*
boundary — Flix calling Java, overriding Java, adapting Java functional
interfaces — is separate code with separate defects, and nothing in §3 or §4
constrains it. Its open issues bound what this report may be read to claim.

- **flix/flix#12970, *Crash on polymorphic interop*** (open). A generic Java
  method returning a Flix tuple produces a `VerifyError` at run time: the
  erased `Object` result is used without a cast. We reproduced it on the
  compiler measured throughout this report, from the seven-line program in the
  issue. This is the exact mirror of the problem §3 solves outward — a value
  crossing at the wrong type — and it demonstrates that solving one direction
  says nothing about the other.
- **flix/flix#12972, *anonymous class parameters and super call arguments are
  not bridged***. Argues the same rule generalizes: a bytecode reference to a
  Java member must be emitted at the reflective member's type, with the value
  bridged on either side. We first cited this as an open risk rather than a
  measurement, because upstream states it as source-read analysis with untested
  repro shapes. Executing them found the parameter half worse than described —
  *every* primitive instantiation of a generic Java interface with parameters
  failed verification — and we have since fixed it, in the same place and by
  the same means as the return direction was already fixed. The `super`-argument
  half is fixed too: `InvokeSuperConstructor` and `InvokeSuperMethod` were the
  only two invoke forms doing no bridging at all.
- **flix/flix#8618, *Soundness issue with Java functional interfaces*** (open),
  and **#5172**. Unifying a Flix arrow with a Java functional interface is
  sound in one direction only and can crash in the other. This is why §4's "a
  shim hands Java a value, never a function" is stated as a property of
  `@Export` rather than of the language boundary.
- **flix/flix#8592, *Collect interop functions*** (open). Records that interop
  logic is duplicated across the compiler. `ExportPlan` is a *local* answer to
  that for one direction of one boundary; it is not the compiler-wide interop
  abstraction #8592 asks for, and §3's "described once" claim should not be
  read as if it were. If anything, the value of §3 is as evidence that the
  shared-description approach is worth generalizing — the shared conversion
  helpers #12972 also argues for.

None of these are regressions from this work; all predate it or concern code it
does not touch. They are listed because a reader who takes §3's unification or
§4's representation result as a statement about "Flix–Java interop" would be
overreading both.

## 8. Conclusion

The belief that monomorphization and erasure are incompatible is, for this
compiler, true of functions and false of data — and an export boundary passes
values, not functions. Specializing data representations after erasure collapses
the reference case to exactly the representation the platform expects, leaving a
bounded and conventional remainder. That a language's own compiler can hold both
disciplines at once, in two passes eight phases apart, is the part we did not
expect and the part most likely to generalize.

The engineering lessons are narrower and probably more portable. A language
boundary should be described once and projected, not restated in each of the
places that consume it — and where one consumer cannot be folded in, that
residual is where the bug will be. A JVM producer that tests only against Java
will ship artifacts its stricter neighbours reject, in ways its own test suite
is structurally unable to observe. And a producer that reasons about those
neighbours instead of running them will describe the failures wrongly, which is
how three of the claims in the first draft of this report came to be false.

## Appendix A: status

| Result | State |
| --- | --- |
| Sibling naming of generated classes | Implemented at every depth; one residual for top-level modules (§7.1) |
| `Option` → `Optional` conversion | Implemented; not injective (§7) |
| Generic `Signature` on exported methods | Implemented |
| Conversion plan | Implemented for three of four consumers (§3) |
| Gate-does-not-outrun-plan invariant (§3.1) | Implemented as a test, in CI |
| Erasure-directed specialization of data (§4) | Measured, Appendix B.2 |
| Source-type monomorphization of functions (§4) | Measured, Appendix B.1 |
| Class/package clash (§5) | Names pinned in CI; consumer compilation measured, Appendix B.3 |
| Missing-`Signature` effects (§5) | Measured, Appendix B.4 |
| `Some(null)` collapse (§7) | Measured and pinned in CI, Appendix B.5 |
| Six-language matrix (§5) | Java in CI; Scala/Kotlin re-measured per B.3–B.4 but not yet in CI; Groovy, Clojure, JRuby hand-run only |
| `List`, `Set`, `Map` → `java.util.List`/`Set`/`Map` (§7) | Implemented as lazy views; `Set` and `Map` in ascending key order. The eager `List` copy is deleted |
| Tuple → `dev.flix.runtime.TupleN` | Implemented as a generated generic record, one class per arity; copied rather than viewed |
| Data-free enum → a real `java.lang.Enum` | Implemented; named beside its namespace, constants verbatim |
| Data-carrying enum → a sealed interface, one record per case | Implemented for non-generic cases whose elements are each directly exportable; nested containers and type parameters still rejected |
| `Vector` → an unmodifiable `java.util.List` view | Implemented, without copying; `Array` stays unexportable (mutable, region-scoped) |
| `Chain` → an unmodifiable `java.util.Collection` view | Implemented, without copying; new tree walk tolerant of any shape the type permits, not only ones the library's own combinators produce |
| Polymorphic exports (§4.1) | Implemented for unconstrained variables; constrained ones rejected |
| Java generic type arguments (§7) | Implemented for returns and parameters |
| Inbound Flix → Java boundary (§7.2) | Out of scope; unsound today, tracked upstream |

## Appendix B: reproducing the measurements

Environment for every measurement below: this repository at `cc73fa102`,
`openjdk 21.0.12` (`javac 21.0.12`), Scala 3.8.4, Kotlin 2.4.10, macOS/aarch64.
Build the compiler once with `./mill flix.assembly`; `$FLIX` below is
`java -jar out/flix/assembly.dest/out.jar`. Each fixture is a fresh `$FLIX init`
project whose `src/Main.flix` is replaced. Class-name hashes are stable for a
given compiler build but are not a compatibility guarantee; match on the
*grouping*, not the literal hash.

**B.1 Functions monomorphize on the source type (§4).** With
`def idf(x: a, n: Int32): a = if (n > 0) idf(x, n - 1) else x` applied at
`"s"` and `12345678901234567890ii`, `$FLIX build` then
`find build/class -name 'Def$idf*'` yields two classes —
`Def$idf$gxHvfHhkmvN` and `Def$idf$X9AoBsJRHtW` — for two *reference*
instantiations. Adding a `Float64` call yields a third.

**B.2 Data representations specialize after erasure (§4).** Export four defs
returning `Option[String]`, `Option[BigInt]`, `Option[Int32]` and
`Option[Float64]` from one module. After `$FLIX build`,
`find build/class -name 'Option$*'` shows three specializations:
`Option$LRvYAvhsMeY` (both reference cases), `Option$XfxNPeZznzG` (`Int32`) and
`Option$NFgb6xberHg` (`Float64`). Substituting `BigDecimal` for either reference
type does not change the grouping. `$FLIX build-jar` then `javap -cp
artifact/*.jar <Module>` shows the boxing of §4 end to end:
`Optional<String>`, `Optional<BigInteger>`, `Optional<Integer>`,
`Optional<Double>`. Measure this on the export path — see the note in §4.

**B.3 The class/package clash (§5).** Build a module tree with exports at
`Acme.Api`, `Acme.Api.Deep`, `Acme.Api.Deep.Deeper` and `Acme.Quiet.Sub`, whose
ancestor `Acme.Quiet` exports nothing. `find build/class/Acme` must list only
files — `Api.class`, `Api$Deep.class`, `Api$Deep$Deeper.class`,
`Quiet$Sub.class` and their `$Def$` siblings — and no directory, since a
directory under `Acme` is a package below a facade class. `javap` on the
`build-jar` artifact confirms the Java-visible names, and `javac`, `scalac` and
`kotlinc` all compile a consumer calling all four.

To see the defect this replaced, restore `packageOfNamespace` to
`ns.dropRight(1)`: the tree then emits `Acme/Api.class` beside a directory
`Acme/Api/`, and `scalac` fails with *"package Acme contains object and package
with same name: Api / one of them needs to be removed from classpath"* while
`kotlinc` reports an unresolved reference and `javac` still succeeds. The
two-level subset compiles under all three either way, which is why a suite whose
fixtures were two levels deep could not see it.

**B.4 What a missing `Signature` costs (§5).** Compile two Java classes with
identical descriptors — `Optional<String> find(String)` and a raw
`Optional find(String)` — confirm with `javap -v` that they differ only in the
`Signature` attribute, and compile the same consumer against each. Against the
raw class: `javac` warns `[unchecked] unchecked conversion` when the use site
names the type and errors when it does not; `scalac` errors *"Found:
java.util.Optional[?]#T, Required: String"*; `kotlinc` errors *"expected
'String', actual 'Any'"*. Against the signed class all three compile. Kotlin
reports the signed return as `Optional<String!>!` — still a platform type,
which is the null-safety point of §5.

**B.5 `Some(null)` and `None` are indistinguishable (§7).** Export
`pub def someNull(): Option[String] = Some(unchecked_cast(null as String))`
beside `pub def none(): Option[String] = None`, then call both from Java and
compare. Both are `Optional.empty` and `equals` returns `true`.

**In CI.** B.1, B.2, B.5, the naming property of B.3 and the §3.1 invariant are
covered by the Scala test suite — `TestJvmName` and `TestNamespaceClasses` for
the names, `TestExportedShims` for the API surface, and
`TestExportedShimsRuntime`, which loads a compiled facade in an isolated
classloader and *calls* it. That last one is what makes B.5 checkable at all:
`Some(null)` and `None` produce identical descriptors and identical signatures,
so no amount of bytecode inspection can tell the two outcomes apart.

Still outside CI is the part of B.3 and B.4 that needs a non-Java compiler. The
unit suite pins the emitted *names*, and names are what the Scala and Kotlin
failures turn on, so the gap is narrower than it was — but it is the same gap in
kind, and §5's whole argument is that a Java-only suite cannot observe these
defects. Pinned Scala and Kotlin toolchains, each compiling a consumer against a
real artifact, are the remaining work; Groovy, Clojure and JRuby may stay
optional with a reported skip, since the static consumers are the ones that
reject.
