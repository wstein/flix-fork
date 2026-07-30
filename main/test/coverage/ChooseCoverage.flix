restrictable enum Choice[s] {
    case First, Second
}

def main(): Unit \ IO = {
    choose Choice.First {
        case Choice.First => println("first")
        case Choice.Second => println("second")
    }
}
