mod Minimax {

    use Down.Down
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
    /// Move preference order (center, corners, edges) to maximize alpha-beta pruning cutoffs.
    ///
    def preferredOrder(): List[Int32] =
        4 :: 0 :: 2 :: 6 :: 8 :: 1 :: 3 :: 5 :: 7 :: Nil

    ///
    /// Orders available moves by heuristic priority to prune the search tree early.
    ///
    def orderMoves(moves: List[Int32]): List[Int32] =
        List.filter(m -> List.memberOf(m, moves), preferredOrder())

    ///
    /// Evaluates the board position recursively using minimax with alpha-beta pruning.
    /// O maximizes the score while X minimizes the score.
    ///
    pub def search(player: Symbol, depth: Int32, board: Board): Int32 =
        searchAlphaBeta(player, depth, board, Int32.minValue(), Int32.maxValue())

    def searchAlphaBeta(player: Symbol, depth: Int32, board: Board, alpha: Int32, beta: Int32): Int32 = {
        match score(board, depth) {
            case Some(s) => s
            case None => {
                let moves = orderMoves(Board.freeCells(board));
                match player {
                    case Symbol.O => {
                        def loop(ms, curAlpha, best) = match ms {
                            case Nil => best
                            case m :: rest => {
                                let nextBoard = Option.getWithDefault(board, Board.makeMove(board, Symbol.O, m));
                                let v = searchAlphaBeta(Symbol.X, depth + 1, nextBoard, curAlpha, beta);
                                let newBest = Order.max(best, v);
                                let newAlpha = Order.max(curAlpha, newBest);
                                if (beta <= newAlpha)
                                    newBest
                                else
                                    loop(rest, newAlpha, newBest)
                            }
                        };
                        loop(moves, alpha, Int32.minValue())
                    }
                    case Symbol.X => {
                        def loop(ms, curBeta, best) = match ms {
                            case Nil => best
                            case m :: rest => {
                                let nextBoard = Option.getWithDefault(board, Board.makeMove(board, Symbol.X, m));
                                let v = searchAlphaBeta(Symbol.O, depth + 1, nextBoard, alpha, curBeta);
                                let newBest = Order.min(best, v);
                                let newBeta = Order.min(curBeta, newBest);
                                if (newBeta <= alpha)
                                    newBest
                                else
                                    loop(rest, newBeta, newBest)
                            }
                        };
                        loop(moves, beta, Int32.maxValue())
                    }
                    case Symbol.Empty => 0
                }
            }
        }
    }

    ///
    /// Returns all equally optimal candidate moves for `player` on `board` using Datalog solving.
    /// Evaluates legal moves and finds the optimal moves using Datalog lattice fixpoint rules.
    ///
    pub def bestMoves(player: Symbol, board: Board): List[Int32] = {
        let moves = Board.freeCells(board);
        if (List.isEmpty(moves)) {
            Nil
        } else {
            let scoredMoves = if (List.length(moves) == 9)
                List.map(m -> (m, 0), moves)
            else
                List.map(m -> {
                    let nextBoard = Option.getWithDefault(board, Board.makeMove(board, player, m));
                    let v = search(Symbol.complement(player), 1, nextBoard);
                    (m, v)
                }, moves);

            match player {
                case Symbol.O => {
                    let facts = inject scoredMoves into Candidate/2;
                    let rules = #{
                        MaxScore(; s) :- Candidate(_, s).
                        OptScore(s) :- fix MaxScore(; s).
                        BestMove(m) :- OptScore(s), Candidate(m, s).
                    };
                    let sol = solve facts <+> rules;
                    query sol select m from BestMove(m) |> Vector.toList
                }
                case Symbol.X => {
                    let scoredMovesDown = List.map(match (m, s) -> (m, Down.Down(s)), scoredMoves);
                    let facts = inject scoredMovesDown into Candidate/2;
                    let rules = #{
                        MinScore(; ds) :- Candidate(_, ds).
                        OptScore(ds) :- fix MinScore(; ds).
                        BestMove(m) :- OptScore(ds), Candidate(m, ds).
                    };
                    let sol = solve facts <+> rules;
                    query sol select m from BestMove(m) |> Vector.toList
                }
                case Symbol.Empty => Nil
            }
        }
    }

    ///
    /// Returns one move chosen pseudorandomly from `moves`.
    ///
    /// `Random.randomInt64` spans the whole `Int64` range, including negative values.
    /// Masking off the sign bit is safe where `Int64.abs` is not: `abs(Int64.minValue())`
    /// overflows back to a negative number.
    ///
    def pickRandom(moves: List[Int32]): Option[Int32] \ Random = {
        let len = List.length(moves);
        let raw = Random.randomInt64();
        let nonNeg = Int64.bitwiseAnd(raw, Int64.maxValue());
        let idx = Int64.clampToInt32(min = 0, max = len - 1, Int64.remainder(nonNeg, Int32.toInt64(len)));
        List.nth(idx, moves)
    }

    ///
    /// Returns the best move for `player` on `board`.
    ///
    /// When several moves are equally good, one of them is drawn from the ambient
    /// `Random` effect. Seed that effect once at the edge of the program --- with
    /// `Math.Random.runWithSeed` for a reproducible game, or `Math.Random.runWithIO`
    /// for a fresh one --- so that successive moves draw successive values. Seeding
    /// per move instead would restart the generator every time and make the computer
    /// pick the same candidate over and over.
    ///
    pub def bestMove(player: Symbol, board: Board): Option[Int32] \ Random = {
        match bestMoves(player, board) {
            case Nil      => None
            case m :: Nil => Some(m)
            case moves    => pickRandom(moves)
        }
    }
}
