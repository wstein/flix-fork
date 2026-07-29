///
/// Tests for commit attribution.
///
mod TestAnalyse {

    use Assert.{assertEq, assertTrue}

    ///
    /// A commit present in a fork and its parent is counted once, not twice.
    ///
    @Test
    def forkSharedCommitCountedOnce(): Unit \ Assert =
        let commits = Map#{
            "alice/lib" => "aaa" :: "bbb" :: Nil,
            "bob/lib"   => "aaa" :: "ccc" :: Nil
        };
        let result = Analyse.dedupeBySha(commits);
        let total = result |> List.map(match (_, shas) -> List.length(shas)) |> List.sum;
        assertEq(expected = 3, total)

    ///
    /// The repository that keeps a shared commit does not depend on fetch order.
    ///
    @Test
    def deduplicationIsDeterministic(): Unit \ Assert =
        let commits = Map#{
            "alice/lib" => "aaa" :: Nil,
            "bob/lib"   => "aaa" :: Nil
        };
        assertEq(expected = ("alice/lib", "aaa" :: Nil) :: Nil, Analyse.dedupeBySha(commits))

    ///
    /// A repository left with no commits of its own is dropped.
    ///
    @Test
    def emptiedRepositoryIsDropped(): Unit \ Assert =
        let commits = Map#{
            "alice/lib" => "aaa" :: Nil,
            "bob/fork"  => "aaa" :: Nil
        };
        assertEq(expected = 1, List.length(Analyse.dedupeBySha(commits)))

    ///
    /// Repositories with no commits at all never reach the work queue.
    ///
    @Test
    def repositoryWithoutCommitsIsDropped(): Unit \ Assert =
        let commits = Map#{"alice/lib" => (Nil: List[String]), "bob/lib" => "aaa" :: Nil};
        assertEq(expected = ("bob/lib", "aaa" :: Nil) :: Nil, Analyse.dedupeBySha(commits))

    ///
    /// Nothing in, nothing out.
    ///
    @Test
    def emptyInput(): Unit \ Assert =
        assertEq(expected = (Nil: List[(String, List[String])]), Analyse.dedupeBySha(Map#{}))

    ///
    /// Distinct commits are all preserved.
    ///
    @Test
    def distinctCommitsArePreserved(): Unit \ Assert =
        let commits = Map#{"a/x" => "1" :: "2" :: Nil, "b/y" => "3" :: Nil};
        let total = Analyse.dedupeBySha(commits) |> List.map(match (_, s) -> List.length(s)) |> List.sum;
        assertEq(expected = 3, total)

}
