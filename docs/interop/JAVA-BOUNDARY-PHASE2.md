# Typed JVM boundary: experimental Phase 2

ADR 3 remains Proposed. Phase 2 adds explicit generated Java types to the Phase 1 contract;
it does not implement phases 3–5, Java interfaces, effect handlers or zero-copy views.

```text
export mod Model as "com.acme.Api" {
    record com.acme.Point(x: int, ys: java.util.List[java.lang.Integer]) = Model.Point;
    tuple com.acme.Pair(left: long, right: double) = (Int64, Float64);
    enum com.acme.Color = Color { case Red; case Blue; };
    sealed com.acme.Tree = Tree { case Leaf(value: int); case Node(left: com.acme.Tree, right: com.acme.Tree); };
    sealed com.acme.IntBox = Box[Int32] { case Box(value: int); };
    def colors: () -> java.util.List[com.acme.Color];
    def tree: () -> com.acme.Tree;
}
```

Use the same `java-api-stubs` / `java-api` commands as Phase 1. Declare every case and
component explicitly. Java record component order follows the contract, not Flix label order.
Each component's checked conversion must match its recorded descriptor and generic signature
in both directions. Alias/expansion duplicates and case-only class collisions are errors.

Records/tuples and concrete generic instantiations use private nominal adapters and cross only
at top-level signature positions. For nested products, use a monomorphic nominal enum wrapper.
Monomorphic enums have direct directional instances: ordinary `List[Color]` / `Option[Shape]`
conversions and recursive `Tree` work without recursive instance constraints. Enum cases use
standard Java enum semantics; payload cases become records inside a sealed interface.
Sealed variant names must differ from their enclosing type's name. Generated class names
cannot also serve as package names within the contract. The checked record gate requires
closed rows, including when the declared labels otherwise match.
Generated nominal conversions in both directions permit up to 128 nested nominal calls per
thread. Deeper values fail with an `IllegalArgumentException` naming the current Java type.
Conversions release their depth budget on success and on exceptions; sibling conversions
reuse the budget, and caller threads have independent budgets.
Generated nominal instances and `boundaryPayload` helpers live in compiler-owned
`BoundaryTypes<hash>.NominalN` modules, so existing type companions and caller helpers can
coexist. Instance lookup is program-wide. Only registered compiler-owned boundary sources
bypass the orphan-instance rule for `JavaResult` and `JavaArgument`; unrelated traits retain
the ordinary rule, and a caller-chosen source URI does not grant the exemption.
Changing source ownership invalidates cached instance validation even when the text is identical.
Generated diagnostics point to the requesting contract member, with paired name and instance
clashes reported once. Conflicting caller declarations appear in the formatted message and in
LSP related locations. Signature checks dependent on a generated overlap are suppressed;
unrelated caller diagnostics retain their own locations. Parent braces and product imports
map to the contract and product declaration respectively.

Generated helpers use IO for ordinary Java construction/access and boundary checks.
This is required by the existing Java effect policy: generated classes have no trusted effect
override, so their constructors and accessors default to IO. Real conversion bodies are
checked normally; only the declaration-only gate bodies inflate effects before being discarded.
Declaring generated accessors pure would require a separate trusted metadata policy.
Default Set/Map argument instances remain unsupported; result instances remain available.

Synthetic metadata is an in-memory classfile overlay, not loaded classes. The same bytes supply
stubs and runtime types, including generic record components and permitted subclasses. Facade
stubs are deliberately not executable; never place them on the runtime classpath.

The declaration, conversion-body and wrapper checks are serialized with the provider scope on
one compiler instance. Incremental library nodes survive those checks. A measured fixture took
865/191/214 ms; an ordinary compile after scope exit was cold at 671 ms. These are observations,
not performance guarantees. Per-provider caches remain a future improvement.

## Validation

From the matching flix-lab checkout, with JDK 21:

```console
FLIX_FORK_ROOT=/path/to/compiler scripts/check-java-boundary-phase2
```

The harness retains artifacts, compiles Java/Kotlin/Scala against API-only stubs, then executes
against real product and nominal output with neither stubs nor the compiler jar at runtime.
Compiler tests cover byte-identical declared stub/runtime classes, generic components, enum
value methods, exhaustive Java switches, recursive trees, nested nominal arguments/results,
two Box instantiations, detached
unmodifiable results, located malformed-contract errors and provider concurrency isolation.
Validation passed 27/27 focused product, nominal, provider and packaged-library tests.
All three staged Java/Kotlin/Scala callers passed against fresh bootstrap/runtime output.
The full compiler suite passed **18,022 tests in 123 suites, zero failures**, with eight ignored
tests. It includes both sequential compiler corpora, the standard library, debugger/LSP,
reachability, naming and package tooling. After that run, the nominal fixture was strengthened
with List/Optional argument round trips and a null-element path assertion; all 27 focused tests
and all three JVM callers passed again. At that point, no compiler implementation had changed after the full run.
The companion-clash follow-up changes compiler implementation after that full-suite run.
Its focused validation passes 33 tests across nominal, product, synthetic-provider and library
suites (including all five original nominal tests and four additional diagnostic regressions),
50 instance tests, and 18 wrapper/contract tests, with no aborted suites. Java, Kotlin and Scala callers are recompiled
against fresh stubs and run against real output only. The full suite must be rerun after the
remaining merge blockers are resolved; the earlier full-suite run does not validate this fix.
The recursion and contract-validation follow-up reproduces four failures before the fixes:
unbounded deep nominal conversion, a sealed variant reusing its enclosing name, a generated
class also naming a package, and an open row accepted by the record gate. Focused checks cover
the 128-level limit, deep arguments and results, recovery after depth and null-child rejection,
and thread isolation. Review follow-ups also restrict the orphan exemption, retain caller
conflict locations, deduplicate hand-written/generated overlaps, and correct line mappings.
The combined follow-up validation passes **109 tests in seven suites, zero failures or aborted
suites**. Java, Kotlin and Scala callers are freshly recompiled against regenerated stubs and
pass against runtime output only. The earlier full-suite evidence still predates these changes.
No merge or push has been performed.
Native-image execution is not covered.
