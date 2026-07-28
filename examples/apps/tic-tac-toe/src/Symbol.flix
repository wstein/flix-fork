///
/// Represents the different symbols used in the game.
///
/// `Order` is required because symbols appear as terms in the Datalog program
/// that drives the machine player.
pub enum Symbol with Eq, Order {
    case X,
    case O,
    case Empty
}

mod Symbol {

    ///
    /// Returns the opposite symbol, i.e., if the input is `X`, it returns `O`, and vice versa.
    ///
    pub def complement(symbol: Symbol): Symbol  = match symbol {
        case Symbol.X       => Symbol.O
        case Symbol.O       => Symbol.X
        case Symbol.Empty   => Symbol.Empty
    }

    ///
    /// Returns a string representation of the symbol.
    ///
    pub def toString(s: Symbol): String = match s {
        case Symbol.X => "X"
        case Symbol.O => "O"
        case Symbol.Empty => " "
    }
}
