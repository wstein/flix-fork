# Tic-Tac-Toe in Flix

A command-line Tic-Tac-Toe game implemented in [Flix](https://flix.dev), featuring both two-player human mode and a Minimax-powered computer AI.

## Features

- **Game Modes**:
  - **Human vs Human**: Two players take turns on the console.
  - **Human vs Computer**: Play against an unbeatable Minimax AI opponent.
- **Minimax AI**:
  - Scores terminal outcomes with depth weighting (`1000 - 50 * depth`) to favor quick wins and prolonged defense.
  - Pseudorandom tie-breaking among equally optimal moves using Flix's `Math.Random` algebraic effect.
  - Configurable turn order: choose whether the human (X) or the computer (O) moves first.
- **Pure Game Logic & Algebraic Effects**:
  - `Board` and `Minimax` are pure, effect-free modules for complete unit testability.
  - Console I/O and randomness are isolated through Flix effects (`Interface`, `Math.Random`, `Sys.Console`, `Sys.Exit`).

## Architecture

- `src/Symbol.flix`: `Symbol` enum (`X`, `O`, `Empty`) and helpers.
- `src/Board.flix`: Immutable board representation, win/draw outcome queries, and legal move generation.
- `src/Minimax.flix`: Depth-weighted minimax search and randomized best-move selector.
- `src/Opponent.flix`: `Opponent` enum (`Human`, `Computer`).
- `src/Interface.flix`: `Interface` effect definition and console handler.
- `src/Main.flix`: Core game loop and main entry point.
- `test/TestBoard.flix`: Unit tests for board mechanics and win/draw detection.
- `test/TestMinimax.flix`: Unit tests for heuristic scoring, forced win/block detection, and AI self-play simulations.

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
