# Tic-Tac-Toe in Flix

A command-line Tic-Tac-Toe game implemented in [Flix](https://flix.dev), featuring both two-player human mode and a Minimax-powered computer AI.

## Features

- **Game Modes**:
  - **Human vs Human**: Two players take turns on the console.
  - **Human vs Computer**: Play against an unbeatable Datalog-powered AI opponent.
- **Datalog-Powered Minimax AI**:
  - Evaluates terminal outcomes with depth weighting (`1000 - 50 * depth`) to favor quick wins and prolonged defense.
  - Uses Flix Datalog facts (`Candidate`), lattice fixpoint rules (`MaxScore`, `MinScore`), and queries to solve for all equally optimal moves.
  - Supports deterministic pseudorandom tie-breaking with an optional random seed or the ambient `Math.Random` algebraic effect.
  - Configurable turn order: choose whether the human (X) or the computer (O) moves first.
- **Pure Game Logic & Algebraic Effects**:
  - `Board` and `Minimax` are pure, effect-free modules for complete unit testability.
  - Console I/O and randomness are isolated through Flix effects (`Interface`, `Math.Random`, `Sys.Console`, `Sys.Exit`).

## Architecture

- `src/Symbol.flix`: `Symbol` enum (`X`, `O`, `Empty`) with `Eq` and `Order` derivations.
- `src/Board.flix`: Immutable board representation, Datalog facts/rules for line analysis (`Line`, `Cell`, `Win`, `FreeCell`), and outcome queries.
- `src/Minimax.flix`: Alpha-beta game search, Datalog lattice solver for optimal candidate moves, and deterministic seeded move selection.
- `src/Opponent.flix`: `Opponent` enum (`Human`, `Computer`).
- `src/Interface.flix`: `Interface` effect definition and console handler.
- `src/Main.flix`: Core game loop and main entry point.
- `test/TestBoard.flix`: Unit tests for board mechanics, win/draw detection, and Datalog outcome queries.
- `test/TestMinimax.flix`: Unit tests for heuristic scoring, forced win/block detection, Datalog best moves queries, deterministic seed tie-breaking, and AI self-play simulations.

## Building and Running

### Run the game
```bash
flix run
```

### Run tests
```bash
flix test
```

### Format source code
```bash
flix format
```
