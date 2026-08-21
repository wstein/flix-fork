///
/// Represents the outcome of a game.
///
pub enum Outcome with Eq, ToString {
    case Win(Symbol),
    case Draw,
    case Ongoing
}

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
    /// Returns `Some` of the updated board if the cell was free, and `None` otherwise.
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
    /// Returns the eight lines that win the game.
    ///
    /// This is the single place the geometry of the board is written down. Both the
    /// Datalog rules below and the direct queries used by the search read it.
    ///
    def lines(): List[(Int32, Int32, Int32)] =
        (0, 1, 2) :: (3, 4, 5) :: (6, 7, 8) ::      // rows
        (0, 3, 6) :: (1, 4, 7) :: (2, 5, 8) ::      // columns
        (0, 4, 8) :: (2, 4, 6) :: Nil               // diagonals

    ///
    /// Returns the rules of tic-tac-toe, as Datalog.
    ///
    /// `Cell(i, sym)` holds the board and `Line(a, b, c)` the winning lines, and two
    /// rules read the state of the game off them: a player wins by owning all three
    /// cells of some line, and a cell is free while it is empty.
    ///
    /// This is the specification of the rules --- it says what winning *is*, not how
    /// to look for it. `TestBoard` checks the direct queries below against it.
    ///
    pub def datalogOutcome(board: Board): Outcome = match board {
        case Board(vec) =>
            let cells = inject Vector.mapWithIndex((i, sym) -> (i, sym), vec) into Cell/2;
            let ls = inject lines() into Line/3;
            let rules = #{
                Win(sym) :- Line(a, b, c), Cell(a, sym), Cell(b, sym), Cell(c, sym), if (sym != Symbol.Empty).

                FreeCell(i) :- Cell(i, sym), if (sym == Symbol.Empty).
            };
            let db = solve cells <+> ls <+> rules;
            let wins = query db select sym from Win(sym);
            let free = query db select i from FreeCell(i);
            if (Vector.memberOf(Symbol.X, wins))
                Outcome.Win(Symbol.X)
            else if (Vector.memberOf(Symbol.O, wins))
                Outcome.Win(Symbol.O)
            else if (Vector.isEmpty(free))
                Outcome.Draw
            else
                Outcome.Ongoing
    }

    ///
    /// Checks if the player with the specified symbol has won the game on the given board vector.
    ///
    def checkWinVec(vec: Vector[Symbol], s: Symbol): Bool =
        // Rows
        (Vector.get(0, vec) == s and Vector.get(1, vec) == s and Vector.get(2, vec) == s) or
        (Vector.get(3, vec) == s and Vector.get(4, vec) == s and Vector.get(5, vec) == s) or
        (Vector.get(6, vec) == s and Vector.get(7, vec) == s and Vector.get(8, vec) == s) or
        // Columns
        (Vector.get(0, vec) == s and Vector.get(3, vec) == s and Vector.get(6, vec) == s) or
        (Vector.get(1, vec) == s and Vector.get(4, vec) == s and Vector.get(7, vec) == s) or
        (Vector.get(2, vec) == s and Vector.get(5, vec) == s and Vector.get(8, vec) == s) or
        // Diagonals
        (Vector.get(0, vec) == s and Vector.get(4, vec) == s and Vector.get(8, vec) == s) or
        (Vector.get(2, vec) == s and Vector.get(4, vec) == s and Vector.get(6, vec) == s)

    ///
    /// Checks if any cell on the board is empty.
    ///
    def hasEmptyCell(vec: Vector[Symbol]): Bool =
        Vector.get(0, vec) == Symbol.Empty or
        Vector.get(1, vec) == Symbol.Empty or
        Vector.get(2, vec) == Symbol.Empty or
        Vector.get(3, vec) == Symbol.Empty or
        Vector.get(4, vec) == Symbol.Empty or
        Vector.get(5, vec) == Symbol.Empty or
        Vector.get(6, vec) == Symbol.Empty or
        Vector.get(7, vec) == Symbol.Empty or
        Vector.get(8, vec) == Symbol.Empty

    ///
    /// Returns a list of indices (0 to 8) of all empty cells on the board.
    ///
    /// The minimax search calls this and `outcome` once per node it visits, which is
    /// tens of thousands of times per move, so both answer the question directly
    /// without intermediate vector allocations.
    ///
    pub def freeCells(board: Board): List[Int32] = match board {
        case Board(vec) =>
            def addIfFree(i, acc) = if (Vector.get(i, vec) == Symbol.Empty) i :: acc else acc;
            addIfFree(0, addIfFree(1, addIfFree(2, addIfFree(3, addIfFree(4, addIfFree(5, addIfFree(6, addIfFree(7, addIfFree(8, Nil)))))))))
    }

    ///
    /// Checks if the player with the specified symbol has won the game on the given board.
    ///
    pub def checkWin(board: Board, symbol: Symbol): Bool = match board {
        case Board(vec) => checkWinVec(vec, symbol)
    }

    ///
    /// Checks if all cells are already filled on the board.
    ///
    pub def boardFilled(board: Board): Bool = match board {
        case Board(vec) => not hasEmptyCell(vec)
    }

    ///
    /// Returns the outcome of the game on the given board.
    ///
    pub def outcome(board: Board): Outcome = match board {
        case Board(vec) =>
            if (checkWinVec(vec, Symbol.X))
                Outcome.Win(Symbol.X)
            else if (checkWinVec(vec, Symbol.O))
                Outcome.Win(Symbol.O)
            else if (hasEmptyCell(vec))
                Outcome.Ongoing
            else
                Outcome.Draw
    }

    ///
    /// Returns a string representation of the board using UTF-8 box drawing characters.
    ///
    pub def toString(board: Board): String = match board {
        case Board(vec) =>
            " ┌───┬───┬───┐\n"
            ++ " │ ${Symbol.toString(Vector.get(0, vec))} │ ${Symbol.toString(Vector.get(1, vec))} │ ${Symbol.toString(Vector.get(2, vec))} │\n"
            ++ " ├───┼───┼───┤\n"
            ++ " │ ${Symbol.toString(Vector.get(3, vec))} │ ${Symbol.toString(Vector.get(4, vec))} │ ${Symbol.toString(Vector.get(5, vec))} │\n"
            ++ " ├───┼───┼───┤\n"
            ++ " │ ${Symbol.toString(Vector.get(6, vec))} │ ${Symbol.toString(Vector.get(7, vec))} │ ${Symbol.toString(Vector.get(8, vec))} │\n"
            ++ " └───┴───┴───┘"
    }
}
