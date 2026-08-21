# Tic-Tac-Toe in Flix

A command-line and graphical Tic-Tac-Toe game implemented in [Flix](https://flix.dev), featuring two-player human mode, an unbeatable Datalog/Minimax AI opponent, and an interactive 2D GUI powered by Processing Core (`processing.core`).

## Features

- **Interface Modes**:
  - **Console (Terminal)**: Interactive CLI with UTF-8 box-drawing grid rendering (`┌───┬───┬───┐`).
  - **GUI (Processing Core)**: Hardware-accelerated 2D canvas with mouse interactions, real-time status banners, piece rendering, and one-click game restart.
- **Game Modes**:
  - **Human vs Human**: Two players take turns.
  - **Human vs Computer**: Play against an unbeatable Datalog-powered AI opponent.
- **The rules of the game, as Datalog**:
  - `Board.datalogOutcome` states what winning *is*, rather than how to look for it:
    `Line(a, b, c)` lists the eight winning lines, `Cell(i, sym)` holds the board, and
    one rule joins them --- `Win(sym) :- Line(a, b, c), Cell(a, sym), Cell(b, sym), Cell(c, sym), if (sym != Symbol.Empty).`
  - `Board.outcome` answers the same question directly, because the search asks it tens
    of thousands of times per move. `TestBoard.testOutcomeAgreesWithDatalogSpec` checks the
    two against each other on every position a game can reach, so the fast path cannot
    drift from the specification.
- **Datalog-Powered Minimax AI**:
  - Evaluates terminal outcomes with depth weighting (`1000 - 50 * depth`) to favor quick wins and prolonged defense.
  - Alpha-beta search, with Datalog lattice fixpoint rules (`MaxScore`, `MinScore`) selecting all equally optimal moves.
  - Configurable turn order: choose whether the human (X) or the computer (O) moves first.
- **Pure Game Logic & Algebraic Effects**:
  - `Board` and `Minimax` are pure, effect-free modules for complete unit testability.
  - Console I/O and randomness are isolated through Flix effects (`Interface`, `Math.Random`, `Sys.Console`, `Sys.Env`, `Sys.Exit`).

## Architecture

- `src/Symbol.flix`: `Symbol` enum (`X`, `O`, `Empty`) with `Eq` and `Order` derivations.
- `src/Board.flix`: Immutable board representation, the Datalog rules of the game (`Line`, `Cell`, `Win`, `FreeCell`), the direct outcome queries used by the search, and the UTF-8 box-drawing board renderer.
- `src/Minimax.flix`: Alpha-beta game search and a Datalog lattice solver for the optimal candidate moves.
- `src/Gui.flix`: Interactive Processing Core (`PApplet`) GUI mode with real-time canvas rendering and mouse click handling.
- `src/Opponent.flix`: `Opponent` enum (`Human`, `Computer`).
- `src/Interface.flix`: `Interface` effect definition, `Mode` enum, and console handler.
- `src/Main.flix`: Core game loop, mode selector, and main entry point.
- `test/TestBoard.flix`: Unit tests for board mechanics, win/draw detection, Datalog outcome queries, and UTF-8 box-drawing output.
- `test/TestGui.flix`: Unit tests for GUI coordinate mapping and button click detection.
- `src/CLIOpts.flix`: Command-line options parser (`Util.GetOpt`) supporting `--console`, `-h`/`--help`, and `-s`/`--seed`.
- `test/TestCLIOpts.flix`: Unit tests for CLI options parsing and validation.
- `test/TestMinimax.flix`: Unit tests for heuristic scoring, forced win/block detection, Datalog best moves queries, deterministic seed tie-breaking, and AI self-play simulations.

## Building and Running

### Run the game (GUI Mode by default)
```bash
./flixw run
```

### Run in Console (Terminal) Mode
```bash
./flixw run --args "--console"
```

### Display Help / Usage
```bash
./flixw run --args "--help"
```

### Replay a game with a deterministic seed

When several moves are equally good --- most obviously the opening, where all nine are ---
the computer draws one from the `Random` effect. Seeding that effect makes a whole game
reproducible:

```bash
./flixw run --args "--console -s 12345"
# or via environment variable:
TICTACTOE_SEED=12345 ./flixw run --args "--console"
```

The seed is applied once, at the start of the game, so that successive moves draw
successive values from it. The same seed replays the same game; a different seed plays a
different one. Without a seed, each run is fresh.

### Run tests
```bash
flix test
```

### Format source code
```bash
flix format
```
