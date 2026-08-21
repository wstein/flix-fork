mod Sound {

    import java.lang.Exception
    import javax.sound.midi.MidiSystem
    import javax.sound.midi.Synthesizer
    import javax.sound.midi.MidiChannel

    ///
    /// Plays a MIDI percussion/woodblock note for click feedback.
    ///
    pub def playNote(note: Int32, velocity: Int32): Unit \ IO = {
        try {
            let synth: Synthesizer = MidiSystem.getSynthesizer();
            if (not synth.isOpen()) {
                synth.open()
            };
            let channels = synth.getChannels();
            // Channel 9 in 0-indexed MIDI is General MIDI Percussion
            // Note 76 is High Woodblock, Note 77 is Low Woodblock
            let _ = OutOfBounds.runWithResult(() -> {
                let ch: MidiChannel = channels[9];
                ch.noteOn(note, velocity)
            });
            ()
        } catch {
            case _: Exception => ()
        }
    }

    ///
    /// Click sound for human move (crisp high woodblock).
    ///
    pub def playHumanClick(): Unit \ IO = playNote(76, 110)

    ///
    /// Click sound for AI move (crisp low woodblock).
    ///
    pub def playAiClick(): Unit \ IO = playNote(77, 95)
}
