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

    @Test
    def testDatalogOutcomeInitial(): Unit \ Assert = {
        let b = Board.newBoard();
        assertEq(expected = Outcome.Ongoing, Board.datalogOutcome(b))
    }

    @Test
    def testDatalogOutcomeWin(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 0) :: (Symbol.X, 1) :: (Symbol.X, 2) :: Nil);
        assertEq(expected = Outcome.Win(Symbol.X), Board.datalogOutcome(b))
    }

    @Test
    def testDatalogOutcomeDraw(): Unit \ Assert = {
        let moves = (Symbol.X, 0) :: (Symbol.O, 1) :: (Symbol.X, 2)
                 :: (Symbol.X, 3) :: (Symbol.X, 4) :: (Symbol.O, 5)
                 :: (Symbol.O, 6) :: (Symbol.X, 7) :: (Symbol.O, 8) :: Nil;
        let b = applyMoves(moves);
        assertEq(expected = Outcome.Draw, Board.datalogOutcome(b))
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
