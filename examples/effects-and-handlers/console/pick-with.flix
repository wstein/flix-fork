use Sys.Console

/// Presents a numbered menu and re-prompts until the user makes a valid selection.
def main(): Unit \ Console =
    let color = Console.pickWith("Pick a color:", "Red" :: "Green" :: "Blue" :: Nil);
    match color {
        case Some(c) => Console.println("You picked: ${c}")
        case None    => Console.println("No input.")
    }
