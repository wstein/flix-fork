# Flix Compiler - Developer Guide

## Running the Compiler

The project uses [Mill](https://mill-build.org/) as its build tool.

- `./mill flix.compile` — Compile the compiler itself
- `./mill flix.run <file.flix>` — Run a Flix source file through the compiler (should take at most 3 minutes)
- `./mill flix.assembly` — Build a fat JAR at `out/flix/assembly.dest/out.jar`

## Project Scaffolding

`flix init [directory]` (`Bootstrap.init`) writes a new project into the current
directory or one supplied target directory, creating a missing target directory:
`src/`, `test/`, `.github/workflows/build-and-test.yaml`, `flix.toml`,
`.gitignore`, `.editorconfig`, `AGENTS.md`, `CLAUDE.md`,
`.github/copilot-instructions.md`, `LICENSE.md`, and
`README.md`. Every file is written through `FileOps.newFileIfAbsent`, so running
`init` in a directory that already has one of them leaves that file untouched —
new template files must keep that property, since users edit what `init` gives
them.

In an interactive terminal, `flix init` asks only for the required package
metadata that it cannot infer: **Project description** and **Author**. The
author default is `git config user.name` plus `user.email` when both are set;
otherwise it is the explicit `TODO`. Blank answers accept the displayed
defaults. It then asks for a license: `apache2`, `mit`, `bsd3`, `gpl3`, or
`none`; the default is `apache2`. A selected short name is written as its SPDX identifier to
`flix.toml`; `LICENSE.md` intentionally requires the project's full license
text and copyright notice before distribution. In a non-interactive invocation,
or when `flix.toml` already exists, it uses those defaults without prompting;
an existing manifest is still never overwritten. `Bootstrap.InitOptions` keeps
the prompt layer separate from the file-writing layer, and manifest strings
must be TOML-escaped before they are written.

`flix init --refresh` (`Bootstrap.refreshAgentGuide`) is the one thing that
overwrites, and it overwrites exactly one file. `AGENTS.md` names the compiler
version that generated it, so it goes stale the moment a project upgrades;
`--refresh` rewrites it for the running compiler. It rewrites only a guide that
still opens with the `<!-- flix-init:` marker — deleting that line hands the
file to the project, the same contract `MarkdownDocumentor` uses for pages it
did not write. `CLAUDE.md` is never rewritten: it is a two-line `@AGENTS.md`
import (Claude Code reads `CLAUDE.md`, not `AGENTS.md`) and anything a project
adds below the import is its own. A `CLAUDE.md` without that import loads
nothing and reports nothing, so `--refresh` says so. Copilot's
`.github/copilot-instructions.md` is also written only if absent; it points to
the root guide and is left for the project to edit or delete.

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
`flix format` reproduces its input exactly — a verified round trip, not a
reformat. `flix format --canonical` reformats: horizontal spacing, indentation,
`match` layout with produced `=>` alignment, struct-field and record-type-alias
alignment, pipeline breaking, and one Datalog constraint per line. It is opt-in,
and choosing it is the consent to reformat, so it does not preserve padding "in
case it was deliberate" — only spacing that is *semantic* survives untouched.

**Editors get the canonical layout, with no flag.** `textDocument/formatting`
goes through `FormattingProvider`, which formats with `Canonical`: asking an
editor to reformat is the same consent the CLI flag stands for, so there is
nothing further to opt into. The client's `FormattingOptions` — `tabSize`,
`insertSpaces` — are ignored on purpose, since honouring them would make the
formatter configurable through the back door and give editor users a different
indentation from everyone else. `textDocument/rangeFormatting` is deliberately
not advertised: laying out a fragment without its enclosing context is a
standing source of idempotence bugs, so "format selection" does nothing on
`.flix` and that is the intended behaviour.

The edits are **minimal**: one replacement of the region that differs, and none
at all for a document already formatted. An editor applies an edit literally, so
replacing the whole buffer to reindent one line collapses undo, moves the caret
and resets folding. Keep the property that applying the edits reproduces exactly
what the printer wrote — a minimal edit that is subtly wrong corrupts a file
rather than formatting it badly, and that is asserted corpus-wide.

Test the LSP through `FormattingProvider`, not through `PrettyPrinter`. The
provider was wired to the *default* policy for its whole life, so it handed back
whatever document it was given and format-on-save did nothing in any editor —
while every formatter property test passed, because none of them went through
that door. `TestFormattingProvider` now does.

**It does not decide where an ordinary expression breaks.** If the author wrapped
a call across three lines it stays wrapped; the formatter fixes the indentation
of those lines but not the decision to have them. That is roughly 5,000 sites in
the corpus and it makes this a **`gofmt`-class formatter, not a `dart format`
one** — a real position on the spectrum, and the one to describe it by. Don't
call the output "one layout per syntax tree" without that qualification. The
reasoning, and why closing the gap is neither required nor currently checkable,
is D23 in `docs/FORMATTER-DECISIONS.md`.

Vertical decisions cannot be made from a pair of adjacent tokens, so they come
from `LayoutPlan`, which walks the tree and emits one directive per gap; the
printer applies a directive where there is one and falls back to the separator
policy everywhere else. A policy opts in via `usesLayoutPlan`, so vertical layout
is a claim a policy has to make rather than something inferred.

Four invariants there are easy to break, and three of them produce output that is
wrong while staying perfectly consistent and idempotent — so the property tests
cannot see them and only reading a diff can:

- A `Break` keeps however many blank lines the gap already had. Alignment groups
  are *defined* by blank lines, so collapsing one regroups the arms on the next
  pass and formatting stops being idempotent.
- A construct indents its **body**, not its own header. A node starts earlier than
  it looks — the parser folds a declaration's doc comment and modifiers into it —
  so indenting everything after `node.start` pushes each header in by a level, at
  every nesting depth, and the whole file drifts right.
- `Parser2.close` folds a *trailing* comment into the preceding declaration, so a
  comment introducing the next declaration must be trimmed from that declaration's
  indent span or it is indented as part of the previous body.
- Quarantine outranks the plan, so nothing is arranged around code the parser
  could not read.

Neither mode requires the program to compile. A declaration whose subtree
contains a parse error is reproduced verbatim and the rest of the file is
formatted, so a file being edited still formats above the breakage.
`TokenStream.quarantined` computes that per token and `PrettyPrinter` applies it,
so every layout policy inherits it. Boundaries come from the parser, never from a
heuristic — which means that when the parser cannot resynchronise it absorbs the
*following* declarations into the broken one and those go untouched too. Only
parse errors quarantine; a program that merely fails to type check is formatted
in full, and `flix format` exits 0 either way because reporting errors is
`flix check`'s job.

The printer emits every token of the tree in order and decides **only the
whitespace between them**. That restriction is the architecture, and it is worth
preserving: no token can be lost, duplicated, or reordered, so declaration order,
`use` order and record labels are untouched because no operation exists that
could touch them — and a comment always keeps the same neighbouring tokens, so
formatting can change which *line* a comment sits on but never which declaration
it belongs to. A layout rule is a `PrettyPrinter.Separators` policy choosing a
gap, added against a baseline that already round-trips. A construct with no rule
costs fidelity nothing.

Three facts about the substrate decide most design questions, and each is easy
to get wrong from reading the pretty-printing literature instead of the code:

- **`Doc` makes no decision from a line width.** Every choice between a
  single-line and a multi-line rendering is encoded in the document itself via
  `LayoutChoice` and `SetLayout`, which is what makes `Doc.pretty` a single pass
  with no backtracking. Adding a width-driven `group` combinator would reintroduce
  the lookahead the algebra exists to avoid. Note the scope: this binds `Doc`, the
  renderer. `LayoutPlan` decides breaks from the tree, outside `Doc`, and *could*
  consult a width. It does not, because the corpus says width predicts Flix
  authors' breaks poorly — a smooth gradient with no knee, where arity has a step.
  That is an evidential reason, not an architectural one; do not cite the algebra
  to close the question.
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
- **Whitespace is sometimes semantic.** `a->b` is struct field access while
  `a -> b` is the function arrow; a `.` with trailing space is the Datalog clause
  terminator and one with leading space is a lexer error; and `@` followed
  immediately by a name char is a single `Annotation` token rather than `At` plus
  a name. Normalising any of these re-lexes the program instead of restyling it,
  so `Canonical` reproduces the original gap next to them. Tightness is also not
  symmetric — treating collection heads as tight turned `else Set#{ }` into
  `elseSet#{ }`. Add a spacing rule only with a case that proves it does not
  re-lex.

**After changing a layout rule, run `./mill flix.formatterDiffReport`.** It ranks
the corpus by how much of each file canonical formatting rewrites, and it is the
only instrument that sees a rule firing where it should not. Every layout defect
found in this subsystem so far passed the automated properties — they establish
that formatting destroys nothing, and are all satisfied by output that is
consistently, reproducibly wrong. Read the top few entries: a file rewritten
almost entirely usually means a broken rule rather than a badly formatted file.
The baseline is 221 of 403 files changed, 23,894 lines; a large jump means
something new is firing too widely.

The formatter test suites live in `main/test/ca/uwaterloo/flix/tools/fmt/` and
share their corpus — the standard library plus `examples` — through
`TestFormatterCommon`. They are the slowest part of the run — roughly six of the
full suite's sixteen minutes — because each property parses the corpus and each
parse is a full compile. Keep that in mind before adding another corpus-wide
property; prefer asserting it inside a pass that already exists.

The fixtures are held by that file's companion *object*
rather than by the trait, and each `Sample` memoises the parse of its own
content: every fixture compiles the standard library and every property starts
from the unmodified sample, so building them per suite and reparsing per property
cost more than all the properties together. Keep new suites on the shared
fixtures. Know one weakness before trusting them: the
non-destructiveness check compares case-class names and collection lengths and
matches everything else with a wildcard, so it would accept output in which
every identifier had been renamed. It constrains the *shape* of the weeded AST
and nothing else.

`TestFormatterStability` asserts two different things and the difference matters.
Over the whole corpus it runs the **default** policy, which is
non-destructiveness — the formatter puts back exactly what it was given. It does
not run the canonical policy there and must not: the corpus lays the same
construct out both ways, so no formatter that imposes one layout per syntax tree
can fix-point it.

Canonicality is asserted over `main/test/resources/fmt/canonical`, a small set of
input/expected pairs whose expected output the formatter produced and a human
read. Regenerate with `./mill flix.updateCanonicalFixtures` and **read the diff**
— that review is the only thing making them evidence, and it is where a layout
change is visible as a layout rather than as a passing test. The suite also
asserts that every layout rule is exercised by some fixture (goldens otherwise rot
behind the rules) and that no fixture is quarantined (an unparseable fixture is
reproduced verbatim, so it would satisfy every property while asserting nothing).
Fixtures need only parse, not compile.

Adding a layout rule therefore means adding a fixture that exercises it, or the
coverage assertion fails. That is deliberate.

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

## Exporting to the JVM

`@Export` makes a Flix def reachable as a `public static` method from Java and
every other JVM language. The compiler emits a shim on a facade class named
after the module, and converts any value whose Flix representation is private —
today `Option`, which crosses as `java.util.Optional`.

`ExportPlan` is the single description of that boundary: the shim's return type,
its generic `Signature`, and the conversion bytecode are all projections of one
plan, so they cannot describe different things. The front-end gate in
`EntryPoints` is *not* a projection of it — it runs on a different AST — so the
standing invariant is that the gate is widened in the same change as the plan,
never ahead of it. `TestExportedShims` asserts it: no return type the gate
accepts may produce a shim naming a `dev.flix.gen` class.

Two things bite. **Only the first namespace segment becomes a package** — `mod
A.B.C` is the facade `A.B$C` with defs `A.B$C$Def$…`, all in package `A` — so no
facade can be a package prefix at any depth. A class and a package of the same
name make every export unreachable from Scala and Kotlin, and taking the
*parent* package instead fixes depth two while moving the clash to depth three,
which is why the rule is stated on the whole namespace. `JvmName.facadeOfNamespace`
and `JvmName.packageOfNamespace` must keep agreeing; `TestJvmName` asserts they
do. And an exported def's return type is deliberately left un-erased in
`Eraser`, which is what lets the shim present the declared type.

Anything about a shim that only shows up when it *runs* — that a conversion
produced the value the signature promised, that a primitive came back as a Java
box, that `Some(null)` collapses onto `None` — belongs in
`TestExportedShimsRuntime`, which loads a compiled facade in an isolated
classloader and calls it. Descriptors alone cannot see those.

Two things about type variables. An **unconstrained** one is exported as
`Object`: `Specialization.run` seeds exported parametric defs so the empty
substitution specializes them at the monomorphizer's own default, and seeding
them *there* is what keeps their symbol, without which `CodeGen` emits no shim.
A **constrained** one is rejected by `EntryPoints` (E1970) — not as a policy but
because reaching `resolveSigSym` with a defaulted variable crashes the compiler
on a missing instance. Relaxing one without the other is a compiler crash on
ordinary code.

`SimpleType.Native` carries the type arguments of a Java generic so an exported
return can declare them, and **deliberately ignores them in `equals`** — a Java
class is one class however it was applied, and the compiler reaches the same one
down paths that erase the arguments differently. `TestSimpleType` pins that; a
change that "fixes" the equality will fail nineteen generic-interop tests.

All of this is the *outbound* direction only. Flix calling Java — generic method
results, anonymous-class overrides, `super` arguments, functional interfaces —
is a separate mechanism with open soundness defects upstream (flix/flix#12970,
#12972, #8618, #5172) that `ExportPlan` neither covers nor repairs. Do not cite
it as evidence about Flix–Java interop generally.

Decisions taken while building this, with their evidence and the alternatives
rejected, are in `docs/JAVA-INTEROP-DECISIONS.md`; `docs/JAVA-INTEROP-PAPER.md`
is the longer design report, whose Appendix B reproduces every measurement
either document quotes. `examples/interoperability/calling-flix-from-java` is
the worked example.

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

- `./mill flix.test.testForked "-oC"` — Run all tests (preferred; `-oC` suppresses passing test output, should take at most 20 minutes)
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
