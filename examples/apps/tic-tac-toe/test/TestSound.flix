mod TestSound {

    @Test
    def testPlayHumanClickDoesNotThrow(): Unit \ IO = {
        Sound.playHumanClick()
    }

    @Test
    def testPlayAiClickDoesNotThrow(): Unit \ IO = {
        Sound.playAiClick()
    }
}
