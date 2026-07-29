use CensusUtil.Stats
use Net.Http
use Util.Json

///
/// Gets all sorts of data from github.
/// Will get the user and token through Config effect.
///
pub eff Github {
    ///
    /// Gets a list of the full name of repositories that the user has contributed to, regardless of privacy.
    ///
    def getRepos(): List[String]

    ///
    /// Gets all the commits from the user in the given repositories.
    ///
    /// Returns a map from the repository name to a list of commit shas.
    ///
    def getCommits(repos: List[String]): Map[String, List[String]]

    ///
    /// Gets all the language data from the given repository name.
    ///
    /// Returns the json of lanuage data.
    ///
    /// The language data should be a map from language name to the byte of code for this language in this repository.
    ///
    def getLanguages(repo: String): Json

    ///
    /// Gets the detailed statistic of a specific commit in the repository.
    ///
    def getCommit(repo: String, sha: String): Json
}

mod Github {
    use Time.Sleep
    use Fs.MkDirs
    use Fs.ReadFile
    use Fs.WriteFile
    use Net.Http
    use CensusUtil.accessJson

    ///
    /// The general header for a github request.
    ///
    def buildHeader(): Map[String, List[String]] \ Config = {
        let token = Config.getToken();
        Map#{
            "Accept"                => "application/vnd.github+json" :: Nil,
            "Authorization"         => "Bearer ${token}" :: Nil,
            "X-Github-Api-Version"  => "2022-11-28" :: Nil
        }
    }

    ///
    /// Fetches the SHAs of `name`'s commits in `repo`.
    ///
    /// Runs inside a pool worker, so it installs its own `Http` handler and turns a
    /// failure into a value instead of raising it: a worker must not exit the
    /// process out from under the other workers.
    ///
    def fetchCommits(name: String, headers: Map[String, List[String]], repo: String): Result[String, (String, List[String])] \ IO =
        run {
            let url = "https://api.github.com/repos/${repo}/commits?author=${name}&per_page=100";
            let shas = CensusUtil.getAllPages(url, headers) |> List.map(CensusUtil.accessJson("sha"));
            Ok((repo, shas))
        } with Http.runWithIO
        with Sleep.runWithIO
        with handler AnalysisFailure {
            def raiseRequestFailure(err, _k) = Err("${err}")
            def raiseHttpError(msg, _k)      = Err(msg)
            def raiseConfigError(msg, _k)    = Err(msg)
            def raiseParseError(msg, _k)     = Err(msg)
            def raiseJsonError(err, _k)      = Err("${err}")
        }

    ///
    /// The handler that gets data from github through REST API.
    ///
    pub def runWithConfigHttp(f: Unit -> a \ ef): a \ ef - Github + Config + Http + AnalysisFailure + ReadFile + WriteFile + MkDirs + Chan + NonDet + Sleep + IO = {
        run {
            f()
        } with handler Github {
            def getRepos(resume) = {
                CensusUtil.log("Fetching repositories...");
                let repos =
                    CensusUtil.getAllPages("https://api.github.com/user/repos?affiliation=owner,collaborator&per_page=100", buildHeader())
                    |> List.map(repoJson -> CensusUtil.accessJson("full_name", repoJson));
                CensusUtil.log("Found ${List.length(repos)} repositories.");
                resume(repos)
            }

            def getCommits(repos, resume) = {
                // Resolve the credentials once, here: a worker cannot reach the
                // `Config` handler, which lives on this thread's stack.
                let headers = buildHeader();
                let name = Config.getName();

                let results = CensusUtil.parMap(
                    CensusUtil.workerCount(),
                    repos,
                    repo -> fetchCommits(name, headers, repo),
                    (i, total, res) -> match res {
                        case Result.Ok((repo, shas)) =>
                            CensusUtil.log("[${CensusUtil.counter(i, total)}] ${repo}: ${List.length(shas)} commits")
                        case Result.Err(_) => ()
                    }
                );

                // Failures are reported back on this thread, where exiting is safe.
                let commits = results |> List.map(res -> match res {
                    case Result.Ok(pair) => pair
                    case Result.Err(msg) => AnalysisFailure.raiseHttpError(msg)
                });

                let needed = commits |> List.map(match (_, shas) -> List.length(shas)) |> List.sum;
                CensusUtil.reportQuota(needed, headers);

                commits |> List.toMap |> resume
            }

            def getLanguages(repo, resume) = {
                let url = "https://api.github.com/repos/${repo}/languages";
                CensusUtil.getAndParse(url, buildHeader()) |> resume
            }

           def getCommit(repo, sha, resume) = {
                // A commit never changes, so a cache hit is always valid.
                match CensusUtil.cacheGet(repo, sha) {
                    case Some(json) => resume(json)
                    case None =>
                        let url = "https://api.github.com/repos/${repo}/commits/${sha}";
                        let json = CensusUtil.getAndParse(url, buildHeader());
                        CensusUtil.cachePut(repo, sha, json);
                        resume(json)
                }
           }
        }
    }
}
