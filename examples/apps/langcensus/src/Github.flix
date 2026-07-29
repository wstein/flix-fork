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
    use Util.Json
    use Util.Json.Json.{JString, JNull}
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
    /// The GraphQL document listing a user's commits on a repository's default branch.
    ///
    /// One request returns up to 100 SHAs and costs a single point, against a budget
    /// separate from the REST quota, so listing the whole account costs a rounding
    /// error rather than one REST request per page.
    ///
    def commitsDocument(): String =
        String.unlines(
            "query($owner: String!, $name: String!, $author: ID!, $cursor: String) {" ::
            "  repository(owner: $owner, name: $name) {" ::
            "    defaultBranchRef { target { ... on Commit {" ::
            "      history(first: 100, author: {id: $author}, after: $cursor) {" ::
            "        pageInfo { hasNextPage endCursor }" ::
            "        nodes { oid }" ::
            "      } } }" ::
            "  } } }" :: Nil)

    ///
    /// Resolves the node id of `login`, which is how GraphQL identifies a commit author.
    ///
    /// REST filters commits by username; GraphQL wants an id, so it is looked up once
    /// and reused for every repository.
    ///
    def authorId(login: String, headers: Map[String, List[String]]): String \ Http + AnalysisFailure + IO =
        let doc = "query($login: String!) { user(login: $login) { id } }";
        let data = CensusUtil.graphQl(doc, Map#{"login" => JString(login)}, headers);
        CensusUtil.accessJson("id", CensusUtil.accessJson("user", data))

    ///
    /// Fetches the SHAs of `name`'s commits in `repo`.
    ///
    /// Runs inside a pool worker, so it installs its own `Http` handler and turns a
    /// failure into a value instead of raising it: a worker must not exit the
    /// process out from under the other workers.
    ///
    def fetchCommits(author: String, headers: Map[String, List[String]], repo: String): Result[String, (String, List[String])] \ IO =
        run {
            Ok((repo, shasOf(author, headers, repo, None)))
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
    /// Pages through `repo`'s history, collecting SHAs.
    ///
    /// A repository with no commits has no default branch, so GraphQL returns null
    /// where REST answers 409. Both mean the same thing: nothing to count.
    ///
    def shasOf(author: String, headers: Map[String, List[String]], repo: String, cursor: Option[String]): List[String] \ Http + AnalysisFailure + IO =
        let (owner, name) = splitRepo(repo);
        let vars = Map#{
            "owner"  => JString(owner),
            "name"   => JString(name),
            "author" => JString(author),
            "cursor" => match cursor { case Some(c) => JString(c) case None => JNull }
        };
        let data = CensusUtil.graphQl(commitsDocument(), vars, headers);
        let branch: Json = CensusUtil.accessJson("defaultBranchRef", CensusUtil.accessJson("repository", data));
        match branch {
            case Json.JNull => Nil
            case _ =>
                let history = CensusUtil.accessJson("history", CensusUtil.accessJson("target", branch));
                let nodes: List[Json] = CensusUtil.accessJson("nodes", history);
                let shas = nodes |> List.map(CensusUtil.accessJson("oid"));
                let pageInfo = CensusUtil.accessJson("pageInfo", history);
                let hasNext: Bool = CensusUtil.accessJson("hasNextPage", pageInfo);
                if (hasNext) {
                    let endCursor: String = CensusUtil.accessJson("endCursor", pageInfo);
                    shas ::: shasOf(author, headers, repo, Some(endCursor))
                } else
                    shas
        }

    ///
    /// Splits "owner/name" into its two halves.
    ///
    def splitRepo(repo: String): (String, String) =
        match String.indexOf(substr = "/", repo) {
            case Some(i) => (String.takeLeft(i, repo), String.drop(i + 1, repo))
            case None    => (repo, repo)
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
                let author = authorId(Config.getName(), headers);

                let results = CensusUtil.parMap(
                    CensusUtil.workerCount(),
                    repos,
                    repo -> fetchCommits(author, headers, repo),
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
