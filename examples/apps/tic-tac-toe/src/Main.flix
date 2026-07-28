use Sys.Console

///
/// The core gameplay loop called repeatedly until the game ends.
///
/// `symbol` is the side to move and `machine` is the side played by the computer.
///
def gameLoop(board: Board, symbol: Symbol, machine: Symbol): Unit \ Interface = {
    let move =
        if (symbol == machine)
            Brain.chooseMove(board, symbol)
        else {
            Interface.display(board, "Enter a move (0 - 8): ");
            Some(Interface.getMove())
        };
    match move {
        case None => Interface.display(board, "Draw")
        case Some(m) => match Board.makeMove(board, symbol, m) {
            case Some(newBoard) => {
                if (Board.checkWin(newBoard, symbol)) {
                    if (symbol == machine)
                        Interface.display(newBoard, "The computer wins!!!")
                    else
                        Interface.display(newBoard, "You win!!!")
                } else if (Board.boardFilled(newBoard)) {
                    Interface.display(newBoard, "Draw")
                } else {
                    gameLoop(newBoard, Symbol.complement(symbol), machine)
                }
            }
            case None => {
                Interface.display(board, "Cell already filled");
                gameLoop(board, symbol, machine)
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
        println(Board.legend());
        println("Enter your move as one these numbers corresponding to a cell");
        println("You play against the computer. X always moves first.");
        println("");
        let board = Board.newBoard();
        let human = Interface.chooseSide();
        gameLoop(board, Symbol.X, Symbol.complement(human))
    } with Interface.interfaceWithConsole
    with Console.runWithIO
}
