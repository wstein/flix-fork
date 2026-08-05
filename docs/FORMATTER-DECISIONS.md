# Formatter Decision Log

Decisions taken while building `flix format` into a working formatter, each with
the evidence behind it and the alternative that was rejected. "Canonical"
throughout means one layout per syntax tree *for what the formatter decides*;
where an ordinary expression breaks is still the author's, which puts the tool in
`gofmt`'s class rather than `dart format`'s (D23).

A decision recorded here is not a preference; it is a claim that can be checked,
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

## D1 — Layout decisions come from tree shape, not from a line width

**Status: Settled**, but on narrower grounds than this entry first claimed.

**What the inherited constraint actually binds.** `Doc` encodes each
single-line/multi-line choice explicitly via `LayoutChoice` and `SetLayout`, which
is what makes `Doc.pretty` a single pass with no backtracking, and there is no
width parameter to consult. That is a constraint on the **rendering algebra**, and
P6 binds us to it.

It does **not** bind `LayoutPlan`. The plan decides breaks by walking the tree,
outside `Doc` entirely, and nothing stops it measuring a rendered width and
emitting a break from it. This entry previously read as though width were
unavailable to us. It is available; we decline it.

**Why we decline it — this is the load-bearing part.** Across the corpus, authors'
break decisions track arity, not width. Pipelines break 0% of the time at one
stage and 40%/71%/80% at two/three/four — a step. The same population sorted by
flat width climbs smoothly from 2.5% under 60 columns to 37% past 120: **a
gradient with no knee**. Every width threshold one could pick disagrees with the
corpus about roughly as much as any other, whereas arity has a place to cut.

So the argument against width is empirical rather than architectural, and it is
weaker than an architectural one would be: it holds for the constructs measured
and says nothing about constructs nobody has measured.

**Rejected:** a Wadler/Leijen `group` combinator selecting flat-or-broken against
the remaining width *inside `Doc`*. That would reintroduce the lookahead the
algebra exists to avoid and would break P6.

**Not rejected, merely unused:** width as a tiebreaker inside `LayoutPlan` for a
construct where arity says nothing. If that is ever proposed, it should be argued
on evidence for that construct, not waved away by citing this entry.

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

Alignment is **produced**, not preserved (D20): the padding is computed from the
group rather than copied from the author, which is what makes two files differing
only in alignment format identically. Groups are bounded so one pathological
pattern cannot push every `=>` off the right margin — a blank line ends a group,
and an arm far wider than its group's narrowest opts out and takes a single
space. That is the `gofmt` model, and it is applied to struct fields and record
type aliases too.

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

## D8 — How far canonicality goes, and the merged test it breaks

**Status: Proposed.** Scope narrowed by D23.

The formatter imposes one layout per syntax tree **for everything it decides**:
spacing, indentation, alignment, `match`, pipelines, Datalog. Two files that
differ in any of those format identically.

It does **not** decide where an ordinary expression breaks — see D23. That is
about 5,000 sites in the corpus, and it puts this tool in `gofmt`'s class rather
than `dart format`'s. Read the rest of this entry with that scope in mind: where
it says the formatter imposes one layout per tree, it means one layout per tree
*modulo the author's break placement*.

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


## D17 — The separator policy decides no-space-versus-one-space, and nothing else

**Status: Proposed.**

The separator policy chooses the gap between two adjacent tokens from the kinds
of those tokens alone, so two files differing only in spacing format identically.
Vertical layout is decided separately (D20, D21); where an ordinary expression
breaks is not decided at all (D23). Its scope is deliberately narrow.

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

## D19 — A file that does not parse is formatted around the part that does not

**Status: Settled.** Supersedes the CLI gate deferred in D4.

`flix format` no longer requires the program to compile. A developer mid-edit has
a broken program most of the time, so a formatter available only on correct code
is unavailable exactly when it is being used.

The parser produces a tree containing `ErrorTree` nodes for a malformed file.
Every declaration whose subtree contains one is reproduced verbatim; the rest are
formatted. The declaration is the unit because the formatter already treats
declarations as independent (D7), and idempotence follows for free: each
declaration is either formatted and stable, or untouched and trivially stable.

The quarantine is applied by the printer rather than by a layout policy, so every
policy inherits it and a future layout rule cannot forget to.

**What this does and does not buy, precisely.** Declaration boundaries come from
the parser, never from a heuristic — no scanning for a keyword at column zero, no
cutting at the error's line. That is the whole safety argument, and it has a
visible cost: when the parser cannot resynchronise, it absorbs the *following*
declarations into the broken one, and those are quarantined too. Given

    def healthy(x:Int32):Int32=x      <- formatted
    def broken(s:String) = match s {  <- quarantined, unclosed brace
    def alsoHealthy(a:Int32):Int32=a  <- quarantined: the parser never saw it

only the first is formatted. In practice this means everything above the edit is
formatted and everything below it may not be, which is the right way round for
someone typing. Widening it means improving parser recovery, not guessing in the
formatter.

**Only parse errors quarantine.** A program that parses but fails to type check
is formatted in full: the tree is well-formed and the formatter does not consult
types (nor may it, since layout must not depend on inference).

**`flix format` exits 0 on a file with syntax errors** and prints nothing about
them. It is not a checker, and a formatter that failed the build on malformed
input would be useless in an editor's format-on-save. `flix check` reports
errors.

**Rejected:** the whole-file bailout — emit the entire input unchanged if any
`ErrorTree` appears anywhere. It contradicts the reason the formatter stops
before the weeder in the first place. Intercepting early buys tolerance of
*weeding* errors, and buys nothing for the actual mid-edit case, which is an
unclosed brace or a half-typed name: those are parse errors, and under a
whole-file bailout the formatter declines exactly when it is most needed.

## D20 — `match` is laid out vertically, and `=>` alignment is produced

**Status: Proposed.**

Two vertical rules are implemented, and only two, because these are the two the
evidence settles outright.

**Every `match` breaks.** One arm per line, indented one unit from the line the
`match` keyword sits on, closing brace back at that indentation. The corpus holds
1,868 `match` expressions and **not one** written inline, at any arity including a
single arm. The rule needs no threshold and contradicts nothing.

**`=>` alignment is computed rather than copied.** `docs/STYLE.md` requires it and
2,047 arms already comply, but preserving an author's padding (D17) left two files
differing only in alignment formatting differently. The padding is now derived: a
group is a run of arms with no blank line between them, and every arm in a group
pads to the widest prefix in it.

An arm more than 24 columns wider than the narrowest in its group opts out, takes a
single space, and does not widen the target — otherwise one long pattern pushes
every other `=>` across the screen. The threshold is a judgement, not a
measurement, and is the weakest part of this entry. An arm carrying a comment
before its `=>` is left alone, since its width is not a property of the code.

**Blank lines survive a break.** A break says two tokens are on different lines,
not how many. This is load-bearing rather than cosmetic: the alignment groups are
*defined* by blank lines, so collapsing one would regroup the arms on the next
pass and formatting would not be idempotent. The corpus caught exactly that —
`IDE.flix` aligned one way on the first pass and another on the second.

**Indentation is measured from the `match` keyword's existing line, not computed.**
The code around a `match` has no layout rule yet and so keeps whatever indentation
it had; indenting arms against a computed base would place them relative to a line
nothing ever moved. This is a knowing compromise on canonicality and it ends when
the enclosing constructs gain rules.

D21 extends this to indentation, pipelines, Datalog, and struct fields.

## D21 — `--canonical` reformats: indentation, pipelines, Datalog, and no preserved padding

**Status: Proposed.** Supersedes the conservatism of D17.

`--canonical` is opt-in, and choosing it is the consent to reformat. Preserving
whatever the author typed "in case it was deliberate" made the flag do nearly
nothing, so the hedges are gone wherever they were caution rather than semantics.

**Indentation is computed for every line**, four spaces per enclosing construct.
It is measured *relative to the line the enclosing construct starts on*, not from
raw tree depth: in `def f(): Int32 = match o {` the arms are nested inside both
the definition and the match, but only one line has been opened, so they indent
once. Counting ancestors would double-indent and drift further with every
construct that fits on a line.

**Pipelines break at two or more stages**, the operator leading each continuation
line. One stage is a function call wearing pipeline syntax and the corpus never
breaks one (493 occurrences, none broken). Beyond that the corpus genuinely
splits — 40% broken at two stages, 71% at three — so a canonical rule has to
pick, and breaking at two is the smaller error and the better reading of why the
language puts the subject last.

**Datalog constraints take one line each**, closing brace on its own line, with
clause bodies left on the head's line. The design document proposes breaking a
body of two or more atoms; its own worked example, taken from the principles
paper, writes exactly that inline, and the corpus agrees. The threshold was
offered "by analogy, not measurement" and the measurement contradicts it.

**Struct fields align their types**, by the same machinery as match arms, and
**runs of two or more spaces are no longer preserved**. D17 kept them because
alignment could not yet be produced; now that match arms and struct fields both
produce it, keeping the rest was only protecting sloppiness —
`def    f( ):Int32     =    42` is not a table.

Two defects here were invisible to every property test and were caught only by
reading a diff of a real library file, which is worth recording as a lesson:

- A declaration's doc comment is folded into its node, so the node starts before
  its own header. Indenting everything after the node's start pushed each
  construct's header in by a level, and since it happened at every nesting depth
  the whole file drifted right — 612 changed lines on `Option.flix`.
- `Parser2.close` folds a *trailing* comment into the preceding declaration, so a
  comment introducing the next declaration was indented as part of the previous
  one's body.

Both produce output that is wrong, and perfectly consistent and idempotent while
being wrong. Fidelity, idempotence and comment-anchor checks all passed
throughout. After the fixes, `Option.flix` changes by 12 lines, every one of them
alignment the file did not previously have.

**Still preserved:** blank lines (they carry paragraph structure and define the
alignment groups), spacing that is semantic (D16, D18), everything inside a
declaration that failed to parse (D19), and the author's choice of *where* to
break a non-pipeline expression — only its indentation is decided.

## D22 — What a diff review found that the property tests could not

**Status: Settled.**

Formatting all 403 corpus files and ranking them by the proportion of lines
changed found five defects. Every one of them passed the fidelity, idempotence
and comment-anchor gates, because each produced output that was wrong *and*
perfectly consistent.

1. **The whole file drifted right.** A declaration's doc comment is folded into
   its node, so a node starts before its own header; indenting everything after
   the node's start pushed every header in by a level at every nesting depth.
   `Option.flix`: 612 changed lines.
2. **A comment introducing the next declaration** was indented into the previous
   one's body, because `Parser2.close` folds a trailing comment into the
   declaration it follows.
3. **`inject l into Link/2` became `Link / 2`.** The slash in a predicate arity is
   part of the name, but a name-slash-number triple is indistinguishable from
   division without the tree.
4. **Inline record types were padded into columns.** `{a = 1, b = 2}` shares a
   line, so aligning it lined nothing up and merely inserted gaps mid-expression.
   Alignment now applies only to fields that start their own line.
5. **Continuations inferred from the preceding token stair-stepped.** Declarations
   do not end in `;`, so a run of `use` lines read as one long continuation and
   moved four columns right per line — 24,000 changed lines became 50,000. The
   rule was narrowed: only a construct rule declares a continuation, and a line
   the plan says nothing about is treated as an item.

Total changed lines across the corpus fell from 50,027 to 23,894 as these were
fixed, and `Option.flix` from 612 to 12 — the remaining 12 being `=>` alignment
the file did not previously have.

The lesson is the one the design document already suspected and this work kept
re-learning: the automated properties prove a formatter *destroys* nothing, and
say nothing at all about whether the result is any good. Reading a diff is not a
final acceptance step to be done once; it is the only instrument that sees this
entire class of defect.

## D23 — Where an ordinary expression breaks is the author's; this is a gofmt, not a dart format

**Status: Settled**, as a description of what exists. The rule itself stays open.

The formatter decides the whitespace *around* a line break — how far the line is
indented, whether it is a continuation — but for an ordinary expression it does
not decide *whether* to break. If the author wrapped a call across three lines,
it stays across three lines; if they wrote it flat, it stays flat.

**Scale.** Roughly 5,000 sites across the 403-file corpus. 44 files have none,
103 have more than twenty, and the worst are `Fs/FileSystem.flix` (536),
`Array.flix` (324) and `List.flix` (264). The count is approximate: separating an
expression wrap from a statement start is itself imprecise, for the same reason
D21 records — a declaration's node begins at its doc comment, so its `def` token
does not look like the start of anything.

**This makes the tool `gofmt`-class.** The design document's own related-work
section says so of Go: *"gofmt preserves author line breaks in composite literals
and argument lists, so two Go files with identical ASTs format differently. The
canonical exemplars are `dart format` and `elm-format`."* By that taxonomy this
is a gofmt. That is a defensible place to be, and it is not what the earlier
entries here claimed.

**Why not close it.**

- *Join everything onto one line, breaking only where a construct rule says.*
  Total, decidable, needs no width — and unreadable. Signature widths already run
  to 330 columns (D.3) under a compactness pressure expressions do not have.
- *Break on width.* The corpus says width is a bad predictor for Flix: break rate
  climbs smoothly from 2.5% under 60 columns to 37% past 120, **with no knee**,
  while arity has one. A width rule would disagree with the corpus at every
  threshold it could pick.
- *And the gates cannot check it.* Five defects this session passed fidelity,
  idempotence and comment-anchor checks while producing wrong output. A change
  rewriting 5,000 break decisions has no automated instrument that can validate
  it; only diff review sees this class, and it does not scale to 5,000.

**Why it costs little.** An author's break is *stable*: it does not move unless
someone edits that expression, so it produces no recurring diff churn. The churn a
formatter exists to remove — drifting indentation, inconsistent spacing,
hand-maintained alignment — is removed.

**Nothing requires closing it.** `docs/STYLE.md` legislates indentation, `=>`
alignment, doc comments and instance ordering, and says nothing about where
expressions wrap. By D0 that silence means the question is unsettled and any rule
we add is a proposal rather than compliance.

**The route forward is per-construct**, where the corpus has a knee: `if`/`else`
leads 662 wrapped lines and is fixed-arity rather than an open expression, so it
is tractable without width. Each such rule is one change with its own diff review.
The open-ended tail stays with the author.

**Dissent, recorded rather than resolved.** A formatter that blesses two layouts
for one tree will drift, and each construct rule added makes the remaining tail
look more arbitrary rather than less. Expect this to be reopened.

## D24 — `if`/`else` and `let` do not get break rules; the evidence refused

**Status: Settled** as a refusal. Reopen only with a measurement, not an argument.

D23 proposed extending per-construct break rules "where the corpus has a knee",
and named `if`/`else` as the obvious next candidate: 662 wrapped lines, and a
fixed-arity construct rather than an open-ended expression. It was rated highly
on that reasoning. The reasoning was wrong.

**`if`/`else` has no knee.** 1,357 occurrences in the corpus: **444 inline
(32.7%), 913 broken (67.3%)**. That is the same shape as the two-stage pipeline
split, and it is not rescued by the arity argument — fixed arity would tell us how
to lay out the branches, but the open question is whether to break *at all*, and
about that the corpus is divided. A blanket "always break" reformats 444 sites
against their authors; "always inline" reformats 913. Either is a coin toss
dressed as a rule.

The pipeline rule survived a similar split only because there was a genuine knee
underneath it: single-stage pipelines are broken 0 times in 493, so
"one inline, two or more broken" matches the corpus at ~85% overall. No analogous
discriminator was found for `if`/`else`.

**`let` was not measured, despite appearances.** The obvious probe reports 98.9%
of `let` bindings as broken, which is an artifact rather than a finding:
`Expr.LetMatch` spans the binding *and the whole remainder of its block*, so
asking whether the node spans lines asks whether the block does, which is nearly
always true. A real measurement has to isolate the bound expression. Until
someone does that, there is no evidence here either way, and 98.9% should not be
quoted as though there were.

**What this costs.** D23's route forward — close the gap construct by construct —
is narrower than it looked. `match`, pipelines, Datalog and the alignment rules
had decisive evidence; the next candidates do not. The remaining ~5,000
author-decided breaks are mostly in constructs where the corpus itself is of two
minds, which is a reason to doubt any rule would be an improvement rather than a
reason to work harder at finding one.

**Rejected:** implementing the rule anyway on the grounds that a canonical
formatter must decide everything. That argument would have justified the width
rule too, and D1 rejects it on the same evidence: a threshold that disagrees with
the corpus roughly half the time is not canonicality, it is a preference imposed
at scale.

## D25 — Canonicality is asserted over reviewed fixtures, not over the corpus

**Status: Settled.** Resolves the open consequence recorded in D8.

D8 recorded that `TestFormatterStability` asserts the corpus is a fixed point of
the formatter, that its stated justification — the corpus *"is maintained in a
canonical formatted form"* — is false, and that no canonical formatter can
therefore pass it. The resolution is to split the property in two rather than to
weaken either half.

- **Non-destructiveness**, over the whole corpus, with the *default* policy:
  `f_verbatim(p(l)) = l`. This is what the test was really checking. It is the
  strongest such statement available, it runs over all 403 files, and it is
  unchanged.
- **Canonicality**, over `main/test/resources/fmt/canonical`, with the
  *canonical* policy. Each fixture is an input and the expected output, and the
  suite asserts both that the input formats to it and that it is a fixed point.

Four properties make the fixtures a gate rather than a mirror:

1. **Regeneration is a separate task.** `./mill flix.updateCanonicalFixtures`
   rewrites them; the suite only ever compares. A test that rewrites its own
   expectation on failure records whatever the formatter currently does.
2. **A coverage assertion.** Every layout rule must be exercised by some fixture,
   keyed on what the rule itself keys on. Without it the goldens rot behind the
   rules: the next rule added gets zero coverage and the suite stays green.
3. **No fixture may be quarantined.** A fixture that fails to parse is reproduced
   verbatim, so it would satisfy every property above while asserting nothing.
   Fixtures need only *parse*, not type check — formatting never required a
   program to compile, and demanding it would exclude the constructs fixtures are
   most useful for.
4. **The seeds are real.** Most inputs are corpus files with their whitespace
   mangled; `datalog` and `structs` format back to the corpus file byte for byte,
   which is a stronger statement about the rules than any hand-written expectation.

**The review is the point, and it paid immediately.** Generating the fixtures for
the first time surfaced two defects that had passed fidelity, idempotence,
comment anchors and every corpus property: `1+2*3-4` printed as `1 + 2 * 3 -4`
(D18's sign rule and the operator rule disagreeing about the same `-`), and a
block comment whose opening line was indented away from its own body (D26). This
is the fifth and sixth time in this subsystem that a layout defect was visible
only to a human reading output.

**Rejected:** making `--canonical` the default now that no test forbids it.
`docs/STYLE.md` still legislates nothing about the layout this mode imposes, so
by D0 every rule here remains a proposal. It stays opt-in until upstream rules.

## D26 — A block comment's interior is not re-indented, and its first line is

**Status: Open.** Recorded so that it is not rediscovered as a surprise.

D10 says block comments are reproduced verbatim. Indentation is decided per gap,
and a block comment is a *single token* whose interior newlines are inside the
token text — so the gap before `/*` is re-indented while the `*` continuation
lines are not, and a comment moved by one level comes out with its body hanging:

```
    /*
   * the opening line moved, this one did not
   */
```

The printer emits every token in order and decides only the whitespace *between*
them (D15), so fixing this properly means rewriting the interior of a token,
which no operation in this architecture can currently do. Three options, none
yet taken:

- **Rewrite the comment's interior.** Correct output; breaks D15's restriction,
  which is the invariant that makes losing or reordering a token impossible.
- **Leave the whole comment where it was**, indenting `/*` to its original column
  rather than to its enclosing construct's. Architecture-preserving and
  self-consistent, but a comment in a re-indented body then sits at the wrong depth.
- **Accept it**, which is the status quo.

The fixture `comments.flix` freezes the current behaviour deliberately, so that
whichever option is taken shows up as a reviewed diff rather than as a surprise.

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

The table above measures the horizontal spacing rules. The suite was re-run after
the `match` layout of D20 landed, over the same sample: still zero fidelity, zero
idempotence and zero anchor failures, with the number of files changed rising in
the repositories that use `match` heavily — 40 of 97 in `flix_game_engine` and 22
of 39 in `frogger`, against 62 and 36 for spacing alone. Vertical layout touches
fewer files than spacing does, and rearranges more in each.

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
