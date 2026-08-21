mod TestBoard {

    use Assert.assertEq

    def applyMoves(moves: List[(Symbol, Int32)]): Board =
        List.foldLeft((b, m) -> {
            match m {
                case (sym, idx) => Option.getWithDefault(b, Board.makeMove(b, sym, idx))
            }
        }, Board.newBoard(), moves)

    @Test
    def testFreeCellsEmpty(): Unit \ Assert = {
        let b = Board.newBoard();
        assertEq(expected = 0 :: 1 :: 2 :: 3 :: 4 :: 5 :: 6 :: 7 :: 8 :: Nil, Board.freeCells(b))
    }

    @Test
    def testFreeCellsPartial(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 0) :: (Symbol.O, 4) :: (Symbol.X, 8) :: Nil);
        assertEq(expected = 1 :: 2 :: 3 :: 5 :: 6 :: 7 :: Nil, Board.freeCells(b))
    }

    @Test
    def testOutcomeInitial(): Unit \ Assert = {
        let b = Board.newBoard();
        assertEq(expected = Outcome.Ongoing, Board.outcome(b))
    }

    @Test
    def testOutcomeWinRow(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 0) :: (Symbol.X, 1) :: (Symbol.X, 2) :: Nil);
        assertEq(expected = Outcome.Win(Symbol.X), Board.outcome(b))
    }

    @Test
    def testOutcomeWinCol(): Unit \ Assert = {
        let b = applyMoves((Symbol.O, 1) :: (Symbol.O, 4) :: (Symbol.O, 7) :: Nil);
        assertEq(expected = Outcome.Win(Symbol.O), Board.outcome(b))
    }

    @Test
    def testOutcomeWinDiagonal(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 2) :: (Symbol.X, 4) :: (Symbol.X, 6) :: Nil);
        assertEq(expected = Outcome.Win(Symbol.X), Board.outcome(b))
    }

    @Test
    def testOutcomeDraw(): Unit \ Assert = {
        // X O X
        // X X O
        // O X O
        let moves = (Symbol.X, 0) :: (Symbol.O, 1) :: (Symbol.X, 2)
                 :: (Symbol.X, 3) :: (Symbol.X, 4) :: (Symbol.O, 5)
                 :: (Symbol.O, 6) :: (Symbol.X, 7) :: (Symbol.O, 8) :: Nil;
        let b = applyMoves(moves);
        assertEq(expected = Outcome.Draw, Board.outcome(b))
    }

    ///
    /// Enumerates positions reachable within 3 moves to validate agreement with Datalog.
    ///
    def samplePositions(board: Board, turn: Symbol, depth: Int32, acc: List[Board]): List[Board] = {
        let acc1 = board :: acc;
        if (depth >= 3)
            acc1
        else
            match Board.outcome(board) {
                case Outcome.Ongoing =>
                    List.foldLeft((a, m) ->
                        samplePositions(
                            Option.getWithDefault(board, Board.makeMove(board, turn, m)),
                            Symbol.complement(turn),
                            depth + 1,
                            a),
                        acc1, Board.freeCells(board))
                case _ => acc1
            }
    }

    @Test
    def testOutcomeAgreesWithDatalogSpec(): Unit \ Assert = {
        // `Board.outcome` answers directly so that the search stays fast, while
        // `Board.datalogOutcome` states the same rules as Datalog. They must agree on
        // every position a real game can reach.
        let positions = samplePositions(Board.newBoard(), Symbol.X, 0, Nil);
        // Guard against the check passing because nothing was enumerated.
        Assert.assertTrue(List.length(positions) > 500);
        let disagreements = List.count(b -> Board.outcome(b) != Board.datalogOutcome(b), positions);
        Assert.assertEq(expected = 0, disagreements)
    }

    @Test
    def testToStringEmpty(): Unit \ Assert = {
        let b = Board.newBoard();
        let expected = " ┌───┬───┬───┐\n"
                    ++ " │   │   │   │\n"
                    ++ " ├───┼───┼───┤\n"
                    ++ " │   │   │   │\n"
                    ++ " ├───┼───┼───┤\n"
                    ++ " │   │   │   │\n"
                    ++ " └───┴───┴───┘";
        assertEq(expected = expected, Board.toString(b))
    }

    @Test
    def testToStringMoves(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 0) :: (Symbol.O, 4) :: (Symbol.X, 8) :: Nil);
        let expected = " ┌───┬───┬───┐\n"
                    ++ " │ X │   │   │\n"
                    ++ " ├───┼───┼───┤\n"
                    ++ " │   │ O │   │\n"
                    ++ " ├───┼───┼───┤\n"
                    ++ " │   │   │ X │\n"
                    ++ " └───┴───┴───┘";
        assertEq(expected = expected, Board.toString(b))
    }
}
