mod TestMinimax {

    use Assert.assertEq
    use Math.Random
    use Math.Random.Random

    def applyMoves(moves: List[(Symbol, Int32)]): Board =
        List.foldLeft((b, m) -> {
            match m {
                case (sym, idx) => Option.getWithDefault(b, Board.makeMove(b, sym, idx))
            }
        }, Board.newBoard(), moves)

    def playSelfGame(board: Board, turn: Symbol): Outcome \ Random = {
        match Board.outcome(board) {
            case Outcome.Ongoing =>
                match Minimax.bestMove(turn, board) {
                    case Some(m) =>
                        let nextBoard = Option.getWithDefault(board, Board.makeMove(board, turn, m));
                        playSelfGame(nextBoard, Symbol.complement(turn))
                    case None => Board.outcome(board)
                }
            case outcome => outcome
        }
    }

    @Test
    def testScoreWinO(): Unit \ Assert = {
        let b = applyMoves((Symbol.O, 0) :: (Symbol.O, 1) :: (Symbol.O, 2) :: Nil);
        assertEq(expected = Some(900), Minimax.score(b, 2))
    }

    @Test
    def testScoreWinX(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 0) :: (Symbol.X, 1) :: (Symbol.X, 2) :: Nil);
        assertEq(expected = Some(-850), Minimax.score(b, 3))
    }

    @Test
    def testScoreDraw(): Unit \ Assert = {
        let moves = (Symbol.X, 0) :: (Symbol.O, 1) :: (Symbol.X, 2)
                 :: (Symbol.X, 3) :: (Symbol.X, 4) :: (Symbol.O, 5)
                 :: (Symbol.O, 6) :: (Symbol.X, 7) :: (Symbol.O, 8) :: Nil;
        let b = applyMoves(moves);
        assertEq(expected = Some(0), Minimax.score(b, 9))
    }

    @Test
    def testScoreOngoing(): Unit \ Assert = {
        let b = Board.newBoard();
        assertEq(expected = (None: Option[Int32]), Minimax.score(b, 0))
    }

    @Test
    def testImmediateWinInOneO(): Unit \ Assert = {
        // O O .
        // X X .
        // . . .
        let b = applyMoves((Symbol.O, 0) :: (Symbol.X, 3) :: (Symbol.O, 1) :: (Symbol.X, 4) :: Nil);
        let move = Random.runWithSeed(42i64, () -> Minimax.bestMove(Symbol.O, b));
        assertEq(expected = Some(2), move)
    }

    @Test
    def testImmediateBlockXWin(): Unit \ Assert = {
        // X X .
        // O . .
        // . . .
        let b = applyMoves((Symbol.X, 0) :: (Symbol.O, 3) :: (Symbol.X, 1) :: Nil);
        let move = Random.runWithSeed(42i64, () -> Minimax.bestMove(Symbol.O, b));
        assertEq(expected = Some(2), move)
    }

    @Test
    def testDatalogBestMovesWinInOne(): Unit \ Assert = {
        let b = applyMoves((Symbol.O, 0) :: (Symbol.X, 3) :: (Symbol.O, 1) :: (Symbol.X, 4) :: Nil);
        let moves = Minimax.bestMoves(Symbol.O, b);
        assertEq(expected = 2 :: Nil, moves)
    }

    @Test
    def testDatalogBestMovesBlockX(): Unit \ Assert = {
        let b = applyMoves((Symbol.X, 0) :: (Symbol.O, 3) :: (Symbol.X, 1) :: Nil);
        let moves = Minimax.bestMoves(Symbol.O, b);
        assertEq(expected = 2 :: Nil, moves)
    }

    @Test
    def testDatalogBestMovesEmptyBoard(): Unit \ Assert = {
        let b = Board.newBoard();
        let moves = Minimax.bestMoves(Symbol.X, b);
        assertEq(expected = 0 :: 1 :: 2 :: 3 :: 4 :: 5 :: 6 :: 7 :: 8 :: Nil, moves)
    }

    ///
    /// Plays a whole game and records every move, so that a run is identified by its move list.
    ///
    def recordSelfGame(board: Board, turn: Symbol, acc: List[Int32]): List[Int32] \ Random = {
        match Board.outcome(board) {
            case Outcome.Ongoing =>
                match Minimax.bestMove(turn, board) {
                    case Some(m) =>
                        let nextBoard = Option.getWithDefault(board, Board.makeMove(board, turn, m));
                        recordSelfGame(nextBoard, Symbol.complement(turn), m :: acc)
                    case None => List.reverse(acc)
                }
            case _ => List.reverse(acc)
        }
    }

    @Test
    def testSameSeedReplaysTheSameGame(): Unit \ Assert = {
        let game1 = Random.runWithSeed(12345i64, () -> recordSelfGame(Board.newBoard(), Symbol.X, Nil));
        let game2 = Random.runWithSeed(12345i64, () -> recordSelfGame(Board.newBoard(), Symbol.X, Nil));
        assertEq(expected = game1, game2)
    }

    @Test
    def testTieBreakDrawsSuccessiveValues(): Unit \ Assert = {
        // On an empty board all nine moves are equally good, so the opening is a pure
        // coin flip. Re-seeding per move would pin every game to the same opening;
        // seeding once must not.
        let openings = List.map(
            seed -> Random.runWithSeed(seed, () -> Minimax.bestMove(Symbol.X, Board.newBoard())),
            1i64 :: 2i64 :: 3i64 :: 4i64 :: 5i64 :: 6i64 :: 7i64 :: 8i64 :: 9i64 :: 10i64 :: Nil
        );
        Assert.assertTrue(List.length(List.distinct(openings)) > 1)
    }

    @Test
    def testDifferentSeedsDivergeWithinAGame(): Unit \ Assert = {
        // A single seed must drive a *sequence* of draws, not one repeated draw.
        let game1 = Random.runWithSeed(2i64, () -> recordSelfGame(Board.newBoard(), Symbol.X, Nil));
        let game2 = Random.runWithSeed(7i64, () -> recordSelfGame(Board.newBoard(), Symbol.X, Nil));
        Assert.assertTrue(game1 != game2)
    }

    @Test
    def testSelfPlayDrawSeed1(): Unit \ Assert = {
        let outcome = Random.runWithSeed(12345i64, () -> playSelfGame(Board.newBoard(), Symbol.X));
        assertEq(expected = Outcome.Draw, outcome)
    }

    @Test
    def testSelfPlayDrawSeed2(): Unit \ Assert = {
        let outcome = Random.runWithSeed(67890i64, () -> playSelfGame(Board.newBoard(), Symbol.X));
        assertEq(expected = Outcome.Draw, outcome)
    }

    @Test
    def testSelfPlayDrawSeed3(): Unit \ Assert = {
        let outcome = Random.runWithSeed(999999i64, () -> playSelfGame(Board.newBoard(), Symbol.X));
        assertEq(expected = Outcome.Draw, outcome)
    }
}
