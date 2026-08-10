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

## Building a Project

**Each build mode owns an output directory**: `build/development/` for
`Build.Development` (`flix build`, `run`, `test`) and `build/production/` for
`Build.Production` (`build-jar`, `build-fatjar`). `Build.directoryName` is the
mapping, `Bootstrap.getOutputDirectory` resolves it, and it is what
`Options.outputPath` is set to — so the class files land in
`build/<mode>/class/`, since `JvmWriter` resolves `class/` against that path.
Class files, build manifest and product set are all per mode. They cannot be
shared: build mode reaches the *typer* (`ConstraintSolverInterface` is lenient
about the `Debug` effect in development), so the two modes do not compile the
same program, and one directory had every `build` reset what the last
`build-jar` left and the other way round. `build/doc/` stays outside, because
documentation is not mode-specific. `clean` visits every mode's output —
`Bootstrap.AllBuilds` is the list, and a new mode has to be added there or its
output becomes uncleanable.

`flix build`, `build-jar` and `build-fatjar` go through `Bootstrap`, and none of
them wipes its class directory any more. They **reconcile** it: compile, then
delete every class file that is not one this compilation wrote, and prune the
directories that empties. `--clean` still empties the directory up front and
rebuilds from nothing, which is what a reproducible release wants: there is then no
moment at which the previous build's output could be taken for this one's.

Reconciling is only sound because of one fact about the compiler, and a change
that stops it being true breaks this silently: **`Flix.codeGen` is
whole-program.** Every call runs the monomorphizer, both tree shakers and
`CodeGen` over the entire program, so the classes it emits are the *complete*
set the current sources require — never a changed subset. `previous −
current` is therefore exactly the products to delete, computed rather than
guessed. `JvmWriter.run` returns the relative path of every file it wrote and
that set reaches `CompilationResult.products`; deriving those names a second
time from the class names instead is how a writer and its bookkeeping drift
apart.

The same fact is why there is **no per-source product ownership**, and why
`BuildManifest.sources` is one group rather than a map. Most generated classes
are keyed on *types* aggregated over the whole program — tuples, records,
function interfaces, closures, tag classes and the effect runtime all come out
of `root.types` — and a monomorphized specialization exists because of a call
site in some source other than the one declaring it. A per-file mapping would
read as ownership while being wrong about it, and nothing needs one: the set
difference is exact where an ownership approximation is not.

`build/<mode>/build.json` (`BuildManifest`) records the product set and a
fingerprint of every *non-source* input: compiler version, the back-end options,
and the dependencies by size and modification time. A build whose fingerprint
differs from the recorded one, or which cannot read the manifest at all, falls
back to a full build. Three details are load-bearing:

- Source changes are deliberately **not** in the fingerprint. They are handled by
  recompiling and diffing the product set; putting them there would force a full
  build on every edit and defeat the point.
- Thread count is not in it either, and must not be. A few generated names carry a
  symbol counter whose allocation order depends on scheduling, so two builds of
  one program can disagree about a handful of closure class *names*. Reconciling
  handles a rename exactly; a fingerprint over it would rebuild everything at
  random. It is also why a test that compares class directories has to pin
  `threads = 1`.
- `clean` knows the manifest by name and deletes it with the products it
  describes. A manifest that outlived them would be trusted by the next build.

Build mode is in the fingerprint too, which is belt and braces now that each mode
has its own directory: a manifest is only ever compared against one written for
the same mode, so the check cannot fire. It stays because the fingerprint is meant
to describe *every* non-source input, and a reader who finds mode missing from it
would reasonably conclude modes are interchangeable.

**A modification time may not license reusing a cached AST.** `Source` equality is
by path, not by content, and `ChangeSet.partition` hands back the *cached* result
for any input not marked changed — so a file whose mtime did not move is compiled
as it was, and the edit silently never reaches the output. Mtimes are millisecond
resolution at best and whole seconds on some filesystems, so two writes inside one
tick are ordinary. `updateStaleSources` therefore re-offers **every** source when
no watcher is active, and is selective only on the watcher path, where an edit is
an event rather than an inference. This is why `build` dropping `incremental =
false` is safe: the front-end cache is only ever reused when something
authoritative said what changed. Real front-end incrementality for a long-lived
non-watcher client (a BSP server) needs content hashes in place of mtimes — that
is a separate change, and it is the thing to do rather than loosening this.

`Bootstrap` also tracks which sources a **particular** `Flix` instance has already
been given, so a *different* instance gets a full rescan. The drained watcher
events are per-instance facts; telling a fresh instance only what changed since
leaves it compiling an empty program, which `reconcileClassDirectory` would then
read as "every class file is stale". It refuses an empty product set for that
reason: emptying the directory and packaging an empty jar is the one failure here
that looks like success.

Two more places where a silent-wrong-output path was closed, both worth keeping:
`removeClassFiles` checks the *bytes* of a `.class` file and not just its name,
because it now runs on every ordinary build where before only `clean` deleted
there — and it tolerates an empty one, since that is what an interrupted write
leaves. And `validateProducts` runs before the jar is opened, because
`FileOps.addToZip` *skips* a path that does not exist rather than failing, and
`createJar` truncates the last good jar first: without the check, a product that
went missing yields a jar quietly short a class and an exit code of zero.

**A full build happens for two reasons, and they are not the same operation.**
`--clean` was asked for, so `emptyOutputDirectory` wipes the class files and the
manifest *before* the compile — and a `--clean` whose compile then fails leaves
nothing, the same bargain `make clean && make` offers. A full build forced by a
changed **fingerprint** was not asked for: it discards the compiler's in-memory
state and touches **nothing on disk**, because reconciling after a successful
compile reaches the same directory anyway, and wiping first would destroy a working
build whenever the compile meant to replace it fails. The inputs that land there —
a compiler upgrade, a new `--coverage`, an updated dependency — are far too common
for that. Collapsing the two back into one operation is the mistake to avoid;
`TestBootstrap` pins both halves.

Two things the incremental path newly exposes are fixed in
`updateStaleSourcesByTimestamp`: a deleted file reads as stale but must be
removed rather than re-added (`addFile` rejects a file that is not there), and
only a `.flix` path may be handed to `remFile`. Note also that `Bootstrap` scans
for sources once, when it is constructed — a file *created* afterwards is
invisible until the next scan, so a test that adds one has to write it before
`Bootstrap.bootstrap`.

**`./mill flix.test` does not run any of this.** `TestBootstrap` is
`@DoNotDiscover` and reached only through `PackageManagerSuite`, so the whole
`Bootstrap` surface — build, `build-jar`, `clean`, the manifest, reconciliation —
is covered by `./mill flix.testPackageManager` alone. CI does run it
(`.github/workflows/package-manager.yaml`, with a token, on every PR), but the
local loop this guide recommends reports green on a regression here. Run
`flix.testPackageManager` too when touching `Bootstrap`; without a token its
GitHub-fetching tests fail on the anonymous rate limit, and a failure whose
message is not `API rate limit exceeded` is a real one.

`main/test/resources` has no fixtures for any of this on purpose: every test
builds a real temp project. Two properties of that setup are load-bearing. A test
comparing generated class *names* must pin `threads = 1` (`mkDeterministicFlix`),
and even then a few specialization hashes vary across `Flix` instances — so
compare a *count* when what matters is that nothing accumulated. And a test that
edits a source between builds must use a fresh `Flix` instance per build, or it
depends on mtime resolution to notice the edit.

## The BSP Server

`flix bsp` serves the Build Server Protocol on stdio, so an editor can drive a real
Flix build. `flix bsp-install` writes `.bsp/flix.json` so a client can find it.
Everything lives in `ca.uwaterloo.flix.api.bsp`, and it is **not** in the language
server: `docs/TOOLING-CONTRACT.md` forbids putting build requests there and that
constraint stands. What that document concluded — that BSP belongs to some other
build tool — does not hold for a plain `flix.toml` project, where `flix` *is* the
build tool, and its section now says so: the two are peers, `flix bsp` for a project
whose build is `flix` and `--diagnostics-json` for one whose build is Gradle, Mill or
Bazel.

**Standard output belongs to the protocol, so never `println` on a path a BSP
request can reach.** `BspServer.run` takes `FileDescriptor.out` for the launcher and
points `System.out` at `BspLogStream`, which turns each line into a
`build/logMessage`. That is not paranoia: `Bootstrap` narrates dependency resolution
on every start, and without the quarantine those lines land between two frames and
end the connection. Redirecting to the client's log rather than to nothing is
deliberate — a crash report that vanishes is worse than one that arrives somewhere
unexpected. `TestBspProcess` asserts the first byte of stdout is the `C` of
`Content-Length`.

**`buildTarget/compile` is a real build, through `Bootstrap`'s own path.** It writes
class files and reuses the reconciliation and manifest logic, because a second compile
path is how a build server's idea of a build drifts from `flix build`'s.
`Bootstrap.CompileOutcome` is what makes that possible: a `Result` says a build worked
or failed, and a compile that *succeeded* can still carry messages a client must be
shown. Three ways to get diagnostics wrong, each now pinned by a test:

- **`lsp.Position` is one-indexed**; only `toJSON`/`toLsp4j` subtract. Reading
  `range.start.line` directly puts every diagnostic one line low. `BspDiagnostics`
  converts through `toLsp4j` rather than repeating the arithmetic.
- **`code` must be the stable `E2136`**, not the kind. The language server's `code`
  field is the category, which hundreds of errors share and nothing can key on.
- **A fixed file needs an explicit empty report**, or the marker stays until the editor
  restarts. `DiagnosticLedger` remembers what was published — and after a *failed*
  compile clears nothing, because a document the compiler never reached has not been
  shown to be clean.

**`buildTarget/run` forks.** `Bootstrap.run` runs `main` in the compiler's own process by
reflection, which a server cannot do: a `System.exit` in user code would take the
connection with it. `ProjectView.runtimeClasspath` is what makes forking possible — class
directory, `resources/`, then the Maven and url jars. It excludes `flix.jar`, and that
exclusion is load-bearing rather than tidy: the compiler ships a *mock*
`dev.flix.runtime.Global` whose `setArgs` throws, so a program that finds the compiler
ahead of its own classes dies before `main`. `jvmRunEnvironment` reports the same
classpath so a client can fork it itself, and `jvmTestEnvironment` reports the same list
again — `@Test` defs are entry points in the same output, so there is no test-only
classpath to invent.

**`buildTarget/test` reports each test, and there is only one runner.** `Tester` decides
what a test outcome *is* — a `false` result is a failure, a non-false result that wrote to
standard error is also one, a `@Skip`ped test never starts the clock — and callers choose
only how those events are rendered (`Tester.TestEventSink`). `flix test` attaches the
console rendering; the server attaches `BspTestSink`. That is the structural reason the
command line and an editor cannot come to disagree about whether a test passed, and
`TestTesterSink` pins the events. The console rendering is the one that must not be
attached in a server: it builds a JLine *system* terminal and writes to the real file
descriptor, which is the protocol channel. Three traps here:

- **A skipped test gets no `Before` event**, so its task pair has to be opened and closed
  together, or the client receives a finish with no start.
- **`TestReport` takes five consecutive `Integer` parameters.** `cancelled` and `skipped`
  the wrong way round compiles, runs, and puts a plausible number in the wrong column.
  `BspTestSink.report` uses setters for exactly that reason.
- **The tests run in the server's process**, because a test is a compiled function the
  compiler reflects and calls; there is no test-runner entry point to fork. A test that
  calls `System.exit` therefore takes the server with it. `jvmTestEnvironment` is the way
  out for a client that wants isolation.

**Requests do not run on lsp4j's message thread, and that has two consequences.** A
handler that ran there would stop the connection being read for the length of a compile,
so `FlixBuildServer` dispatches to the connection's executor; builds stay serialised by
`BspSession`'s lock rather than by the transport. The consequences: cancellation is *soft*
(the work finishes and its result is dropped, because the compiler's ForkJoin pool and
`JvmWriter`'s writes are not interrupt-safe and a half-reconciled class directory is worse
than a late answer), and work can outlive the `shutdown` that ended the session — so every
notification goes through one accessor that returns no client once shut down, and
`shutdown` bumps the generation so an in-flight build's result is discarded.

**`workspace/reload` is transactional, and `buildTarget/cleanCache` is not
`Bootstrap.clean`.** A reload builds a fresh `Bootstrap` and a fresh `Flix` and installs
only a complete one — a half-applied reload would answer some questions from the old
project and some from the new — and a reload that fails leaves the previous session
serving, because a typo in `flix.toml` must not leave an editor connected to a dead
server. It bumps the generation, so a compile already in flight is discarded rather than
published, and it clears published markers before forgetting them: a file dropped from
the project can never be spoken for again, so the marker would otherwise outlive the
editor session. `Bootstrap.cleanOutput(flix, build)` is what `cleanCache` uses — one
build mode's class files, its manifest, and the compiler's in-memory caches. `clean()`
would also delete `doc/`, `stubs/` and the coverage reports, which no client asked about.

**Source membership is reconciled before every compile** (`Steps.rescanSources`).
Modification-time polling answers which *known* files changed, and a created file is
not among them. Two related traps, both found by tests rather than by reading:
re-offering every source is not the same as *forgetting* what was loaded — the
timestamp map doubles as the record of what the compiler was given, which is what
identifies a deleted file, and clearing it left the deleted file in the compiler's
inputs until the reader failed on it. And a failed build writes no manifest, so every
later build takes the stale-inputs path, which is how that bug reached every build
after the first failure.

**One target per project, and it is forced rather than chosen.** `src/`, `test/` and
the project root compile together as one whole program and `@Test` defs are entry
points, so a `src`/`test` split would publish test diagnostics against the `src`
target while `inverseSources` assigned those files to `test` — and the marker would
never be cleared. `BuildTargets` is the only place this is decided. The tag is
`library`, never `application` (unknown until it compiles, and discovery must answer
for a broken project) and never `test` (a client turns that into a test source root,
which with one target means every source in the project).

**Advertise nothing that is not implemented.** `BspCapabilities.Implemented` is the
single source of truth: it drives both the capability flags and the `MethodNotFound`
refusals in `FlixBuildServer`, so a request and its advertisement cannot drift.
`TestBspCapabilities` holds them in step in both directions. A phase adds its request
and its flag together. `debugSessionStart` is the one that never becomes available —
there is no debug adapter, so `canDebug` is false permanently.

Five things learned the hard way, each now pinned by a test:

- **A target id needs an *empty* URI authority, not a null one.** `new URI(scheme,
  null, path, query, null)` renders `file:/path`, which is legal and is not what
  `Path.toUri` or any other build server produces — a client that computes the id
  itself and compares strings would see a different target.
- **Comparing normalised paths refuses correct clients.** `rootUri` is checked with
  `toRealPath`, because on macOS every temp directory is under `/var`, a link to
  `/private/var`; `normalize` is textual and cannot see a link, so the path the JVM
  reports and the path the user opened are two spellings of one directory.
- **`Source.name` is not a URI.** It is `path.toString`. `BspUri.ofSource` is total —
  `String`, not `Option[String]` — so nothing is silently dropped. The language
  server's `file://` filter drops diagnostics from bundled and packaged code, and
  would drop ordinary project files too if documents did not reach it as virtual
  URIs. Bundled library sources get `flix-lib:`, `.fpkg` contents get `jar:…!/…`.
- **A test harness must not connect the two ends with `PipedInputStream`.** It is a pipe
  between *threads*, not streams: it remembers the last thread that wrote and throws
  `Write end dead` from `read` once that thread exits. A server writes on whatever thread
  produced the event, and `Tester`'s reporter thread is deliberately short-lived, so a
  piped pair killed the client's reader mid-request and accused working code.
  `BspTestChannel` opens a `java.nio.channels.Pipe`, which has no thread affinity, and
  every suite goes through it.
- **`ProjectView` is a snapshot, and there is one per request.** `Bootstrap`'s source
  and dependency lists are mutable, so accessors would let one request be answered
  from two different projects. It carries only what is known *without* compiling,
  because discovery is asked before the first build and while the project is broken.

`./mill flix.test` runs the in-process tests. The ones that need a built assembly or
a process of their own are `@DoNotDiscover` in `BspSuite`, reached by `./mill
flix.testBsp`, which builds the assembly first — a test that cancels itself when the
jar is missing reports green while proving nothing. `.github/workflows/bsp.yaml`
runs it; it needs no token, since nothing there reaches the network.

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
`TestFormatterCommon`. They are the slowest part of the run — around ten of the
full suite's twenty-odd minutes, which is what pushed CI past its wall — because
each property reparses the corpus and each reparse is a full `Flix.check()`.

So: **prefer asserting a new property inside a pass that already exists** rather
than adding another corpus-wide one. Idempotency and non-destructiveness share a
pass in `TestFormatterCorrectness` for exactly this reason — both need `p(f(c))`,
and computing it twice was four corpus-wide compiles where two do.

The larger saving is still on the table and is not free: `check()` runs the whole
pipeline through `Typer`, while the formatter needs only `Parser2` and `Weeder2`.
A parse-and-weed entry point would cut these suites several-fold, at the cost of
no longer proving that formatted output still type checks. That guarantee is
weaker than it looks — the negative-literal defect produced non-compiling output
and the corpus compile did not catch it, because the corpus had no case — but it
is not nothing, and dropping it is a decision rather than an optimisation.

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
after the module, and converts any value whose Flix representation is private:
`Option` crosses as `java.util.Optional` and `List` as an unmodifiable
`java.util.List` (`ExportPlan.AsOptional` and `ExportPlan.AsList`).

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
is a separate mechanism that `ExportPlan` neither covers nor repairs. Do not
cite it as evidence about Flix–Java interop generally.

That boundary has one recurring defect, and it is worth recognising by shape: a
bytecode reference to a Java member must be emitted at the *reflective member's*
type, with the value bridged to or from the Flix type on either side of the
instruction. The two type lists may not be substituted for one another. A
generic Java call returning a reference needed a cast on the way back
(flix/flix#12970); an anonymous class overriding a generic method needed its
primitive parameters unboxed on the way in (#12972, fixed in
`Lowering.lowerJvmMethod` beside the boxing already applied to the return).
`super` arguments (#12972) and Java functional interfaces (#8618, #5172) are
still open. They keep arriving one at a time because there are four independent
Java-to-Flix type mappers and no shared bridge — which is what #8592 asks for.

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
