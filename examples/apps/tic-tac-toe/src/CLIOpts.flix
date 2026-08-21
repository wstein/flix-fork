mod CLIOpts {

    use Util.GetOpt
    use Util.GetOpt.OptionDescr
    use Util.GetOpt.ArgDescr
    use Util.GetOpt.ArgOrder

    ///
    /// Supported command-line option tokens.
    ///
    pub enum CliOption with Eq {
        case Help,
        case Console,
        case Seed(Int64)
    }

    ///
    /// Parsed command line options.
    ///
    pub type alias ParsedOpts = {
        help = Bool,
        console = Bool,
        seed = Option[Int64],
        errors = List[String]
    }

    ///
    /// Option descriptors defining flags, arguments, and help explanations.
    ///
    pub def options(): List[OptionDescr[CliOption]] =
        {
            optionIds = 'h' :: Nil,
            optionNames = "help" :: Nil,
            argDescriptor = ArgDescr.NoArg(CliOption.Help),
            explanation = "Display this help message and exit"
        } ::
        {
            optionIds = 'c' :: Nil,
            optionNames = "console" :: Nil,
            argDescriptor = ArgDescr.NoArg(CliOption.Console),
            explanation = "Launch in Console (Terminal) mode instead of default GUI"
        } ::
        {
            optionIds = 's' :: Nil,
            optionNames = "seed" :: Nil,
            argDescriptor = ArgDescr.ReqArg(s -> Int64.fromString(s) |> Option.map(CliOption.Seed), "SEED"),
            explanation = "Random seed for reproducible AI move selection"
        } :: Nil

    ///
    /// Generates the formatted usage help string.
    ///
    pub def usage(): String =
        GetOpt.usageInfo("Usage: tic-tac-toe [OPTIONS] [SEED]", options())

    ///
    /// Parses the given list of command-line arguments.
    /// Default interface mode is GUI unless --console / -c is passed.
    ///
    pub def parse(args: List[String]): ParsedOpts = {
        match GetOpt.getOpt(ArgOrder.Permute, options(), args) {
            case Validation.Success(res) => {
                let isHelp = List.exists(opt -> match opt { case CliOption.Help => true case _ => false }, res#options);
                let isConsole = List.exists(opt -> match opt { case CliOption.Console => true case _ => false }, res#options);
                let optSeed = List.findMap(opt -> match opt { case CliOption.Seed(s) => Some(s) case _ => None }, res#options);
                let posSeed = List.head(res#nonOptions) |> Option.flatMap(Int64.fromString);
                let finalSeed = match optSeed {
                    case Some(s) => Some(s)
                    case None => posSeed
                };
                let emptyErrors: List[String] = Nil;
                { help = isHelp, console = isConsole, seed = finalSeed, errors = emptyErrors }
            }
            case Validation.Failure(errs) => {
                { help = false, console = false, seed = None, errors = Nec.toList(errs) }
            }
        }
    }
}
