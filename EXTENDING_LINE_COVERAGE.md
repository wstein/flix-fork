# Extending Expression-Level Line Coverage

## Current contract

Coverage reports compiled project definitions that survive the first tree-shaking
pass. Probes are inserted before the optimizer, so they describe instrumented
source-reachable expressions rather than post-optimization bytecode.

Current line coverage includes function bodies, `let` bindings, statement
expressions, `if` conditions and bodies, definition/signature/closure calls, lambda
construction, tuples, record operations, array and vector literals, struct operations, unchecked and checked casts, `RunWith`, Java interop calls (`InvokeMethod`, `InvokeStaticMethod`, `InvokeConstructor`, `GetField`, `PutField`, `GetStaticField`, `PutStaticField`), effect handler constructs (`TryCatch`, `Throw`, `Handler`), and concurrency/lazy expressions (`Spawn`, `Lazy`, `Force`).
Operator syntax reaches this phase as `ApplySig`. `if` uses
`BranchTrue` and `BranchFalse`; `match`
and restrictable `choose` rule bodies use `BranchRule`. Pattern match guard expressions record `BranchTrue` and `BranchFalse` outcomes.

Line identity is `(qualifiedName, source, line)`. Multiple expressions on a line
share one line result; branch probes remain distinct by probe ID. Coverage reports can be emitted in standard JSON format (`coverage.json`) and LCOV tracefile format (`coverage.info`).

## Safe implementation pattern

Add a case to `CoverageInstrumentation.instrumentExpression` only after identifying
the expression's execution point:

```scala
case e @ TypedAst.Expr.SomeExpression(/* children */) =>
  val (instrumentedChildren, nextProbeId) = /* recursively transform children */
  instrumentLine(e.copy(/* instrumented children */), qualifiedName, nextProbeId, registeredLineProbes)
```

Use `instrumentExpressions` for ordered expression lists. It allocates IDs in source
order and reconstructs the original list order. Instrument the parent expression,
then recurse so nested executable expressions on other lines receive probes.

## Implemented and Candidate forms

- Implemented: `Tag`, `RestrictableTag`, `StructNew`, `StructGet`, `StructPut`, `VectorLit`, `VectorLoad`, `VectorLength`.
- Implemented: `ArrayNew`, `ArrayLoad`, `ArrayStore`, `ArrayLength`.
- Implemented: `Ascribe`, `CheckedCast`, `UncheckedCast`.
- Implemented: Java Interop (`InvokeConstructor`, `InvokeMethod`, `InvokeStaticMethod`, `GetField`, `PutField`, `GetStaticField`, `PutStaticField`).
- Implemented: Control flow (`TryCatch`, `Throw`, `Handler`, `RunWith`).
- Implemented: Concurrency and Lazy (`Spawn`, `Lazy`, `Force`).
- Next: Logic/query (`Fixpoint*`, `ConstraintSet`, `Constraint`, `Scope`, `ScopeExit`).
  Confirm runtime lowering and retain real-source filtering.
- Low value: literals, variables, holes, and type-only nodes normally rely on their
  containing executable expression.

Inspect the actual constructor in `TypedAst.scala` before implementing a form; this
guide intentionally does not guess constructor parameters or evaluation semantics.

## Branch policy

A line probe records execution of a source line; a branch probe records a selectable
outcome. Keep them separate:

- `if`: one true and one false probe at the branch-body locations.
- `match` and restrictable `choose`: one rule probe per body; `match` guards record `BranchTrue` and `BranchFalse` probes.
- Catches, handlers, and query alternatives require a documented policy and
  a selected/unselected runtime fixture before instrumentation.

Both JSON and LCOV reporters preserve every branch probe, including multiple rule probes on one
line, as ordered records containing `id`, `kind`, `covered`, and `function`.

## Test recipe

1. Create a virtual Flix program with the target expression on a distinct line.
2. Compile with `Options.DefaultTest.copy(coverage = true)` and execute `main`.
3. Assert matching `ProbeKind.Line` metadata exists and at least one ID is hit.
4. For branches, assert all metadata exists and only selected IDs are hit.
5. Run `./mill flix.compile`, focused coverage tests, and `git diff --check`.

The nested-call regression in `TestLineBranchCoverage` is the reference pattern.

## Known limits

- Coverage remains pre-optimization.
- Expression-level line coverage is intentionally incomplete.
- Bundled libraries, packages, dependencies, test definitions, synthetic locations,
  and tree-shaken definitions are excluded.
- A source line may contain several executable expressions but has one line result.
