///
/// Enum representing the board, backed by an immutable vector.
///

enum Board(Vector[Symbol])

mod Board {

    ///
    /// Returns a new, empty tic-tac-toe board.
    ///
    pub def newBoard(): Board = {
        Board(Vector.repeat(9, Symbol.Empty))
    }

    ///
    /// Makes a move on the board at the specified index with the given symbol.
    ///
    /// Returns the updated board if the move is valid, otherwise returns the original board.
    ///
    pub def makeMove(board: Board, symbol: Symbol, index: Int32): Option[Board]  = match board {
        case Board(vec) => {
            if (Vector.get(index, vec) == Symbol.Empty) {
                let newBoard = Board(Vector.update(index, symbol, vec));
                Some(newBoard)
            }
            else {
               None
            }
        }
    }

    ///
    /// Checks if the player with the specified symbol has won the game on the given board.
    ///
    pub def checkWin(board: Board, symbol: Symbol): Bool = match board {
        case Board(vec) => {
            let patterns = (0, 1, 2) :: (3, 4, 5) :: (6, 7, 8) :: (0, 3, 6) :: (1, 4, 7) :: (2, 5, 8) :: (0, 4, 8) :: (2, 4, 6) :: Nil;
            List.exists((pattern: (Int32, Int32, Int32)) -> {
                    match pattern {
                        case (a, b, c) =>
                            (Vector.get(a, vec) == symbol)
                            and (Vector.get(b, vec) == symbol)
                            and (Vector.get(c, vec) == symbol)
                    }
            }, patterns)
        }
    }

    ///
    /// Checks if all cells are already filled on the board.
    ///
    pub def boardFilled(board: Board): Bool = match board {
        case Board(vec) =>
            Vector.forAll(sym -> sym != Symbol.Empty, vec)
    }

    ///
    /// Returns the occupied cells of the board as `(index, symbol)` pairs.
    ///
    /// This is the board as seen by the machine player, which consumes it as Datalog facts.
    ///
    pub def occupiedCells(board: Board): Vector[(Int32, Symbol)] = match board {
        case Board(vec) =>
            vec
                |> Vector.mapWithIndex((i, sym) -> (i, sym))
                |> Vector.filter(match (_, sym) -> sym != Symbol.Empty)
    }

    ///
    /// Draws a 3x3 grid of already-rendered cell contents, using box-drawing characters.
    ///
    /// ```
    /// ┌───┬───┬───┐
    /// │ X │ O │ X │
    /// ├───┼───┼───┤
    /// │ O │ X │ O │
    /// ├───┼───┼───┤
    /// │ X │ O │ X │
    /// └───┴───┴───┘
    /// ```
    ///
    def grid(cells: Vector[String]): String =
        let row = i -> "│ ${Vector.get(i, cells)} │ ${Vector.get(i + 1, cells)} │ ${Vector.get(i + 2, cells)} │";
        String.unlines(
                   "┌───┬───┬───┐"
            :: row(0)
            ::     "├───┼───┼───┤"
            :: row(3)
            ::     "├───┼───┼───┤"
            :: row(6)
            ::     "└───┴───┴───┘"
            :: Nil
        )

    ///
    /// Returns a string representation of the board.
    ///
    pub def toString(board: Board): String = match board {
        case Board(vec) => grid(Vector.map(Symbol.toString, vec))
    }

    ///
    /// Returns a grid showing how the cells are numbered, for the instructions.
    ///
    pub def legend(): String =
        grid(Vector.range(0, 9) |> Vector.map(Int32.toString))
}
