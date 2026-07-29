use CensusUtil.Stat
use CensusUtil.Stats

///
/// The effect to analyse a user's github data.
///
eff Analyse {
    ///
    /// Analyses the data in the granularity of repositories.
    ///
    def analyseByRepo(commits: Map[String, List[String]]): Stats

    ///
    /// Analyses the data by counting the byte of code for each language.
    ///
    def analyseByByte(repos: List[String]): Map[String, BigInt]
}

mod Analyse {
    use CensusUtil.Stat
    use Util.Json

    ///
    /// Handles analysis effect through Github Effect.
    ///
    pub def runWithGithub(f: Unit -> a \ ef): a \ ef - Analyse + Github + AnalysisFailure + Chan + NonDet + IO = {
        run {
            f()
        } with handler Analyse {
            def analyseByRepo(commits, resume) =
                let headers = Github.credentials();

                // Repos the user has no commits in contribute nothing, so drop them:
                // they would only produce empty work and noisy output.
                let pairs =
                    Map.toList(commits)
                    |> List.filter(match (_, shas) -> not List.isEmpty(shas))
                    |> List.flatMap(match (repo, shas) -> shas |> List.map(sha -> (repo, sha)));

                // Fetching a commit is a network round trip, so the whole phase is
                // spread over the pool rather than walked one repository at a time.
                let fetched = CensusUtil.parMap(
                    CensusUtil.workerCount(),
                    pairs,
                    match (repo, sha) -> Github.fetchCommitJson(headers, repo, sha) |> Result.map(json -> (repo, json)),
                    (i, total, res) -> match res {
                        case Result.Ok((repo, _)) =>
                            CensusUtil.log("[${CensusUtil.counter(i, total)}] ${repo}")
                        case Result.Err(_) => ()
                    }
                );

                let allStats : List[(String, Stat)] = fetched |> List.map(res -> match res {
                    case Result.Ok((repo, json)) => (repo, statOf(json))
                    case Result.Err(msg)         => AnalysisFailure.raiseHttpError(msg)
                });
                mergeStats(allStats) |> resume

            def analyseByByte(repos, resume) = {
                let repoCount = List.length(repos);
                let allStats: List[(String, BigInt)] =
                    repos |> List.mapWithIndex((i, repo) -> {
                        CensusUtil.log("[${CensusUtil.counter(i + 1, repoCount)}] ${repo}: languages");
                        let languageStat = Github.getLanguages(repo);
                        let languageStatMap: Map[String, BigInt] = CensusUtil.fromJson(languageStat);
                        Map.toList(languageStatMap)
                    }) |> List.flatten;
                mergeStatsByByte(allStats) |> resume
            }
        }
    }

    ///
    /// Collect adds and dels from all changed valid files in a commit.
    ///
    def statOf(commit: Json): Stat \ AnalysisFailure = {
        CensusUtil.accessJson("files", commit) |> List.foldLeft((acc, file) -> {
            let filename =  CensusUtil.accessJson("filename", file);
            let extOpt =  filename |> CensusUtil.extractExt;
            match extOpt {
                case Option.None  => acc
                case Option.Some(ext) =>
                    let additions = CensusUtil.accessJson("additions", file);
                    let deletions = CensusUtil.accessJson("deletions", file);
                    Map.insertWith(
                        match (old_adds, old_dels) -> match (new_adds, new_dels) -> (old_adds + new_adds, old_dels + new_dels),
                        ext, (additions, deletions), acc
                    )
                }
        }, Map.empty())
    }

    ///
    /// Convert a list of repository name and stat to a map from language name to stat, with stats for the same repository merged.
    ///
    def mergeStats(stats: List[(String, Stat)]): Map[String, Stat] =
        List.foldLeft(
            match (acc) -> match (repo, stat) -> Map.insertWith(mergeStat, repo, stat, acc),
            Map.empty(), stats
        )

    ///
    /// Merge the stat from one commit to the accumulated stat, which should belong to the same repository.
    ///
    def mergeStat(oldStat: Stat, stat: Stat): Stat = {
        Map.foldLeftWithKey((acc, lang, data) -> {
            Map.insertWith(
                match (oldAdds, oldDels) -> match (newAdds, newDels) -> (oldAdds + newAdds, oldDels + newDels),
                lang, data, acc
            )
        }, oldStat, stat)
    }

    ///
    /// Convert a list of langauge name and bytes to a map from language name to bytes, with bytes for the same language merged.
    ///
    def mergeStatsByByte(allStat: List[(String, BigInt)]): Map[String, BigInt] =
        List.foldLeft(
            match (acc) -> match(lang, bytes) -> Map.insertWith((old_bytes, new_bytes) -> old_bytes + new_bytes, lang, bytes, acc),
            Map.empty(), allStat
        )
}
