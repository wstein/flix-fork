mod Sound {

    import java.lang.Exception
    import java.lang.Thread
    import java.lang.Runnable
    import javax.sound.midi.MidiSystem
    import javax.sound.midi.Synthesizer
    import javax.sound.midi.MidiChannel

    ///
    /// Plays a single MIDI note on the specified channel.
    ///
    pub def playSingleNote(channelIdx: Int32, note: Int32, velocity: Int32): Unit \ IO = {
        try {
            let synth: Synthesizer = MidiSystem.getSynthesizer();
            if (not synth.isOpen()) {
                synth.open()
            };
            let channels = synth.getChannels();
            let _ = OutOfBounds.runWithResult(() -> {
                let ch: MidiChannel = channels[channelIdx];
                ch.noteOn(note, velocity)
            });
            ()
        } catch {
            case _: Exception => ()
        }
    }

    ///
    /// Helper to iterate and play a sequence of notes.
    ///
    def playNotesHelper(ch: MidiChannel, remaining: List[(Int32, Int32, Int64)]): Unit \ IO = {
        match remaining {
            case Nil => ()
            case (note, vel, dur) :: tail => {
                ch.noteOn(note, vel);
                Thread.sleep(dur);
                ch.noteOff(note);
                playNotesHelper(ch, tail)
            }
        }
    }

    ///
    /// Plays a timed sequence of MIDI notes asynchronously in a dedicated daemon thread.
    ///
    pub def playSequence(channelIdx: Int32, notes: List[(Int32, Int32, Int64)]): Unit \ IO = {
        let task = new Runnable {
            def $run(_this: Runnable): Unit \ IO = {
                try {
                    let synth: Synthesizer = MidiSystem.getSynthesizer();
                    if (not synth.isOpen()) {
                        synth.open()
                    };
                    let channels = synth.getChannels();
                    let _ = OutOfBounds.runWithResult(() -> {
                        let ch: MidiChannel = channels[channelIdx];
                        playNotesHelper(ch, notes)
                    });
                    ()
                } catch {
                    case _: Exception => ()
                }
            }
        };
        let t = new Thread(task);
        t.setDaemon(true);
        t.start()
    }

    ///
    /// Crisp high wood click for human move (Claves - Percussion Key 75).
    ///
    pub def playHumanClick(): Unit \ IO = playSingleNote(9, 75, 127)

    ///
    /// Distinct low wood click for AI move (Low Woodblock - Percussion Key 77).
    ///
    pub def playAiClick(): Unit \ IO = playSingleNote(9, 77, 115)

    ///
    /// Ascending triumphant fanfare when the human player wins.
    /// C5 -> E5 -> G5 -> C6
    ///
    pub def playWinFanfare(): Unit \ IO = {
        let notes = (72, 110, 100i64) :: (76, 115, 100i64) :: (79, 120, 110i64) :: (84, 127, 450i64) :: Nil;
        playSequence(0, notes)
    }

    ///
    /// Descending sad sound when the AI wins.
    /// F4 -> Eb4 -> D4 -> C4
    ///
    pub def playLoseSound(): Unit \ IO = {
        let notes = (65, 105, 130i64) :: (63, 100, 130i64) :: (62, 95, 130i64) :: (57, 110, 400i64) :: Nil;
        playSequence(0, notes)
    }

    ///
    /// Clear two-tone resolution chime on a draw outcome.
    /// E4 -> C4
    ///
    pub def playDrawSound(): Unit \ IO = {
        let notes = (64, 110, 150i64) :: (60, 110, 350i64) :: Nil;
        playSequence(0, notes)
    }
}
