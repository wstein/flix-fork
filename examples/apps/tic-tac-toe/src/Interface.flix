///
/// Effect representing the user interface for the game.
///
pub eff Interface {

    ///
    /// Prints the current state of the board and a message.
    ///
    def display(board: Board, msg: String): Unit

    ///
    /// Gets a move from the user.
    ///
    def getMove(): Int32

    ///
    /// Asks the user which side they want to play. `X` always moves first, so this
    /// is also the choice of who opens the game.
    ///
    def chooseSide(): Symbol
}

mod Interface {

    use Sys.Console

    ///
    /// Recursive helper function to read and return only valid moves.
    ///
    def getMoveFromConsole(): Int32 \ Console = {
        match Console.readln() {
            case Option.None => {
                Console.println("Invalid move. Move must be a positive integer between 0 and 8");
                getMoveFromConsole()
            }
            case Option.Some(line) => match Int32.parse(10, line) {
                case Result.Ok(move) if (0 <= move and move <= 8) => move
                case _ => {
                    Console.println("Invalid move. Move must be a positive integer between 0 and 8");
                    getMoveFromConsole()
                }
            }
        }
    }

    ///
    /// Recursive helper function to read and return the side the user wants to play.
    ///
    def getSideFromConsole(): Symbol \ Console = {
        Console.print("Do you want to move first? [y/n] ");
        match Console.readln() |> Option.map(String.trim >> String.toLowerCase) {
            case Some("y") => Symbol.X
            case Some("n") => Symbol.O
            case _         => {
                Console.println("Please answer 'y' or 'n'.");
                getSideFromConsole()
            }
        }
    }

    ///
    /// Runs the Interface effect of the given function `f`.
    ///
    /// In other words, re-interprets the Interface effect using the IO and Console effects.
    ///
    pub def interfaceWithConsole(f: Unit -> a \ ef): a \ ef - Interface + Console =
        run {
            f()
        }
        with handler Interface {
            def display(board, msg, resume) = {
                Console.println("");
                Console.println(Board.toString(board));
                Console.println("");
                Console.println(msg);
                resume()
            }

            def getMove(resume) = {
                resume(getMoveFromConsole())
            }

            def chooseSide(resume) = {
                resume(getSideFromConsole())
            }
        }
}
