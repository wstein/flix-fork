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
    /// Returns the rules that describe how a tic-tac-toe position is won.
    ///
    /// `Line` enumerates the eight lines that win the game, `Cell` holds the board
    /// itself, and the two rules read the state of the game off them: a player wins
    /// by owning all three cells of some line, and a cell is free while it is empty.
    ///
    def rules(): #{
        Cell(Int32, Symbol),
        Line(Int32, Int32, Int32),
        Win(Symbol),
        FreeCell(Int32) | r
    } = #{
        Line(0, 1, 2). Line(3, 4, 5). Line(6, 7, 8).    // rows
        Line(0, 3, 6). Line(1, 4, 7). Line(2, 5, 8).    // columns
        Line(0, 4, 8). Line(2, 4, 6).                   // diagonals

        Win(sym) :- Line(a, b, c), Cell(a, sym), Cell(b, sym), Cell(c, sym), if (sym != Symbol.Empty).

        FreeCell(i) :- Cell(i, sym), if (sym == Symbol.Empty).
    }

    ///
    /// Returns the board as `Cell` facts, one per position.
    ///
    def facts(board: Board): #{ Cell(Int32, Symbol) | r } = match board {
        case Board(vec) => inject Vector.mapWithIndex((i, sym) -> (i, sym), vec) into Cell/2
    }

    ///
    /// Returns the solved Datalog database for the given board.
    ///
    /// Every query below reads from this one database, so the board is interpreted in
    /// exactly one place.
    ///
    def solved(board: Board): #{
        Cell(Int32, Symbol),
        Line(Int32, Int32, Int32),
        Win(Symbol),
        FreeCell(Int32) | r
    } = solve facts(board) <+> rules()

    ///
    /// Returns a list of indices (0 to 8) of all empty cells on the board.
    ///
    pub def freeCells(board: Board): List[Int32] =
        query solved(board) select i from FreeCell(i) |> Vector.toList

    ///
    /// Checks if the player with the specified symbol has won the game on the given board.
    ///
    pub def checkWin(board: Board, symbol: Symbol): Bool =
        query solved(board) select sym from Win(sym) |> Vector.memberOf(symbol)

    ///
    /// Checks if all cells are already filled on the board.
    ///
    pub def boardFilled(board: Board): Bool =
        query solved(board) select i from FreeCell(i) |> Vector.isEmpty

    ///
    /// Returns the outcome of the game on the given board.
    ///
    pub def outcome(board: Board): Outcome = {
        let db = solved(board);
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
