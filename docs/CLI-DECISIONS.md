# Command Line Decision Log

Decisions taken while rebuilding the `flix` command line on picocli, each with
the evidence behind it and the alternative that was rejected. They are recorded
here because most of them are about what a reader is *shown*, and a help text is
the one part of a compiler that has no test until somebody writes one.

Status values are **Settled** (in the repository, asserted by `TestMain`),
**Proposed** (implemented and asserted, but a judgement call the team may want to
overturn -- D3, D4, D5 and D7 are the ones worth arguing about), and **Deferred**
(knowingly unresolved, with the blocker named).

The invariant that binds the rest: **hiding is a property of the usage text and
never of the parser.** `Main.HelpScope` decides how much of the line a usage text
describes; both scopes parse the same language. An option a reader was once shown
may be in a script they saved, and a help text that has been tidied must not
break that line.

---

## D1 — One `--json`, declared once, meaning one thing

**Status: Settled.**

`--json` was two options with one name: a global setting `Options.json`, and a
child of `metric` selecting the report format. scopt resolved the name by
position, so each spelling did half of what it read as -- `flix --json metric`
printed a text report, and `flix metric --json` left `json` unset. Neither is
what the word says.

There is now one `--json`, it is global, and every command accepts it wherever it
appears. What made this expressible is that a command declaring a name a second
time is now an error at spec-build time rather than a precedence rule.

**Rejected:** keeping both and documenting the precedence. A rule a reader has to
know in order to predict which of two identical words wins is a defect with a
manual, not a design.

## D2 — `--json` is not a shorthand for `--format json`

**Status: Settled.**

When the collision was resolved, `metric` kept reading the global `--json` as
"emit the JSON report when no `--format` was given". That is a shorthand for one
of five formats, and it reads as an answer to a question it only half answers:
`--format` names text, json, csv, md and sarif, and `--json` names one of them.
The shorthand was also the second meaning of the word that produced D1 in the
first place, surviving the fix that removed the first.

`metric` now reads `--format` alone and defaults to text.

The resolution lives in `Main.metricFormatOf` rather than inline in the command,
because a withdrawn shorthand leaves no trace in `CmdOpts`: a parse-level test
would pass whether or not it came back.

**Consequence, accepted:** `flix metric --json` is accepted and prints the text
report, since `--json` is global and every command takes it. Refusing it there
would mean a command rejecting a global, which is a larger decision than this
one -- see D8.

## D3 — `-h`, and it is the only short name

**Status: Proposed.**

`-h` is what a reader types before having read anything, and every other tool on
their machine takes it. It is declared on the global `--help`, so it is answered
by every command: a short name that works on `flix` but not on `flix
build` teaches a habit that then fails.

**Rejected:** a short name per option (`-t` for `--threads`, `-o` for output).
A letter is worth its ambiguity for the option you reach for when lost, and for
nothing else; the rest are typed by scripts, which do not save keystrokes.

## D4 — The experimental surface is off the standard help, and `--Xhelp` shows it

**Status: Proposed.**

`flix --help` listed forty-odd options, twenty-two of them experimental. A list
is only worth reading if everything on it is meant for the reader, so the
experimental ones cost the whole of it. The three benchmarking commands (`Xperf`,
`Xmemory`, `Xzhegalkin`) were already hidden, which made the omission
inconsistent rather than absent.

What is experimental is read off the **name** (`Main.isExperimental`), not a flag
beside it, because the name is the part that survives being quoted in a bug
report: `--Xdebug` says what it is wherever it appears. `TestMain` holds the
`[experimental]` description prefix to the same rule in both directions, so an
option cannot be experimental in its name and stable in its description.

Hiding an option is indistinguishable from having removed it unless the reader is
told where it went, so the standard footer names `--Xhelp`, which prints the same
page with nothing left out -- experimental options and hidden commands together,
rather than two half-answers.

**Rejected:** a `--verbose-help` or `--help-all` spelled without the `X`. The
flag exists to reveal the experimental surface and belongs to it; a stable name
for it would be a promise about options that carry no promise.

## D5 — A command's help is about that command

**Status: Proposed.**

`flix init --help` listed twenty-one options, of which `init` takes one. The page
a reader opens to learn what a command takes has to answer that before anything
else, and reprinting the root page for every command answered it
nowhere.

The shared options are therefore **copied onto each command rather than
inherited**. `ScopeType.INHERIT` carries one visibility everywhere an option
lands, and these need two: listed on `flix`, where a reader looks for what
applies to everything, and accepted but unlisted on `flix build`, where they look
for what `build` takes. Copying keeps the guard inheriting gave, since a command
redeclaring one of these names is declaring it twice, which picocli refuses.

A footer names `flix --help` and `flix --Xhelp`, because a shorter page must not
read as a smaller command: a reader who concludes that `build` does not take
`--threads` has been misinformed by an omission. `TestMain` asserts that every
command still accepts every global, listed or not.

**Rejected:** removing the globals from the commands and requiring them before
the command word. That is a real simplification of the grammar, and it breaks
every `flix build --threads 4` in every script, for a page that a footer fixes.

## D6 — The parser is the source of the usage text, and of the tests

**Status: Settled.**

Every claim `TestMain` makes about the help is read from the spec or from the
rendered usage text, never from a hand-written list: which commands exist, which
of them parse, which options are hidden, which names answer `-h`, what the
version option prints. The predecessor could not do this. scopt's
`OptionParser.options` is `protected` and `kind`/`getParentId` are
`protected[scopt]`, so a lint could read names and nothing else -- it was
scope-blind and kind-blind, and had to be told which repeated names were
legitimate.

That is also why the duplicate-name lint written against scopt is not carried
over: picocli refuses a duplicate name within a command when the spec is built,
which is the same check, earlier, and without a list of exceptions to maintain.

## D7 — The synopsis names the commands

**Status: Proposed.**

`Usage: flix [OPTIONS] <file>... [COMMAND]` is picocli's default and it says that
a command exists without saying which, so the first line a reader sees named
nothing they could type. It now reads `flix [init|check|build|...] [options]
<file>...`, which is the shape the scopt parser printed before the migration.

The list is generated from the visible subcommands rather than written out. A
hand-written synopsis is a second list of commands, and the one it replaced had
already gone stale -- it named eighteen commands when the parser took
twenty-four.

Wrapping it is the part that needed code: picocli treats the bracketed group as
one word and breaks it wherever the width runs out, which produced `metr|ic` and
`eff-|check`. `synopsisLines` wraps at name boundaries, keeps the separator on
the line that breaks, and carries the closing bracket with the last name so it is
never orphaned. `TestMain` rejoins the wrapped lines and compares against the
parser's own list, which is the one check a split name cannot pass.

**Rejected:** `synopsisSubcommandLabel`, picocli's supported hook for this. It
replaces the `[COMMAND]` token in place, so the list lands after the options and
the line reads `flix [OPTIONS] <file>... [init|check|...]` -- the commands last,
which is the opposite of what makes them findable.

## D8 — A global that only some commands read

**Status: Deferred.**

`--json` is accepted by every command and read by two (`Xmemory` and the
`Xbenchmark…` options). `flix clean --json` is accepted and does nothing, and
after D2 so is `flix metric --json`. Silence is the wrong answer to a flag: the
options are either per-command, or the parser knows which command reads which
global and says so.

**Blocker:** both fixes are structural. Per-command declaration reintroduces the
name-per-command bookkeeping D1 removed; a "which command reads this" table is a
second source of truth beside the setters unless it is derived from them, and
nothing derives it today.

## D9 — Completions and a machine-readable spec

**Status: Deferred.**

picocli can generate a completion script and expose the whole model, which is
what scopt's `protected` traversal made impossible. Neither is generated today.
Doing it is not a decision so much as work, but it has one consequence worth
recording in advance: a completion script published for a version is a promise
about option names in that version, and the experimental ones would be the
promise most easily broken. D4's naming rule is what makes it possible to leave
them out of a generated script without maintaining a second list.
