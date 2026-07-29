///
/// Helpers shared across the analysis.
///
/// Named `CensusUtil` rather than `Util` because the standard library owns the
/// top-level `Util` module.
///
mod CensusUtil {
    import java.lang.System
    import java.time.Instant
    import java.time.ZoneId
    import java.time.format.DateTimeFormatter
    import java.lang.management.ManagementFactory
    import java.util.regex.Pattern
    use Fs.MkDirs
    use Fs.ReadFile
    use Fs.WriteFile
    use Net.Http
    use Time.Duration
    use Time.Sleep
    use Net.HttpRequest
    use Net.HttpResponse
    use Util.Json
    use Util.Json.FromJson
    use Util.Json.JsonError
    use Util.Json.Json.{JObject, JString, JNull}

    ///
    /// Prints `msg` prefixed with the whole seconds elapsed since the program started.
    ///
    /// The elapsed time comes from the JVM uptime so that every call shares one
    /// baseline, without threading a start timestamp through the effect handlers.
    ///
    pub def log(msg: String): Unit \ IO =
        println("[${elapsed()}] ${msg}")

    ///
    /// The elapsed wall-clock time, right-aligned, e.g. `" 76s"`.
    ///
    def elapsed(): String \ IO =
        let uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String.padLeft(4, ' ', "${uptimeMs / 1000i64}s")

    ///
    /// Writes `s` without a trailing newline, flushing so it appears immediately.
    ///
    def printInline(s: String): Unit \ IO =
        System.out.print(s);
        System.out.flush()

    ///
    /// How wide the progress line is drawn.
    ///
    /// Kept under 80 columns on purpose. A line wider than the terminal wraps, and a
    /// carriage return then only returns to the start of the last visual row, so the
    /// bar scrolls instead of redrawing in place.
    ///
    def progressWidth(): Int32 = 78

    ///
    /// Redraws a one-line progress bar in place.
    ///
    /// Uses a carriage return rather than a newline, because a per-commit line for
    /// thousands of commits scrolls everything else off the screen. Note that the
    /// bar is redrawn unconditionally, so redirecting output to a file collects one
    /// long line rather than a readable log.
    ///
    pub def progress(done: Int32, total: Int32, name: String, detail: String): Unit \ IO =
        let barWidth = 14;
        let filled = if (total <= 0) 0 else (done * barWidth) / total;
        let bar = String.repeat(filled, "=") ++ String.repeat(barWidth - filled, " ");
        let pct = if (total <= 0) 0 else (done * 100) / total;
        let prefix = "[${elapsed()}] [${bar}] ${counter(done, total)} ${String.padLeft(3, ' ', "${pct}")}%  ";
        let suffix = if (String.isEmpty(detail)) "" else " - ${detail}";
        // Give the name a fixed column and trim only it. Trimming the whole label
        // would cut the counter off the end, which is the part worth reading.
        let room = Int32.max(0, progressWidth() - String.length(prefix) - String.length(suffix));
        let line = prefix ++ String.padRight(room, ' ', String.takeLeft(room, name)) ++ suffix;
        // Pad to a fixed width so a shorter line cannot leave the tail of a longer
        // one behind on screen.
        printInline("\r" ++ String.padRight(progressWidth(), ' ', String.takeLeft(progressWidth(), line)))

    ///
    /// Ends the current progress line so later output starts on a fresh line.
    ///
    pub def progressDone(): Unit \ IO = println("")

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
    /// Bumped whenever a change alters what a cached response should contain, so that
    /// entries recorded by an older, less complete implementation are not reused.
    pub def cacheVersion(): String = "v2"

    pub def cacheDir(): String = ".langcensus-cache/${cacheVersion()}"

    ///
    /// The cache file holding the response for `sha` in `repo`.
    ///
    def cachePath(repo: String, sha: String): String =
        "${cacheDir()}/${String.replace(src = "/", dst = "_", repo)}_${sha}.json"

    ///
    /// Returns the cached response for `sha`, or `None` if it has not been fetched yet.
    ///
    pub def cacheGet(repo: String, sha: String): Option[Json] \ ReadFile =
        match ReadFile.read(cachePath(repo, sha)) {
            case Result.Err(_)   => None
            // A cache entry that will not parse is treated as a miss, so a partially
            // written file heals itself on the next run instead of poisoning it.
            case Result.Ok(body) => Json.parse(body) |> Result.toOption
        }

    ///
    /// Stores the response for `sha`. A failure to write is not fatal: the cache is
    /// an optimisation, so a broken cache should slow the run down, not stop it.
    ///
    pub def cachePut(repo: String, sha: String, json: Json): Unit \ MkDirs + WriteFile =
        MkDirs.mkDirs(cacheDir()) |> Result.map(_ ->
            WriteFile.write(str = Json.toCompactString(json), cachePath(repo, sha))
        ) |> _ -> ()

    ///
    /// How many repositories are fetched concurrently.
    ///
    /// Kept deliberately small: GitHub throttles bursts of concurrent requests, and
    /// the quota is shared, so more workers reach the rate limit sooner rather than
    /// finishing sooner.
    ///
    pub def workerCount(): Int32 = 8

    ///
    /// Returns the remaining REST quota and the seconds until it resets.
    ///
    /// Querying `/rate_limit` does not itself count against the quota.
    ///
    pub def quota(headers: Map[String, List[String]]): Option[(Int32, Int32)] \ Http + IO =
        match Http.send(HttpRequest.get("https://api.github.com/rate_limit") |> HttpRequest.withHeaders(headers)) {
            case Result.Err(_) => None
            case Result.Ok(response) =>
                // The quota is reported in the headers, so the body is not needed.
                if (HttpResponse.status(response) != 200)
                    None
                else
                    Some((remainingOf(response), secondsUntilReset(response)))
        }

    ///
    /// Returns the quota left according to `response`, or -1 if it did not say.
    ///
    def remainingOf(response: HttpResponse): Int32 =
        header("x-ratelimit-remaining", response)
        |> Option.flatMap(Int32.fromString)
        |> Option.getWithDefault(-1)

    ///
    /// Warns if `needed` requests will not fit in the remaining quota.
    ///
    /// The census issues roughly one request per uncached commit, so a large account
    /// can exceed an hour's quota outright. Saying so up front beats discovering it
    /// most of the way through a long run.
    ///
    pub def reportQuota(needed: Int32, headers: Map[String, List[String]]): Unit \ Http + IO =
        match quota(headers) {
            case None => ()
            case Some((remaining, resetSecs)) =>
                let mins = resetSecs / 60;
                log("Quota: ${remaining} requests left, resets at ${clockTime(resetSecs)} (${mins} min). This run needs up to ${needed}.");
                if (needed > remaining)
                    log("  That is more than remains. Cached commits cost nothing, and the run waits for the reset rather than giving up.")
                else
                    ()
        }

    ///
    /// Applies `f` to every element of `items` across `workers` threads, calling
    /// `onDone` on the collecting thread as each result arrives.
    ///
    /// `f` must be `IO`-only. A worker cannot perform an algebraic effect handled by
    /// its parent, because the handler lives on the parent's stack, so anything
    /// effectful has to install its own handlers inside `f`.
    ///
    /// Results come back in completion order, not input order.
    ///
    pub def parMap(workers: Int32, items: List[a], f: a -> b \ IO, onDone: (Int32, Int32, b) -> Unit \ IO): List[b] \ Chan + NonDet + IO =
        region rc {
            let total = List.length(items);
            let (workTx, workRx) = Channel.buffered(total + workers);
            let (resTx, resRx) = Channel.buffered(total);

            // Queue the work, then one `None` per worker to tell it to stop.
            foreach (x <- items) Channel.send(Some(x), workTx);
            foreach (_ <- List.range(0, workers)) Channel.send(None, workTx);

            foreach (_ <- List.range(0, workers)) spawn drain(workRx, resTx, f) @ rc;

            List.range(0, total) |> List.map(i -> {
                let r = Channel.recv(resRx);
                onDone(i + 1, total, r);
                r
            })
        }

    ///
    /// Pulls work until the queue is exhausted.
    ///
    def drain(rx: Receiver[Option[a]], tx: Sender[b], f: a -> b \ IO): Unit \ Chan + NonDet + IO =
        match Channel.recv(rx) {
            case None    => ()
            case Some(x) => Channel.send(f(x), tx); drain(rx, tx, f)
        }

    ///
    /// Sends a GraphQL query with `variables` and returns the `data` object.
    ///
    /// The body is assembled as a `Json` value and serialised, rather than spliced
    /// together as text, so the query and its arguments cannot be mis-escaped.
    ///
    /// GraphQL reports failures inside a 200 response, so `errors` is checked
    /// explicitly; a transport-level failure still goes through `checkStatus`.
    ///
    pub def graphQl(document: String, variables: Map[String, Json], headers: Map[String, List[String]]): Json \ Http + AnalysisFailure + IO =
        let url = "https://api.github.com/graphql";
        let body = Json.toCompactString(JObject(Map#{"query" => JString(document), "variables" => JObject(variables)}));
        match Http.send(HttpRequest.post(url, body) |> HttpRequest.withHeaders(headers)) {
            case Result.Err(err) => AnalysisFailure.raiseRequestFailure(err)
            case Result.Ok(response) =>
                checkStatus(url, response);
                let json = parse(url, HttpResponse.body(response));
                let errors: Result[JsonError, Json] = FromJson.decodeAtKey("errors", json);
                match errors {
                    case Result.Ok(errs) =>
                        AnalysisFailure.raiseHttpError("GitHub GraphQL reported an error: ${errs}")
                    case Result.Err(_) => accessJson("data", json)
                }
        }

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
    def parse(context: String, jsonString: String): Json \ AnalysisFailure =
        match Json.parse(jsonString) {
            case Result.Ok(json) => json
            case Result.Err(err) =>
                // Include the source and a slice of the payload: without them a parse
                // failure part-way through thousands of commits is undiagnosable.
                let excerpt = String.takeLeft(300, jsonString);
                AnalysisFailure.raiseParseError(
                    String.unlines(
                        "Could not parse a JSON response from GitHub." ::
                        "  source: ${context}" ::
                        "  error:  ${err}" ::
                        "  bytes:  ${String.length(jsonString)}" ::
                        "  begins: ${excerpt}" :: Nil))
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
    /// Renders when the quota resets, given the epoch-second `reset` header.
    ///
    def resetsAt(reset: String): String \ IO =
        match Int64.fromString(reset) {
            case None      => "an unknown time"
            case Some(secs) =>
                let nowSecs = System.currentTimeMillis() / 1000i64;
                let remaining = Int64.tryToInt32(secs - nowSecs) |> Option.getWithDefault(0);
                if (remaining <= 0) "any moment now" else "${clockTime(remaining)} (${remaining / 60} min)"
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
                        "  The limit resets at ${resetsAt(reset)}." ::
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
    /// Renders the local wall-clock time `inSeconds` from now, as `HH:mm:ss`.
    ///
    /// A waiting message is easier to act on as a time to come back than as a
    /// duration the reader has to add to the clock themselves.
    ///
    def clockTime(inSeconds: Int32): String \ IO =
        let millis = System.currentTimeMillis() + Int32.toInt64(inSeconds) * 1000i64;
        let zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        zoned.format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    ///
    /// Returns how many seconds remain until the quota in `response` resets.
    ///
    def secondsUntilReset(response: HttpResponse): Int32 \ IO =
        match header("x-ratelimit-reset", response) |> Option.flatMap(Int64.fromString) {
            case None       => 60
            case Some(secs) =>
                let nowSecs = System.currentTimeMillis() / 1000i64;
                Int32.max(1, Int64.tryToInt32(secs - nowSecs) |> Option.getWithDefault(60))
        }

    ///
    /// Returns `true` if `response` was refused because the quota is exhausted.
    ///
    def isRateLimited(response: HttpResponse): Bool =
        let status = HttpResponse.status(response);
        (status == 403 or status == 429)
            and header("x-ratelimit-remaining", response) == Some("0")

    ///
    /// Sends a GET request to `url` carrying the given `headers`.
    ///
    /// If the quota is exhausted, waits for it to reset and tries again rather than
    /// abandoning the census: with commit responses cached, a run that resumes after
    /// the reset picks up where it left off. Gives up after `attempts` waits so a
    /// misreported quota cannot loop forever.
    ///
    def getWithin(attempts: Int32, url: String, headers: Map[String, List[String]]): Result[IoError, HttpResponse] \ Http + Sleep + IO =
        let response = Http.send(HttpRequest.get(url) |> HttpRequest.withHeaders(headers));
        match response {
            case Result.Ok(r) =>
                if (isRateLimited(r) and attempts > 0) {
                    let waitSecs = secondsUntilReset(r);
                    log("Quota exhausted. Waiting until ${clockTime(waitSecs)} (${waitSecs / 60} min) for it to reset, then retrying.");
                    Sleep.sleep(Duration.seconds(waitSecs + 1));
                    getWithin(attempts - 1, url, headers)
                } else
                    response
            case Result.Err(_) => response
        }

    def get(url: String, headers: Map[String, List[String]]): Result[IoError, HttpResponse] \ Http + Sleep + IO =
        getWithin(3, url, headers)

    ///
    /// First gets the url, then parses the whole response body into a json element.
    ///
    pub def getAndParse(url: String, header: Map[String, List[String]]): Json \ Http + AnalysisFailure + Sleep + IO =
        match get(url, header) {
            case Result.Ok(response)  =>
                checkStatus(url, response);
                parse(url, HttpResponse.body(response))
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
    pub def getAllPages(url: String, headers: Map[String, List[String]]): List[Json] \ Http + AnalysisFailure + Sleep + IO = {
        match get(url, headers) {
            case Result.Ok(response)  =>
                let status = HttpResponse.status(response);
                // GitHub answers 409 for a repository that has no commits at all, and
                // 404 for one that is gone or invisible to this token. Both are ordinary
                // facts about a repository, not reasons to abandon the census.
                if (status == 409)
                    Nil
                else if (status == 404) {
                    log("  skipping ${url}: not found (renamed, deleted, or not visible to this token)");
                    Nil
                } else {
                checkStatus(url, response);
                let thisPage: List[Json] = parse(url, HttpResponse.body(response)) |> fromJson;
                let nextPages = match nextLink(response) {
                    case Some(next) => getAllPages(next, headers)
                    case None       => Nil
                };
                thisPage ::: nextPages
                }
            case Result.Err(err) => AnalysisFailure.raiseRequestFailure(err)
        }
    }

    ///
    /// Returns the `rel="next"` link of a paginated response, if there is one.
    ///
    def nextLink(response: HttpResponse): Option[String] \ IO =
        forM (
            linkValueList <- HttpResponse.headers(response) |> Map.get("link");
            linkString <- List.head(linkValueList);
            pattern = Pattern.compile("<([^>]*)>; rel=\"next\"");
            matcher = pattern.matcher(linkString);
            if (matcher.find());
            next = matcher.group(1)
        ) yield next

    ///
    /// Collects the `files` of a commit across every page of the response.
    ///
    /// A commit touching more than 300 files is paginated, and reading only the first
    /// response silently undercounts it. One commit in the repositories this was
    /// tested against spans five pages.
    ///
    pub def getAllFiles(url: String, headers: Map[String, List[String]]): List[Json] \ Http + AnalysisFailure + Sleep + IO =
        match get(url, headers) {
            case Result.Err(err) => AnalysisFailure.raiseRequestFailure(err)
            case Result.Ok(response) =>
                let status = HttpResponse.status(response);
                if (status == 409 or status == 404)
                    Nil
                else {
                    checkStatus(url, response);
                    let json = parse(url, HttpResponse.body(response));
                    let files: List[Json] = accessJson("files", json);
                    match nextLink(response) {
                        case Some(next) => files ::: getAllFiles(next, headers)
                        case None       => files
                    }
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
