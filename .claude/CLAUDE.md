# Flix Compiler - Developer Guide

## Running the Compiler

The project uses [Mill](https://mill-build.org/) as its build tool.

- `./mill flix.compile` — Compile the compiler itself
- `./mill flix.run <file.flix>` — Run a Flix source file through the compiler (should take at most 3 minutes)
- `./mill flix.assembly` — Build a fat JAR at `out/flix/assembly.dest/out.jar`

## Dependency Downloads

Installing a dependency fetches two files from a GitHub release, and **the
address of each is computed rather than looked up**. Asking the REST API for a
release listing in order to rediscover a URL cost one request per file, and
anonymous REST traffic is capped at 60 requests an hour per address — which is
why bootstrapping a project with a handful of dependencies used to fail, and why
`./mill flix.testPackageManager` used to report a double-digit number of
failures whose count changed between runs.

What is computable and what is not is the thing to understand before changing
`FlixPackageManager.install`:

- The **manifest** is always `flix.toml`. `Bootstrap.release` uploads the
  project's manifest unchanged, so this holds for every package ever published.
  Resolving a dependency graph — the recursive phase, one manifest per
  transitive dependency — therefore reads no API at all.
- The **package** is `<repo>.fpkg` *for releases made by a current compiler*.
  It did not used to be: `release` uploaded whatever `getPkgFile` named the file
  after, which is the directory it was built in. So `flix-test-pkg-eff-upgrade`
  published `test-pkg-eff-upgrade.fpkg`, and neither the repository name nor the
  manifest's `name` predicts it in general — `flix-test-pkg-trust-transitive-plain`
  declares `name = "test-pkg-trust-transitive-java"` and publishes
  `test-pkg-trust-transitive-plain.fpkg`. `AssetSource.NamedOrLookedUp` therefore
  tries the computed address first and reads a listing only on a 404, so a
  current package costs no request and a legacy one costs one.

Do not "simplify" that fallback away, and do not replace it with a second guess:
a guessed name that is wrong produces a 404 that cannot be told apart from a
release that does not exist.

`getReleases` is the only function that spends REST quota. Two paths reach it:
`outdated`, which genuinely needs metadata, and the legacy half of the package
lookup above. `GitHub.download` handles the rest, and keeps the failures apart —
a refusal (usually a rate limit, with `Retry-After` when given), any other
status including a redirect that could not be followed, and never reaching a
server. The shared `HttpClient` follows redirects because a release download
address redirects to storage; Java's default is to follow nothing.

## Running Tests

**Step 1:** First, verify the standard library compiles by running an empty file:

```bash
touch out/empty.flix && ./mill flix.run out/empty.flix
```

This catches standard library compilation errors early.

**Step 2:** Write a minimal test case (positive or negative) in `out/example.flix` and run it with `./mill flix.run out/example.flix`. This gives a fast feedback loop before running the full test suite.

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
