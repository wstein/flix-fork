mod TestGui {

    use Assert.assertEq

    @Test
    def testCellAtInside(): Unit \ Assert = {
        let ox = Gui.boardOffsetX();
        let oy = Gui.boardOffsetY();
        let sz = Gui.cellSize();

        // Top-left cell (0) center
        assertEq(expected = Some(0), Gui.cellAt(ox + sz * 0.5f32, oy + sz * 0.5f32));

        // Center cell (4) center
        assertEq(expected = Some(4), Gui.cellAt(ox + sz * 1.5f32, oy + sz * 1.5f32));

        // Bottom-right cell (8) center
        assertEq(expected = Some(8), Gui.cellAt(ox + sz * 2.5f32, oy + sz * 2.5f32));

        // Top-right cell (2) center
        assertEq(expected = Some(2), Gui.cellAt(ox + sz * 2.5f32, oy + sz * 0.5f32));

        // Bottom-left cell (6) center
        assertEq(expected = Some(6), Gui.cellAt(ox + sz * 0.5f32, oy + sz * 2.5f32))
    }

    @Test
    def testCellAtOutside(): Unit \ Assert = {
        let ox = Gui.boardOffsetX();
        let oy = Gui.boardOffsetY();
        let sz = Gui.cellSize();

        // Above board
        assertEq(expected = None, Gui.cellAt(ox + sz, oy - 10.0f32));

        // Below board
        assertEq(expected = None, Gui.cellAt(ox + sz, oy + sz * 3.0f32 + 10.0f32));

        // Left of board
        assertEq(expected = None, Gui.cellAt(ox - 10.0f32, oy + sz));

        // Right of board
        assertEq(expected = None, Gui.cellAt(ox + sz * 3.0f32 + 10.0f32, oy + sz))
    }

    @Test
    def testIsRestartClicked(): Unit \ Assert = {
        let rx = Gui.restartButtonX();
        let ry = Gui.restartButtonY();
        let rw = Gui.restartButtonW();
        let rh = Gui.restartButtonH();

        // Inside button
        assertEq(expected = true, Gui.isRestartClicked(rx + rw * 0.5f32, ry + rh * 0.5f32));
        assertEq(expected = true, Gui.isRestartClicked(rx, ry));
        assertEq(expected = true, Gui.isRestartClicked(rx + rw, ry + rh));

        // Outside button
        assertEq(expected = false, Gui.isRestartClicked(rx - 5.0f32, ry));
        assertEq(expected = false, Gui.isRestartClicked(rx, ry - 5.0f32));
        assertEq(expected = false, Gui.isRestartClicked(rx + rw + 5.0f32, ry));
        assertEq(expected = false, Gui.isRestartClicked(rx, ry + rh + 5.0f32))
    }

    @Test
    def testIsCloseClicked(): Unit \ Assert = {
        let cx = Gui.closeButtonX();
        let cy = Gui.closeButtonY();
        let cw = Gui.closeButtonW();
        let ch = Gui.closeButtonH();

        // Inside button
        assertEq(expected = true, Gui.isCloseClicked(cx + cw * 0.5f32, cy + ch * 0.5f32));
        assertEq(expected = true, Gui.isCloseClicked(cx, cy));
        assertEq(expected = true, Gui.isCloseClicked(cx + cw, cy + ch));

        // Outside button
        assertEq(expected = false, Gui.isCloseClicked(cx - 5.0f32, cy));
        assertEq(expected = false, Gui.isCloseClicked(cx, cy - 5.0f32));
        assertEq(expected = false, Gui.isCloseClicked(cx + cw + 5.0f32, cy));
        assertEq(expected = false, Gui.isCloseClicked(cx, cy + ch + 5.0f32))
    }

    @Test
    def testButtonsDoNotOverlap(): Unit \ Assert = {
        // A click must never mean both "new game" and "quit".
        Assert.assertTrue(Gui.restartButtonX() + Gui.restartButtonW() < Gui.closeButtonX());

        // Neither button may hang off the 450px canvas.
        Assert.assertTrue(Gui.closeButtonX() + Gui.closeButtonW() <= 450.0f32);
        Assert.assertTrue(Gui.restartButtonX() >= 0.0f32)
    }

    @Test
    def testButtonsAreClearOfTheBoard(): Unit \ Assert = {
        // Clicking a button must not also drop a piece on the board.
        let boardBottom = Gui.boardOffsetY() + Gui.cellSize() * 3.0f32;
        Assert.assertTrue(Gui.restartButtonY() > boardBottom);
        Assert.assertTrue(Gui.closeButtonY() > boardBottom);
        assertEq(expected = None, Gui.cellAt(Gui.closeButtonX(), Gui.closeButtonY()));
        assertEq(expected = None, Gui.cellAt(Gui.restartButtonX(), Gui.restartButtonY()))
    }

    @Test
    def testAiMoveDelayMs(): Unit \ Assert = {
        assertEq(expected = 540i64, Gui.aiMoveDelayMs())
    }

    @Test
    def testAutoModeTiming(): Unit \ Assert = {
        assertEq(expected = 3000i64, Gui.autoModeIdleMs());

        // The idle threshold must exceed a move delay, or a game against a human would
        // slip into auto mode while they were simply watching the computer reply.
        Assert.assertTrue(Gui.autoModeIdleMs() > Gui.aiMoveDelayMs());

        // A finished game must stay on screen long enough to read before it is dealt again.
        Assert.assertTrue(Gui.autoRestartDelayMs() >= Gui.aiMoveDelayMs())
    }
}
