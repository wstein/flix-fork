# Acting on what `flix metric` reports

Instructions for an agent asked to fix the smells in a `flix metric` report.

Read this before changing code. Each smell has one meaning, one or two fixes
that work, and at least one fix that looks right and is not. The metric is
computed from the compiler's own tree, so a number here is a fact about the
code — but a fact is not yet a reason to change it.

## How to get the list

Smells are in every report, in every format. There is no flag to ask for them:
a report that has to be asked for what it already knows is a report someone
will forget to ask.

```sh
flix metric                    # readable, for a person
flix metric --format json      # a "smells" array, for a program
flix metric --format md        # a ranked work plan
flix metric --format sarif     # annotations, for a pull request
```

`--format` is the only way to ask for one of these. The global `--json` does not
select the report: it names one of five formats, which is half of the question
`--format` asks, and a report is easier to script against when one flag decides
it.

Each smell is addressed by a program:

```json
{ "category": "nesting", "subject": "Game.moveMystery",
  "file": "src/Game.flix", "line": 312, "actual": 6.0, "limit": 4.0 }
```

`category` is one of `length`, `parameters`, `nesting`, `complexity`,
`density`, `lineLength`, `docCoverage`, `orphan`. Work from `category`, never
from the prose.

## Reporting is not failing

The limits above are defaults, and a default limit **never fails a build**:

| limit | default | fails the build |
| --- | --- | --- |
| `--max-lines` | 40 | only when given |
| `--max-params` | 5 | only when given |
| `--max-nesting` | 4 | only when given |
| `--max-complexity` | 15 | only when given |
| `--max-line-tokens` | 25 | only when given |
| `--max-line-length` | 100 | only when given |
| `--min-doc-coverage` | *none* | only when given |

`flix metric` exits 1 when a limit **you passed** is exceeded, or when the
project does not compile. Otherwise it exits 0 however many smells it printed.
A suggestion that breaks a build is not a suggestion.

`docCoverage` is the one category with no default, because no round number for
it is defensible. It is reported only when `--min-doc-coverage` is given.

## The rule that governs all of them

**Fix the code, or argue the limit. Never move the number.**

A smell is satisfied by making the code simpler or by deciding the limit was
wrong for this project. It is *not* satisfied by splitting a function at the
line before the cap, renaming a parameter, or wrapping an expression to move a
branch out of the counted range. Those change the measurement and leave the
code as it was, which is worse than the smell, because the next reader now
believes it was addressed.

If a limit is wrong for the project, change the limit and say why:

```sh
flix metric --max-nesting 6
```

## By category

### `nesting` — branches inside branches

**Means:** the deepest chain of enclosing `if` / `match` / `ExtMatch` /
`RestrictableChoose` expressions. Sibling arms do not count: a 46-arm flat
`match` measures 1. So a reported 6 really is six levels deep.

**Do:** replace nested conditionals with a single `match` on a tuple or an
enum; extract the inner branch into a named function; return early where the
language allows.

**Do not:** assume it is a lookup table. That was measured and it is not — if
this fires, something genuinely nests.

### `complexity` — decisions to hold in mind at once

**Means:** each branch costs one plus the number of branches enclosing it, plus
one per `and`/`or` and one per `match` guard. Arms cost nothing, so a wide
`match` is cheap and a deep one is not.

**Do:** the same as `nesting`, and collapse boolean chains into a named
predicate. A guard that repeats across arms is usually a missing case in the
pattern.

**Do not:** convert `and`/`or` into nested `if`s. That lowers nothing and
raises `nesting`.

### `parameters` — the widest parameter list anywhere inside

**Means:** the *widest*, including local definitions. A `def one(tuning, seed)`
whose body threads eight accumulators through a local `loop` reports 8, and the
report says `in a local definition`.

**Do:** group the accumulators into a record with named fields and thread one
value. This is almost always the right fix, and it usually removes a class of
argument-order bug at the same time.

**Do not:** reorder or rename the parameters. Do not split the function so each
half takes four.

### `length` — lines, excluding the doc comment

**Means:** lines of a definition that has control flow. A function with no
branching at all is treated as data and is never reported, however long: a
hundred-line record literal is a table, and splitting it helps nobody.

**Do:** extract a step that has a name worth giving. If no extracted piece
would have a name, the function is probably a table with one `if` in it —
check whether that conditional can move to the caller.

**Do not:** extract halves called `part1` and `part2`.

### `density` — tokens on one line

**Means:** the most tokens on any single line of the definition, counted from
the lexer, comments excluded. Buse and Weimer found reading time degrades
sharply past roughly 25. When a local definition owns the line, the smell is
reported against the local and not its enclosing `def`.

**Do:** break the line — one expression, arm or argument list per line. A
crammed line is usually several statements written as one, and naming the
intermediate values is the fix.

**Do not:** delete a comment to lower the count. Comments are not counted.

### `lineLength` — characters on one line

**Means:** the longest line of the definition, in characters, against 100.

**Do:** wrap it. A line read by scrolling sideways is read twice, and a diff of
it shows one changed line whatever changed in it.

**Do not:** rename identifiers to shorten them. `n` is not an improvement on
`accumulator` bought at 8 characters.

**Note:** `flix format` will do this for you where it can. Run it before
treating a `lineLength` smell as work — and if a line survives formatting, the
formatter has decided it cannot break there, which is a stronger signal than
the count.

### `docCoverage` — public API without a doc comment

**Means:** the fraction of public, non-test definitions carrying `///`, from
the doc the compiler recorded. Reported only when `--min-doc-coverage` asks
for it.

**Do:** write what the function is for and what it returns for edge inputs.
Prefer documenting the least obvious functions first, not the alphabetically
first.

**Do not:** write `/// Returns the result.` on everything. It moves the number
and teaches the next reader that the docs are worthless.

### `orphan` — a module nothing depends on

**Means:** a module with no incoming references that does reference others.
Test modules are excluded, because being reached only by the test runner is
what they are for.

**Do:** check whether it is dead and delete it, or whether it *should* be used
and something is duplicating it. An entry point reached only from `main` may be
a legitimate orphan — say so and move on.

**Do not:** add a reference to silence it.

## What not to look for

Flix rejects unused definitions, parameters, variables, type parameters, enums
and enum cases as **errors** (`E7956` and its family). Dead code cannot exist
in a project that compiles, so do not hunt for it and do not report finding
none.

## Verifying a fix

Every change must leave all three true:

```sh
flix check                    # it still compiles
flix test                     # behaviour is unchanged
flix metric                   # the smell is gone, and no new one appeared
```

The third matters: extracting a function can move a smell rather than remove
it, and lowering `nesting` by raising `parameters` is not progress. Compare the
counts before and after, not just the one being fixed.

If tests do not cover the code being changed, write the test first. A
refactoring justified by a metric and verified by nothing is a behaviour change
with a number attached.
