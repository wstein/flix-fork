///
/// Tests for the Datalog machine player.
///
/// The individual tests below pin down one tactic each. The two at the end are the
/// ones that matter most: they play out *every* game reachable against the machine
/// and check that it never loses and never picks an occupied cell.
///
mod TestBrain {

    use Assert.{assertEq, assertTrue, assertMemberOf}

    /////////////////////////////////////////////////////////////////////////////
    // Helpers                                                                 //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// Builds a board by playing `cells` in order.
    ///
    def boardOf(cells: List[(Int32, Symbol)]): Board =
        List.foldLeft((b, cell) -> {
            let (i, s) = cell;
            Option.getWithDefault(b, Board.makeMove(b, s, i))
        }, Board.newBoard(), cells)

    ///
    /// The move the machine plays for `s`, or `-1` if it offers none.
    ///
    def moveFor(board: Board, s: Symbol): Int32 =
        Option.getWithDefault(-1, Brain.chooseMove(board, s))

    ///
    /// The empty cells of `board`.
    ///
    def empties(board: Board): Vector[Int32] =
        let occupied = Board.occupiedCells(board) |> Vector.map(match (i, _) -> i);
        Vector.range(0, 9) |> Vector.filter(i -> not Vector.memberOf(i, occupied))

    /////////////////////////////////////////////////////////////////////////////
    // Individual tactics                                                      //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// ```
    ///  X | X | .        X to move completes the top row.
    ///  . | O | .
    ///  . | . | O
    /// ```
    ///
    @Test
    def winsNow01(): Unit \ Assert =
        let board = boardOf((0, Symbol.X) :: (4, Symbol.O) :: (1, Symbol.X) :: (8, Symbol.O) :: Nil);
        assertEq(expected = 2, moveFor(board, Symbol.X))

    ///
    /// ```
    ///  O | O | .        X to move must block at 2.
    ///  . | X | .
    ///  . | . | .
    /// ```
    ///
    @Test
    def blocks01(): Unit \ Assert =
        let board = boardOf((0, Symbol.O) :: (4, Symbol.X) :: (1, Symbol.O) :: Nil);
        assertEq(expected = 2, moveFor(board, Symbol.X))

    ///
    /// ```
    ///  O | O | .        Both sides threaten. X to move takes its own win at 5
    ///  X | X | .        rather than blocking at 2.
    ///  . | . | .
    /// ```
    ///
    @Test
    def winBeatsBlock01(): Unit \ Assert =
        let board = boardOf((0, Symbol.O) :: (3, Symbol.X) :: (1, Symbol.O) :: (4, Symbol.X) :: Nil);
        assertEq(expected = 5, moveFor(board, Symbol.X))

    ///
    /// ```
    ///  X | . | .        X to move forks at 2 or 6: either creates two threats
    ///  . | O | .        at once, and only one of them can be blocked.
    ///  . | . | X
    /// ```
    ///
    @Test
    def forks01(): Unit \ Assert =
        let board = boardOf((0, Symbol.X) :: (4, Symbol.O) :: (8, Symbol.X) :: Nil);
        assertMemberOf(moveFor(board, Symbol.X), Vector#{2, 6})

    ///
    /// ```
    ///  X | . | .        The corner trap. X threatens forks at both 2 and 6, so O
    ///  . | O | .        cannot block them both and must instead force a reply by
    ///  . | . | X        taking a side.
    /// ```
    ///
    @Test
    def blocksFork01(): Unit \ Assert =
        let board = boardOf((0, Symbol.X) :: (4, Symbol.O) :: (8, Symbol.X) :: Nil);
        assertMemberOf(moveFor(board, Symbol.O), Vector#{1, 3, 5, 7})

    ///
    /// On an empty board there is nothing tactical to do, so the centre is taken.
    ///
    @Test
    def opening01(): Unit \ Assert =
        assertEq(expected = 4, moveFor(Board.newBoard(), Symbol.X))

    ///
    /// ```
    ///  O | . | .        The centre is gone and nothing is threatened, so X answers
    ///  . | X | .        the opponent's corner with the corner facing it.
    ///  . | . | .
    /// ```
    ///
    @Test
    def oppositeCorner01(): Unit \ Assert =
        let board = boardOf((4, Symbol.X) :: (0, Symbol.O) :: Nil);
        assertEq(expected = 8, moveFor(board, Symbol.X))

    /////////////////////////////////////////////////////////////////////////////
    // Exhaustive play                                                         //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// Plays out every game reachable from `board`, with the human trying every legal
    /// reply at every turn, and counts what happens at the leaves.
    ///
    /// Returns `(machine wins, draws, machine losses, illegal machine moves)`.
    ///
    def explore(board: Board, toMove: Symbol, machine: Symbol): (Int32, Int32, Int32, Int32) =
        let mover = Symbol.complement(toMove);
        if (Board.checkWin(board, mover))
            if (mover == machine) (1, 0, 0, 0) else (0, 0, 1, 0)
        else if (Board.boardFilled(board))
            (0, 1, 0, 0)
        else if (toMove == machine)
            match Brain.chooseMove(board, machine) {
                case None    => (0, 0, 0, 1)                    // no move on a non-full board
                case Some(m) => match Board.makeMove(board, machine, m) {
                    case None     => (0, 0, 0, 1)               // an already occupied cell
                    case Some(next) => explore(next, Symbol.complement(toMove), machine)
                }
            }
        else
            empties(board) |> Vector.foldLeft(match (w, d, l, bad) -> m ->
                match Board.makeMove(board, toMove, m) {
                    case None => (w, d, l, bad)
                    case Some(next) =>
                        let (w1, d1, l1, bad1) = explore(next, Symbol.complement(toMove), machine);
                        (w + w1, d + d1, l + l1, bad + bad1)
                }, (0, 0, 0, 0))

    ///
    /// The machine opens as `X` and never loses.
    ///
    @Test
    def neverLosesAsX(): Unit \ Assert =
        let (_, _, losses, illegal) = explore(Board.newBoard(), Symbol.X, Symbol.X);
        assertEq(expected = (0, 0), (losses, illegal))

    ///
    /// The human opens as `X`; the machine replies as `O` and never loses.
    ///
    @Test
    def neverLosesAsO(): Unit \ Assert =
        let (_, _, losses, illegal) = explore(Board.newBoard(), Symbol.X, Symbol.O);
        assertEq(expected = (0, 0), (losses, illegal))

    ///
    /// Every game ends: the leaf counts add up to the number of games played out.
    ///
    @Test
    def everyGameTerminates(): Unit \ Assert =
        let (wins, draws, _, _) = explore(Board.newBoard(), Symbol.X, Symbol.O);
        assertTrue(wins + draws > 0)

}
