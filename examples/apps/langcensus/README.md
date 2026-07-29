# langcensus

Summarises which languages you have been working in, by reading your commits
through the GitHub API.

It produces a terminal summary and a `report.md` containing tables and Mermaid
charts.

## What it measures — and what it does not

The report contains **two different measurements over two different
populations**. They are not comparable and must not be added together.

|            | Churn by language                    | Composition by language              |
| :--------- | :----------------------------------- | :----------------------------------- |
| Counts     | Lines you added and deleted          | Bytes currently in the repository    |
| Whose work | Only commits attributed to you       | Everyone who ever contributed        |
| When       | Summed over history                  | As the repository stands now         |

Limitations that apply to both:

- Only commits reachable from each repository's **current default branch** are
  seen. Deleted branches, unmerged work and rebased-away history are invisible.
- A file's language is inferred from its extension. Some extensions are claimed
  by several languages and cannot be resolved from commit metadata alone.
- Vendored and generated files are excluded, so lock files do not dominate.
- Files whose extension no language claims are reported as `Unclassified` rather
  than dropped, and the overview says how many lines that is.

**These figures describe activity. They do not measure proficiency,
productivity, code quality or ownership.**

## Running it

```bash
export GITHUB_NAME=your-github-login
export GITHUB_TOKEN=ghp_...
java -jar artifact/langcensus.jar
```

Build it first with `flix build-jar`, or run it with `flix run`.

### Credentials

`GITHUB_TOKEN` needs only enough scope to read the repositories you want
counted. A fine-grained token with **read-only** access to repository contents
and metadata is sufficient; a classic token needs `public_repo`, or `repo` if
you opt into private repositories.

Prefer the narrowest scope that works. The tool only ever reads.

`GITHUB_NAME` selects whose commits are counted, and is independent of the
token. If the two disagree the run says so, because counting one person's
commits across another person's repositories otherwise looks entirely normal.

### Privacy

By default **only public repositories are counted**, because `report.md` names
every repository it counts and is meant to be shareable.

```bash
export LANGCENSUS_INCLUDE_PRIVATE=true   # opt in; the report will name private repositories
```

The run reports how many repositories it omitted.

The cache below stores complete API responses, including file paths. Treat
`.langcensus-cache/` as sensitive if you enable private repositories, and delete
it when you are done:

```bash
rm -rf .langcensus-cache
```

## Runtime and rate limits

The analysis makes roughly **one request per commit**, against a GitHub limit of
5000 requests per hour. A large account can exceed that in a single run: the
account this was developed against needs about 5900 requests.

The tool copes rather than failing:

- Commit responses are cached under `.langcensus-cache/`, keyed by SHA. A commit
  never changes, so a cached entry never needs invalidating, and a second run
  replays them locally in milliseconds instead of spending quota.
- Commit *lists* are fetched over GraphQL, which is priced separately, so
  listing costs the REST quota nothing.
- Eight workers fetch concurrently.
- When the quota runs out the run waits for the reset and continues, rather than
  abandoning the work already done.

It reports the projection up front:

```
Quota: 4981 requests left, resets at 10:40:05 (31 min). This run needs up to 5918.
```

The cache directory is versioned (`.langcensus-cache/v2`). A change to what a
cached response should contain bumps the version, so stale entries are ignored
rather than silently reused.

## Tests

```bash
flix test
```

The tests cover aggregation, extension classification, deletion-only changes,
unclassified files and fork deduplication. They use fixed fixtures and need no
network.

## Data

`data/languages.json` maps extensions to languages and is generated from
[github-linguist][linguist]. See `data/README.md`.

[linguist]: https://github.com/github-linguist/linguist
