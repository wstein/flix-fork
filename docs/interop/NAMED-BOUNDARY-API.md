# Named Java boundary API (experimental)

The next ADR 3 slice adds a programmatic declaration and an opt-in compiler entry point:
`Flix.codeGenWithJavaApi(checkedRoot, declaration)`. It exposes already checked, concrete
boundary wrappers through an explicitly named class. It does not restore `@Export`.

## Declaration and compilation

```scala
implicit val compiler: Flix = flix
val (checked, errors) = flix.check()
require(errors.isEmpty)
val root = checked.get
val wrapper = root.defs.values.find(_.sym.toString == "Acme.intsJava").get.sym
val api = JavaBoundaryApi.Declaration("com.acme.Api",
  List(JavaBoundaryApi.Member("ints", wrapper)))
val plan = JavaBoundaryApi.prepare(api, root).unsafeGet
val apiOnlyStub = JavaBoundaryApi.stub(plan)
val program = flix.codeGenWithJavaApi(root, api).unsafeGet
```

The wrapper must already have a concrete Java signature, for example
`java.util.List[java.lang.Integer]`, and an ordinary, checked conversion body. The preceding
concrete elaboration proof shows how to derive that signature and recheck a generated wrapper.
Automatically orchestrating those passes for a source API declaration remains separate work.
The subsequent [automatic wrapper API](AUTOMATIC-BOUNDARY-WRAPPERS.md) now orchestrates them
for programmatic declarations selecting original Flix definitions. Source syntax is still future work.
Consumers should handle the returned errors, not use `unsafeGet` as in this short illustration.

## Implemented contract

- The chosen class name is used verbatim. Invalid Java names and reserved `java.*` and
  `dev.flix.*` packages are rejected.
- Members have distinct Java names and refer to public, monomorphic, unconstrained definitions.
- Top-level primitives retain their descriptors; Java reference types and `String` pass through.
  Nullary Flix `Unit` parameters become no Java arguments; a `Unit` result becomes Java `void`.
- Generic arguments must already be boxed. Nested native type arguments are recorded in the
  method's JVM `Signature` before simplification. Unsupported types never fall back to `Object`.
- This low-level entry point accepts ground, finite primitive effects only. The higher-level
  source wrapper path installs default handlers before forwarding.
- The API's wrappers are added to tree-shaking roots. Forwarding bytecode only initializes their
  normal argument fields and uses the existing thunk/result-unwinding machinery. It does not
  implement collection conversion, inspect Flix data layouts, or perform reflective dispatch.
- Exact and case-folded collisions with generated class names fail before returning an artifact.
- Original unchecked exceptions propagate to the Java caller.

## API-only staging

`JavaBoundaryApi.stub(plan)` emits only the chosen API class with the exact same descriptors and
generic signatures as the real facade. Stub bodies throw `UnsupportedOperationException` and
refer to no Flix runtime classes. The staged test compiles Java against that class alone, then
runs the compiled caller in a fresh JVM with only real generated classes and caller classes.

These stubs require a previously checked contract. They are **not** the pre-type-check bootstrap
needed when Flix depends on Java classes that do not yet exist. They prove linking and the ABI
contract, not a solution to the cyclic joint-compilation problem.

## Validation

All 23 tests in the two replacement suites pass (18 concrete elaboration tests and 5 facade
tests). The lab harness's shell syntax and missing-configuration guards also pass. No full compiler
suite is run for this slice.

```console
./mill --no-server flix.test.testOnly ca.uwaterloo.flix.language.phase.interop.TestJavaBoundaryApi
```

From `flix-lab`, run both replacement proof suites:

```console
FLIX_FORK_ROOT=/path/to/compiler ./scripts/check-java-boundary-facade
```

The ABI corpus pins `List<Integer>`, nested lists, generic Java parameters, `int`/`long`/`double`
slot handling, `String`, and `void`. The staged caller checks converted values, reference identity,
primitive results, nullary calls, and original exception propagation. Negative tests cover names,
duplicates, polymorphism, unconverted Flix types, unboxed generic arguments, unsupported effects,
and exact/case-only class collisions.

## Phase 1 integration

The [Phase 1 guide](JAVA-BOUNDARY-PHASE1.md) covers the source contract, packaged library,
syntax-only bootstrap stubs, effect/opaque/region policy, and staged Java/Kotlin/Scala callers.
`JavaBoundaryApi.stub(plan)` remains the checked-plan API; the new `java-api-stubs` command is
the syntax-only path that breaks cyclic builds. ADR 3 remains Proposed.
