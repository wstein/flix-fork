use CensusUtil.Stats

///
/// The effect to display different analysis results.
///
eff Display {
    def displayByUser(stat: Stats): Unit
    def displayByRepo(stat: Stats): Unit
    def displayByByte(stat: Map[String, BigInt]): Unit

    ///
    /// Writes the census to a Markdown file.
    ///
    def writeReport(user: String, includePrivate: Bool, byRepo: Stats, byByte: Map[String, BigInt]): Unit
}

mod Display{
    use CensusUtil.Stat
    use CensusUtil.Stats
    use Fs.ReadFile
    use Fs.WriteFile
    use Util.Json
    use Util.Json.FromJson
    use Util.Json.JsonError

    ///
    /// Handles the display effect through IO.
    ///
    pub def runWithIO(f: Unit -> a \ ef): a \ ef - Display + WriteFile + ReadFile + IO = {
        let languages = loadLanguages();
        run {
            f()
        } with handler Display {
            def displayByRepo(stat, resume) =  {
                println("\n==================== Analysis Result Per Repo ====================\n");
                Map.forEach((repo, thisStat) -> {
                    println("\nRepository: ${repo}");
                    thisStat |> statFilter |> renameAndMerge(languages) |> sortMapByValue |> printEachLanguage
                }, stat);
                resume()
            }

            def displayByUser(stats, resume) = {
                println("\n==================== Analysis Result For User ====================\n");
                region rc {
                    let map = MutMap.empty(rc);
                    foreach (
                        (_repo, stat) <- stats;
                        (ext, data) <- stat
                    ) {
                        MutMap.putWith(
                            match (oldAdds, oldDels) -> match (newAdds, newDels) -> (oldAdds + newAdds, oldDels + newDels),
                            ext, data, map
                        )
                    };
                    map |> MutMap.toMap |> statFilter |> renameAndMerge(languages) |> sortMapByValue |> printEachLanguage;
                    resume()
                }
            }

            def writeReport(user, includePrivate, byRepo, byByte, resume) = {
                let path = "report.md";
                match WriteFile.write(str = renderReport(user, includePrivate, languages, byRepo, byByte), path) {
                    case Result.Ok(_)  => println("\nWrote ${path}")
                    case Result.Err(e) => println("\nCould not write ${path}: ${e}")
                };
                resume()
            }

            def displayByByte(stat, resume) = {
                println("\n==================== Analysis Result By Bytes ====================\n");
                stat
                |> sortMapByValue
                |> List.forEach(match (lang, size) -> println("${lang}: ${size} Bytes")) ;
                resume()
            }
        }
    }

    ///
    /// Drops entries that record no change at all.
    ///
    /// Deletions count as work. Requiring additions discarded every language a person
    /// only removed code in, which is exactly the activity a cleanup or a migration
    /// consists of.
    ///
    pub def statFilter(stat: Stat): Stat =
        Map.filter(match (adds, dels) -> adds > 0ii or dels > 0ii, stat)

    ///
    /// Prints the stat for each language.
    ///
    def printEachLanguage(stat: List[(String, (BigInt, BigInt))]): Unit \ IO =
        List.forEach(match (language, (adds, dels)) -> println("${language}: ${adds} additions, ${dels} deletions."), stat)

    ///
    /// Renames the extention to langauges and merge entries of the same language.
    ///
    ///
    /// The bucket for files whose extension no language claims.
    ///
    /// Naming the leftovers keeps the totals honest: silently dropping them made the
    /// report look complete while quietly losing work.
    ///
    pub def unclassified(): String = "Unclassified"

    pub def renameAndMerge(languages: Map[String, String], stat: Stat): Stat =
        Map.foldLeftWithKey((acc, ext, data) -> {
            match Map.get(String.toLowerCase(ext), languages) {
                case Option.None        =>
                    Map.insertWith(
                        match (oldAdds, oldDels) -> match (newAdds, newDels) -> (oldAdds + newAdds, oldDels + newDels),
                        unclassified(), data, acc
                    )
                case Option.Some(language) =>
                    Map.insertWith(
                        match (oldAdds, oldDels) -> match (newAdds, newDels) -> (oldAdds + newAdds, oldDels + newDels),
                        language, data, acc
                    )
            }
        }, Map.empty(), stat)

    ///
    /// Sorts the map by converting it to a list.
    ///
    def sortMapByValue(map: Map[k, v]): List[(k, v)] with Order[v] = Map.toList(map) |> List.sortBy(match (_, v) -> v)

    ///
    /// Loads the extension-to-language table.
    ///
    /// The table is generated from github-linguist's `languages.yml`, which is the
    /// database GitHub itself uses, rather than maintained by hand here.
    ///
    /// Extensions are looked up in lower case. Linguist resolves an extension that
    /// several languages claim by inspecting file contents, which a census working
    /// from commit metadata cannot do, so the generated table pins the common
    /// ambiguous cases instead.
    ///
    def loadLanguages(): Map[String, String] \ ReadFile + IO =
        let path = "data/languages.json";
        match ReadFile.read(path) {
            case Result.Err(err) =>
                println("Could not read ${path}: ${err}");
                Map.empty()
            case Result.Ok(body) => match Json.parse(body) {
                case Result.Err(err) =>
                    println("Could not parse ${path}: ${err}");
                    Map.empty()
                case Result.Ok(json) =>
                    let decoded: Result[JsonError, Map[String, String]] = FromJson.fromJson(json);
                    match decoded {
                        case Result.Ok(table) => table
                        case Result.Err(err)  =>
                            println("Could not decode ${path}: ${err}");
                            Map.empty()
                    }
            }
        }

    /////////////////////////////////////////////////////////////////////////////
    // Markdown report                                                         //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// How many slices the language pie chart shows before folding the rest into
    /// "Other". Part-to-whole reads at a glance only while the slice count is small.
    ///
    def pieSlices(): Int32 = 5

    ///
    /// How many bars the lines-changed chart shows.
    ///
    def barCount(): Int32 = 10

    ///
    /// Renders the census as a GitHub-flavoured Markdown document.
    ///
    def renderReport(user: String, includePrivate: Bool, languages: Map[String, String], byRepo: Stats, byByte: Map[String, BigInt]): String =
        let langs = mergeRepos(byRepo) |> statFilter |> renameAndMerge(languages);
        let langRows = descending(langs);
        let byteRows = descending(byByte);
        let scope = if (includePrivate) "public and private" else "public";
        String.unlines(
            ("# GitHub default-branch contribution summary" ::
             "" ::
             "Generated by [langcensus](https://github.com/flix/flix/tree/master/examples/apps/langcensus)" ::
             "for `${user}`, covering ${scope} repositories." ::
             "" :: Nil)
            ::: methodologySection(includePrivate)
            ::: overviewSection(byRepo, langRows, byteRows)
            ::: bytesSection(byteRows)
            ::: linesSection(langRows)
            ::: reposSection(languages, byRepo))

    ///
    /// The headline numbers, as a table rather than a chart: a handful of unrelated
    /// totals is not a shape, it is a list of facts.
    ///
    def methodologySection(includePrivate: Bool): List[String] =
        "## What these numbers are" ::
        "" ::
        "This report contains **two different measurements over two different populations**." ::
        "They are not comparable and should not be added together." ::
        "" ::
        "| | Churn by language | Composition by language |" ::
        "| :-- | :-- | :-- |" ::
        "| Counts | Lines you added and deleted | Bytes currently in the repository |" ::
        "| Whose work | Only commits attributed to you | Everyone who ever contributed |" ::
        "| When | Summed over history | As the repository stands now |" ::
        "" ::
        "Limitations that affect both:" ::
        "" ::
        "- Only commits reachable from each repository's **current default branch** are seen." ::
        "  Deleted branches, unmerged work and rebased-away history are not counted." ::
        (if (includePrivate)
            "- Private repositories are **included**, so this report names repositories that may not be public."
         else
            "- Only **public** repositories are included. Set `LANGCENSUS_INCLUDE_PRIVATE=true` to count private ones.") ::
        "- Vendored and generated files are excluded, so lock files and `node_modules` do not dominate." ::
        "- A file's language is inferred from its extension, which is ambiguous for some extensions." ::
        "- These figures describe activity. They do not measure proficiency, productivity or ownership." ::
        "" :: Nil

    def overviewSection(byRepo: Stats, langRows: List[(String, (BigInt, BigInt))], byteRows: List[(String, BigInt)]): List[String] =
        let repos = Map.size(byRepo);
        let adds = langRows |> List.foldLeft((acc, row) -> match row { case (_, (a, _)) => acc + a }, 0ii);
        let dels = langRows |> List.foldLeft((acc, row) -> match row { case (_, (_, d)) => acc + d }, 0ii);
        let bytes = byteRows |> List.foldLeft((acc, row) -> match row { case (_, b) => acc + b }, 0ii);
        "## Overview" ::
        "" ::
        "| | |" ::
        "| :-- | --: |" ::
        let unclassifiedLines =
            langRows
            |> List.filter(match (lang, _) -> lang == unclassified())
            |> List.foldLeft((acc, row) -> match row { case (_, (a, d)) => acc + a + d }, 0ii);
        let changed = adds + dels;
        "| Repositories with counted commits | ${group(repos)} |" ::
        "| Languages you changed | ${group(List.length(langRows))} |" ::
        "| Languages present in those repositories | ${group(List.length(byteRows))} |" ::
        "| Lines in files no language claims | ${groupBig(unclassifiedLines)} (${percent(unclassifiedLines, changed)}) |" ::
        "| Lines added | ${groupBig(adds)} |" ::
        "| Lines deleted | ${groupBig(dels)} |" ::
        "| Bytes of code | ${groupBig(bytes)} |" ::
        "" :: Nil

    ///
    /// Share of code by language. Part-to-whole, so a pie is legible here, but only
    /// for a few slices; the remainder is folded into "Other" and the full list is
    /// in the table below.
    ///
    def bytesSection(byteRows: List[(String, BigInt)]): List[String] =
        let total = byteRows |> List.foldLeft((acc, row) -> match row { case (_, b) => acc + b }, 0ii);
        let top = List.take(pieSlices(), byteRows);
        let rest = List.drop(pieSlices(), byteRows) |> List.foldLeft((acc, row) -> match row { case (_, b) => acc + b }, 0ii);
        let slices = top |> List.map(match (lang, b) -> "    \"${lang}\" : ${b}");
        let other = if (rest > 0ii) "    \"Other\" : ${rest}" :: Nil else Nil;
        ("## Composition by language — whole repositories, everyone's work" ::
         "" ::
         "```mermaid" ::
         "pie showData" ::
         "    title Bytes of code" :: Nil)
        ::: slices ::: other :::
        ("```" ::
         "" ::
         "| Language | Bytes | Share |" ::
         "| :-- | --: | --: |" :: Nil)
        ::: (byteRows |> List.map(match (lang, b) -> "| ${lang} | ${groupBig(b)} | ${percent(b, total)} |"))
        ::: ("" :: Nil)

    ///
    /// Lines changed by language.
    ///
    /// One bar series, not two. Additions and deletions share a unit, but Mermaid
    /// cannot label two series clearly enough for identity to survive, so the chart
    /// carries the total and the table carries the split.
    ///
    def linesSection(langRows: List[(String, (BigInt, BigInt))]): List[String] =
        let byChanged = langRows
            |> List.sortBy(match (_, (a, d)) -> 0ii - (a + d));
        let top = List.take(barCount(), byChanged);
        let names = top |> List.map(match (lang, _) -> "\"${lang}\"") |> List.join(", ");
        let values = top |> List.map(match (_, (a, d)) -> "${a + d}") |> List.join(", ");
        ("## Churn by language — your commits only" ::
         "" ::
         "```mermaid" ::
         "xychart-beta" ::
         "    title \"Lines changed, top ${barCount()} languages\"" ::
         "    x-axis [${names}]" ::
         "    y-axis \"Lines changed\"" ::
         "    bar [${values}]" ::
         "```" ::
         "" ::
         "| Language | Added | Deleted | Net |" ::
         "| :-- | --: | --: | --: |" :: Nil)
        ::: (byChanged |> List.map(match (lang, (a, d)) ->
                "| ${lang} | ${groupBig(a)} | ${groupBig(d)} | ${groupBig(a - d)} |"))
        ::: ("" :: Nil)

    ///
    /// Per-repository totals, largest first.
    ///
    def reposSection(languages: Map[String, String], byRepo: Stats): List[String] =
        let rows = Map.toList(byRepo)
            |> List.map(match (repo, stat) -> {
                let merged = stat |> statFilter |> renameAndMerge(languages);
                let adds = Map.foldLeft((acc, v) -> match v { case (a, _) => acc + a }, 0ii, merged);
                let dels = Map.foldLeft((acc, v) -> match v { case (_, d) => acc + d }, 0ii, merged);
                (repo, adds, dels, Map.size(merged))
            })
            |> List.filter(match (_, a, _, _) -> a > 0ii)
            |> List.sortBy(match (_, a, d, _) -> 0ii - (a + d));
        ("## Repositories" ::
         "" ::
         "| Repository | Languages | Added | Deleted |" ::
         "| :-- | --: | --: | --: |" :: Nil)
        ::: (rows |> List.map(match (repo, a, d, n) ->
                "| ${repo} | ${n} | ${groupBig(a)} | ${groupBig(d)} |"))
        ::: ("" :: Nil)

    ///
    /// Sums every repository's per-extension stat into one.
    ///
    def mergeRepos(byRepo: Stats): Stat =
        Map.foldLeft((acc, stat) ->
            Map.foldLeftWithKey((inner, ext, data) ->
                Map.insertWith(
                    match (oldAdds, oldDels) -> match (newAdds, newDels) -> (oldAdds + newAdds, oldDels + newDels),
                    ext, data, inner
                ), acc, stat),
            Map.empty(), byRepo)

    ///
    /// Sorts a map into a list, largest value first.
    ///
    def descending(map: Map[k, v]): List[(k, v)] with Order[k], Order[v] =
        Map.toList(map) |> List.sortBy(match (_, v) -> v) |> List.reverse

    ///
    /// Renders `n` of `total` as a percentage with one decimal.
    ///
    def percent(n: BigInt, total: BigInt): String =
        if (total == 0ii) "0.0%"
        else
            let tenths = (n * 1000ii) / total;
            "${tenths / 10ii}.${tenths `BigInt.remainder` 10ii}%"

    ///
    /// Groups digits in threes, so long numbers stay readable.
    ///
    def groupBig(n: BigInt): String = groupString("${n}")

    def group(n: Int32): String = groupString("${n}")

    def groupString(s: String): String =
        let len = String.length(s);
        if (len <= 3) s
        else groupString(String.takeLeft(len - 3, s)) ++ "," ++ String.drop(len - 3, s)
}
