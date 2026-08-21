mod Gui {

    import java.lang.Thread
    import java.lang.Object
    import java.util.concurrent.atomic.AtomicReference
    import processing.core.PApplet
    import processing.core.PConstants

    ///
    /// Cell dimensions and coordinates in the GUI window.
    ///
    pub def boardOffsetX(): Float32 = 45.0f32
    pub def boardOffsetY(): Float32 = 100.0f32
    pub def cellSize(): Float32 = 120.0f32
    pub def restartButtonX(): Float32 = 145.0f32
    pub def restartButtonY(): Float32 = 480.0f32
    pub def restartButtonW(): Float32 = 160.0f32
    pub def restartButtonH(): Float32 = 40.0f32

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
    /// Checks if coordinates (x, y) are within the restart button.
    ///
    pub def isRestartClicked(x: Float32, y: Float32): Bool = {
        let rx = restartButtonX();
        let ry = restartButtonY();
        let rw = restartButtonW();
        let rh = restartButtonH();
        x >= rx and x <= rx + rw and y >= ry and y <= ry + rh
    }

    ///
    /// Computes the AI's move using Math.Random effect handler.
    ///
    def computeAiMove(turn: Symbol, board: Board): Option[Int32] \ IO =
        Math.Random.runWithIO(() -> Minimax.bestMove(turn, board))

    ///
    /// Draws a single piece (X or O) on the board canvas.
    ///
    def drawPiece(app: PApplet, ox: Float32, oy: Float32, sz: Float32, idx: Int32, sym: Symbol): Unit \ IO = {
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
    pub def runGui(): Unit \ IO = {
        let boardRef: AtomicReference[Board] = new AtomicReference(Board.newBoard());
        let turnRef: AtomicReference[Symbol] = new AtomicReference(Symbol.X);
        let humanSymbol = Symbol.X;
        let opponent = Opponent.Computer;

        let sketch = new PApplet {
            def settings(app: PApplet): Unit \ IO = {
                app.size(450, 550)
            }

            def setup(app: PApplet): Unit \ IO = {
                app.pixelDensity(1);
                app.frameRate(30.0f32)
            }

            def draw(app: PApplet): Unit \ IO = {
                // Background dark theme
                app.background(28.0f32, 33.0f32, 40.0f32);

                let board: Board = boardRef.get();
                let currentTurn: Symbol = turnRef.get();
                let outcome = Board.outcome(board);

                // Handle AI turn
                if (opponent == Opponent.Computer and currentTurn == Symbol.complement(humanSymbol) and outcome == Outcome.Ongoing) {
                    let aiMove = computeAiMove(currentTurn, board);
                    match aiMove {
                        case Some(m) => {
                            match Board.makeMove(board, currentTurn, m) {
                                case Some(nb) => {
                                    boardRef.set(nb);
                                    turnRef.set(Symbol.complement(currentTurn))
                                }
                                case None => ()
                            }
                        }
                        case None => ()
                    }
                };

                let updatedBoard: Board = boardRef.get();
                let updatedTurn: Symbol = turnRef.get();
                let updatedOutcome = Board.outcome(updatedBoard);

                // 1. Draw Title and Status Header
                app.textAlign(PConstants.CENTER, PConstants.CENTER);
                app.textSize(26.0f32);
                app.fill(240.0f32, 246.0f32, 252.0f32);
                app.text("Tic-Tac-Toe", 225.0f32, 35.0f32);

                app.textSize(16.0f32);
                match updatedOutcome {
                    case Outcome.Ongoing =>
                        if (updatedTurn == humanSymbol) {
                            app.fill(255.0f32, 107.0f32, 107.0f32);
                            app.text("Your Turn (X)", 225.0f32, 70.0f32)
                        } else {
                            app.fill(78.0f32, 205.0f32, 196.0f32);
                            app.text("Computer is thinking... (O)", 225.0f32, 70.0f32)
                        }
                    case Outcome.Win(Symbol.X) =>
                        app.fill(152.0f32, 195.0f32, 121.0f32);
                        app.text("You Win!", 225.0f32, 70.0f32)
                    case Outcome.Win(Symbol.O) =>
                        app.fill(224.0f32, 108.0f32, 117.0f32);
                        app.text("Computer Wins!", 225.0f32, 70.0f32)
                    case Outcome.Draw =>
                        app.fill(229.0f32, 192.0f32, 123.0f32);
                        app.text("It's a Draw!", 225.0f32, 70.0f32)
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
                    case Board.Board(vec) => {
                        drawPiece(app, ox, oy, sz, 0, Vector.get(0, vec));
                        drawPiece(app, ox, oy, sz, 1, Vector.get(1, vec));
                        drawPiece(app, ox, oy, sz, 2, Vector.get(2, vec));
                        drawPiece(app, ox, oy, sz, 3, Vector.get(3, vec));
                        drawPiece(app, ox, oy, sz, 4, Vector.get(4, vec));
                        drawPiece(app, ox, oy, sz, 5, Vector.get(5, vec));
                        drawPiece(app, ox, oy, sz, 6, Vector.get(6, vec));
                        drawPiece(app, ox, oy, sz, 7, Vector.get(7, vec));
                        drawPiece(app, ox, oy, sz, 8, Vector.get(8, vec))
                    }
                };

                // 5. Draw Restart Button
                let rx = restartButtonX();
                let ry = restartButtonY();
                let rw = restartButtonW();
                let rh = restartButtonH();

                let mx = Int32.toFloat32(app.mouseX);
                let my = Int32.toFloat32(app.mouseY);
                if (isRestartClicked(mx, my)) {
                    app.fill(68.0f32, 76.0f32, 90.0f32);
                    app.stroke(98.0f32, 106.0f32, 120.0f32)
                } else {
                    app.fill(48.0f32, 54.0f32, 66.0f32);
                    app.stroke(68.0f32, 76.0f32, 90.0f32)
                };
                app.strokeWeight(2.0f32);
                app.rect(rx, ry, rw, rh);

                app.fill(240.0f32, 246.0f32, 252.0f32);
                app.textSize(16.0f32);
                app.textAlign(PConstants.CENTER, PConstants.CENTER);
                app.text("New Game", rx + rw / 2.0f32, ry + rh / 2.0f32)
            }

            def mousePressed(app: PApplet): Unit \ IO = {
                let mx = Int32.toFloat32(app.mouseX);
                let my = Int32.toFloat32(app.mouseY);

                if (isRestartClicked(mx, my)) {
                    boardRef.set(Board.newBoard());
                    turnRef.set(Symbol.X)
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
                                        turnRef.set(Symbol.complement(currentTurn))
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
