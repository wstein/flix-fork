mod TestCLIOpts {

    @Test
    def testDefaultOptions(): Unit \ Assert = {
        let parsed = CLIOpts.parse(Nil);
        Assert.assertEq(expected = false, parsed#help);
        Assert.assertEq(expected = false, parsed#console);
        Assert.assertEq(expected = None, parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testHelpShort(): Unit \ Assert = {
        let parsed = CLIOpts.parse("-h" :: Nil);
        Assert.assertEq(expected = true, parsed#help);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testHelpLong(): Unit \ Assert = {
        let parsed = CLIOpts.parse("--help" :: Nil);
        Assert.assertEq(expected = true, parsed#help);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testConsoleShort(): Unit \ Assert = {
        let parsed = CLIOpts.parse("-c" :: Nil);
        Assert.assertEq(expected = true, parsed#console);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testConsoleLong(): Unit \ Assert = {
        let parsed = CLIOpts.parse("--console" :: Nil);
        Assert.assertEq(expected = true, parsed#console);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testSeedShort(): Unit \ Assert = {
        let parsed = CLIOpts.parse("-s" :: "12345" :: Nil);
        Assert.assertEq(expected = Some(12345i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testSeedLong(): Unit \ Assert = {
        let parsed = CLIOpts.parse("--seed=99999" :: Nil);
        Assert.assertEq(expected = Some(99999i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testPositionalSeed(): Unit \ Assert = {
        let parsed = CLIOpts.parse("54321" :: Nil);
        Assert.assertEq(expected = Some(54321i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testCombinedConsoleAndSeed(): Unit \ Assert = {
        let parsed = CLIOpts.parse("--console" :: "-s" :: "42" :: Nil);
        Assert.assertEq(expected = true, parsed#console);
        Assert.assertEq(expected = Some(42i64), parsed#seed);
        Assert.assertTrue(List.isEmpty(parsed#errors))
    }

    @Test
    def testInvalidOption(): Unit \ Assert = {
        let parsed = CLIOpts.parse("--invalid-flag" :: Nil);
        Assert.assertTrue(not List.isEmpty(parsed#errors))
    }
}
