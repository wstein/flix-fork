# Formatter Decision Log

Decisions taken while building `flix format` into a working canonical formatter,
each with the evidence behind it and the alternative that was rejected. A
decision recorded here is not a preference; it is a claim that can be checked,
and several entries below exist because an earlier claim was checked and failed.

Status values are **Settled** (evidence is decisive and in the repository),
**Proposed** (our choice, offered upstream, reversible on maintainer objection),
and **Deferred** (knowingly unresolved, with the blocker named).

Sources referred to throughout:

- `docs/STYLE.md` — the maintainers' written style guide, in this repository.
- The corpus — the standard library plus `examples`, the same set the formatter
  test suites use.
- Madsen, *The Principles of the Flix Programming Language*, Onward! 2022.

---

## D0 — The style guide outranks the principles paper

**Status: Settled.**

`docs/STYLE.md` is maintainer-authored, in-repository, and explicitly extensible:
*"If a PR discovers a new style principle, feel free to add it to this file as
part of the same PR."* Where it speaks, it decides. Where it is silent, the
corpus is evidence and the principles paper generates hypotheses.

This inverts the order a formatter design is usually argued in, and it retires a
question that had been treated as open: `=>` alignment in match arms (D6) is
written down, not inferred.

**Rejected:** deriving layout from the principles paper alone. The paper fixes
the indentation unit and little else about layout; treating it as the primary
authority made two rules wrong that the style guide and corpus both get right.

---

## D1 — Layout decisions come from tree shape, never from a line width

**Status: Settled** (inherited from the merged `Doc` algebra).

`Doc` encodes each single-line/multi-line choice explicitly via `LayoutChoice`
and `SetLayout`, which is what makes `Doc.pretty` a single pass with no
backtracking. There is no width parameter to consult.

**Rejected:** a Wadler/Leijen `group` combinator selecting flat-or-broken against
the remaining width. Adding it would reintroduce the lookahead the algebra was
built to avoid, and it would make every layout decision depend on a constant
nobody has justified.

Supporting evidence, independent of the algebra: across the corpus, authors'
break decisions track arity, not width. Pipelines break 0% of the time at one
stage and 40%/71%/80% at two/three/four, while the same population sorted by
flat width climbs gradually from 2.5% under 60 columns to 37% past 120 — a
gradient with no knee. Width was buying less than it appeared to.

---

## D2 — Indentation is four spaces, and continuations are multiples of four

**Status: Settled.**

`docs/STYLE.md`: *"Indentation is 4 spaces."* The corpus contains no tabs and its
leading-space histogram peaks at 4, 8, 12, 16, 20, 24. The principles paper
derives the same number from keyword morphology.

**Rejected:** aligning continuation lines to an opening delimiter's column. It is
fragile under renaming — changing a function's name reflows its arguments — and
it consumes horizontal space in proportion to the identifier it aligns to.

---

## D3 — The formatter never reorders anything

**Status: Settled.**

No reordering of declarations, `use`/`import` clauses, enum cases, or record
labels. Reordering is a semantic operation wearing layout's clothes.

This has a sharp consequence worth stating, because it looks like a defect:
`docs/STYLE.md` asks that instances appear below their type declaration in the
order `Eq`, `Order`, `ToString`. **The formatter must not enforce that.** It is a
real rule and the formatter is the wrong tool for it — a formatter that
sometimes repairs programs produces output nobody can trust to be inert. That
rule belongs to a linter or a code action.

The same reasoning covers the compiler's `CompanionMustBeFirst` error: on a
malformed input the formatter preserves the order and lets the compiler report.

---

## D4 — The formatter is total: no input makes it refuse or destroy

**Status: Settled** for the mechanism, **Deferred** for the CLI gate.

Any gap the printer has no rule for keeps the whitespace the source had (D15).
This makes the printer total from the first rule onward, and it makes every
layout rule a *delta* against a known-faithful baseline rather than a step into
open water.

The property this buys is worth naming: at every commit, formatting is
non-destructive by construction, because anything not deliberately reformatted
is copied. Coverage grows; correctness does not have to wait for it.

**Deferred:** the CLI formats only when `flix.check()` reports no errors
(`Main.scala`), while the LSP path has no such gate, so editor and command line
already disagree about when formatting is available. Relaxing it is a behavioural
change to a merged command and belongs to its author.

---

## D5 — Comment movement gets its own instrument

**Status: Settled.**

Every other check in the subsystem is blind to a comment moving to the wrong
declaration. The non-destructiveness check compares AST shape and wildcards leaf
values; a byte comparison only helps while output is byte-identical to input;
and a projection-style tree comparison strips comments before comparing, which
is exactly what makes it correct for structural equivalence and useless here.

So comments are checked directly: each comment's *anchor pair* is the ordinal
position of its nearest non-comment neighbour on each side, in the file's
non-comment token stream. Ordinals rather than source positions, because
formatting changes every position by construction while leaving the non-comment
token sequence intact.

**Rejected:** relying on the AST-shape check. It cannot see the failure. A
formatter that relocated a comment into the wrong declaration would pass it
cleanly, and doc comments feed generated API documentation, so the corruption
would be published rather than merely cosmetic.

---

## D6 — Match arms align their `=>`

**Status: Settled.**

`docs/STYLE.md`: *"Pattern matches should align `=>`."* The corpus contains 2,047
aligned arms. This had been treated as the largest open question in the design;
it is not open, and the corpus figure is compliance with a written rule rather
than an emergent practice that might be overruled.

Alignment is padding *within* a chosen layout, expressible with the merged
algebra's `fill`. It does not need a new combinator.

**Rejected:** stripping alignment, as Prettier would. It is simpler to implement
and it contradicts the style guide in writing.

Alignment is currently **preserved rather than produced** (D17): the formatter
keeps the padding an author wrote and does not compute it. Producing it needs the
whole group — every arm of the match — which is the next layout rule to write.
When it is written, groups should be bounded so one pathological pattern cannot
push every `=>` off the right margin: a blank line ends a group, and an arm whose
pattern is far longer than its neighbours' opts out and takes a single space.
That is the `gofmt` model for struct field alignment.

---

## D7 — Alignment never crosses a declaration boundary

**Status: Settled.**

Aligning across top-level declarations would mean that adding one long
declaration reflows unrelated ones, producing diffs attributable to nothing in
the change. Within a declaration the reflow is attributable to the edit that
caused it, which is why D6 is compatible with this.

This is the formatter-level reading of *Declaration Monotonicity*: adding a
declaration should not change the meaning — or here, the rendering — of the
others.

---

## D8 — Canonicality is the point, and it breaks a merged test

**Status: Proposed.**

The formatter imposes one layout per syntax tree: two files that parse to the
same tree format identically, regardless of how they were written. Without this
the tool is a normaliser, and normalisers leave exactly the churn a canonical
formatter exists to remove.

The consequence has to be stated rather than discovered later.
`TestFormatterStability` asserts that the corpus is a fixed point of the
formatter, justified by the claim that the corpus *"is maintained in a canonical
formatted form."* **That claim is false**, and measurement of the corpus refutes
it: parameter lists are inline at 6,927 sites and broken at 152; declaration
bodies are inline at 2,158 and broken at 2,916. The same construct is laid out
both ways.

A canonical formatter therefore cannot satisfy that test, and no implementation
effort will change this — it is a property of the corpus, not of the printer.
The honest resolution is to keep the *aesthetic* gate on material that really is
canonical (golden fixtures the formatter itself produces and a human reviews)
and to report corpus churn as a migration measurement rather than assert it as a
pass/fail property.

**Rejected:** abandoning canonicality to keep the test green. That discards the
only property distinguishing this work from the permissive mode.

**Rejected:** deleting the test. Its *property* is the strongest
non-destructiveness statement available and it should keep guarding whatever
corpus is genuinely canonical.

---

## D9 — Comment attachment is deferred, and the parser is why

**Status: Deferred.**

`Parser2.comments` collects comment runs into a `TreeKind.CommentList` node, and
both `open` and `close` call it. Attachment is therefore symmetric: a comment
before a node's first token and a comment after its last token both land inside
that node, and which node claims a given comment falls out of parser call order
rather than from any trivia model.

That is enough to guarantee comments are never *lost*, which is what D4's
verbatim fallback and D5's anchor check rest on. It is **not** enough to decide
where a comment should be re-emitted once surrounding tokens move. Distinguishing
a trailing comment on the preceding statement from a leading comment on the
following one requires line information the tree does not carry as structure.

So: rules that would move a comment relative to its neighbouring tokens are not
implemented, and the anchor check (D5) fails loudly if one ever does by accident.
Resolving this properly means a trivia model in `Parser2`, which is upstream
work.

**Rejected:** inferring attachment from source line numbers inside the builder.
It would work, and it would make layout depend on the input's original line
breaks, silently destroying canonicality (D8) for every construct near a comment.

---

## D10 — Block comments are reproduced verbatim

**Status: Settled.**

`docs/STYLE.md`: *"Every file must start with a copyright header."* Those headers
are `/* */` blocks whose continuation lines are aligned on a leading `* `, which
is where the corpus's one-space-indented lines come from. Re-indenting them to a
multiple of four would rewrite the licence header of every file that has one.

Doc comments are likewise never rewrapped: they feed generated API documentation
and may contain code blocks and tables whose meaning depends on line structure.

---

## D11 — Never rewrite one member-access sigil into another

**Status: Settled.**

Flix has three: `.` for module qualification and Java member access, `#` for
record labels, and `->` for struct fields. Confusing them is a common authoring
error with a poor diagnostic, which makes "fixing" it tempting. It is a semantic
change and therefore not the formatter's business. Improving the diagnostic is a
compiler task.

---

## D12 — Formatting never alters the interior of a string literal

**Status: Settled.**

Including inside `${...}` interpolations, whose contents the parser may expose as
a sub-tree. Whitespace inside a literal is data. The printer reproduces literal
token text exactly.

---

## D13 — The write path preserves encoding and skips no-op writes

**Status: Settled.**

A file is decoded and re-encoded with the same charset, so formatting never
transcodes as a side effect; and a file whose formatted output equals its
current content is not written at all.

The second is not merely tidiness. Before this change, `flix format` rewrote
every file it was given even though it produced no edits, touching timestamps
across a project to no purpose and defeating incremental builds.

---

## D14 — Tokens are printed through `printableTokens`, never from `Token.text`

**Status: Settled.**

The syntax tree is full-fidelity in the sense that matters — every non-whitespace
character of the source can be recovered from it — but not in the sense a printer
would naively assume. `Lexer.acceptEscapedName` resets the token start past the
`$` of an escaped name, with the comment *"Don't include the $ sign in the
name"*, so in `def $run(...)` and `x.$and(y)` the `$` belongs to **no token**.

A printer that concatenated `Token.text` would emit `def run(...)`, renaming a
definition to a keyword and either changing the program's meaning or breaking it
outright. Nothing in the AST-shape check would catch it, because the weeder
strips the `$` too.

`TokenStream.printableTokens` attributes any non-whitespace character between two
tokens to the token that follows, which restores the `$` and generalises to any
future character the lexer chooses to exclude. The corpus test asserts the
resulting property directly: the printable texts account for every non-whitespace
character of every file.

This was found by measurement, not by reading the lexer — the corpus check failed
on `BigInt.flix` and one interoperability example, and the lexer explained why.
It is the third time in this work that a claim about the implementation was
settled by running something rather than by reasoning about it.

**Rejected:** changing the lexer to include the `$` in the token. The exclusion is
deliberate — the resolved name really is `run`, not `$run` — and a formatter is
the wrong reason to change what a name means.


## D15 — The printer decides inter-token whitespace and nothing else

**Status: Settled.**

`PrettyPrinter` emits every token of the tree in order and chooses only the gap
between each adjacent pair. Layout rules are `Separators` policies choosing gaps;
the baseline policy chooses the gap the source already had, which reproduces the
input exactly.

This turns three properties from rules that could regress into facts that cannot:

- **Nothing is reordered, lost, or duplicated** (D3), because no operation in the
  printer can do any of those things.
- **A comment keeps its neighbours** (D5, D9), because comments are tokens. The
  printer can change which line a comment sits on; it cannot change which
  declaration it belongs to. That is the failure that is otherwise silent, and it
  is now unreachable rather than merely tested for.
- **Every construct is printable from the first commit**, because a construct
  with no rule yet keeps its original gaps. Coverage grows without correctness
  ever being in doubt.

It also removes the reason the deferral in D9 was uncomfortable. Comment
attachment stays unresolved, but it can no longer be got *wrong* — the worst
available outcome is a comment left on an unhelpful line, not one silently moved
into a different declaration.

**Rejected:** building a `Doc` tree for the whole program and rendering it. It is
the conventional design and the merged algebra supports it, but it makes fidelity
a property of rule *coverage*: any construct without a rule has no rendering, so
the printer cannot be correct until it is complete. Emitting a verbatim region
inside a `Doc` does not rescue this, because `Line` and `HardLine` re-indent to
the prevailing nesting level and would silently re-indent the verbatim text —
which is exactly how a licence header (D10) gets rewritten. Gap decisions compose
with untouched regions; `Doc` nesting does not.

The `Doc` algebra remains the right tool for the layout rules themselves, where a
construct is rendered wholly under one policy.


## D16 — Whitespace around `->`, `.`, `@` and backticks is preserved, because it is semantic

**Status: Settled.**

Whitespace is not always insignificant in Flix. Four places where it is not are
all places a spacing rule would naturally touch, and three of the four were found
by running the formatter over real codebases rather than by reading the lexer.

**`->`.** The lexer produces `ArrowThinRTight` for `a->b` and
`ArrowThinRWhitespace` for `a ->b`, `a-> b`, and `a -> b`. The tight form is
struct field access; the spaced form is the function arrow. Inserting a space
around `->` therefore does not restyle an expression, it re-lexes it into a
different construct.

**`.`.** A `.` followed by whitespace is `DotWhiteSpace`, a distinct token that
terminates a Datalog constraint, kept separate so it does not clash with
qualified names. A `.` *preceded* by whitespace is not a token at all — the lexer
reports `FreeDot`. So `Shape.    Rectangle` is an error rather than an unusual
layout.

**`@`.** An `@` followed immediately by a name character lexes as a single
`Annotation` token; with a space it is `At` followed by a name. So closing up
`new Stack @ rc { ... }` into `new Stack @rc { ... }` turns two tokens into one.
This was found by measurement: the region syntax in `SoftRaster.flix` came out
unstable, formatting to one thing on the first pass and another on the second,
because the first pass changed how the second pass lexed it.

**Backticks.** Infix application `x `Int32.mod` 2` is spaced outside the ticks
and tight inside, so the correct gap depends on whether a tick opens or closes —
which cannot be told from one adjacent pair of tokens.

The formatter therefore reproduces the original gap adjacent to these tokens and
never normalises it. "No spaces around `.` and `->`" as a *style* rule would give
the right answer for the wrong reason and would be actively wrong wherever the
corpus writes `x -> x + 1`.

A related mistake, also caught by measurement rather than review: tightness must
not be assumed symmetric. Treating the collection-literal heads as tight turned
`else Set#{ }` into `elseSet#{ }` — welding a keyword onto a name — and would
have turned `= #{` into `=#{`, which lexes as an operator. Only record selection
(`p1#x`) is tight on both sides.

This is a constraint on any layout rule, not a rule itself, and it is the
strongest single argument for the architecture in D15: a printer that rewrites
gaps only where it has a reason to has somewhere safe to stand. One that lays out
every construct from scratch must get this right everywhere at once.


## D17 — The canonical policy decides no-space-versus-one-space, and nothing else

**Status: Proposed.**

`flix format --canonical` chooses the gap between two adjacent tokens from the
kinds of those tokens alone, which is what makes it canonical: two files
differing only in spacing format identically. Its scope is deliberately narrow.

- **Gaps that span a line are left alone.** Indentation, blank lines, and where a
  construct breaks are vertical decisions. They need the enclosing syntax rather
  than two adjacent tokens, and none of them is settled.
- **Runs of two or more spaces are left alone.** They are column alignment, which
  `docs/STYLE.md` requires for match arms (D6) and which the corpus uses for
  struct fields and record type aliases. Collapsing them would reformat thousands
  of sites against a written rule. This is a knowing compromise on canonicality —
  two files differing only in alignment still format differently — and it stands
  until alignment is *produced* rather than preserved, which needs the whole
  group.

So the policy normalises `def add(x:Int32,y:Int32):Int32=x+y` into
`def add(x: Int32, y: Int32): Int32 = x + y` and leaves everything structural
where it was. That is a smaller claim than "canonical formatter", and it is the
part that can be made true and kept true today.


## D18 — A minus sign keeps the spacing it had, and braces are not touched

**Status: Settled.**

**Minus.** `-9223372036854775808i64` is `Int64`'s least value and is representable
only as a negative literal; `- 9223372036854775808i64` is out of range and does
not compile. Nothing tells that apart from ordinary subtraction in a pair of
adjacent tokens, so the source's spacing is kept whenever a minus precedes a
numeric literal: `-1` stays `-1`, `x - 1` stays `x - 1`, and the policy gives up
normalising `x-1`. Correctness outranks the tidier output.

**Braces.** `{` and `}` open blocks, records, record types, Datalog values, and
handler bodies, and the corpus spaces them differently in each. A rule that
cannot tell those apart would reformat every brace in the language on no
evidence, so gaps adjacent to a brace are reproduced. Brace spacing needs the
enclosing construct, which makes it a job for a real layout rule rather than for
this policy.

## Validation against real codebases

`flix format --canonical` was run over nine third-party Flix repositories, in
addition to the standard library and `examples`. Three properties were checked
per file: that the output contains the same non-whitespace characters as the
input, that formatting the output again changes nothing, and that no comment's
anchor moved.

| Repository | sampled | formatted | skipped | changed | fidelity | idempotence | anchors |
|---|---|---|---|---|---|---|---|
| ababup1192/flix_game_engine | 120 | 97 | 23 | 62 | 0 | 0 | 0 |
| ababup1192/yarn_spinner_plugin | 120 | 64 | 56 | 49 | 0 | 0 | 0 |
| ababup1192/frogger | 45 | 39 | 6 | 36 | 0 | 0 | 0 |
| ababup1192/kaidan | 37 | 35 | 2 | 14 | 0 | 0 | 0 |
| ababup1192/dodge_the_creeps_flix | 23 | 17 | 6 | 15 | 0 | 0 | 0 |
| stephentetley/flix-time | 59 | 16 | 43 | 8 | 0 | 0 | 0 |
| stephentetley/sheetio | 67 | 11 | 56 | 7 | 0 | 0 | 0 |
| w0rxbend/compression-flix | 26 | 24 | 2 | 18 | 0 | 0 | 0 |
| w0rxbend/scalachess-flix | 59 | 2 | 57 | 2 | 0 | 0 | 0 |
| **total** | **556** | **305** | **251** | **211** | **0** | **0** | **0** |

Two honest qualifications. The two largest repositories were sampled evenly
rather than exhaustively, because each file costs two full compiles. And 251
files were **skipped**: they produced no syntax tree under this compiler, since
these projects target other Flix versions and the surface syntax has changed
repeatedly. The zero-failure result covers the 305 that did parse. The skip rate
is itself a finding — it is what the `flix format` requirement of a clean
`check()` (D4) costs on real third-party code.

Four defects were found this way and are fixed, each recorded above: the `@`
spacing that re-lexed a region annotation and made formatting unstable (D16),
the asymmetric tightness that welded `else Set#{ }` into `elseSet#{ }` (D16), the
alignment collapse that would have reformatted every aligned match arm against
`docs/STYLE.md` (D17), and the minus sign detached from its literal (D18). All
four were invisible to reasoning about the grammar and obvious within minutes of
running the tool over code nobody here wrote.

**The fourth one also showed that these three properties are not enough.**
Detaching the minus from `-9223372036854775808i64` preserves every non-whitespace
character, is perfectly idempotent, and moves no comment — so it passed all three
external gates cleanly while producing a program that does not compile. What
caught it was the corpus test that *reparses* the output and requires a clean
compile. A formatter's output being well-formed is a property in its own right,
and it is not implied by the output containing the right characters.

`flix format` accepts canonical output unchanged, as P6 requires — though that
holds trivially today, since the default policy reproduces its input. The check
is in the suite so that it starts meaning something the day the default stops
being the identity.

## Open questions

- **Pipeline break threshold.** Breaking at two stages disagrees with ~60% of
  two-stage sites; breaking at three disagrees with 40% of two-stage sites and
  71% of three-stage ones. Two is the smaller error, and 101 sites separate the
  options — small enough to settle by reviewing the diff rather than by argument.
- **Parameter lists.** 97.9% are inline, and the 152 broken ones are spread
  across every arity, so nothing in the tree predicts them. Always-inline matches
  the overwhelming majority and reformats 152 sites; whether any of those are
  readability-critical is a question for diff review.
- **Constructs not yet calibrated:** Datalog clause bodies, enum cases, struct
  fields, and trait and instance members.
