use Sys.Env

/// Reads the user's home directory, current working directory, and OS name.
/// Each getter returns an `Option`, since the underlying property may be unset.
/// The Env effect has a default handler, so no explicit `runWithIO` is needed.
def main(): Unit \ { Env, IO } =
    let home = Env.getUserHomeDirectory() |> Option.getWithDefault("unknown");
    println("Home: ${home}");
    let cwd = Env.getCurrentWorkingDirectory() |> Option.getWithDefault("unknown");
    println("CWD: ${cwd}");
    let os = Env.getOsName() |> Option.getWithDefault("unknown");
    println("OS: ${os}")
