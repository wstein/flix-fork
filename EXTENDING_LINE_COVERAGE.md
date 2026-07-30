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

## Supported AST Coverage Matrix

The coverage pipeline explicitly categorizes every `TypedAst.Expr` variant. This table is enforced by `TestTypedAstCoverageCompleteness.scala` to prevent documentation-implementation drift.

| Category | Behaviors | TypedAst.Expr Variants | Test Verification |
|---|---|---|---|
| **LineOrBranchInstrumented** | Direct line or branch (`BranchTrue`, `BranchFalse`, `BranchRule`) probe inserted | `Lambda`, `ApplyDef`, `ApplyClo`, `Unary`, `Binary`, `Let`, `IfThenElse`, `Stm`, `Match`, `RestrictableChoose`, `Tuple`, `Array*`, `Struct*`, `Vector*`, `CheckedCast`, `TryCatch`, `Throw`, `Handler`, `Invoke*`, `GetField`, `PutField`, `PutStaticField`, `Spawn`, `Lazy`, `Force` | `TestLineBranchCoverage` |
| **TraversedChildOnly** | Children recursively traversed without inserting direct probes | `HoleWithExp`, `OpenAs`, `Use`, `ApplyLocalDef`, `ApplyOp`, `ApplySig`, `LocalDef`, `Region`, `Discard`, `ExtMatch`, `Tag`, `RestrictableTag`, `ExtTag`, `Record*`, `Ascribe`, `InstanceOf`, `UncheckedCast`, `Unsafe`, `RunWith`, `InvokeSuper*`, `NewObject`, `*Channel`, `ParYield`, `Fixpoint*` | `TestTypedAstCoverageCompleteness` |
| **SyntheticOrTerminal** | Terminal, primitive, or compiler-internal node | `Cst`, `Var`, `Hole`, `GetStaticField`, `FixpointConstraintSet`, `CoverageHit`, `Error` | `TestTypedAstCoverageCompleteness` |

## Safe implementation pattern

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

## Execution & Report Timing Semantics

1. **Snapshot Timing**: Coverage reports are finalized post-execution when probe hit counters are stable. `Coverage.reportSnapshot()` reads metadata and atomic counters within a synchronized block, ensuring coherent JSON and LCOV output without mixing session states.
2. **CLI Failure Policy**:
   - **Compilation Failure** (`Validation.Failure`): No coverage report is created because code failed to compile and no bytecode was executed.
   - **Execution Failure** (`Result.Err` during test or run execution): Coverage reports ARE generated (`coverage.json` and `coverage.info`) with exit code `1`, reflecting all probes registered during compilation and hits recorded prior to runtime failure.

## Known limits

- Coverage remains pre-optimization.
- Expression-level line coverage is intentionally incomplete.
- Bundled libraries, packages, dependencies, test definitions, synthetic locations,
  and tree-shaken definitions are excluded.
- A source line may contain several executable expressions but has one line result.
