///
/// The graphical interface, built on Swing and AWT.
///
/// Both are part of the JDK, so this file needs no dependency at all --- everything it
/// calls is Java that is already on the machine.
///
/// The division of labour is the thing to notice. Swing may repaint a window whenever it
/// likes: when it is uncovered, resized, or dragged between screens. So `paintComponent`
/// only ever *draws the current position*, and never advances the game. Moving a piece
/// happens in `tick`, which the loop at the bottom of `runGui` calls on a clock. Mixing
/// the two would mean the computer took a turn because somebody dragged the window.
///
mod Gui {

    import java.awt.BasicStroke
    import java.awt.Color
    import java.awt.Dimension
    import java.awt.Font
    import java.awt.FontMetrics
    import java.awt.Graphics
    import java.awt.Graphics2D
    import java.awt.RenderingHints
    import java.awt.event.MouseAdapter
    import java.awt.event.MouseEvent
    import java.awt.event.WindowAdapter
    import java.awt.event.WindowEvent
    import java.lang.Math
    import java.lang.System
    import java.lang.Thread
    import java.util.concurrent.atomic.AtomicReference
    import javax.swing.JFrame
    import javax.swing.JPanel
    import javax.swing.WindowConstants

    /////////////////////////////////////////////////////////////////////////////
    // Layout. Pure arithmetic, so the tests can check it without a window.     //
    /////////////////////////////////////////////////////////////////////////////

    pub def canvasWidth(): Int32 = 450
    pub def canvasHeight(): Int32 = 550

    pub def boardOffsetX(): Int32 = 45
    pub def boardOffsetY(): Int32 = 100
    pub def cellSize(): Int32 = 120

    pub def closeButtonW(): Int32 = 160
    pub def closeButtonH(): Int32 = 40
    pub def closeButtonX(): Int32 = (canvasWidth() - closeButtonW()) / 2
    pub def closeButtonY(): Int32 = 480

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
    /// How long a finished game stays on screen before the board clears itself.
    ///
    pub def newGameDelayMs(): Int64 = 3000i64

    ///
    /// How opaque the "DEMO" watermark is, out of 255, and how much of the window's
    /// diagonal the word spans.
    ///
    pub def demoOverlayAlpha(): Int32 = 40
    pub def demoOverlaySpan(): Float64 = 0.72f64

    ///
    /// How long the loop sleeps between frames: about thirty a second.
    ///
    pub def frameDelayMs(): Int64 = 33i64

    ///
    /// Returns the cell index (0-8) corresponding to canvas coordinates (x, y), if
    /// within the board.
    ///
    pub def cellAt(x: Int32, y: Int32): Option[Int32] = {
        let ox = boardOffsetX();
        let oy = boardOffsetY();
        let size = cellSize();
        let boardDim = size * 3;

        if (x >= ox and x < ox + boardDim and y >= oy and y < oy + boardDim) {
            let col = (x - ox) / size;
            let row = (y - oy) / size;
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
    def isInside(x: Int32, y: Int32, bx: Int32, by: Int32, bw: Int32, bh: Int32): Bool =
        x >= bx and x <= bx + bw and y >= by and y <= by + bh

    ///
    /// Checks if coordinates (x, y) are within the "Close" button.
    ///
    pub def isCloseClicked(x: Int32, y: Int32): Bool =
        isInside(x, y, closeButtonX(), closeButtonY(), closeButtonW(), closeButtonH())

    ///
    /// Returns true when a click at (x, y), after `idleMillis` without input, should
    /// take the game back from the demo.
    ///
    /// Only clicks on the board count. Clicking Close already means something, and
    /// clicking the margin should not throw away a game that is being watched.
    ///
    pub def clickTakesOverDemo(idleMillis: Int64, x: Int32, y: Int32): Bool =
        idleMillis >= autoModeIdleMs() and Option.nonEmpty(cellAt(x, y))

    /////////////////////////////////////////////////////////////////////////////
    // Drawing.                                                                //
    /////////////////////////////////////////////////////////////////////////////

    def background(): Color \ IO = new Color(28i32, 33i32, 40i32)
    def boardFill(): Color \ IO = new Color(34i32, 40i32, 49i32)
    def boardEdge(): Color \ IO = new Color(62i32, 68i32, 81i32)
    def gridLine(): Color \ IO = new Color(82i32, 88i32, 101i32)
    def foreground(): Color \ IO = new Color(240i32, 246i32, 252i32)
    def xColour(): Color \ IO = new Color(255i32, 107i32, 107i32)
    def oColour(): Color \ IO = new Color(78i32, 205i32, 196i32)
    def winColour(): Color \ IO = new Color(152i32, 195i32, 121i32)
    def loseColour(): Color \ IO = new Color(224i32, 108i32, 117i32)
    def drawColour(): Color \ IO = new Color(229i32, 192i32, 123i32)

    def sansBold(size: Int32): Font \ IO = new Font("SansSerif", Font.BOLD, size)

    ///
    /// Draws `text` centred on (cx, cy).
    ///
    /// AWT places a string by the left end of its baseline, not by its middle, so the
    /// centring has to be done here: back off half the width, and half the cap height.
    ///
    def drawCentred(g2: Graphics2D, text: String, cx: Int32, cy: Int32): Unit \ IO = {
        let fm: FontMetrics = g2.getFontMetrics();
        g2.drawString(text, cx - fm.stringWidth(text) / 2i32, cy + fm.getAscent() / 2i32 - 2i32)
    }

    ///
    /// Draws a single piece (X or O) in the given cell.
    ///
    def drawPiece(g2: Graphics2D, idx: Int32, sym: Symbol): Unit \ IO = {
        let sz = cellSize();
        let cx = boardOffsetX() + Int32.remainder(idx, 3) * sz + sz / 2;
        let cy = boardOffsetY() + (idx / 3) * sz + sz / 2;
        let r = sz * 32 / 100;

        g2.setStroke(new BasicStroke(7.0f32));
        match sym {
            case Symbol.X =>
                g2.setColor(xColour());
                g2.drawLine(cx - r, cy - r, cx + r, cy + r);
                g2.drawLine(cx + r, cy - r, cx - r, cy + r)
            case Symbol.O =>
                g2.setColor(oColour());
                g2.drawOval(cx - r, cy - r, r * 2, r * 2)
            case Symbol.Empty => ()
        }
    }

    ///
    /// Draws the Close button, lit up while the pointer is over it.
    ///
    def drawCloseButton(g2: Graphics2D, hovered: Bool): Unit \ IO = {
        let bx = closeButtonX();
        let by = closeButtonY();
        let bw = closeButtonW();
        let bh = closeButtonH();

        g2.setColor(if (hovered) new Color(68i32, 76i32, 90i32) else new Color(48i32, 54i32, 66i32));
        g2.fillRect(bx, by, bw, bh);
        g2.setStroke(new BasicStroke(2.0f32));
        g2.setColor(if (hovered) new Color(98i32, 106i32, 120i32) else new Color(68i32, 76i32, 90i32));
        g2.drawRect(bx, by, bw, bh);

        g2.setColor(foreground());
        g2.setFont(sansBold(16i32));
        drawCentred(g2, "Close", bx + bw / 2, by + bh / 2)
    }

    ///
    /// Draws the "DEMO" watermark, running corner to corner across the whole window the
    /// way a watermark on paper does, and kept faint through the alpha channel so that
    /// everything underneath stays readable.
    ///
    /// Neither the angle nor the size is a fixed number. The angle is the window's own
    /// diagonal, and the size is measured rather than guessed: the word is set at an
    /// arbitrary size, the font metrics say how wide that came out, and the result is
    /// scaled to span `demoOverlaySpan` of the diagonal. Both then survive a change of
    /// window size.
    ///
    def drawDemoOverlay(g2: Graphics2D): Unit \ IO = {
        let w = Int32.toFloat64(canvasWidth());
        let h = Int32.toFloat64(canvasHeight());
        let diagonal = Math.sqrt(w * w + h * h);

        // Bottom left corner to top right: rightwards and, since y grows downwards, up.
        let angle = Math.atan2(-h, w);

        let probeSize = 100i32;
        let measured: Int32 = g2.getFontMetrics(sansBold(probeSize)).stringWidth("DEMO");
        let scaled = Int32.toFloat64(probeSize) * diagonal * demoOverlaySpan() / Int32.toFloat64(measured);

        g2.rotate(angle, w / 2.0f64, h / 2.0f64);
        g2.setFont(sansBold(Float64.tryToInt32(scaled) |> Option.getWithDefault(probeSize)));
        g2.setColor(new Color(240i32, 246i32, 252i32, demoOverlayAlpha()));
        drawCentred(g2, "DEMO", canvasWidth() / 2, canvasHeight() / 2);

        // Undo the rotation, since the same Graphics is handed back to Swing.
        g2.rotate(-angle, w / 2.0f64, h / 2.0f64)
    }

    ///
    /// Draws the whole window: the title, the status line, the board and its pieces, the
    /// Close button, and the watermark if the demo is running.
    ///
    def render(g2: Graphics2D, board: Board, turn: Symbol, humanSymbol: Symbol,
               autoMode: Bool, mouseX: Int32, mouseY: Int32): Unit \ IO = {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(background());
        g2.fillRect(0i32, 0i32, canvasWidth(), canvasHeight());

        // Title.
        g2.setColor(foreground());
        g2.setFont(sansBold(26i32));
        drawCentred(g2, "Tic-Tac-Toe", canvasWidth() / 2, 35i32);

        // Status line. In auto mode the watermark says what is going on, and nobody
        // needs telling that a board is clicked to play on it.
        let outcome = Board.outcome(board);
        g2.setFont(sansBold(16i32));
        if (not autoMode) {
            match outcome {
                case Outcome.Ongoing =>
                    if (turn == humanSymbol) {
                        g2.setColor(xColour());
                        drawCentred(g2, "Your Turn (X)", canvasWidth() / 2, 70i32)
                    } else {
                        g2.setColor(oColour());
                        drawCentred(g2, "Computer is thinking... (O)", canvasWidth() / 2, 70i32)
                    }
                case Outcome.Win(Symbol.X) =>
                    g2.setColor(winColour());
                    drawCentred(g2, "You Win!", canvasWidth() / 2, 70i32)
                case Outcome.Win(Symbol.O) =>
                    g2.setColor(loseColour());
                    drawCentred(g2, "Computer Wins!", canvasWidth() / 2, 70i32)
                case Outcome.Draw =>
                    g2.setColor(drawColour());
                    drawCentred(g2, "It's a Draw!", canvasWidth() / 2, 70i32)
                case _ => ()
            }
        } else ();

        // Board.
        let ox = boardOffsetX();
        let oy = boardOffsetY();
        let sz = cellSize();

        g2.setColor(boardFill());
        g2.fillRect(ox, oy, sz * 3, sz * 3);
        g2.setStroke(new BasicStroke(3.0f32));
        g2.setColor(boardEdge());
        g2.drawRect(ox, oy, sz * 3, sz * 3);

        g2.setStroke(new BasicStroke(4.0f32));
        g2.setColor(gridLine());
        g2.drawLine(ox + sz, oy + 10i32, ox + sz, oy + sz * 3 - 10i32);
        g2.drawLine(ox + sz * 2, oy + 10i32, ox + sz * 2, oy + sz * 3 - 10i32);
        g2.drawLine(ox + 10i32, oy + sz, ox + sz * 3 - 10i32, oy + sz);
        g2.drawLine(ox + 10i32, oy + sz * 2, ox + sz * 3 - 10i32, oy + sz * 2);

        match board {
            case Board.Board(vec) => Vector.forEachWithIndex((idx, sym) -> drawPiece(g2, idx, sym), vec)
        };

        drawCloseButton(g2, isCloseClicked(mouseX, mouseY));

        if (autoMode) drawDemoOverlay(g2) else ()
    }

    /////////////////////////////////////////////////////////////////////////////
    // The game.                                                               //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// Computes the AI's move, using the seeded generator when a seed was given.
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
    /// Returns the given score at demo volume while auto mode is running, and as
    /// written otherwise.
    ///
    def atCurrentVolume(autoMode: Bool, score: List[Sound.Step]): List[Sound.Step] =
        if (autoMode) Sound.atVolume(demoVolumePercent(), score) else score

    ///
    /// How loud auto mode plays, as a percentage of the written velocity. The demo runs
    /// unattended, so it should be audible without demanding attention.
    ///
    pub def demoVolumePercent(): Int32 = 20

    ///
    /// Opens the window and plays until it is closed.
    ///
    pub def runGui(seed: Option[Int64]): Unit \ IO = {
        // Swing repaints on its own thread, so every piece of state the painter reads is
        // held in an AtomicReference rather than a plain variable.
        let boardRef: AtomicReference[Board] = new AtomicReference(Board.newBoard());
        let turnRef: AtomicReference[Symbol] = new AtomicReference(Symbol.X);
        let seedRef: AtomicReference[Option[Int64]] = new AtomicReference(seed);
        let aiMoveTimeRef: AtomicReference[Int64] = new AtomicReference(0i64);
        let gameOverSoundPlayedRef: AtomicReference[Bool] = new AtomicReference(false);
        let lastInputTimeRef: AtomicReference[Int64] = new AtomicReference(System.currentTimeMillis());
        let autoRestartTimeRef: AtomicReference[Int64] = new AtomicReference(0i64);
        let closedRef: AtomicReference[Bool] = new AtomicReference(false);
        let mouseXRef: AtomicReference[Int32] = new AtomicReference(0i32);
        let mouseYRef: AtomicReference[Int32] = new AtomicReference(0i32);

        let humanSymbol = Symbol.X;
        let opponent = Opponent.Computer;

        // Opened once: building and opening a synthesizer per click is audible as lag.
        let sound = Sound.open();

        // Reached from two places: a finished game clearing itself, and a player taking
        // the board back from the demo.
        let startNewGame = () -> {
            boardRef.set(Board.newBoard());
            turnRef.set(Symbol.X);
            aiMoveTimeRef.set(0i64);
            autoRestartTimeRef.set(0i64);
            gameOverSoundPlayedRef.set(false)
        };

        let inAutoMode = () -> System.currentTimeMillis() - lastInputTimeRef.get() >= autoModeIdleMs();

        let panel = new JPanel {
            def paintComponent(_this: JPanel, g: Graphics): Unit \ IO = {
                let g2 = unchecked_cast(g as Graphics2D);
                render(g2, boardRef.get(), turnRef.get(), humanSymbol, inAutoMode(),
                       mouseXRef.get(), mouseYRef.get())
            }
        };
        panel.setPreferredSize(new Dimension(canvasWidth(), canvasHeight()));

        panel.addMouseListener(new MouseAdapter {
            def mousePressed(_this: MouseAdapter, e: MouseEvent): Unit \ IO = {
                let mx = e.getX();
                let my = e.getY();
                let now = System.currentTimeMillis();
                let idleMillis = now - lastInputTimeRef.get();

                // Any click counts as attention, which takes the game out of auto mode.
                // It deliberately does not touch the countdown on a finished game, or
                // clicking about would keep postponing the clear.
                lastInputTimeRef.set(now);

                if (isCloseClicked(mx, my)) {
                    closedRef.set(true)
                } else if (clickTakesOverDemo(idleMillis, mx, my)) {
                    // Taking the board back from the demo clears it and stops there. The
                    // click is not also played as a move: it landed on a position the
                    // player was watching rather than choosing, so treating it as a
                    // considered opening would put a piece somewhere they did not mean.
                    startNewGame()
                } else {
                    let board = boardRef.get();
                    let currentTurn = turnRef.get();
                    if (Board.outcome(board) == Outcome.Ongoing and currentTurn == humanSymbol) {
                        match cellAt(mx, my) {
                            case Some(idx) =>
                                match Board.makeMove(board, currentTurn, idx) {
                                    case Some(nb) =>
                                        boardRef.set(nb);
                                        turnRef.set(Symbol.complement(currentTurn));
                                        aiMoveTimeRef.set(now + aiMoveDelayMs());
                                        Sound.click(sound, Sound.humanClick())
                                    case None => ()
                                }
                            case None => ()
                        }
                    } else ()
                }
            }
        });

        panel.addMouseMotionListener(new MouseAdapter {
            def mouseMoved(_this: MouseAdapter, e: MouseEvent): Unit \ IO = {
                mouseXRef.set(e.getX());
                mouseYRef.set(e.getY())
            }
        });

        let frame = new JFrame("Tic-Tac-Toe");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter {
            def windowClosing(_this: WindowAdapter, _e: WindowEvent): Unit \ IO =
                closedRef.set(true)
        });
        frame.add(panel);
        frame.pack();
        frame.setResizable(false);
        frame.setVisible(true);

        // Advances the game by one frame's worth of time.
        let tick = () -> {
            let board = boardRef.get();
            let currentTurn = turnRef.get();
            let outcome = Board.outcome(board);
            let now = System.currentTimeMillis();

            // Left alone for a while, the game starts playing itself, and keeps playing
            // until the next click. Both sides are then driven by the search.
            let autoMode = inAutoMode();
            let machineToMove = currentTurn == Symbol.complement(humanSymbol) or autoMode;

            // Machine turns run on a timer rather than a sleep, so that the window keeps
            // redrawing while the search is thinking.
            if (opponent == Opponent.Computer and outcome == Outcome.Ongoing and machineToMove) {
                let scheduledTime = aiMoveTimeRef.get();
                if (scheduledTime == 0i64) {
                    aiMoveTimeRef.set(now + aiMoveDelayMs())
                } else if (now >= scheduledTime) {
                    match computeAiMove(currentTurn, board, seedRef) {
                        case Some(m) =>
                            match Board.makeMove(board, currentTurn, m) {
                                case Some(nb) =>
                                    boardRef.set(nb);
                                    turnRef.set(Symbol.complement(currentTurn));
                                    aiMoveTimeRef.set(0i64);
                                    // Keep the sides audibly apart even in auto mode,
                                    // where the machine plays both of them, but drop the
                                    // volume since nobody asked to hear it.
                                    let base = if (currentTurn == humanSymbol)
                                            Sound.humanClick()
                                        else
                                            Sound.computerClick();
                                    Sound.click(sound, atCurrentVolume(autoMode, base))
                                case None => ()
                            }
                        case None => ()
                    }
                } else ()
            } else ();

            let updatedBoard = boardRef.get();
            let updatedOutcome = Board.outcome(updatedBoard);

            // Trigger the end-of-game sound once.
            if (updatedOutcome != Outcome.Ongoing and not gameOverSoundPlayedRef.get()) {
                gameOverSoundPlayedRef.set(true);
                match updatedOutcome {
                    case Outcome.Win(sym) if sym == humanSymbol =>
                        Sound.playAsync(sound, atCurrentVolume(autoMode, Sound.winFanfare()))
                    case Outcome.Win(_) =>
                        Sound.playAsync(sound, atCurrentVolume(autoMode, Sound.loseSound()))
                    case _ => ()
                }
            } else ();

            // Every finished game clears itself after a pause: long enough to see how it
            // ended and hear the fanfare out, short enough not to need a button. This is
            // also what keeps the demo dealing new games.
            if (updatedOutcome != Outcome.Ongoing) {
                let restartAt = autoRestartTimeRef.get();
                if (restartAt == 0i64) {
                    autoRestartTimeRef.set(now + newGameDelayMs())
                } else if (now >= restartAt) {
                    startNewGame()
                } else ()
            } else ()
        };

        // The game loop. `repaint` asks Swing to redraw when it next gets the chance
        // rather than drawing here and now, so the painter always sees a position that
        // `tick` has finished with.
        def loop() = {
            if (closedRef.get()) {
                frame.dispose()
            } else {
                tick();
                panel.repaint();
                Thread.sleep(frameDelayMs());
                loop()
            }
        };
        loop()
    }
}
