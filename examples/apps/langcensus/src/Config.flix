///
/// The effect to get the user configuration.
///
eff Config {
    ///
    /// Gets the github user name.
    ///
    def getName(): String

    ///
    /// Gets the github user token.
    ///
    def getToken(): String

    ///
    /// Whether repositories that are not public may be included.
    ///
    /// Off unless `LANGCENSUS_INCLUDE_PRIVATE` is set to `true`, because the report
    /// names every repository it counts and is meant to be shareable.
    ///
    def includePrivate(): Bool

    ///
    /// Whether to print the per-repository breakdown as well as the summary.
    ///
    def verbose(): Bool
}

mod Config {

    use Sys.Env

    ///
    /// Gets an environment variable.
    ///
    def getEnv(envName: String): String \ Env + AnalysisFailure = {
        let envVarOpt = Env.getEnv() |> Map.get(envName);
        match envVarOpt {
            case Some(envVar) => envVar
            case None => AnalysisFailure.raiseConfigError("Environment variable ${envName} not found.")
        }
    }

    ///
    /// Gets an optional environment variable.
    ///
    def getEnvOpt(envName: String): Option[String] \ Env =
        Env.getEnv() |> Map.get(envName)

    ///
    /// Reads a boolean environment variable, defaulting to false.
    ///
    def flag(envName: String): Bool \ Env =
        getEnvOpt(envName)
        |> Option.map(String.toLowerCase)
        |> Option.map(v -> v == "true" or v == "1" or v == "yes")
        |> Option.getWithDefault(false)

    ///
    /// Handles Config effect through accessing environment variables.
    ///
    pub def runWithEnv(f: Unit -> a \ ef): a \ ef - Config + AnalysisFailure + Env = {
        run {
            f()
        } with handler Config {
            def getName(resume) = getEnv("GITHUB_NAME") |> resume
            def getToken(resume) = getEnv("GITHUB_TOKEN") |> resume
            def verbose(resume) = flag("LANGCENSUS_VERBOSE") |> resume
            def includePrivate(resume) =
                flag("LANGCENSUS_INCLUDE_PRIVATE") |> resume
        }
    }
}
