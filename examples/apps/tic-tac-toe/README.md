# Tic-Tac-Toe in Flix

A command-line and graphical Tic-Tac-Toe game implemented in [Flix](https://flix.dev), featuring two-player human mode, an unbeatable Datalog/Minimax AI opponent, and an interactive 2D GUI powered by Processing Core (`processing.core`).

## Features

- **Interface Modes**:
  - **Console (Terminal)**: Interactive CLI with UTF-8 box-drawing grid rendering (`┌───┬───┬───┐`).
  - **GUI (Processing Core)**: Hardware-accelerated 2D canvas with mouse interactions, real-time status banners, piece rendering, and one-click game restart.
- **Game Modes**:
  - **Human vs Human**: Two players take turns.
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
- `src/Board.flix`: Immutable board representation, Datalog facts/rules for line analysis (`Line`, `Cell`, `Win`, `FreeCell`), outcome queries, and UTF-8 box-drawing board renderer.
- `src/Minimax.flix`: Alpha-beta game search, Datalog lattice solver for optimal candidate moves, and deterministic seeded move selection.
- `src/Gui.flix`: Interactive Processing Core (`PApplet`) GUI mode with real-time canvas rendering and mouse click handling.
- `src/Opponent.flix`: `Opponent` enum (`Human`, `Computer`).
- `src/Interface.flix`: `Interface` effect definition, `Mode` enum, and console handler.
- `src/Main.flix`: Core game loop, mode selector, and main entry point.
- `test/TestBoard.flix`: Unit tests for board mechanics, win/draw detection, Datalog outcome queries, and UTF-8 box-drawing output.
- `test/TestGui.flix`: Unit tests for GUI coordinate mapping and button click detection.
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
