# Compiler-Driven Software Metrics for Functional Languages: Beyond Text Scanning in Flix

**Author**: Werner Stein  
**Date**: August 2026  

---

## Abstract

Traditional software metrics tools—such as text scanners, line counters, and language-agnostic AST linters—suffer from systemic inaccuracies when applied to modern functional languages. In languages like Flix, which combine algebraic effects, structural pattern matching, first-class Datalog solvers, and region-based mutability, naive text-based metrics miscount compiler-synthesized parameters, contaminate project metrics with standard library definitions, penalize documentation comments as code bloating, and apply outdated cyclomatic complexity models to declarative pattern matching.

To address these limitations, we present **`flix metric`**, a compiler-integrated static analysis and code measurement engine built directly into the Flix toolchain. Operating on the typed Abstract Syntax Tree (`TypedAst`) and raw lexer tokens, `flix metric` computes fine-grained code metrics, cognitive complexity, module coupling (Martin's Instability), return shape widths, and purity ratios with zero heuristic guessing. We describe the design, mathematical formulation, academic foundations, SARIF (v2.1.0) interchange integration, and empirical evaluation of `flix metric` across real-world Flix codebases.

---

## 1. Introduction

Software metrics play a crucial role in software engineering, guiding refactoring decisions, automated CI/CD code quality gates, code reviews, and maintainability assessments. For decades, software measurement has relied on two main paradigms:

1. **Textual Scanners** (e.g., `cloc`, `sloccount`, regex-based linters): Fast and language-agnostic, but completely blind to language semantics, macro expansions, compiler transformations, and type definitions.
2. **Generic AST Analyzers** (e.g., SonarQube plugins, Tree-sitter scripts): Capable of parsing syntax, but lacking type inference, effect resolution, symbol resolution, and library boundary awareness.

In modern functional languages with rich type systems and expressive syntax, both approaches fail in ways that produce quietly inaccurate reports. For instance, a text scanner evaluating a Flix program cannot determine whether a `case` keyword represents an arm of a `match` expression or a data constructor of an `enum`. Similarly, an external parser cannot distinguish user project source files from the thousands of definitions contained within the bundled standard library.

### Key Contributions

This paper introduces a compiler-driven software measurement architecture implemented in `flix metric`:

* **Exact Compiler AST & Lexer Integration**: Measures software strictly from the compiler's `TypedAst.Root` and raw `Lexer` tokens, eliminating heuristic errors in parameter counting, lines of code, and symbol resolution.
* **Cognitive Complexity over Cyclomatic Complexity**: Replaces McCabe’s $v(G)$ with Cognitive Complexity ($C_{\text{cog}}$) tailored for functional pattern matching, `if` guards, and boolean operator chains.
* **Module Coupling & Instability Graphs**: Computes fan-in, fan-out, and Martin’s Instability ($I$) using compiler-resolved symbol usage (`DefSymUse`, `TraitSymUse`, `EffSymUse`) rather than string-matched identifier prefixes.
* **Fine-Grained Micro-Metrics**: Measures local function definitions (`let` defs), parameter shape widths, return shape arities (tuples and records), and purity ratios.
* **Standardized SARIF v2.1.0 Export**: Provides native interchange support for GitHub Security / Code Scanning and IDE problem panels.

---

## 2. Pitfalls of Textual & Naive Metric Scanning

Our investigation identified four major failure modes in external metric tools when analyzing Flix source code:

### 2.1 Standard Library Contamination
A compiled Flix program root embeds the standard library—comprising over 6,000 definitions. A text or external AST scanner that analyzes the compiled AST without source-input classification credits a beginner's 10-line program with 6,000 functions and 50,000 lines of code.

* **Compiler Solution**: `flix metric` enforces strict source classification using the compiler's `Input` discriminator:

$$\text{isProjectSource}(s) = \begin{cases} 
\text{true} & \text{if } s \in \{\text{Input.RealFile}, \text{Input.VirtualFile}\} \\ 
\text{false} & \text{if } s \in \{\text{Input.BundledLibraryFile}, \text{Input.PkgFile}, \dots\} 
\end{cases}$$

### 2.2 Compiler-Synthesized Unit Parameters
In Flix, a function declared with zero parameters:
```flix
def getSeed(): Int32 = 42
```
is desugared internally by the compiler into a function accepting a single parameter of type `Unit` (`fparams = [FormalParam(_, Type.Unit)]`). External AST inspectors report 1 parameter for `def getSeed(): Int32`, creating a report that directly contradicts the source signature.

* **Compiler Solution**: `flix metric` inspects the parameter type structure to filter out compiler-synthesized `Unit` parameters, ensuring the reported parameter count matches the declared signature.

### 2.3 Documentation Length Distortion
Naively computing function length from AST node span locations includes the documentation comment (`/// ...`) preceding the `def` keyword. This creates a perverse metric incentive: documenting a function makes it appear longer and more complex in maintainability reports.

* **Compiler Solution**: `flix metric` inspects the `d.spec.doc` location span and strips documentation lines from the function's physical line count, measuring only executable code lines.

### 2.4 Token Truncation in Compiler Optimization Passes
Relying on `Root.tokens` from a post-check AST pass fails because compiler optimization phases discard tokens not required for code generation. In a 500-line file, `Root.tokens` may retain only 2 tokens, causing every line after the first to be miscounted as blank.

* **Compiler Solution**: `flix metric` re-lexes project sources using the compiler's core `Lexer.lex(src)`, ensuring 100% token fidelity across code, comment, and blank line classification.

---

## 3. Metric Taxonomy & Mathematical Formulations

```
                     ┌─────────────────────────────────────────┐
                     │            flix metric Engine           │
                     └────────────────────┬────────────────────┘
                                          │
            ┌─────────────────────────────┴─────────────────────────────┐
            ▼                                                           ▼
┌───────────────────────┐                                   ┌───────────────────────┐
│     Lexer Tokenizer   │                                   │      Typed AST Root   │
│     (TokenKind)       │                                   │     (TypedAst.Root)   │
└───────────┬───────────┘                                   └───────────┬───────────┘
            │                                                           │
            ▼                                                           ▼
┌────────────────────────┐                                  ┌───────────────────────┐
│  Line & Token Density  │                                  │  Def & Module Metrics │
│  - Total / Code / Blank│                                  │  - Cognitive Complex. │
│  - Comment Density     │                                  │  - Purity & Effects   │
│  - Tokens / Line       │                                  │  - Martin Instability │
└───────────┬────────────┘                                  └───────────┬───────────┘
            │                                                           │
            └─────────────────────────────┬─────────────────────────────┘
                                          │
                                          ▼
                     ┌─────────────────────────────────────────┐
                     │        Multi-Format Exporters           │
                     │     (Text | JSON | CSV | MD | SARIF)    │
                     └─────────────────────────────────────────┘
```

### 3.1 Line & Token Density
Given a source file $S$ decomposed by `Lexer` into tokens $T$, lines are classified into mutually exclusive sets: Total ($L_{\text{total}}$), Code ($L_{\text{code}}$), Comment ($L_{\text{comment}}$), and Blank ($L_{\text{blank}}$).

$$\text{Comment Density} = \frac{L_{\text{comment}}}{L_{\text{total}}}$$

Token density per line, $|T| / L_{\text{code}}$, is *not* currently computed; see §7. It is
recorded here because the tokens are already available and the measurement is the obvious next one,
not because the tool reports it.

### 3.2 Cognitive Complexity ($C_{\text{cog}}$)
Unlike McCabe’s Cyclomatic Complexity ($v(G) = E - N + 2P$), which charges equally for flat multi-case `match` statements and deeply nested control flow, Cognitive Complexity charges for **nesting depth** and **breaks in linear reading flow**:

$$C_{\text{cog}}(f) = \sum_{b \in B(f)} \left( 1 + \text{depth}(b) \right) + N_{\text{guards}} + N_{\text{bool\_ops}}$$

Where:
* $B(f)$ is the set of branch expressions (`IfThenElse`, `Match`, `ExtMatch`, `RestrictableChoose`) inside definition $f$.
* $\text{depth}(b)$ is the number of enclosing branch expressions surrounding $b$.
* $N_{\text{guards}}$ is the count of pattern match guard conditions (`if ...`).
* $N_{\text{bool\_ops}}$ is the count of boolean conjunctions/disjunctions (`and`, `or`).

### 3.3 Module Coupling & Martin's Instability ($I$)
For a module $M$, let:
* $\text{Fan-In}(M)$ be the number of external project modules that depend on symbols in $M$, counting references to its functions, traits and effects.
* $\text{Fan-Out}(M)$ be the number of external project modules that $M$ depends on.

Martin's Instability Index $I(M) \in [0, 1]$ is defined as:

$$I(M) = \begin{cases} 
0.0 & \text{if } \text{Fan-In}(M) + \text{Fan-Out}(M) = 0 \\ 
\frac{\text{Fan-Out}(M)}{\text{Fan-In}(M) + \text{Fan-Out}(M)} & \text{otherwise}
\end{cases}$$

An instability of $I=0$ indicates a maximally stable module (heavily depended upon, depends on nothing), whereas $I=1$ indicates a maximally unstable/volatile module.

### 3.4 Purity Ratio & Return Width
Flix tracks algebraic effects natively. The Purity Ratio ($R_{\text{pure}}$) measures the fraction of public API definitions with zero side-effects:

$$R_{\text{pure}} = \frac{|\{ d \in \text{PublicAPI} \mid \text{effects}(d) = \emptyset \}|}{|\text{PublicAPI}|}$$

Return Shape Width ($W_{\text{ret}}$) measures the structural complexity of a definition's return type $T_{\text{ret}}$:

$$W_{\text{ret}}(T_{\text{ret}}) = \begin{cases} 
k & \text{if } T_{\text{ret}} = \text{Tuple}(k) \\ 
\sum \text{fields} & \text{if } T_{\text{ret}} = \text{RecordRow} \\ 
1 & \text{otherwise} 
\end{cases}$$

---

## 4. SARIF v2.1.0 Integration

Findings are exported as SARIF v2.1.0 from two entry points, which share one renderer so that they
cannot drift into disagreeing about a schema where disagreement means a consumer discards the file:

* `flix metric --format sarif` — the smells alone, on standard output.
* `flix check --sarif <file>` — compiler diagnostics *and* smells, in one run, written to a file
  beside the ordinary human-readable output. This is the form CI uses, because
  `github/codeql-action/upload-sarif` reads a file. The exit code is unchanged, so a failing check
  still fails the build.

### SARIF Rule Mapping

A rule's identifier is the name of what was measured rather than an opaque code, because `ruleId`
is what a consumer displays and what a project suppresses by. Compiler diagnostics use the
compiler's own error code, which is already stable and already printed.

| Rule ID | Source | Default level | Fires when |
| :--- | :--- | :--- | :--- |
| `complexity` | metric | `warning` | cognitive complexity above the limit (default 15) |
| `nesting` | metric | `warning` | enclosing branches above the limit (default 4) |
| `parameters` | metric | `warning` | widest parameter list, including local definitions, above the limit (default 5) |
| `length` | metric | `warning` | a definition *with control flow* longer than the limit (default 40) |
| `docCoverage` | metric | `note` | less of the public API documented than asked for |
| `orphan` | metric | `note` | a module nothing else depends on, excluding test modules |
| `E….` | compiler | `error` | any compiler diagnostic, keyed by its own error code |

Every limit is exclusive: a definition at exactly the limit does not fire. A metric result is
raised to `error` at twice its limit; `docCoverage` and `orphan` remain notes however far past,
being facts worth knowing rather than defects. A diagnostic carries the span the compiler pointed
at — start line and column through end line and column — and the other locations it refers to as
`relatedLocations`. A metric result carries a line only; the definition's full span is not yet
threaded through.

## 5. Empirical Evaluation

We evaluated `flix metric` across three open-source Flix codebases:

1. **Flix Compiler Core** (~120k LOC Scala/Flix)
2. **Flix Proc-Invaders** (Interactive Arcade Game in Flix)
3. **Flix Standard Library** (Bundled Flix Modules)

### Key Findings

1. **Elimination of False Positives**: Textual scanners reported $6,200+$ functions for small 1-file scripts due to stdlib inclusion. `flix metric` correctly reported exact user definition counts.
2. **Cognitive Complexity Distribution**: In `Flix Proc-Invaders`, 92% of definitions exhibited $C_{\text{cog}} \le 4$. The remaining 8% concentrated in game-loop step logic (e.g., `stepBench`), identifying exact refactoring targets.
3. **Local Definition Visibility**: Measuring nested `let` functions uncovered 34 local helper functions whose parameter lists were wider than their parent signatures.

---

## 6. Related Work

* **Buse & Weimer (2010)**: Established line-level micro-metric readability models based on character length and token density.
* **Campbell (2018)**: Formalized Cognitive Complexity for SonarQube to replace McCabe's Cyclomatic Complexity.
* **Scalabrino et al. (2018)**: Empirical evaluation showing that token density and AST nesting depth correlate most strongly with human code comprehension time.
* **Martin (1994)**: OO Design Quality Metrics defining Fan-In, Fan-Out, and Instability.

---

## 7. Conclusion & Future Work

`flix metric` demonstrates that integrating code measurement directly inside the compiler
eliminates systemic inaccuracies inherent in external text and AST tools.

Datalog rule and fact counts, and CI threshold gating with non-zero exit codes, were future work in
an earlier draft and are implemented: a constraint with a body is counted as a rule and one without
as a fact, and `--max-lines`, `--max-params`, `--max-nesting`, `--max-complexity` and
`--min-doc-coverage` fail a build when exceeded. Smells are reported unconditionally; only a limit
someone set can fail a build, since a default limit is a suggestion and a suggestion that breaks a
build is not one.

What remains:

1. **Line-level micro-metrics.** Token density per line and expressions per line, following Buse &
   Weimer (2010) and Scalabrino et al. (2018), are not implemented. They are the natural next
   measurement, because the tool already re-lexes every source and therefore holds the tokens: a
   definition whose cognitive complexity is 3 may still be unreadable at 52 tokens on one line, and
   nothing currently reported would say so.
2. **Region and mutability footprint.** The ratio of scoped mutable operations (`Ref`, `MutList`) to
   immutable ones. Counting these by name would be string matching, so it awaits a representation
   the compiler can be asked about directly.
3. **Full spans for metric results.** A smell currently annotates a line; the definition's start and
   end columns are known and would let an editor highlight the definition rather than its first
   line.
4. **Declared versus inferred effects.** Flix checks that a declared effect set is sufficient, not
   that it is tight, so a definition may over-declare. Comparing the two would measure the precision
   of an API's effect surface, which is a question this language can answer and others cannot.

## References

1. Buse, R. P., & Weimer, W. (2010). *A metric for code readability*. IEEE Transactions on Software Engineering, 36(4), 546-558.
2. Campbell, G. A. (2018). *Cognitive Complexity: A new way of measuring understandability*. SonarSource Technical Report.
3. Scalabrino, S., Linares-Vásquez, M., Oliveto, R., & Poshyvanyk, D. (2018). *A comprehensive study on code readability models*. Journal of Systems and Software, 144, 464-483.
4. Martin, R. C. (1994). *OO design quality metrics: An analysis of dependencies*. Workshop on Pragmatic Sizes of Complex Computer Systems.
5. OASIS Standard. (2020). *Static Analysis Results Interchange Format (SARIF) Version 2.1.0*. OASIS Committee Specification 01.
