# Extending Expression-Level Line Coverage

## Current contract

Coverage reports compiled project definitions that survive the first tree-shaking
pass. Probes are inserted before the optimizer, so they describe instrumented
source-reachable expressions rather than post-optimization bytecode.

Current line coverage includes function bodies, `let` bindings, statement
expressions, `if` conditions and bodies, direct calls, and tuples. `if` uses
`BranchTrue` and `BranchFalse`; `match`
and restrictable `choose` rule bodies use `BranchRule`. Guard outcomes are not
separately covered.

Line identity is `(qualifiedName, source, line)`. Multiple expressions on a line
share one line result; branch probes remain distinct by probe ID.

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

## Candidate forms

- Next: `Tag`, `RestrictableTag`, `RecordSelect`, `RecordExtend`, and
  `RecordRestrict`. Probe the enclosing construction or access after recursively
  transforming values.
- Next: `ArrayLit`, `ArrayNew`, `ArrayLoad`, `ArrayStore`, `StructNew`,
  `StructGet`, and `StructPut`. Preserve child order and effects.
- Next: `Ascribe`, `Cast`, `CheckedCast`, `UncheckedCast`, and
  `UncheckedMaskingCast`. Probe the enclosing runtime expression, not type-only data.
- Lambda coverage requires locating the transformed lambda representation at this
  phase, or moving that probe to an earlier phase; `TypedAst.Expr.Lambda` is not
  reached for the source-level lambda fixture.
- Binary and operator coverage likewise requires identifying the representation that
  reaches this phase before adding a probe and claiming source-level coverage.
- Control flow: `TryCatch`, `TryWith`, `Throw`, `Resume`, `Without`, and `Handler`.
  Define line and branch semantics, then add selected/unselected fixtures.
- Logic/query: `Fixpoint*`, `ConstraintSet`, `Constraint`, `Scope`, and `ScopeExit`.
  Confirm runtime lowering and retain real-source filtering.
- Low value: literals, variables, holes, and type-only nodes normally rely on their
  containing executable expression.

Inspect the actual constructor in `TypedAst.scala` before implementing a form; this
guide intentionally does not guess constructor parameters or evaluation semantics.

## Branch policy

A line probe records execution of a source line; a branch probe records a selectable
outcome. Keep them separate:

- `if`: one true and one false probe at the branch-body locations.
- `match` and restrictable `choose`: one rule probe per body.
- Guards, catches, handlers, and query alternatives require a documented policy and
  a selected/unselected runtime fixture before instrumentation.

The JSON reporter preserves every branch probe, including multiple rule probes on one
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
