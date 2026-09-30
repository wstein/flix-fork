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
value methods, exhaustive Java switches, recursive trees, two Box instantiations, detached
unmodifiable results, located malformed-contract errors and provider concurrency isolation.
Validation passed 27/27 focused product, nominal, provider and packaged-library tests.
All three staged Java/Kotlin/Scala callers passed against fresh bootstrap/runtime output.
The broad compiler suite is running; Phase 2 is not yet declared fully validated.
Native-image execution is not covered.
