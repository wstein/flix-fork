///
/// The main entry point of the application.
///
/// Parses command line options and launches the Processing GUI.
///
def main(): Unit \ IO = {
    run {
        let args = Sys.Env.getArgs();
        let parsed = CLIOpts.parse(args);

        if (not List.isEmpty(parsed#errors)) {
            List.forEach((e: String) -> println("Error: ${e}"), parsed#errors);
            println("");
            println(CLIOpts.usage());
            Sys.Exit.exit(1)
        } else if (parsed#help) {
            println(CLIOpts.usage())
        } else {
            let seed = match parsed#seed {
                case Some(s) => Some(s)
                case None => Sys.Env.getVar("TICTACTOE_SEED") |> Option.flatMap(Int64.fromString)
            };
            Gui.runGui(seed)
        }
    } with Sys.Env.runWithIO
    with Sys.Exit.runWithIO
}
