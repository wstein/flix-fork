use Fs.{FileRead, FileWrite, MkDirs, ReadFile, WriteFile}
use Net.Http
use Time.Sleep
use Sys.{Env, Exit}

///
/// Gets the language usage statistics for the user.
///
/// Will print three kinds of statistics:
///   - The adds and dels of the user for each language for each repository.
///   - The total adds and dels of the user for each language.
///   - The total bytes of code for each language for all the reporitorites the user has participated in.
///
def main(): Unit \ IO + Chan + NonDet =
    run {
        let (repos, omitted) = Github.getRepos();
        let repoShaMap = Github.getCommits(repos);
        let statByRepo = Analyse.analyseByRepo(repoShaMap);
        let activeRepos =
            Map.toList(repoShaMap)
            |> List.filterMap(match (repo, shas) -> if (List.isEmpty(shas)) None else Some(repo));
        let statByByte = Analyse.analyseByByte(activeRepos);

        // Account totals first: they are the answer. The per-repository breakdown is
        // supporting detail and is printed only when asked for.
        Display.displayByUser(statByRepo);
        Display.displayByByte(statByByte);
        if (Config.verbose()) Display.displayByRepo(statByRepo) else ();

        let commitCount =
            Map.toList(repoShaMap) |> List.map(match (_, shas) -> List.length(shas)) |> List.sum;
        Display.writeReport({
            user = Config.getName(),
            tokenOwner = Github.tokenOwner(),
            generatedAt = CensusUtil.utcNow(),
            repositories = List.length(repos),
            commits = commitCount,
            omitted = omitted,
            includePrivate = Config.includePrivate()
        }, statByRepo, statByByte)
    } with Display.runWithIO
    with Analyse.runWithGithub
    with Github.runWithConfigHttp
    with Config.runWithEnv
    with Http.runWithIO
    with Sleep.runWithIO
    with ReadFile.runWithFileRead
    with WriteFile.runWithFileWrite
    with MkDirs.runWithFileWrite
    with FileRead.runWithIO
    with FileWrite.runWithIO
    with Env.runWithIO
    with AnalysisFailure.handleAnalysisFailure
    with Exit.runWithIO
