///
/// Helpers shared across the analysis.
///
/// Named `CensusUtil` rather than `Util` because the standard library owns the
/// top-level `Util` module.
///
mod CensusUtil {
    import java.lang.System
    import java.lang.management.ManagementFactory
    import java.util.regex.Pattern
    use Fs.MkDirs
    use Fs.ReadFile
    use Fs.WriteFile
    use Net.Http
    use Net.HttpRequest
    use Net.HttpResponse
    use Util.Json
    use Util.Json.FromJson

    ///
    /// Prints `msg` prefixed with the whole seconds elapsed since the program started.
    ///
    /// The elapsed time comes from the JVM uptime so that every call shares one
    /// baseline, without threading a start timestamp through the effect handlers.
    ///
    pub def log(msg: String): Unit \ IO =
        let uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        let secs = String.padLeft(4, ' ', "${uptimeMs / 1000i64}s");
        println("[${secs}] ${msg}")

    ///
    /// Formats `i` of `total` as a right-aligned counter, e.g. `" 20/106"`.
    ///
    /// Right-aligning keeps the repository names in a single column, so a long run
    /// stays scannable.
    ///
    pub def counter(i: Int32, total: Int32): String =
        let width = String.length("${total}");
        "${String.padLeft(width, ' ', "${i}")}/${total}"

    ///
    /// Where cached commit responses are kept.
    ///
    /// A commit is immutable once written, so its SHA is a sound cache key and an
    /// entry never needs invalidating.
    ///
    pub def cacheDir(): String = ".langcensus-cache"

    ///
    /// The cache file holding the response for `sha` in `repo`.
    ///
    def cachePath(repo: String, sha: String): String =
        "${cacheDir()}/${String.replace(src = "/", dst = "_", repo)}_${sha}.json"

    ///
    /// Returns the cached response for `sha`, or `None` if it has not been fetched yet.
    ///
    pub def cacheGet(repo: String, sha: String): Option[Json] \ ReadFile + AnalysisFailure =
        match ReadFile.read(cachePath(repo, sha)) {
            case Result.Err(_)   => None
            case Result.Ok(body) => Some(parse(body))
        }

    ///
    /// Stores the response for `sha`. A failure to write is not fatal: the cache is
    /// an optimisation, so a broken cache should slow the run down, not stop it.
    ///
    pub def cachePut(repo: String, sha: String, json: Json): Unit \ MkDirs + WriteFile =
        MkDirs.mkDirs(cacheDir()) |> Result.map(_ ->
            WriteFile.write(str = Json.toCompactString(json), cachePath(repo, sha))
        ) |> _ -> ()

    pub type alias Stat = Map[String, (BigInt, BigInt)]

    pub type alias Stats = Map[String, Stat]

    ///
    /// A wrapper for FromJson.fromJson, with the error handled by effect.
    ///
    pub def fromJson(json: Json): a \ AnalysisFailure with FromJson[a] =
        match FromJson.fromJson(json) {
            case Result.Ok(res)  => res
            case Result.Err(err) => AnalysisFailure.raiseJsonError(err)
        }

    ///
    /// Gets the value of the corresponding key from the json element, which is supposed to be a map.
    ///
    pub def accessJson(key: String, json: Json): a \ AnalysisFailure with FromJson[a] =
        match FromJson.decodeAtKey(key, json) {
            case Result.Ok(res)  => res
            case Result.Err(err) => AnalysisFailure.raiseJsonError(err)
        }

    ///
    /// A wrapper for Json.parse, with the error handled by effect.
    ///
    def parse(jsonString: String): Json \ AnalysisFailure =
        match Json.parse(jsonString) {
            case Result.Err(_)   => AnalysisFailure.raiseParseError()
            case Result.Ok(json) => json
        }

    ///
    /// Returns the first value of the response header `name`, matched case-insensitively.
    ///
    def header(name: String, response: HttpResponse): Option[String] =
        HttpResponse.headers(response)
        |> Map.toList
        |> List.filterMap(match (k, vs) ->
            if (String.toLowerCase(k) == name) List.head(vs) else None)
        |> List.head

    ///
    /// Renders how long until the epoch-second `reset`, for the rate limit message.
    ///
    def resetsIn(reset: String): String \ IO =
        match Int64.fromString(reset) {
            case None      => "an unknown time"
            case Some(secs) =>
                let nowSecs = System.currentTimeMillis() / 1000i64;
                let mins = (secs - nowSecs) / 60i64;
                if (mins <= 0i64) "less than a minute" else "${mins} minutes"
        }

    ///
    /// Raises a descriptive failure unless `response` succeeded.
    ///
    /// Without this a rate-limited run looked like a successful one: `getAllPages`
    /// treated any non-200 as "no more data" and returned an empty list, so the
    /// report came out full of zeroes.
    ///
    def checkStatus(url: String, response: HttpResponse): Unit \ AnalysisFailure + IO =
        let status = HttpResponse.status(response);
        if (status >= 200 and status < 300)
            ()
        else
            let remaining = header("x-ratelimit-remaining", response) |> Option.getWithDefault("");
            if ((status == 403 or status == 429) and remaining == "0")
                let reset = header("x-ratelimit-reset", response) |> Option.getWithDefault("");
                AnalysisFailure.raiseHttpError(
                    String.unlines(
                        "GitHub rejected a request: HTTP ${status}, rate limit exhausted." ::
                        "  ${url}" ::
                        "  The limit resets in ${resetsIn(reset)}." ::
                        "  Authenticated requests get a far higher quota than anonymous ones." ::
                        "  Set GITHUB_TOKEN to a personal access token with 'repo' scope, or wait for the reset." :: Nil))
            else if (status == 401)
                AnalysisFailure.raiseHttpError(
                    String.unlines(
                        "GitHub rejected a request: HTTP 401, the token was not accepted." ::
                        "  ${url}" ::
                        "  Check that GITHUB_TOKEN is set to a valid personal access token with 'repo' scope." :: Nil))
            else
                AnalysisFailure.raiseHttpError("GitHub rejected a request: HTTP ${status} for ${url}.")

    ///
    /// Sends a GET request to `url` carrying the given `headers`.
    ///
    def get(url: String, headers: Map[String, List[String]]): Result[IoError, HttpResponse] \ Http =
        Http.send(HttpRequest.get(url) |> HttpRequest.withHeaders(headers))

    ///
    /// First gets the url, then parses the whole response body into a json element.
    ///
    pub def getAndParse(url: String, header: Map[String, List[String]]): Json \ Http + AnalysisFailure + IO =
        match get(url, header) {
            case Result.Ok(response)  =>
                checkStatus(url, response);
                response |> HttpResponse.body |> parse
            case Result.Err(err) => AnalysisFailure.raiseRequestFailure(err)
        }

    ///
    /// Gets all the data page by page.
    ///
    /// We assume the link to the next page is stored in the link field of the header, with a rel="next" specification.
    /// Example:
    ///   link: <https://api.github.com/repositories/1300192/issues?page=2>; rel="prev",
    ///   <https://api.github.com/repositories/1300192/issues?page=4>; rel="next",
    ///   <https://api.github.com/repositories/1300192/issues?page=515>; rel="last",
    ///   <https://api.github.com/repositories/1300192/issues?page=1>; rel="first"
    ///
    pub def getAllPages(url: String, headers: Map[String, List[String]]): List[Json] \ Http + AnalysisFailure + IO = {
        match get(url, headers) {
            case Result.Ok(response)  =>
                checkStatus(url, response);
                let thisPage: List[Json] = response |> HttpResponse.body |> parse |> fromJson;
                let nextPagesOpt = forM (
                    linkValueList <- HttpResponse.headers(response) |> Map.get("link");
                    linkString <- List.head(linkValueList);
                    pattern = Pattern.compile("<([^>]*)>; rel=\"next\"");
                    matcher = pattern.matcher(linkString);
                    if (matcher.find());
                    nextLink = matcher.group(1)
                ) yield getAllPages(nextLink, headers);
                let nextPages = Option.getWithDefault(Nil, nextPagesOpt);
                thisPage ::: nextPages
            case Result.Err(err) => AnalysisFailure.raiseRequestFailure(err)
        }
    }

    ///
    /// Filters out files that are likely generated by machine
    ///
    pub def isGeneratedFilePath(filePath: String): Bool = {
        let generatedPaths = regex".*(/?dist/|/?build/|/?_build/|/?out/|/?target/).*" :: regex".*(package-lock\\.json|yarn\\.lock)$" :: regex".*/?node_modules/.*" :: regex".*(ssl\\.crt|\\.env|\\.config\\.js)$" :: Nil;
        List.exists(generatedPath -> {
            Regex.isSubmatch(generatedPath, filePath)
        }, generatedPaths)
    }

    ///
    /// Extracts the extension from a file path.
    ///
    /// To extract the extension, the algorithm is:
    ///   - take only the substring after the last '/' character
    ///   - take only the substring after the last '.' character if its index is not the first or the last
    ///
    pub def extractExt(filePath: String): Option[String] = {
        let lastPart = String.takeWhileRight(c -> c != '/', filePath);
        match String.findIndexOfRight(c -> c == '.', lastPart) {
            case Option.Some(idx) if (idx != 0 and idx != String.length(lastPart) - 1) =>
                Some(String.drop(idx + 1, lastPart))
            case _ => None
        }
    }
}
