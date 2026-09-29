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
Consumers should handle the returned errors, not use `unsafeGet` as in this short illustration.

## Implemented contract

- The chosen class name is used verbatim. Invalid Java names and reserved `java.*` and
  `dev.flix.*` packages are rejected.
- Members have distinct Java names and refer to public, monomorphic, unconstrained definitions.
- Top-level primitives retain their descriptors; Java reference types and `String` pass through.
  Nullary Flix `Unit` parameters become no Java arguments; a `Unit` result becomes Java `void`.
- Generic arguments must already be boxed. Nested native type arguments are recorded in the
  method's JVM `Signature` before simplification. Unsupported types never fall back to `Object`.
- This slice supports Pure and IO only. Other effects are rejected, including effects with
  default handlers: handler synthesis is not yet integrated into this entry point.
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

## Remaining phase-1 gates

Source declaration syntax and automatic wrapper orchestration; export-source diagnostics and
instance-dependent incremental invalidation; pre-type-check cyclic-build stubs; Kotlin and Scala
callers; boundary library packaging and Java argument conversion; default handlers and the full
allowed-effect rule; explicit opaque handles and region restrictions. ADR 3 remains Proposed.
