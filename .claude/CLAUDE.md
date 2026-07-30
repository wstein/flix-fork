# Flix Compiler - Developer Guide

## Running the Compiler

The project uses [Mill](https://mill-build.org/) as its build tool.

- `./mill flix.compile` — Compile the compiler itself
- `./mill flix.run <file.flix>` — Run a Flix source file through the compiler (should take at most 3 minutes)
- `./mill flix.assembly` — Build a fat JAR at `out/flix/assembly.dest/out.jar`

## Generating API Documentation

`./mill flix.run doc <file.flix>` writes API documentation for the standard
library and the given file to `build/doc/`. Two backends share one model
(`Documentor`), which turns the typed AST into a tree of documentable items:

- `HtmlDocumentor` — the default; pages plus `styles.css`, `index.js`, and `favicon.png`
- `MarkdownDocumentor` — one `.md` page per documentable item

Select with `--doc-format html|md|all`, e.g.
`./mill flix.run doc --doc-format md out/empty.flix`.

When changing either backend, note two things:

- `Bootstrap.isValidDocumentFile` decides what `flix clean` is willing to
  delete. A new output file type has to be added there too, or the build
  directory becomes uncleanable.
- Every Markdown page starts with a marker comment. A run deletes marked pages
  it did not produce, so renaming a module no longer leaves an orphan page
  behind; files without the marker are never touched. Changing the marker text
  strands every page written by an earlier version.
- `SvgDocumentor` writes trait-hierarchy and module-structure diagrams under
  `build/doc/diagrams/`. `Documentor.run` creates one diagram manifest and
  passes it to both renderers, so pages link only to files generated in that
  run. Stale marker-tagged SVGs are cleaned recursively; handwritten files are
  preserved.

Neither backend documents structs or restrictable enums: `Documentor` has no
representation for them, and drops them in the wildcard case of `splitModules`.

The LSP request `flix/getDiagram` accepts an `itemName` and returns the SVG for
a documentable trait or module when one exists. Keep request parsing and SVG
payload tests aligned when extending the diagram model.

## Running Tests

**Step 1:** First, verify the standard library compiles by running an empty file:

```bash
touch out/empty.flix && ./mill flix.run out/empty.flix
```

This catches standard library compilation errors early.

**Step 2:** Write a minimal test case (positive or negative) in `out/example.flix` and run it with `./mill flix.run out/example.flix`. This gives a fast feedback loop before running the full test suite.

**Important:** `flix.run` executes `main`, *not* `@Test` functions. A file containing only `@Test` definitions will report success even when its assertions fail, so this step verifies that code compiles and that `main` behaves — nothing more. To actually run assertions, use the test suites below: `.flix` tests live in `main/test/flix/` (run by `flix.CompilerSuite`) and `main/test/ca/uwaterloo/flix/library/` (run by `ca.uwaterloo.flix.StandardLibrarySuite`), and both are discovered automatically.

**Step 3:** Once both pass, run the test suite:

- `./mill flix.test.testForked "-oC"` — Run all tests (preferred; `-oC` suppresses passing test output, should take at most 10 minutes)
- `./mill flix.test` — Run all tests (verbose)
- `./mill flix.test.testOnly <pattern>` — Run specific test suites by fully qualified class name

Examples:

```bash
./mill flix.test.testOnly ca.uwaterloo.flix.language.phase.TestTyper
./mill flix.test.testOnly ca.uwaterloo.flix.language.phase.TestLexer
./mill flix.test.testOnly 'ca.uwaterloo.flix.language.phase.*'
```

**Tip:** If `flix.test` fails due to an error in a Flix test file (e.g. `main/test/flix/Test.Exp.IfThen.flix`), it is faster to iterate with `./mill flix.run main/test/flix/Test.Exp.IfThen.flix` than to rerun the full test suite.

**Note:** Flix test files that reference `dev.flix.test.*` classes (test Java classes) will fail when run via `flix.run` because those classes are only on the classpath during `flix.test`. These failures are expected — use the test suite to run them.

## Coverage Instrumentation

The coverage system adds instrumentation probes to compiled project definitions:

- `--coverage` flag enables coverage instrumentation during compilation
- `Coverage.hit(probeId)` is called at function, executable-line, and `if` branch entry
- Coverage probes are inserted after the first tree-shaking pass, during `CoverageInstrumentation`
- Coverage data is recorded to `build/coverage.json` when instrumented code runs

### Implementation Details

**Phases Involved:**
1. **TreeShaker1 / CoverageInstrumentation** — Retains reachable definitions, then inserts function, body-line, selected `let`-line, and `if` branch probes
2. **Monomorphization/Lowering** — Lowers `CoverageHit` to `ApplyAtomic(AtomicOp.CoverageHit, ...)` typed as `Type.Pure`
3. **Optimizer/Inliner** — **Critical**: Preserves `CoverageHit` via `mustPreserve()` barrier to prevent dead-code elimination

**Source-Level Purity Preservation:**
- `CoverageHit` calls `Coverage.hit()` (real side effect) but is typed `Type.Pure`
- This preserves observable function purity: `def foo(): Int32 = ...` stays pure at source level
- User functions remain pure; coverage is a compiler-internal detail
- **Optimizer barrier prevents elimination:** Dead-code elimination and pure statement filtering check `mustPreserve()` predicate

**Testing Coverage:**
- Regression test: `TestCoverageOptimization` — verifies coverage probes are preserved through optimization and hits are recorded at runtime
- Probes must survive dead-code elimination (checked by `Inliner.mustPreserve()`)
- Execute code to verify `Coverage.snapshot()` contains expected probe hits

**Current Semantics and Limits:**
- Reports exclude bundled libraries, packages, dependencies, and tree-shaken definitions.
- Line probes cover function bodies, `let` bindings, statement expressions, and `if` conditions and bodies; full expression-level line coverage is not implemented.
- Branch probes cover `if` then/else entries and `match`/restrictable `choose` rule bodies. Guard outcomes are not separately instrumented.
- Probes are placed before optimization. An optimizer-folded branch can remain in source coverage metadata; this is not a post-optimization bytecode coverage mode.

### Common Pitfalls

1. **Probes disappear at runtime**: Check that `Inliner.mustPreserve()` is applied; probes are optimized away if barrier is removed
2. **Zero hits recorded**: Verify that compiled code is actually executed; coverage data is only recorded when probes are called
3. **Compilation succeeds but no hits**: May indicate optimizer eliminated probes; run regression tests to verify mustPreserve barrier is working

## Benchmarking Performance

When asked to benchmark the performance impact of a change, run:

```bash
Xperf --frontend --par --n 50
```

If the numbers are not stable, increase the sample count to `--n 100` or `--n 250`.

Drop `--frontend` if the change affects the backend, so the benchmark covers the full pipeline.

## Commit Messages

Commit messages must start with a lowercase prefix followed by a colon and space:

- `feat:` — new feature or capability
- `fix:` — bug fix
- `refactor:` — code restructuring with no behavior change
- `chore:` — maintenance tasks (dependencies, CI, gitignore, etc.)
- `perf:` — performance improvement

Example: `feat: add type argument support for new object expressions`

## Branch Names

Branch names must be prefixed with the same categories as commit messages:

- `feat/` — new feature or capability
- `fix/` — bug fix
- `refactor/` — code restructuring with no behavior change
- `chore/` — maintenance tasks (dependencies, CI, gitignore, etc.)
- `perf/` — performance improvement

Example: `refactor/simplify-type-reduction`

## GitHub Pull Requests

- Omit the testing section from the PR description.
- Omit the "Generated with Claude Code" line from the PR description.

## Writing Flix Code

If you are unsure about Flix syntax, consult: https://doc.flix.dev/for-llms.html

Key syntax reminders (Flix v0.68.0+):

- **Main function:** `def main(): Unit \ IO = ...`
  Access command-line args via `Env.getArgs()`, not as parameters.
- **Effects** use `\` (backslash), not `&`:
  `def divide(x: Int32, y: Int32): Int32 \ DivByZero`
- **Effect operations** are called like regular functions — no `do` keyword:
  `DivByZero.divByZero()`
- **Effect handlers** use `run`/`with handler`:
  ```
  run { ... } with handler EffectName { def operation(...) = ... }
  ```
  Chain multiple handlers — never nest multiple `run` blocks.
- **Java interop:** `import` at file/module top level, use `new ClassName()` and `object.method()`. Prefix pure Java methods with `unsafe`. All Java interop carries the `IO` effect.
- **Annotations** are uppercase: `@Test`, `@Parallel`, `@Lazy`, `@MustUse`.

## Temporary Files

All temporary files (e.g. scratch `.flix` files) should be placed in the `out/` directory.
