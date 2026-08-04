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

Any subtree the printer has no rule for is reproduced from the source verbatim.
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

Alignment groups are bounded so that one pathological pattern cannot push every
`=>` off the right margin: a blank line ends a group, and an arm whose pattern is
far longer than its neighbours' opts out and takes a single space. This is the
`gofmt` model for struct field alignment.

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
