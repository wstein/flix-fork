# Debugging Datalog Programs

This document explains why a Java debugger cannot step through Flix Datalog
rules, what the compiler offers instead, and the invariant that keeps the
tracing machinery free when it is switched off.

## Background: rules are values, not code

Every other Flix construct is compiled to bytecode. Datalog is not. The
`Lowering` phase translates the Datalog subset into `Fixpoint3.Ast.Datalog`
values, which lets the solver be written as an ordinary Flix program rather
than as part of the compiler.

A rule such as

```flix
Path(x, z) :- Path(x, y), Edge(y, z).
```

therefore becomes a nested tree of tag constructors handed to
`Fixpoint3.Solver`. Its variables become `BodyTerm.Var` *values* that identify a
variable at runtime, not JVM local slots.

The consequences are worth stating plainly, because they are easy to mistake
for a bug:

- **A rule has no line numbers.** Compiling a program whose rules span lines 5
  to 8 produces a `LineNumberTable` for the enclosing function with a single
  entry, on the function's `def` line. There is no bytecode for a rule, so
  there is nothing to attach a breakpoint to.
- **Solving happens somewhere else.** A nine-line program with two rules
  generates roughly 2500 classes, of which three come from the user's file. The
  rest are the solver. Stepping into a query lands in `Fixpoint3/Interpreter.flix`,
  which is correct behaviour and no help at all when the question is "why did I
  get this fact?".

The value a query *returns* is an ordinary Flix value and inspects normally in a
debugger. It is only the derivation that is out of reach.

## Tracing the solver

Since the rules cannot be stepped through, the solver reports on itself. Pass
`--Xdatalog-debug` with any combination of:

| Choice  | Reports                                                        |
|---------|----------------------------------------------------------------|
| `rules` | the Datalog program as the solver sees it, after lowering       |
| `facts` | the input facts and the minimal model                           |
| `ram`   | the relation algebra machine after each phase, and the indexes  |

For example:

```bash
flix --Xdatalog-debug=rules,facts Main.flix
```

The choices are independent because they answer different questions. `rules`
and `facts` describe *what the program means* and are what you want when a
query returns the wrong answer. `ram` describes *how the solver executes it* and
is intended for people working on the solver itself. The split matters for
volume: on a two-rule program the full trace is 271 lines, of which `ram`
accounts for 238.

The trace goes to standard output. To write it to a file instead, set
`Fixpoint3.Options.enableDebugToFile` and `Fixpoint3.Options.debugFileName`.

## Asking why a fact was derived

A trace shows the whole computation. When the question is narrower -- why does
this one fact hold? -- Flix answers it directly with `psolve` and `pquery`,
which reconstruct a fact's provenance as ordinary values:

```flix
let db = #{
    A(1). A(2). A(3).
    B(1). B(2). B(3).
    R(x) :- A(x), B(x), B(x), A(x).
};
let pm = psolve db;
let result = pquery pm select R(1) with {A, B};
// result is Vector#{A(1), B(1), B(1), A(1)} -- the facts that derived R(1)
```

The results are extensible variants, matched with `ematch`. This is usually a
better tool than a trace: it names real predicates, carries real values, and
scales to programs whose full trace would be unreadable.

## Invariant: the tracing switches must be compile-time constants

Each trace is guarded by a function in `Fixpoint3.Options`:

```flix
pub def enableDebugRules(): Bool = false
```

These must stay literal constants. The optimizer folds the constant and then
eliminates the guarded code, so a program compiled without `--Xdatalog-debug`
carries no tracing code at all -- not a disabled branch, but nothing. That is
what makes the feature free for everyone who does not use it, and it is easy to
verify: with the flag absent, no generated class contains the tracing code.

The invariant has a direct consequence. **The switches cannot be read at
runtime.** There is no environment variable or system property to set, because
when tracing is off the code that would consult it does not exist. Turning a
trace on is necessarily a compile-time decision.

The `DatalogDebugging` phase implements that decision. It runs before the
optimizer and rewrites the body of each requested switch to `true`, after which
constant folding retains the guarded code instead of deleting it. Anything that
reads these switches must therefore keep them constant-valued; making one depend
on runtime state would silently impose the tracing code on every Flix program.
