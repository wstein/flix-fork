# Flix Compiler - Developer Guide

## Running the Compiler

The project uses [Mill](https://mill-build.org/) as its build tool.

- `./mill flix.compile` — Compile the compiler itself
- `./mill flix.run <file.flix>` — Run a Flix source file through the compiler (should take at most 3 minutes)
- `./mill flix.assembly` — Build a fat JAR at `out/flix/assembly.dest/out.jar`

## Project Scaffolding

`flix init` (`Bootstrap.init`) writes a new project into an existing directory:
`src/`, `test/`, `.github/workflows/build-and-test.yaml`, `flix.toml`,
`.gitignore`, `.editorconfig`, `AGENTS.md`, `CLAUDE.md`, `LICENSE.md`, and
`README.md`. Every file is written through `FileOps.newFileIfAbsent`, so running
`init` in a directory that already has one of them leaves that file untouched —
new template files must keep that property, since users edit what `init` gives
them.

`flix init --refresh` (`Bootstrap.refreshAgentGuide`) is the one thing that
overwrites, and it overwrites exactly one file. `AGENTS.md` names the compiler
version that generated it, so it goes stale the moment a project upgrades;
`--refresh` rewrites it for the running compiler. It rewrites only a guide that
still opens with the `<!-- flix-init:` marker — deleting that line hands the
file to the project, the same contract `MarkdownDocumentor` uses for pages it
did not write. `CLAUDE.md` is never rewritten: it is a two-line `@AGENTS.md`
import (Claude Code reads `CLAUDE.md`, not `AGENTS.md`) and anything a project
adds below the import is its own. A `CLAUDE.md` without that import loads
nothing and reports nothing, so `--refresh` says so.

Four rules bind what may go in the generated guide, and a change that breaks one
is a bug even though nothing fails to compile:

1. **Under ~50 lines.** It is loaded in full into every agent session, and the
   user's own content goes on top of it.
2. **Nothing that can rot in place.** No version numbers in prose, no standard
   library API names, no syntax that has ever changed. `flix.toml` is the pin
   and `doc.flix.dev` is the source; the guide links rather than copies.
3. **Only what the shipped binary does.** `flix format` stays out of the command
   list while it makes no layout decision — a guide that names a command which
   silently does nothing is worse than one that omits it. See *Source Code
   Formatting* for what the command currently is.
4. **Never fetch-and-execute.** The guide may tell an agent what to read. It may
   not tell it to run what it downloads.

The generated `.editorconfig` is a compatibility floor, not a style guide: it
carries only settings an editor can apply without parsing Flix (charset, line
endings, indent unit, final newline, whitespace trimming). Anything that needs
the syntax tree — spacing, wrapping, alignment — belongs to `flix format`.
Adding such a rule here would create a second, weaker authority that the
formatter then has to fight. Two settings are load-bearing and easy to get
wrong:

- `indent_size` on `*.flix`, not just `tab_width`. Per the EditorConfig
  specification `tab_width` defaults to `indent_size`, not the reverse, so
  setting only `tab_width` leaves the width of a space indent to each editor.
- `max_line_length = off`. Some editors read a width as an instruction to
  hard-wrap, which reformats code without understanding it.

## Source Code Formatting

`flix format` parses `.flix` sources and rewrites them through
`ca.uwaterloo.flix.tools.fmt`. The CLI subcommand, the REPL `:format`, the LSP
`FormattingProvider`, and the file I/O in `FormatterLsp` are all in place.
**No layout rule is implemented**: `PrettyPrinter` is a stub returning the empty
string, and `FormatterLsp.treeToTextEdits` returns no edits, so the command
parses its input and changes nothing. Say that plainly rather than describing
`flix format` as reformatting anything.

Three facts about the substrate decide most design questions, and each is easy
to get wrong from reading the pretty-printing literature instead of the code:

- **`Doc` makes no decision from a line width.** Every choice between a
  single-line and a multi-line rendering is encoded in the document itself via
  `LayoutChoice` and `SetLayout`, which is what makes `Doc.pretty` a single pass
  with no backtracking. Adding a width-driven `group` combinator would reintroduce
  the lookahead the algebra exists to avoid.
- **Comments are in the tree; whitespace is not.** `Parser2.comments` collects
  runs of comment tokens into a `TreeKind.CommentList` node, and both `open` and
  `close` call it. Attachment is therefore *symmetric*: a comment before a node's
  first token and a comment after its last token both land inside that node, and
  which node wins is decided by parser call order rather than by a trivia model.
  Any layout rule that moves a comment needs a real attachment model first —
  the parser does not supply one.
- **Inter-token spacing comes from the source, not the tree.** The lexer does not
  emit whitespace tokens. `Token` carries `startIndex`/`endIndex` into its
  `Source`, so the gap between two tokens is recoverable by slicing. A printer
  that wants the original spacing has to go back to the source for it.
- **Not every character belongs to a token.** `Lexer.acceptEscapedName` resets the
  token start past the `$` of an escaped name, so the `$` of `$run` or `x.$and(y)`
  is in no token at all. Concatenating `Token.text` therefore emits `def run` and
  renames a definition to a keyword. Print through
  `TokenStream.printableTokens`, which attributes such characters to the token
  that follows them, rather than reading `Token.text` directly.

The formatter test suites live in `main/test/ca/uwaterloo/flix/tools/fmt/` and
share their corpus — the standard library plus `examples` — through
`TestFormatterCommon`. They are `@Ignore`d because the stub fails them — the
empty string is not a faithful rendering of anything — so the `@Ignore`
annotations come off with the printer, not before. Know one weakness before
trusting them once they run: the
non-destructiveness check compares case-class names and collection lengths and
matches everything else with a wildcard, so it would accept output in which
every identifier had been renamed. It constrains the *shape* of the weeded AST
and nothing else.

`TestFormatterStability` asserts that the standard library and `examples` are
fixed points of the formatter. Its docstring explains this by calling the corpus
canonical, which is not true — the corpus mixes inline and broken layouts for
the same constructs. The assertion is still the right one, because a formatter
that reproduces its input satisfies it; but a formatter that imposed one layout
per syntax tree could not, and that conflict has to be settled with the upstream
maintainers rather than by weakening the test.

Two invariants hold in the write path (`FormatterLsp`, covered by
`TestFormatterLsp`): a file is decoded and re-encoded through the **same**
charset, and a file whose formatted output equals its current content is not
written at all. The second is why a run that changes nothing leaves every
timestamp in the project alone. `computeLineOffsets` splits on `"\n"`, which is
correct for CRLF as well — the `"\r"` stays inside the preceding line's length,
so the offsets still land on the first character after the break.

Decisions taken while building the formatter, with their evidence and the
alternatives rejected, are recorded in `docs/FORMATTER-DECISIONS.md`. Style rules
the formatter enforces belong in `docs/STYLE.md`, which invites exactly that:
*"If a PR discovers a new style principle, feel free to add it to this file as
part of the same PR."*

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
- `Coverage.hit(probeId)` is called at function, executable-line, `if` branch, and `match`/restrictable `choose` rule entry
- Coverage probes are inserted after the first tree-shaking pass, during `CoverageInstrumentation`
- Coverage data is recorded to `build/coverage.json` when instrumented code runs

### Implementation Details

**Phases Involved:**
1. **TreeShaker1 / CoverageInstrumentation** — Retains reachable definitions, then inserts function, line, `if` branch, and `match`/restrictable `choose` rule probes
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
- Line probes cover function bodies, `let` bindings, statement expressions, `if` conditions and bodies, definition/signature/closure calls, lambda construction, tuples, record operations, array literals, unchecked casts, and `run … with handler`; full expression-level line coverage is not implemented.
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

## Releasing

Pushing a `v*` tag runs `.github/workflows/release-jar.yaml`, which builds
`flix.assembly` and attaches it to a GitHub Release as `flix-<version>.jar`.

Before the release is created the workflow runs the jar and checks its self-reported
version against the tag. That version is derived at build time by `gitDescribe` in
`build.mill` and written to a `version.txt` resource, which `Version.scala` parses
(covered by `TestVersion`). Two details there are load-bearing:

- The describe glob is `v[0-9]*`, not `v*`. A bare `v*` is a glob, not a prefix on
  release tags: it also matches `v.0.8.1` and `v.0.9.0`, which this history carries and
  which parse as neither release form, silently degrading the reported version. On the
  commit tagged `v.0.9.0`, `--match 'v*'` describes as `v.0.9.0-0-g8d678a860` where
  `--match 'v[0-9]*'` correctly reaches past it to `v0.8.0-29-g8d678a860`.
- `release-jar.yaml` filters its push trigger on the same `v[0-9]*`, and re-checks it in
  the workflow because `workflow_dispatch` takes an arbitrary tag and is not filtered.
  The two must agree: a tag that can trigger a release but cannot be described produces a
  jar whose `--version` contradicts the release it is attached to.
- The checkout uses `fetch-depth: 0`. Without tag history `git describe` finds nothing
  and the build stamps itself `unknown`.

This fork publishes no Maven artifacts at all, and `build.mill` deliberately does not mix
in `PublishModule`: there is no POM, no coordinate, and nothing to keep in sync with a
registry. Adding one back means adding the module, `pomSettings`, and `publishVersion`
together — a bare `PublishModule` will not compile without the latter two.

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
