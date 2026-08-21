mod TestSound {

    use Assert.assertEq

    ///
    /// Returns the pitches of a score, in order, flattening chords.
    ///
    def pitchesOf(score: List[Sound.Step]): List[Int32] =
        List.flatMap(s -> match s { case Sound.Step.Step(ps, _, _) => ps }, score)

    ///
    /// Returns the lowest note of each step --- the melodic line, with chords reduced
    /// to their root. Comparing flattened pitches instead would let the top of a final
    /// chord stand in for where the line actually went.
    ///
    def rootsOf(score: List[Sound.Step]): List[Int32] =
        List.filterMap(s -> match s { case Sound.Step.Step(ps, _, _) => List.minimum(ps) }, score)

    ///
    /// Returns the velocities of a score.
    ///
    def velocitiesOf(score: List[Sound.Step]): List[Int32] =
        List.map(s -> match s { case Sound.Step.Step(_, v, _) => v }, score)

    ///
    /// MIDI only defines note numbers and velocities from 0 to 127; anything outside
    /// that range is silently dropped or clipped by the synthesizer.
    ///
    def isPlayable(score: List[Sound.Step]): Bool =
        List.forAll(p -> p >= 0 and p <= 127, pitchesOf(score))
            and List.forAll(v -> v >= 1 and v <= 127, velocitiesOf(score))
            and Sound.duration(score) > 0i64

    @Test
    def testEveryScoreIsPlayable(): Unit \ Assert = {
        Assert.assertTrue(isPlayable(Sound.humanClick()));
        Assert.assertTrue(isPlayable(Sound.computerClick()));
        Assert.assertTrue(isPlayable(Sound.winFanfare()));
        Assert.assertTrue(isPlayable(Sound.loseSound()));
        Assert.assertTrue(isPlayable(Sound.drawSound()))
    }

    @Test
    def testTheTwoClicksAreDistinguishable(): Unit \ Assert = {
        // The players must not sound alike, or the board stops being readable by ear.
        Assert.assertTrue(pitchesOf(Sound.humanClick()) != pitchesOf(Sound.computerClick()))
    }

    @Test
    def testClicksAreShort(): Unit \ Assert = {
        // A click must fit inside one frame at 30fps, or placing a piece feels laggy.
        Assert.assertTrue(Sound.duration(Sound.humanClick()) <= 33i64);
        Assert.assertTrue(Sound.duration(Sound.computerClick()) <= 33i64)
    }

    @Test
    def testWinRisesAndLoseFalls(): Unit \ Assert = {
        // The two outcomes must be told apart with the eyes closed.
        let win = rootsOf(Sound.winFanfare());
        let lose = rootsOf(Sound.loseSound());
        Assert.assertTrue(List.last(win) > List.head(win));
        Assert.assertTrue(List.last(lose) < List.head(lose))
    }

    @Test
    def testFanfareEndsOnAChord(): Unit \ Assert = {
        let endsOnChord = score -> match List.last(score) {
            case Some(Sound.Step.Step(ps, _, _)) => List.length(ps) > 1
            case None => false
        };
        Assert.assertTrue(endsOnChord(Sound.winFanfare()));
        Assert.assertTrue(endsOnChord(Sound.loseSound()))
    }
}
