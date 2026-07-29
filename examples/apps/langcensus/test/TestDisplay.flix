///
/// Tests for how activity is bucketed into languages.
///
/// These are the steps where the census can quietly lose or misattribute work, so
/// each case here corresponds to a defect that reached a generated report.
///
mod TestDisplay {

    use Assert.{assertEq, assertTrue}
    use CensusUtil.Stat

    /// A small extension table, so the tests do not depend on the generated one.
    def languages(): Map[String, String] = Map#{
        "rs" => "Rust",
        "md" => "Markdown",
        "scala" => "Scala"
    }

    /////////////////////////////////////////////////////////////////////////////
    // statFilter                                                              //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// A language a person only deleted code in is still work they did.
    ///
    /// This used to be dropped, because the filter required additions.
    ///
    @Test
    def deletionOnlyIsKept(): Unit \ Assert =
        let stat: Stat = Map#{"rs" => (0ii, 42ii)};
        assertEq(expected = stat, Display.statFilter(stat))

    ///
    /// An entry recording no change at all carries nothing and is dropped.
    ///
    @Test
    def emptyEntryIsDropped(): Unit \ Assert =
        let stat: Stat = Map#{"rs" => (0ii, 0ii)};
        assertEq(expected = (Map#{}: Stat), Display.statFilter(stat))

    @Test
    def additionsAreKept(): Unit \ Assert =
        let stat: Stat = Map#{"rs" => (10ii, 0ii)};
        assertEq(expected = stat, Display.statFilter(stat))

    /////////////////////////////////////////////////////////////////////////////
    // renameAndMerge                                                          //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// Extensions are resolved through the table, not by their own name.
    ///
    @Test
    def extensionsBecomeLanguages(): Unit \ Assert =
        let stat: Stat = Map#{"rs" => (10ii, 2ii)};
        assertEq(expected = (Map#{"Rust" => (10ii, 2ii)}: Stat),
                 Display.renameAndMerge(languages(), stat))

    ///
    /// Extensions differing only in case are the same language.
    ///
    @Test
    def lookupIgnoresCase(): Unit \ Assert =
        let stat: Stat = Map#{"RS" => (1ii, 0ii), "rs" => (2ii, 0ii)};
        assertEq(expected = (Map#{"Rust" => (3ii, 0ii)}: Stat),
                 Display.renameAndMerge(languages(), stat))

    ///
    /// Several extensions mapping to one language are summed, not overwritten.
    ///
    @Test
    def sameLanguageIsSummed(): Unit \ Assert =
        let table = Map#{"rs" => "Rust", "rlib" => "Rust"};
        let stat: Stat = Map#{"rs" => (10ii, 1ii), "rlib" => (5ii, 2ii)};
        assertEq(expected = (Map#{"Rust" => (15ii, 3ii)}: Stat),
                 Display.renameAndMerge(table, stat))

    ///
    /// An unknown extension is bucketed rather than discarded.
    ///
    /// Dropping it made the report look complete while losing the work.
    ///
    @Test
    def unknownExtensionIsBucketed(): Unit \ Assert =
        let stat: Stat = Map#{"wat" => (7ii, 3ii)};
        assertEq(expected = (Map#{Display.unclassified() => (7ii, 3ii)}: Stat),
                 Display.renameAndMerge(languages(), stat))

    ///
    /// Unknown extensions accumulate together instead of shadowing each other.
    ///
    @Test
    def unknownExtensionsAccumulate(): Unit \ Assert =
        let stat: Stat = Map#{"wat" => (7ii, 3ii), "zzz" => (1ii, 1ii)};
        assertEq(expected = (Map#{Display.unclassified() => (8ii, 4ii)}: Stat),
                 Display.renameAndMerge(languages(), stat))

    ///
    /// Known and unknown extensions coexist in one result.
    ///
    @Test
    def knownAndUnknownCoexist(): Unit \ Assert =
        let stat: Stat = Map#{"rs" => (10ii, 0ii), "wat" => (1ii, 0ii)};
        assertEq(expected = (Map#{"Rust" => (10ii, 0ii), Display.unclassified() => (1ii, 0ii)}: Stat),
                 Display.renameAndMerge(languages(), stat))

    /////////////////////////////////////////////////////////////////////////////
    // Empty input                                                             //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def emptyStatStaysEmpty(): Unit \ Assert =
        assertEq(expected = (Map#{}: Stat), Display.renameAndMerge(languages(), Map#{}))

    @Test
    def emptyTableBucketsEverything(): Unit \ Assert =
        let stat: Stat = Map#{"rs" => (4ii, 1ii)};
        assertEq(expected = (Map#{Display.unclassified() => (4ii, 1ii)}: Stat),
                 Display.renameAndMerge(Map#{}, stat))

}
