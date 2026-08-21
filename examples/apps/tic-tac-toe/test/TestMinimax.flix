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
