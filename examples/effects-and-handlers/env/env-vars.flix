use Sys.Env

/// Reads a specific environment variable with `getVar` and retrieves
/// all environment variables as a map with `getEnv`.
/// `getVar` returns `None` if the variable is not set.
def main(): Unit \ { Env, IO } =
    match Env.getVar("PATH") {
        case Some(path) => println("PATH: ${path}")
        case None       => println("PATH is not set.")
    };
    let all = Env.getEnv();
    println("Total env vars: ${Map.size(all)}")
