# Compile-Time Sequential Execution Mode for Flix Fixpoint3

This document describes the design, configuration, and verification of the compile-time selectable sequential execution mode for the Flix compiler and its bundled `Fixpoint3` Datalog/RAM solver library.

---

## 1. Overview and Motivation

Flix's `Fixpoint3` solver executes a lowered relational-algebra program (RAM). By default, its execution strategy utilizes concurrency:
- Statement parallelism via virtual thread spawning (`RamStmt.Par`).
- Search parallelism via parallel B+ tree traversals (`BPlusTree.parForEach`).
- Region-level synchronization and per-thread search context cloning (`copyCtx`).

In single-threaded, embedded, deterministic, or resource-constrained environments, virtual thread creation and concurrent tree traversals introduce unnecessary overhead or incompatibility.

A naive runtime boolean flag inside the interpreter is insufficient because both parallel and sequential code branches remain statically reachable, preventing tree shaking and dead-code elimination.

This feature implements a **compile-time selectable sequential mode** that guarantees:
1. **Semantic Parity**: Equivalent minimal-model stratified Datalog and lattice fixpoint computation results.
2. **Complete Parallel Path Erasure**: The parallel interpreter family (`evalStmtParallel`, `evalOpParallel`, `parEvalSearchOrQueryOp`, `parEvalSearchOrQueryOpLattice`, `copyCtx`, and `BPlusTree.parForEach`) is entirely pruned by Flix's inliner and tree-shaker passes.
3. **Zero Context Cloning**: Sequential evaluation shares and mutates execution environments without copying.

---

## 2. Compiler Options and CLI

### CLI Flag
The compiler accepts the experimental flag:
```bash
flix compile --Xdatalog-execution=parallel|sequential <files>
```

- `parallel` (default): Compiles Fixpoint3 with full parallel statement and search capabilities.
- `sequential`: Rewrites the Fixpoint3 configuration constant to `false`, causing the optimizer to erase all parallel execution routines.

### Compiler API Options
In Scala:
```scala
import ca.uwaterloo.flix.util.{DatalogExecution, Options}

val options = Options.Default.copy(
  datalogExecution = DatalogExecution.Sequential // or DatalogExecution.Parallel
)
```

---

## 3. Architecture and Implementation

### Phase Pipeline Integration (`DatalogExecutionMode`)
1. **Flix Standard Library Stub**: `Fixpoint3.Options.enableParallelExecution(): Bool` returns `true` by default in Flix source.
2. **AST Rewrite Phase**: When `datalogExecution == DatalogExecution.Sequential`, the compiler phase `DatalogExecutionMode` intercepts `TypedAst.Root` and rewrites `Fixpoint3.Options.enableParallelExecution`'s body to `Constant.Bool(false)`.
3. **Dispatch & Inlining**: In `Fixpoint3.Interpreter.interpretWithDatabase`:
   ```flix
   if (Options.enableParallelExecution())
       evalStmtParallel(rc, ctx, parLevel(), stmt)
   else
       evalStmtSequential(rc, ctx, stmt)
   ```
4. **Optimization and Tree Shaking**:
   - `Optimizer` / `Inliner` inlines the constant condition `if (false)` and eliminates the dead branch.
   - `TreeShaker1` and `TreeShaker2` strip `evalStmtParallel`, `evalOpParallel`, `parEvalSearchOrQueryOp`, `copyCtx`, and `BPlusTree.parForEach`.
5. **Incremental Cache Invalidation**: Switching `datalogExecution` on a `Flix` compiler instance automatically invalidates AST caches to force recompilation of solver paths.

---

## 4. Interpreter Split

The `Fixpoint3.Interpreter` module is structured into two independent evaluator families:

### Parallel Evaluator Family
- `evalStmtParallel(rc, ctx, parLevel, stmt)`: Handles `RamStmt.Par` by spawning virtual threads in a sub-region with `copyCtx`.
- `evalOpParallel(rc, ctx, parLevel, op)`: Dispatches to `BPlusTree.parForEach` when `parLevel > 0`.
- `parEvalSearchOrQueryOp` & `parEvalSearchOrQueryOpLattice`: Clones `ctx` for worker threads.

### Sequential Evaluator Family
- `evalStmtSequential(rc, ctx, stmt)`: Evaluates `RamStmt.Par` as a deterministic sequential loop (`Vector.forEach(evalStmtSequential(rc, ctx), stmts)`), without spawning regions or threads.
- `evalOpSequential(rc, ctx, op)`: Uses standard sequential `BPlusTree.forEach`.
- `evalSearchOrQueryOpSequential` & `evalSearchOrQueryOpLatticeSequential`: Operates directly on the shared context with zero allocations for context cloning.

---

## 5. Verification & Test Coverage

The implementation includes comprehensive automated test suites:

1. **Option Plumbing & AST Rewrite** (`TestDatalogExecutionMode.scala`):
   - CLI option parsing (`--Xdatalog-execution=parallel|sequential`, invalid value rejection).
   - Constant AST replacement verification under both modes.
   - Incremental compiler cache invalidation upon mode switching.

2. **Semantic Parity** (`TestDatalogExecutionParity.scala` & `CompilerSequentialSuite.scala`):
   - Recursive transitive closures.
   - Multiple index merge groups (`RamStmt.Par`).
   - Lattice relations and least-upper-bound computations.
   - Stratified negation.
   - Provenance queries and join-with-existing-model solving.
   - Full Flix test suite (314 tests) execution under sequential mode.

3. **Bytecode Reachability & Erasure** (`TestDatalogReachability.scala`):
   - Bytecode inspection verifying zero references to `Thread.startVirtualThread`, `BPlusTree.parForEach`, `CyclicBarrier`, `parMapWithKey`, or `parExists` in sequential builds.
