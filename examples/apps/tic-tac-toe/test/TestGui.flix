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
    def testAiMoveDelayMs(): Unit \ Assert = {
        assertEq(expected = 120i64, Gui.aiMoveDelayMs())
    }
}
