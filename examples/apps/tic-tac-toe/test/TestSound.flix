mod TestSound {

    @Test
    def testPlayHumanClickDoesNotThrow(): Unit \ IO = {
        Sound.playHumanClick()
    }

    @Test
    def testPlayAiClickDoesNotThrow(): Unit \ IO = {
        Sound.playAiClick()
    }

    @Test
    def testPlayWinFanfareDoesNotThrow(): Unit \ IO = {
        Sound.playWinFanfare()
    }

    @Test
    def testPlayLoseSoundDoesNotThrow(): Unit \ IO = {
        Sound.playLoseSound()
    }

    @Test
    def testPlayDrawSoundDoesNotThrow(): Unit \ IO = {
        Sound.playDrawSound()
    }
}
