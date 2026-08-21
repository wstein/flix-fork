mod TestGui {

    use Assert.assertEq

    @Test
    def testCellAtInside(): Unit \ Assert = {
        let ox = Gui.boardOffsetX();
        let oy = Gui.boardOffsetY();
        let sz = Gui.cellSize();
        let mid = sz / 2;

        // Top-left cell (0) centre
        assertEq(expected = Some(0), Gui.cellAt(ox + mid, oy + mid));

        // Centre cell (4)
        assertEq(expected = Some(4), Gui.cellAt(ox + sz + mid, oy + sz + mid));

        // Bottom-right cell (8)
        assertEq(expected = Some(8), Gui.cellAt(ox + sz * 2 + mid, oy + sz * 2 + mid));

        // Top-right cell (2)
        assertEq(expected = Some(2), Gui.cellAt(ox + sz * 2 + mid, oy + mid));

        // Bottom-left cell (6)
        assertEq(expected = Some(6), Gui.cellAt(ox + mid, oy + sz * 2 + mid))
    }

    @Test
    def testCellAtEdges(): Unit \ Assert = {
        let ox = Gui.boardOffsetX();
        let oy = Gui.boardOffsetY();
        let sz = Gui.cellSize();

        // The top-left pixel of the board belongs to cell 0; the pixel just past the
        // bottom-right corner belongs to no cell. Integer pixels make these exact.
        assertEq(expected = Some(0), Gui.cellAt(ox, oy));
        assertEq(expected = Some(8), Gui.cellAt(ox + sz * 3 - 1, oy + sz * 3 - 1));
        assertEq(expected = None, Gui.cellAt(ox + sz * 3, oy + sz * 3))
    }

    @Test
    def testCellAtOutside(): Unit \ Assert = {
        let ox = Gui.boardOffsetX();
        let oy = Gui.boardOffsetY();
        let sz = Gui.cellSize();

        assertEq(expected = None, Gui.cellAt(ox + sz, oy - 10));          // above
        assertEq(expected = None, Gui.cellAt(ox + sz, oy + sz * 3 + 10)); // below
        assertEq(expected = None, Gui.cellAt(ox - 10, oy + sz));          // left
        assertEq(expected = None, Gui.cellAt(ox + sz * 3 + 10, oy + sz))  // right
    }

    @Test
    def testIsCloseClicked(): Unit \ Assert = {
        let cx = Gui.closeButtonX();
        let cy = Gui.closeButtonY();
        let cw = Gui.closeButtonW();
        let ch = Gui.closeButtonH();

        // Inside
        assertEq(expected = true, Gui.isCloseClicked(cx + cw / 2, cy + ch / 2));
        assertEq(expected = true, Gui.isCloseClicked(cx, cy));
        assertEq(expected = true, Gui.isCloseClicked(cx + cw, cy + ch));

        // Outside
        assertEq(expected = false, Gui.isCloseClicked(cx - 5, cy));
        assertEq(expected = false, Gui.isCloseClicked(cx, cy - 5));
        assertEq(expected = false, Gui.isCloseClicked(cx + cw + 5, cy));
        assertEq(expected = false, Gui.isCloseClicked(cx, cy + ch + 5))
    }

    @Test
    def testCloseButtonIsCentredAndClearOfTheBoard(): Unit \ Assert = {
        // The only button, so it sits in the middle.
        assertEq(expected = Gui.canvasWidth() / 2, Gui.closeButtonX() + Gui.closeButtonW() / 2);

        // Below the board and inside the canvas, so a click on it cannot also place a
        // piece or fall off the window.
        Assert.assertTrue(Gui.closeButtonY() > Gui.boardOffsetY() + Gui.cellSize() * 3);
        Assert.assertTrue(Gui.closeButtonX() >= 0);
        Assert.assertTrue(Gui.closeButtonX() + Gui.closeButtonW() <= Gui.canvasWidth());
        assertEq(expected = None, Gui.cellAt(Gui.closeButtonX(), Gui.closeButtonY()))
    }

    @Test
    def testBoardAndControlsFitTheCanvas(): Unit \ Assert = {
        Assert.assertTrue(Gui.boardOffsetX() + Gui.cellSize() * 3 <= Gui.canvasWidth());
        Assert.assertTrue(Gui.closeButtonY() + Gui.closeButtonH() < Gui.canvasHeight())
    }

    @Test
    def testDemoVolumeIsQuietButAudible(): Unit \ Assert = {
        assertEq(expected = 20, Gui.demoVolumePercent());

        // Below full volume, but not muted: 0 would be silence, 100 no reduction.
        Assert.assertTrue(Gui.demoVolumePercent() > 0);
        Assert.assertTrue(Gui.demoVolumePercent() < 100);

        // And it must survive the trip through Sound without silencing anything.
        let quiet = Sound.atVolume(Gui.demoVolumePercent(), Sound.winFanfare());
        assertEq(expected = Sound.duration(Sound.winFanfare()), Sound.duration(quiet))
    }

    @Test
    def testDemoOverlayIsTranslucent(): Unit \ Assert = {
        // Opaque would hide the board it is watermarking; zero would be invisible.
        Assert.assertTrue(Gui.demoOverlayAlpha() > 0);
        Assert.assertTrue(Gui.demoOverlayAlpha() < 255)
    }

    @Test
    def testDemoOverlaySpanStaysInsideTheWindow(): Unit \ Assert = {
        // The word is scaled to this fraction of the diagonal. At 1.0 it would touch the
        // corners and the outer letters would be clipped; at 0.0 it would vanish.
        Assert.assertTrue(Gui.demoOverlaySpan() > 0.0f64);
        Assert.assertTrue(Gui.demoOverlaySpan() < 1.0f64)
    }

    @Test
    def testClickOnBoardTakesOverFromTheDemo(): Unit \ Assert = {
        let sz = Gui.cellSize();
        let bx = Gui.boardOffsetX() + sz + sz / 2;
        let by = Gui.boardOffsetY() + sz + sz / 2;
        Assert.assertTrue(Gui.clickTakesOverDemo(Gui.autoModeIdleMs(), bx, by))
    }

    @Test
    def testClickOffTheBoardLeavesTheDemoRunning(): Unit \ Assert = {
        let idle = Gui.autoModeIdleMs();

        // Close already means something, and the margin means nothing; neither should
        // throw away a game somebody is watching.
        assertEq(expected = false, Gui.clickTakesOverDemo(idle, Gui.closeButtonX(), Gui.closeButtonY()));
        assertEq(expected = false, Gui.clickTakesOverDemo(idle, 5, 5))
    }

    @Test
    def testClickDuringANormalGameIsNotATakeover(): Unit \ Assert = {
        let sz = Gui.cellSize();
        let bx = Gui.boardOffsetX() + sz + sz / 2;
        let by = Gui.boardOffsetY() + sz + sz / 2;

        // Below the idle threshold the demo is not running, so a click on the board is
        // an ordinary move and must not clear it.
        assertEq(expected = false, Gui.clickTakesOverDemo(0i64, bx, by));
        assertEq(expected = false, Gui.clickTakesOverDemo(Gui.autoModeIdleMs() - 1i64, bx, by));

        // And exactly at the threshold it is a takeover, matching the loop's own test.
        Assert.assertTrue(Gui.clickTakesOverDemo(Gui.autoModeIdleMs(), bx, by))
    }

    @Test
    def testFinishedGameClearsItselfInTime(): Unit \ Assert = {
        assertEq(expected = 3000i64, Gui.newGameDelayMs());

        // Long enough for the win fanfare to finish, or games would cut each other off.
        Assert.assertTrue(Gui.newGameDelayMs() > Sound.duration(Sound.winFanfare()));
        Assert.assertTrue(Gui.newGameDelayMs() > Sound.duration(Sound.loseSound()));

        // And no longer than the wait before the demo starts, so an abandoned game is
        // always cleared before the demo picks the board up.
        Assert.assertTrue(Gui.newGameDelayMs() <= Gui.autoModeIdleMs())
    }

    @Test
    def testAiMoveDelayMs(): Unit \ Assert = {
        assertEq(expected = 840i64, Gui.aiMoveDelayMs())
    }

    @Test
    def testAutoModeTiming(): Unit \ Assert = {
        assertEq(expected = 10000i64, Gui.autoModeIdleMs());

        // The idle threshold must exceed a move delay, or a game against a human would
        // slip into auto mode while they were simply watching the computer reply.
        Assert.assertTrue(Gui.autoModeIdleMs() > Gui.aiMoveDelayMs())
    }

    @Test
    def testFrameRateIsSmoothButNotBusy(): Unit \ Assert = {
        // Between about 20 and 60 frames a second: fast enough not to look stepped,
        // slow enough that the loop is not spinning.
        Assert.assertTrue(Gui.frameDelayMs() >= 16i64);
        Assert.assertTrue(Gui.frameDelayMs() <= 50i64);

        // A move must not land in the same frame as the click that prompted it.
        Assert.assertTrue(Gui.aiMoveDelayMs() > Gui.frameDelayMs())
    }
}
