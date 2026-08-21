mod TestCliOpt {

    @Test
    def testDefaultOptions(): Unit \ Assert = {
        let parsed = CliOpt.parse(Nil);
        Assert.assertEq(expected = false, parsed#help);
        Assert.assertEq(expected = None, parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testHelpShort(): Unit \ Assert = {
        let parsed = CliOpt.parse("-h" :: Nil);
        Assert.assertEq(expected = true, parsed#help);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testHelpLong(): Unit \ Assert = {
        let parsed = CliOpt.parse("--help" :: Nil);
        Assert.assertEq(expected = true, parsed#help);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testSeedShort(): Unit \ Assert = {
        let parsed = CliOpt.parse("-s" :: "12345" :: Nil);
        Assert.assertEq(expected = Some(12345i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testSeedLong(): Unit \ Assert = {
        let parsed = CliOpt.parse("--seed=99999" :: Nil);
        Assert.assertEq(expected = Some(99999i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testSeedLongSpaceSeparated(): Unit \ Assert = {
        let parsed = CliOpt.parse("--seed" :: "77777" :: Nil);
        Assert.assertEq(expected = Some(77777i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testPositionalSeed(): Unit \ Assert = {
        let parsed = CliOpt.parse("54321" :: Nil);
        Assert.assertEq(expected = Some(54321i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testInvalidOption(): Unit \ Assert = {
        let parsed = CliOpt.parse("--invalid-flag" :: Nil);
        Assert.assertTrue(not List.isEmpty(parsed#errors))
    }

    @Test
    def testConsoleOptionIsNowInvalid(): Unit \ Assert = {
        let parsed = CliOpt.parse("--console" :: Nil);
        Assert.assertTrue(not List.isEmpty(parsed#errors))
    }
}
