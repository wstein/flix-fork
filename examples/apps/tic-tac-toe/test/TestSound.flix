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
    def testAtVolumeQuietensWithoutSilencing(): Unit \ Assert = {
        let quiet = Sound.atVolume(20, Sound.winFanfare());

        // Quieter than written, but still audible, and still playable MIDI.
        Assert.assertTrue(List.sum(velocitiesOf(quiet)) < List.sum(velocitiesOf(Sound.winFanfare())));
        Assert.assertTrue(isPlayable(quiet))
    }

    @Test
    def testAtVolumeNeverReachesZero(): Unit \ Assert = {
        // Velocity 0 is a note *off* in MIDI, so rounding down to it would silence the
        // score rather than quieten it.
        let scores = Sound.humanClick() :: Sound.computerClick() :: Sound.winFanfare()
                  :: Sound.loseSound() :: Sound.drawSound() :: Nil;
        let atOnePercent = List.flatMap(sc -> velocitiesOf(Sound.atVolume(1, sc)), scores);
        Assert.assertTrue(List.forAll(v -> v >= 1, atOnePercent))
    }

    @Test
    def testAtVolumeKeepsPitchesAndTiming(): Unit \ Assert = {
        // Only loudness changes: the tune and its rhythm must survive.
        let original = Sound.winFanfare();
        let quiet = Sound.atVolume(20, original);
        assertEq(expected = pitchesOf(original), pitchesOf(quiet));
        assertEq(expected = Sound.duration(original), Sound.duration(quiet))
    }

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
