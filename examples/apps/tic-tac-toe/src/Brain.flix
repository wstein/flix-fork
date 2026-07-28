///
/// The machine player.
///
/// The strategy is written as a Datalog program. The rules do not search the game
/// tree; they describe what a *good* move looks like -- a win, a block, a fork --
/// and `chooseMove` asks the solver for each kind of move in turn, playing the
/// first one it finds.
///
/// The priority order is the classic one due to Newell and Simon (1972). A player
/// that follows it never loses at tic-tac-toe. `test/TestBrain.flix` checks this by
/// playing out every game that can possibly be reached.
///
/// Cells are numbered from left to right, top to bottom:
///
/// ```
///     0 | 1 | 2
///    ---+---+---
///     3 | 4 | 5
///    ---+---+---
///     6 | 7 | 8
/// ```
///
mod Brain {

    ///
    /// The eight winning lines: three rows, three columns, two diagonals.
    ///
    /// The first component is a line id. It is what makes a fork detectable: a fork
    /// is a move that threatens two *distinct* lines at once, and without ids there
    /// would be no way to say "distinct".
    ///
    def lines(): Vector[(Int32, Int32, Int32, Int32)] =
        Vector#{ (0, 0, 1, 2), (1, 3, 4, 5), (2, 6, 7, 8),      // rows
                 (3, 0, 3, 6), (4, 1, 4, 7), (5, 2, 5, 8),      // columns
                 (6, 0, 4, 8), (7, 2, 4, 6) }                   // diagonals

    ///
    /// The pairs of diagonally opposite corners, in both directions.
    ///
    /// This doubles as the definition of "corner": a cell is a corner exactly when
    /// it has an opposite.
    ///
    def opposites(): Vector[(Int32, Int32)] =
        Vector#{ (0, 8), (8, 0), (2, 6), (6, 2) }

    ///
    /// The rules describing the tactical situation on the board.
    ///
    /// A short tour of the Datalog notation, for readers meeting it for the first
    /// time.
    ///
    /// A *predicate* is a named table of tuples. The fact `Cell(4, Symbol.O)` says
    /// that the tuple `(4, Symbol.O)` is in the table `Cell`, i.e. that cell 4 holds
    /// an `O`. Inside a rule, capitalised names are predicates and lower-case names
    /// are variables.
    ///
    /// A *rule* `Head(..) :- Body1(..), Body2(..).` is read right to left: whenever
    /// every body predicate can be satisfied at the same time, derive the head. A
    /// variable used in two body predicates joins them, so both occurrences must
    /// take the same value; `_` matches anything and binds nothing. The solver keeps
    /// applying the rules until no new tuple can be derived.
    ///
    /// Two extras appear below:
    ///
    ///   - `not P(x)` holds when the tuple is *absent*. Flix requires such programs
    ///     to be stratified: a predicate may not depend on its own negation, even
    ///     indirectly, or the program is rejected at compile time.
    ///   - `if (x < y)` is a guard, an ordinary Flix expression that filters matches.
    ///
    /// The type of a Datalog value is a *schema*, listing each predicate with its
    /// column types. The trailing `| r` makes the schema *open*, meaning "at least
    /// these predicates, and possibly others". That is what allows the rules here to
    /// be written once and later combined with facts contributing further predicates.
    ///
    /// Datalog values are built with `inject`, which turns an ordinary collection
    /// into facts, and joined together with `<+>`. Nothing is actually computed until
    /// a `query`, which runs the solver and reads columns back out as a vector:
    ///
    ///     query db select i from Wins(s, i)
    ///
    def tactics(): #{ Line(Int32, Int32, Int32, Int32), LineWithMove(Int32, Int32, Int32, Int32),
                      Opposite(Int32, Int32), Opponent(Symbol, Symbol), Index(Int32),
                      Cell(Int32, Symbol), Occupied(Int32), Empty(Int32),
                      Wins(Symbol, Int32), ThreatAt(Symbol, Int32, Int32, Int32),
                      Threatens(Symbol, Int32, Int32), Forks(Symbol, Int32),
                      Forcing(Symbol, Int32), Center(Int32), Corner(Int32),
                      OppositeCorner(Symbol, Int32) | r } = #{

        //
        // Geometry.
        //

        // Restate every line three times, once per cell, so that a single rule can
        // ask "what if this cell were the one played?" without caring where in the
        // line it sits. `LineWithMove(l, m, a, b)` means: line `l` is made up of the
        // cells `m`, `a` and `b`, with `m` singled out as the cell being considered.
        LineWithMove(l, x, y, z) :- Line(l, x, y, z).
        LineWithMove(l, y, x, z) :- Line(l, x, y, z).
        LineWithMove(l, z, x, y) :- Line(l, x, y, z).

        Occupied(i) :- Cell(i, _).
        Empty(i)    :- Index(i), not Occupied(i).

        //
        // Tactics.
        //

        // Playing `i` completes a line for `s`, because `s` already holds the other
        // two cells of that line.
        Wins(s, i) :- LineWithMove(_, i, a, b), Empty(i), Cell(a, s), Cell(b, s).

        // Playing `i` leaves line `l` held twice by `s` with only `c` still empty,
        // i.e. it threatens to win at `c` on the following turn. Two rules are needed
        // because the other own cell may be either of the two remaining positions.
        ThreatAt(s, i, l, c) :- LineWithMove(l, i, a, c), Empty(i), Cell(a, s), Empty(c).
        ThreatAt(s, i, l, c) :- LineWithMove(l, i, c, a), Empty(i), Cell(a, s), Empty(c).

        Threatens(s, i, l) :- ThreatAt(s, i, l, _).

        // A move that creates two threats at once. Only one of them can be blocked,
        // so a fork wins. The guard `l1 < l2` demands two different lines, and also
        // avoids reporting the same pair twice.
        Forks(s, i) :- Threatens(s, i, l1), Threatens(s, i, l2), if (l1 < l2).

        // A move that forces a reply, at a cell that is not itself a fork for the
        // opponent. Playing one of these is how a double fork is broken up: the
        // opponent has to answer the threat instead of building their own fork.
        Forcing(s, i) :- ThreatAt(s, i, _, c), Opponent(s, t), not Forks(t, c).

        //
        // Positional fallbacks, used when nothing tactical is available.
        //

        Center(i) :- Empty(i), if (i == 4).

        // A cell is a corner exactly when it has a diagonally opposite cell.
        Corner(i) :- Empty(i), Opposite(i, _).

        // The corner facing one that the opponent has already taken.
        OppositeCorner(s, i) :- Empty(i), Opposite(i, j), Opponent(s, t), Cell(j, t).
    }

    ///
    /// The board, the static geometry, and the rules, combined into one Datalog value.
    ///
    def facts(board: Board): #{ Line(Int32, Int32, Int32, Int32), LineWithMove(Int32, Int32, Int32, Int32),
                                Opposite(Int32, Int32), Opponent(Symbol, Symbol), Index(Int32),
                                Cell(Int32, Symbol), Occupied(Int32), Empty(Int32),
                                Wins(Symbol, Int32), ThreatAt(Symbol, Int32, Int32, Int32),
                                Threatens(Symbol, Int32, Int32), Forks(Symbol, Int32),
                                Forcing(Symbol, Int32), Center(Int32), Corner(Int32),
                                OppositeCorner(Symbol, Int32) | r } =
        let cells = inject Board.occupiedCells(board) into Cell/2;
        let geometry = inject lines(), opposites(), Vector.range(0, 9) into Line/4, Opposite/2, Index/1;
        let sides = inject Vector#{(Symbol.X, Symbol.O), (Symbol.O, Symbol.X)} into Opponent/2;
        cells <+> geometry <+> sides <+> tactics()

    ///
    /// Returns the first move offered by the first tier that offers any at all, or
    /// `None` when every tier is empty.
    ///
    /// Each tier is the result of one query, so an empty vector means "this rule has
    /// nothing to suggest here, try the next one".
    ///
    def firstAvailableMove(tiers: List[Vector[Int32]]): Option[Int32] =
        List.findMap(Vector.head, tiers)

    ///
    /// Returns the move that `me` should play on `board`, or `None` if the board is full.
    ///
    pub def chooseMove(board: Board, me: Symbol): Option[Int32] =
        let db = facts(board);
        let opp = Symbol.complement(me);

        // Blocking a fork is the one tier that has to count. A single fork square can
        // simply be taken. Two or more cannot all be taken, so the fork has to be
        // pre-empted with a forcing move instead.
        let oppForks = query db select i from Forks(opp, i);
        let blockFork = match Vector.length(oppForks) {
            case 0 => Vector#{}
            case 1 => oppForks
            case _ => query db select i from Forcing(me, i)
        };

        // The strategy, in descending order of priority.
        firstAvailableMove(
                 (query db select i from Wins(me, i))               // 1. win now
            :: (query db select i from Wins(opp, i))                // 2. block a win
            :: (query db select i from Forks(me, i))                // 3. build a fork
            :: blockFork                                            // 4. stop a fork
            :: (query db select i from Center(i))                   // 5. take the centre
            :: (query db select i from OppositeCorner(me, i))       // 6. opposite corner
            :: (query db select i from Corner(i))                   // 7. any corner
            :: (query db select i from Empty(i))                    // 8. a side
            :: Nil
        )

}
