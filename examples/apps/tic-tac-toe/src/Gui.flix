mod Gui {

    import java.lang.System
    import java.lang.Thread
    import java.lang.Object
    import java.util.concurrent.atomic.AtomicReference
    import processing.core.PApplet
    import processing.core.PConstants

    ///
    /// Cell dimensions and coordinates in the GUI window.
    ///
    pub def canvasWidth(): Int32 = 450
    pub def canvasHeight(): Int32 = 550

    pub def boardOffsetX(): Float32 = 45.0f32
    pub def boardOffsetY(): Float32 = 100.0f32
    pub def cellSize(): Float32 = 120.0f32
    pub def restartButtonX(): Float32 = 55.0f32
    pub def restartButtonY(): Float32 = 480.0f32
    pub def restartButtonW(): Float32 = 160.0f32
    pub def restartButtonH(): Float32 = 40.0f32
    pub def closeButtonX(): Float32 = 235.0f32
    pub def closeButtonY(): Float32 = 480.0f32
    pub def closeButtonW(): Float32 = 160.0f32
    pub def closeButtonH(): Float32 = 40.0f32

    ///
    /// How long the game waits before making a move, so that the computer does not
    /// answer faster than the eye can follow.
    ///
    pub def aiMoveDelayMs(): Int64 = 840i64

    ///
    /// How long the window may sit untouched before the game starts playing itself.
    ///
    pub def autoModeIdleMs(): Int64 = 10000i64

    ///
    /// How long a finished game stays on screen before auto mode deals a new one.
    ///
    pub def autoRestartDelayMs(): Int64 = 1800i64

    ///
    /// How loud auto mode plays, as a percentage of the written velocity. The demo runs
    /// unattended, so it should be audible without demanding attention.
    ///
    pub def demoVolumePercent(): Int32 = 20

    ///
    /// How opaque the "DEMO" watermark is, out of 255, and how much of the window's
    /// diagonal the word spans.
    ///
    pub def demoOverlayAlpha(): Float32 = 40.0f32
    pub def demoOverlaySpan(): Float32 = 0.72f32

    ///
    /// The horizontal centre of the canvas, where the headings are drawn.
    ///
    pub def canvasCenterX(): Float32 = Int32.toFloat32(canvasWidth()) / 2.0f32
    pub def canvasCenterY(): Float32 = Int32.toFloat32(canvasHeight()) / 2.0f32

    ///
    /// Returns the cell index (0-8) corresponding to canvas coordinates (x, y), if within bounds.
    ///
    pub def cellAt(x: Float32, y: Float32): Option[Int32] = {
        let ox = boardOffsetX();
        let oy = boardOffsetY();
        let size = cellSize();
        let boardDim = size * 3.0f32;

        if (x >= ox and x < ox + boardDim and y >= oy and y < oy + boardDim) {
            let col = Float32.truncateToInt32((x - ox) / size);
            let row = Float32.truncateToInt32((y - oy) / size);
            if (col >= 0 and col < 3 and row >= 0 and row < 3)
                Some(row * 3 + col)
            else
                None
        } else {
            None
        }
    }

    ///
    /// Checks if coordinates (x, y) fall inside the rectangle at (bx, by) of size (bw, bh).
    ///
    def isInside(x: Float32, y: Float32, bx: Float32, by: Float32, bw: Float32, bh: Float32): Bool =
        x >= bx and x <= bx + bw and y >= by and y <= by + bh

    ///
    /// Checks if coordinates (x, y) are within the "New Game" button.
    ///
    pub def isRestartClicked(x: Float32, y: Float32): Bool =
        isInside(x, y, restartButtonX(), restartButtonY(), restartButtonW(), restartButtonH())

    ///
    /// Checks if coordinates (x, y) are within the "Close" button.
    ///
    pub def isCloseClicked(x: Float32, y: Float32): Bool =
        isInside(x, y, closeButtonX(), closeButtonY(), closeButtonW(), closeButtonH())

    ///
    /// Computes the AI's move using Math.Random effect handler (with seed if provided).
    ///
    def computeAiMove(turn: Symbol, board: Board, seedRef: AtomicReference[Option[Int64]]): Option[Int32] \ IO = {
        let currentSeed = seedRef.get();
        match currentSeed {
            case Some(s) =>
                let nextSeed = Int64.remainder(s * 6364136223846793005i64 + 1442695040888963407i64, 9223372036854775807i64);
                seedRef.set(Some(nextSeed));
                Math.Random.runWithSeed(s, () -> Minimax.bestMove(turn, board))
            case None =>
                Math.Random.runWithIO(() -> Minimax.bestMove(turn, board))
        }
    }

    ///
    /// Draws a single piece (X or O) on the board canvas.
    ///
    def drawPiece(app: PApplet, ox: Float32, oy: Float32, sz: Float32, idx: Int32,
                  sym: Symbol): Unit \ IO = {
        let col = Int32.toFloat32(Int32.remainder(idx, 3));
        let row = Int32.toFloat32(idx / 3);
        let cx = ox + col * sz + sz / 2.0f32;
        let cy = oy + row * sz + sz / 2.0f32;
        let r = sz * 0.32f32;

        match sym {
            case Symbol.X => {
                app.stroke(255.0f32, 107.0f32, 107.0f32);
                app.strokeWeight(7.0f32);
                app.line(cx - r, cy - r, cx + r, cy + r);
                app.line(cx + r, cy - r, cx - r, cy + r)
            }
            case Symbol.O => {
                app.noFill();
                app.stroke(78.0f32, 205.0f32, 196.0f32);
                app.strokeWeight(7.0f32);
                app.ellipse(cx, cy, r * 2.0f32, r * 2.0f32)
            }
            case Symbol.Empty => ()
        }
    }

    ///
    /// Draws a button, lit up while the pointer is over it.
    ///
    def drawButton(app: PApplet, bx: Float32, by: Float32, bw: Float32, bh: Float32,
                   label: String, hovered: Bool): Unit \ IO = {
        if (hovered) {
            app.fill(68.0f32, 76.0f32, 90.0f32);
            app.stroke(98.0f32, 106.0f32, 120.0f32)
        } else {
            app.fill(48.0f32, 54.0f32, 66.0f32);
            app.stroke(68.0f32, 76.0f32, 90.0f32)
        };
        app.strokeWeight(2.0f32);
        app.rect(bx, by, bw, bh);
        app.fill(240.0f32, 246.0f32, 252.0f32);
        app.textSize(16.0f32);
        app.textAlign(PConstants.CENTER, PConstants.CENTER);
        app.text(label, bx + bw / 2.0f32, by + bh / 2.0f32)
    }

    ///
    /// Returns true when a click at (x, y), after `idleMillis` without input, should
    /// take the game back from the demo.
    ///
    /// Only clicks on the board count. Clicking a button already means something, and
    /// clicking the margin should not throw away a game that is being watched.
    ///
    pub def clickTakesOverDemo(idleMillis: Int64, x: Float32, y: Float32): Bool =
        idleMillis >= autoModeIdleMs() and Option.nonEmpty(cellAt(x, y))

    ///
    /// Returns the given score at demo volume while auto mode is running, and as
    /// written otherwise.
    ///
    def atCurrentVolume(autoMode: Bool, score: List[Sound.Step]): List[Sound.Step] =
        if (autoMode) Sound.atVolume(demoVolumePercent(), score) else score

    ///
    /// Draws the "DEMO" watermark across the middle of the canvas.
    ///
    /// It is drawn last, running corner to corner across the whole window the way a
    /// watermark on paper does, and kept faint through the alpha channel so that
    /// everything underneath stays readable.
    ///
    /// The angle is the window's own diagonal rather than a fixed number of degrees, and
    /// the size is measured rather than guessed: the word is set at an arbitrary size,
    /// `textWidth` says how wide that came out, and the result is scaled so it spans the
    /// intended fraction of the diagonal. Both then survive a change of window size.
    ///
    def drawDemoOverlay(app: PApplet): Unit \ IO = {
        let w = Int32.toFloat32(canvasWidth());
        let h = Int32.toFloat32(canvasHeight());
        let diagonal = PApplet.sqrt(w * w + h * h);

        // Bottom left corner to top right: rightwards and, since y grows downwards, up.
        let angle = PApplet.atan2(-h, w);

        app.pushMatrix();
        app.translate(canvasCenterX(), canvasCenterY());
        app.rotate(angle);

        app.textAlign(PConstants.CENTER, PConstants.CENTER);
        app.textSize(100.0f32);
        let measured = app.textWidth("DEMO");
        app.textSize(100.0f32 * diagonal * demoOverlaySpan() / measured);

        app.fill(240.0f32, 246.0f32, 252.0f32, demoOverlayAlpha());
        app.text("DEMO", 0.0f32, 0.0f32);
        app.popMatrix()
    }

    ///
    /// Blocks the main thread until the Processing sketch window is closed.
    ///
    def waitForExit(app: PApplet): Unit \ IO = {
        Thread.sleep(100i64);
        if (not app.exitCalled() and not app.finished)
            waitForExit(app)
        else
            ()
    }

    ///
    /// Launches the Processing GUI for Tic-Tac-Toe.
    ///
    pub def runGui(seed: Option[Int64]): Unit \ IO = {
        let boardRef: AtomicReference[Board] = new AtomicReference(Board.newBoard());
        let turnRef: AtomicReference[Symbol] = new AtomicReference(Symbol.X);
        let seedRef: AtomicReference[Option[Int64]] = new AtomicReference(seed);
        let aiMoveTimeRef: AtomicReference[Int64] = new AtomicReference(0i64);
        let gameOverSoundPlayedRef: AtomicReference[Bool] = new AtomicReference(false);
        let lastInputTimeRef: AtomicReference[Int64] = new AtomicReference(System.currentTimeMillis());
        let autoRestartTimeRef: AtomicReference[Int64] = new AtomicReference(0i64);
        let humanSymbol = Symbol.X;
        let opponent = Opponent.Computer;

        // Opened once: building and opening a synthesizer per click is audible as lag.
        let sound = Sound.open();

        // Reached from three places: the button, the demo dealing another game, and a
        // player taking the board back from the demo.
        let startNewGame = () -> {
            boardRef.set(Board.newBoard());
            turnRef.set(Symbol.X);
            aiMoveTimeRef.set(0i64);
            autoRestartTimeRef.set(0i64);
            gameOverSoundPlayedRef.set(false)
        };

        let sketch = new PApplet {
            def settings(app: PApplet): Unit \ IO = {
                app.size(canvasWidth(), canvasHeight());
                app.pixelDensity(1)
            }

            def setup(app: PApplet): Unit \ IO = {
                app.frameRate(30.0f32)
            }

            def draw(app: PApplet): Unit \ IO = {
                // Background dark theme
                app.background(28.0f32, 33.0f32, 40.0f32);

                let board: Board = boardRef.get();
                let currentTurn: Symbol = turnRef.get();
                let outcome = Board.outcome(board);

                let now = System.currentTimeMillis();

                // Left alone for a while, the game starts playing itself, and keeps
                // playing until the next click. Both sides are then driven by the search.
                // See `autoModeIdleMs` for how long "a while" is.
                let autoMode = now - lastInputTimeRef.get() >= autoModeIdleMs();
                let machineToMove = currentTurn == Symbol.complement(humanSymbol) or autoMode;

                // Machine turns run on a timer rather than a sleep, so that the window
                // keeps redrawing while the search is thinking.
                if (opponent == Opponent.Computer and outcome == Outcome.Ongoing and machineToMove) {
                    let scheduledTime = aiMoveTimeRef.get();
                    if (scheduledTime == 0i64) {
                        aiMoveTimeRef.set(now + aiMoveDelayMs())
                    } else if (now >= scheduledTime) {
                        match computeAiMove(currentTurn, board, seedRef) {
                            case Some(m) =>
                                match Board.makeMove(board, currentTurn, m) {
                                    case Some(nb) => {
                                        boardRef.set(nb);
                                        turnRef.set(Symbol.complement(currentTurn));
                                        aiMoveTimeRef.set(0i64);
                                        // Keep the sides audibly apart even in auto mode,
                                        // where the machine plays both of them, but drop
                                        // the volume since nobody asked to hear it.
                                        let base = if (currentTurn == humanSymbol)
                                                Sound.humanClick()
                                            else
                                                Sound.computerClick();
                                        Sound.click(sound, atCurrentVolume(autoMode, base))
                                    }
                                    case None => ()
                                }
                            case None => ()
                        }
                    }
                };

                let updatedBoard: Board = boardRef.get();
                let updatedTurn: Symbol = turnRef.get();
                let updatedOutcome = Board.outcome(updatedBoard);

                // Trigger game over sounds once upon resolution
                if (updatedOutcome != Outcome.Ongoing and not gameOverSoundPlayedRef.get()) {
                    gameOverSoundPlayedRef.set(true);
                    match updatedOutcome {
                        case Outcome.Win(sym) if sym == humanSymbol =>
                            Sound.playAsync(sound, atCurrentVolume(autoMode, Sound.winFanfare()))
                        case Outcome.Win(_) =>
                            Sound.playAsync(sound, atCurrentVolume(autoMode, Sound.loseSound()))
                        case _ => ()
                    }
                };

                // While unattended, clear the finished board and deal another, so the
                // demo keeps running.
                if (autoMode and updatedOutcome != Outcome.Ongoing) {
                    let restartAt = autoRestartTimeRef.get();
                    if (restartAt == 0i64) {
                        autoRestartTimeRef.set(now + autoRestartDelayMs())
                    } else if (now >= restartAt) {
                        startNewGame()
                    }
                } else ();

                // 1. Draw Title and Status Header
                app.textAlign(PConstants.CENTER, PConstants.CENTER);
                app.textSize(26.0f32);
                app.fill(240.0f32, 246.0f32, 252.0f32);
                app.textAlign(PConstants.CENTER, PConstants.CENTER);
                app.textSize(26.0f32);
                app.fill(240.0f32, 246.0f32, 252.0f32);
                app.text("Tic-Tac-Toe", canvasCenterX(), 35.0f32);

                app.textSize(16.0f32);
                match updatedOutcome {
                    case Outcome.Ongoing =>
                        // In auto mode the watermark already says what is going on, and
                        // nobody needs telling that a board is clicked to play on it.
                        if (autoMode) {
                            ()
                        } else if (updatedTurn == humanSymbol) {
                            app.fill(255.0f32, 107.0f32, 107.0f32);
                            app.text("Your Turn (X)", canvasCenterX(), 70.0f32)
                        } else {
                            app.fill(78.0f32, 205.0f32, 196.0f32);
                            app.text("Computer is thinking... (O)", canvasCenterX(), 70.0f32)
                        }
                    case Outcome.Win(Symbol.X) =>
                        app.fill(152.0f32, 195.0f32, 121.0f32);
                        app.text("You Win!", canvasCenterX(), 70.0f32)
                    case Outcome.Win(Symbol.O) =>
                        app.fill(224.0f32, 108.0f32, 117.0f32);
                        app.text("Computer Wins!", canvasCenterX(), 70.0f32)
                    case Outcome.Draw =>
                        app.fill(229.0f32, 192.0f32, 123.0f32);
                        app.text("It's a Draw!", canvasCenterX(), 70.0f32)
                    case _ => ()
                };

                // 2. Draw Board Background
                let ox = boardOffsetX();
                let oy = boardOffsetY();
                let sz = cellSize();

                app.fill(34.0f32, 40.0f32, 49.0f32);
                app.stroke(62.0f32, 68.0f32, 81.0f32);
                app.strokeWeight(3.0f32);
                app.rect(ox, oy, sz * 3.0f32, sz * 3.0f32);

                // 3. Draw Grid Lines
                app.stroke(82.0f32, 88.0f32, 101.0f32);
                app.strokeWeight(4.0f32);
                // Vertical lines
                app.line(ox + sz, oy + 10.0f32, ox + sz, oy + sz * 3.0f32 - 10.0f32);
                app.line(ox + sz * 2.0f32, oy + 10.0f32, ox + sz * 2.0f32, oy + sz * 3.0f32 - 10.0f32);
                // Horizontal lines
                app.line(ox + 10.0f32, oy + sz, ox + sz * 3.0f32 - 10.0f32, oy + sz);
                app.line(ox + 10.0f32, oy + sz * 2.0f32, ox + sz * 3.0f32 - 10.0f32, oy + sz * 2.0f32);

                // 4. Draw Pieces (X and O)
                match updatedBoard {
                    case Board.Board(vec) =>
                        Vector.forEachWithIndex((idx, sym) -> drawPiece(app, ox, oy, sz, idx, sym), vec)
                };

                // 5. Draw the buttons
                let mx = Int32.toFloat32(app.mouseX);
                let my = Int32.toFloat32(app.mouseY);
                drawButton(app, restartButtonX(), restartButtonY(), restartButtonW(),
                           restartButtonH(), "New Game", isRestartClicked(mx, my));
                drawButton(app, closeButtonX(), closeButtonY(), closeButtonW(),
                           closeButtonH(), "Close", isCloseClicked(mx, my));

                // 6. Watermark the demo, over everything else.
                if (autoMode) drawDemoOverlay(app) else ()
            }

            def mousePressed(app: PApplet): Unit \ IO = {
                let mx = Int32.toFloat32(app.mouseX);
                let my = Int32.toFloat32(app.mouseY);

                let now = System.currentTimeMillis();
                let idleMillis = now - lastInputTimeRef.get();

                // Any click counts as attention, which takes the game out of auto mode.
                lastInputTimeRef.set(now);
                autoRestartTimeRef.set(0i64);

                if (isCloseClicked(mx, my)) {
                    app.exit()
                } else if (isRestartClicked(mx, my)) {
                    startNewGame()
                } else if (clickTakesOverDemo(idleMillis, mx, my)) {
                    // Taking the board back from the demo clears it and stops there. The
                    // click is not also played as a move: it landed on a position the
                    // player was watching rather than choosing, so treating it as a
                    // considered opening would put a piece somewhere they did not mean.
                    startNewGame()
                } else {
                    let board: Board = boardRef.get();
                    let currentTurn: Symbol = turnRef.get();
                    let outcome = Board.outcome(board);

                    if (outcome == Outcome.Ongoing and currentTurn == humanSymbol) {
                        match cellAt(mx, my) {
                            case Some(idx) => {
                                match Board.makeMove(board, currentTurn, idx) {
                                    case Some(nb) => {
                                        boardRef.set(nb);
                                        turnRef.set(Symbol.complement(currentTurn));
                                        aiMoveTimeRef.set(System.currentTimeMillis() + aiMoveDelayMs());
                                        Sound.click(sound, Sound.humanClick())
                                    }
                                    case None => ()
                                }
                            }
                            case None => ()
                        }
                    }
                }
            }
        };

        region rc {
            Thread.currentThread().setContextClassLoader(sketch.getClass().getClassLoader());
            let args: Array[String, rc] = Array#{"TicTacToeGui"} @ rc;
            PApplet.runSketch(args, sketch);
            waitForExit(sketch)
        }
    }
}
