///
/// Enum representing the user-selected game interface mode.
///
pub enum Mode with Eq, ToString {
    case Console
    case Gui
}

///
/// Effect representing the user interface for the game.
///
pub eff Interface {

    ///
    /// Prompts the user to choose the interface mode (Console or GUI).
    ///
    def chooseMode(): Mode

    ///
    /// Prints the current state of the board and a message.
    ///
    def display(board: Board, msg: String): Unit

    ///
    /// Gets a move from the user.
    ///
    def getMove(): Int32

    ///
    /// Prompts the user to choose an opponent.
    ///
    def chooseOpponent(): Opponent

    ///
    /// Prompts the user to choose who plays first.
    ///
    def chooseFirstPlayer(): Symbol
}

mod Interface {

    use Sys.Console

    ///
    /// Recursive helper function to read and return only valid moves.
    ///
    def getMoveFromConsole(): Int32 \ Console + Sys.Exit = {
        match Console.readln() {
            case None => {
                Console.println("No input available. Exiting.");
                Sys.Exit.exit(0)
            }
            case Some(line) => match Int32.parse(10, line) {
                case Result.Ok(move) if (0 <= move and move <= 8) => move
                case _ => {
                    Console.println("Invalid move. Move must be an integer between 0 and 8 (inclusive)");
                    getMoveFromConsole()
                }
            }
        }
    }

    ///
    /// Recursive helper function to prompt for interface mode choice.
    ///
    def getModeFromConsole(): Mode \ Console + Sys.Exit = {
        Console.println("Choose interface mode:");
        Console.println("  1. Console (Terminal)");
        Console.println("  2. GUI (Processing Core)");
        Console.print("Enter choice (1 or 2): ");
        match Console.readln() {
            case None => {
                Console.println("No input available. Exiting.");
                Sys.Exit.exit(0)
            }
            case Some(line) => match String.trim(line) {
                case "1" => Mode.Console
                case "2" => Mode.Gui
                case _   => {
                    Console.println("Invalid choice. Please enter 1 or 2.");
                    getModeFromConsole()
                }
            }
        }
    }

    ///
    /// Recursive helper function to prompt for opponent choice.
    ///
    def getOpponentFromConsole(): Opponent \ Console + Sys.Exit = {
        Console.println("Choose opponent:");
        Console.println("  1. Human");
        Console.println("  2. Computer");
        Console.print("Enter choice (1 or 2): ");
        match Console.readln() {
            case None => {
                Console.println("No input available. Exiting.");
                Sys.Exit.exit(0)
            }
            case Some(line) => match String.trim(line) {
                case "1" => Opponent.Human
                case "2" => Opponent.Computer
                case _   => {
                    Console.println("Invalid choice. Please enter 1 or 2.");
                    getOpponentFromConsole()
                }
            }
        }
    }

    ///
    /// Recursive helper function to prompt for who moves first when playing against the computer.
    ///
    def getFirstPlayerFromConsole(): Symbol \ Console + Sys.Exit = {
        Console.println("Who plays first?");
        Console.println("  1. You (X)");
        Console.println("  2. Computer (O)");
        Console.print("Enter choice (1 or 2): ");
        match Console.readln() {
            case None => {
                Console.println("No input available. Exiting.");
                Sys.Exit.exit(0)
            }
            case Some(line) => match String.trim(line) {
                case "1" => Symbol.X
                case "2" => Symbol.O
                case _   => {
                    Console.println("Invalid choice. Please enter 1 or 2.");
                    getFirstPlayerFromConsole()
                }
            }
        }
    }

    ///
    /// Runs the Interface effect of the given function `f`.
    ///
    /// In other words, re-interprets the Interface effect using the IO and Console effects.
    ///
    pub def interfaceWithConsole(f: Unit -> a \ ef): a \ ef - Interface + Console + Sys.Exit =
        run {
            f()
        }
        with handler Interface {
            def chooseMode(resume) = {
                resume(getModeFromConsole())
            }

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

            def chooseOpponent(resume) = {
                resume(getOpponentFromConsole())
            }

            def chooseFirstPlayer(resume) = {
                resume(getFirstPlayerFromConsole())
            }
        }
}
