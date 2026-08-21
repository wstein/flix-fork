use Sys.Console
use Math.Random
use Math.Random.Random

///
/// The core gameplay loop called repeatedly until the game ends.
///
def gameLoop(board: Board, currentTurn: Symbol, opponent: Opponent, humanSymbol: Symbol): Unit \ {Interface, Random} = {
    match opponent {
        case Opponent.Human => {
            Interface.display(board, "Enter a move (0 - 8): ");
            let move = Interface.getMove();
            match Board.makeMove(board, currentTurn, move) {
                case Some(newBoard) => {
                    if (Board.checkWin(newBoard, currentTurn)) {
                        match currentTurn {
                            case Symbol.X => Interface.display(newBoard, "Player One wins!!!")
                            case Symbol.O => Interface.display(newBoard, "Player Two wins!!!")
                            case _ => ()
                        }
                    } else if (Board.boardFilled(newBoard)) {
                        Interface.display(board, "Draw")
                    } else {
                        gameLoop(newBoard, Symbol.complement(currentTurn), opponent, humanSymbol)
                    }
                }
                case None => {
                    Interface.display(board, "Cell already filled");
                    gameLoop(board, currentTurn, opponent, humanSymbol)
                }
            }
        }
        case Opponent.Computer => {
            if (currentTurn == humanSymbol) {
                Interface.display(board, "Enter a move (0 - 8): ");
                let move = Interface.getMove();
                match Board.makeMove(board, currentTurn, move) {
                    case Some(newBoard) => {
                        if (Board.checkWin(newBoard, currentTurn)) {
                            Interface.display(newBoard, "You win!!!")
                        } else if (Board.boardFilled(newBoard)) {
                            Interface.display(board, "Draw")
                        } else {
                            gameLoop(newBoard, Symbol.complement(currentTurn), opponent, humanSymbol)
                        }
                    }
                    case None => {
                        Interface.display(board, "Cell already filled");
                        gameLoop(board, currentTurn, opponent, humanSymbol)
                    }
                }
            } else {
                match Minimax.bestMove(currentTurn, board) {
                    case Some(move) => {
                        let newBoard = Option.getWithDefault(board, Board.makeMove(board, currentTurn, move));
                        Interface.display(newBoard, "Computer played cell ${move}.");
                        if (Board.checkWin(newBoard, currentTurn)) {
                            Interface.display(newBoard, "I win!!!")
                        } else if (Board.boardFilled(newBoard)) {
                            Interface.display(board, "Draw")
                        } else {
                            gameLoop(newBoard, Symbol.complement(currentTurn), opponent, humanSymbol)
                        }
                    }
                    case None => {
                        Interface.display(board, "Draw")
                    }
                }
            }
        }
    }
}

///
/// The main entry point of the application.
///
/// Creates a new game board and starts the game loop.
///
def main(): Unit \ IO = {
    run {
        println("");
        println("  _______ _          _______             _______           ");
        println(" |__   __(_)        |__   __|           |__   __|        ");
        println("    | |   _  ___       | | __ _  ___       | | ___   ___ ");
        println("    | |  | |/ __|      | |/ _` |/ __|      | |/ _ \\ / _ \\");
        println("    | |  | | (__       | | (_| | (__       | | (_) |  __/");
        println("    |_|  |_|\\___|      |_|\\__,_|\\___|      |_|\\___/ \\___|");
        println("");
        println("Instructions:");
        println("The cells are numbered as follows:\n");
        println("   0   |   1   |   2   " );
        println(" - - - | - - - | - - - ");
        println("   3   |   4   |   5   " );
        println(" - - - | - - - | - - - ");
        println("   6   |   7   |   8   " );
        println("       |       |       ");
        println("Enter your move as one these numbers corresponding to a cell");
        println("");
        let opponent = Interface.chooseOpponent();
        let (firstPlayer, humanSymbol) = match opponent {
            case Opponent.Human => (Symbol.X, Symbol.X)
            case Opponent.Computer =>
                let first = Interface.chooseFirstPlayer();
                (first, Symbol.X)
        };
        let board = Board.newBoard();
        gameLoop(board, firstPlayer, opponent, humanSymbol)
    } with Interface.interfaceWithConsole
    with Math.Random.runWithIO
    with Console.runWithIO
    with Sys.Exit.runWithIO
}
