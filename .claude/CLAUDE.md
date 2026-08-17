# Flix Compiler - Developer Guide

## Running the Compiler

The project uses [Mill](https://mill-build.org/) as its build tool.

- `./mill flix.compile` — Compile the compiler itself
- `./mill flix.run <file.flix>` — Run a Flix source file through the compiler (should take at most 3 minutes)
- `./mill flix.assembly` — Build a fat JAR at `out/flix/assembly.dest/out.jar`

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

## Generated Names and Source Identity

Every compiler-minted symbol id is content-addressed: a SHA-256 digest of a key
that describes what the symbol is *for*, rendered as fixed-width lowercase
base-36 (`StableName`). The point is that a generated class name changes only
when the code it names changes, so repeated builds do not fill `build/class`
with renamed copies of the same classes. `--Xstable-name-length` sets the width;
`0` opts out and restores `GenSym` counters.

The width is uniform and enforced: `StableName.validated` rejects any id that is
not exactly `width` lowercase base-36 digits, so a rendering that loses a leading
zero fails the compile instead of reaching a class name where nothing can tell it
from a narrower id or a counter.

Mint through the named functions on `Symbol` — `specializedDefnSym`,
`liftedDefnSym`, `memberDefnSym`, and the enum/struct/anon-class equivalents —
never by calling `StableName.suffix` directly. Each of them routes through one
private helper that honours `--Xstable-name-length`; the six sites that once
bypassed it silently ignored the flag. `Symbol.memberKey` is the single
description of an instance member's key, used both by the two `memberDefnSym`
forms and by every collision claim made about the result.

One consequence is worth knowing before touching `Namer` or `Deriver`: a
content-addressed id is the same for two declarations that say the same thing,
so duplicate instance members, repeated derivations and overlapping instances
mint equal symbols. The compiler's guarantee is bounded accordingly — **in a
program that passes validation, distinct declarations have distinct symbols;
on the error-recovery path they may not, and a consumer reading a
pre-validation AST must key declarations by location.** The reasoning, the
alternatives rejected, and the one unchecked residual are in
`docs/adr/0001-source-identity-vs-generated-name-identity.md`;
`TestSourceIdentity` holds both halves of that sentence.

`CollisionRegistry` guards the other direction: two *different* keys minting one
symbol is a genuine hash collision and fails loudly. It cannot fire on duplicate
declarations, which claim one key for one value, and is not meant to.

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
