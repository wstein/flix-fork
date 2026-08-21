# Tic-Tac-Toe in Flix

An interactive 2D graphical Tic-Tac-Toe game implemented in [Flix](https://flix.dev), featuring an unbeatable Datalog/Minimax AI opponent and dynamic canvas rendering powered by Processing Core (`org.processing:core`).

## Features

- **Interactive GUI (Processing Core)**:
  - Hardware-accelerated 2D canvas with mouse interactions.
  - Real-time status banner ("Your turn (X)", "Computer is thinking...", "You win!", "I win!", "Draw!").
  - Crisp vector piece rendering with smooth hover states and a one-click "New Game" restart button.
  - Natural 120ms pacing for computer moves.
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
  - Alpha-beta search with move ordering (`center -> corners -> edges`) and Datalog lattice fixpoint rules (`MaxScore`, `MinScore`) selecting all equally optimal moves.
  - Deterministic pseudo-random seed support for reproducible move selection.
- **Pure Game Logic & Algebraic Effects**:
  - `Board` and `Minimax` are pure, effect-free modules for complete unit testability.
  - Randomness is isolated through Flix effects (`Math.Random`, `Sys.Env`, `Sys.Exit`).

## Architecture

- `src/Symbol.flix`: `Symbol` enum (`X`, `O`, `Empty`) with `Eq` and `Order` derivations.
- `src/Board.flix`: Immutable board representation, the Datalog rules of the game (`Line`, `Cell`, `Win`, `FreeCell`), and fast direct outcome queries.
- `src/Minimax.flix`: Alpha-beta game search and a Datalog lattice solver for optimal candidate moves.
- `src/Gui.flix`: Interactive Processing Core (`PApplet`) GUI mode with real-time canvas rendering, mouse handling, and 120ms AI move pacing.
- `src/Opponent.flix`: `Opponent` enum (`Human`, `Computer`).
- `src/CLIOpts.flix`: Command-line options parser (`Util.GetOpt`) supporting `-h`/`--help` and `-s`/`--seed`.
- `src/Main.flix`: Main application entry point that parses CLI options and launches the GUI.
- `test/TestBoard.flix`: Unit tests for board mechanics, win/draw detection, and Datalog outcome queries.
- `test/TestGui.flix`: Unit tests for GUI coordinate mapping and button click detection.
- `test/TestCLIOpts.flix`: Unit tests for CLI options parsing and validation.
- `test/TestMinimax.flix`: Unit tests for heuristic scoring, forced win/block detection, Datalog best moves queries, deterministic seed tie-breaking, and AI self-play simulations.

## Building and Running

### Run the game
```bash
./flixw run
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
./flixw run --args "--seed 12345"
# or with short flag:
./flixw run --args "-s 12345"
# or via environment variable:
TICTACTOE_SEED=12345 ./flixw run
```

The seed is applied once, at the start of the game, so that successive moves draw
successive values from it. The same seed replays the same game; a different seed plays a
different one. Without a seed, each run is fresh.

### Run tests
```bash
./flixw test
```
