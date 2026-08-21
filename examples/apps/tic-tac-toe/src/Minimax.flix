mod Minimax {

    use Math.Random
    use Math.Random.Random

    ///
    /// Evaluates the terminal state of the board from O's (computer's) perspective,
    /// weighted by search depth to prefer faster wins and slower losses.
    ///
    pub def score(board: Board, depth: Int32): Option[Int32] = {
        match Board.outcome(board) {
            case Outcome.Win(Symbol.O) => Some(1000 - 50 * depth)
            case Outcome.Win(Symbol.X) => Some(-1000 + 50 * depth)
            case Outcome.Draw          => Some(0)
            case _                     => None
        }
    }

    ///
    /// Evaluates the board position recursively using minimax.
    /// O maximizes the score while X minimizes the score.
    ///
    pub def search(player: Symbol, depth: Int32, board: Board): Int32 = {
        match score(board, depth) {
            case Some(s) => s
            case None => {
                let moves = Board.freeCells(board);
                match player {
                    case Symbol.O =>
                        List.foldLeft((best, m) -> {
                            let nextBoard = Option.getWithDefault(board, Board.makeMove(board, Symbol.O, m));
                            let v = search(Symbol.X, depth + 1, nextBoard);
                            Order.max(best, v)
                        }, Int32.minValue(), moves)
                    case Symbol.X =>
                        List.foldLeft((best, m) -> {
                            let nextBoard = Option.getWithDefault(board, Board.makeMove(board, Symbol.X, m));
                            let v = search(Symbol.O, depth + 1, nextBoard);
                            Order.min(best, v)
                        }, Int32.maxValue(), moves)
                    case Symbol.Empty => 0
                }
            }
        }
    }

    ///
    /// Returns the best move for the given player on the board using minimax search.
    /// If multiple moves share the optimal evaluation, one is chosen pseudorandomly.
    ///
    pub def bestMove(player: Symbol, board: Board): Option[Int32] \ Random = {
        let moves = Board.freeCells(board);
        if (List.isEmpty(moves)) {
            None
        } else {
            let scoredMoves = List.map(m -> {
                let nextBoard = Option.getWithDefault(board, Board.makeMove(board, player, m));
                let v = search(Symbol.complement(player), 1, nextBoard);
                (m, v)
            }, moves);

            let bestMoves = match player {
                case Symbol.O => {
                    let bestVal = List.foldLeft((acc, p) -> match p { case (_, v) => Order.max(acc, v) }, Int32.minValue(), scoredMoves);
                    List.filterMap(match (m, v) -> if (v == bestVal) Some(m) else None, scoredMoves)
                }
                case Symbol.X => {
                    let bestVal = List.foldLeft((acc, p) -> match p { case (_, v) => Order.min(acc, v) }, Int32.maxValue(), scoredMoves);
                    List.filterMap(match (m, v) -> if (v == bestVal) Some(m) else None, scoredMoves)
                }
                case Symbol.Empty => Nil
            };

            let len = List.length(bestMoves);
            if (len == 0) {
                None
            } else if (len == 1) {
                List.head(bestMoves)
            } else {
                let raw = Random.randomInt64();
                let nonNeg = Int64.bitwiseAnd(raw, Int64.maxValue());
                let rem = Int64.remainder(nonNeg, Int32.toInt64(len));
                let idx = Int64.clampToInt32(min = 0, max = len - 1, rem);
                List.nth(idx, bestMoves)
            }
        }
    }
}
