# Expression-Level Line Coverage: Maintainer Checklist

## Implemented

`CoverageInstrumentation.instrumentExpression` recursively instruments definition, signature,
and closure applications, lambda construction, tuples, records, structs, vectors, array literals, checked and unchecked casts,
`run … with handler`, exception handling (`TryCatch`, `Throw`), Java interop (`InvokeMethod`, `InvokeStaticMethod`, `InvokeConstructor`, `GetField`, `PutField`, `GetStaticField`, `PutStaticField`), concurrency and lazy expressions (`Spawn`, `Lazy`, `Force`), function bodies, `let` bindings, statement expressions, and `if` conditions and bodies. Coverage reports are generated in both JSON (`coverage.json`) and LCOV (`coverage.info`) formats.

A probe is attached to the reconstructed expression itself. Recursion discovers
nested executable expressions on different source lines. Probing only call arguments
is incorrect because it misses calls with literal arguments.

## Required checks

1. Add an explicit `TypedAst.Expr` case before the catch-all.
2. Recursively transform child expressions without changing their list order.
3. Call `instrumentLine` on the reconstructed parent when its evaluation executes
   the source line.
4. Preserve source locations, types, and effects by using `copy`.
5. Add a runtime test that executes the expression and verifies that its line probe
   is both registered and present in `Coverage.snapshot()`.
6. Run `./mill flix.compile`, the focused `TestLineBranchCoverage` suite, and
   `git diff --check`.

## Do not do this

- Do not claim an unmeasured coverage percentage.
- Do not label rule probes as line probes: `match` and restrictable `choose` use
  `BranchRule` probes for selected rule bodies.
- Do not change evaluation order while transforming expression lists.
- Do not treat post-optimizer bytecode coverage as implemented.

See `EXTENDING_LINE_COVERAGE.md` for the complete extension guide.
