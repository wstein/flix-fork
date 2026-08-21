///
/// Sound effects, played through the MIDI synthesizer built into the JVM.
///
/// The module is split in two halves, and the split is the point of the example:
///
///   - A *score* is a plain list of steps. Writing one is pure computation, so the
///     tests in `TestSound` can check what a sound is made of without making a noise.
///   - `play` is the only part that talks to Java, and the only part with the `IO`
///     effect. Flix's type system keeps the two apart for us.
///
mod Sound {

    import java.lang.Exception
    import java.lang.Thread
    import java.lang.Runnable
    import javax.sound.midi.MidiSystem
    import javax.sound.midi.Synthesizer
    import javax.sound.midi.MidiChannel

    ///
    /// One step of a score: the pitches to sound together, how hard, and for how long.
    ///
    /// Pitches are MIDI note numbers, where 60 is middle C and each step of 1 is a
    /// semitone. A step with several pitches is a chord; a step with one is a note.
    ///
    pub enum Step(List[Int32], Int32, Int64) with Eq, ToString

    ///
    /// An open synthesizer, with the two channels the game plays on: one set to a
    /// woodblock for clicks, one to a trumpet for the end of the game.
    ///
    /// Note that neither is channel 9. General MIDI reserves channel 9 for percussion,
    /// where the note number picks a drum rather than a pitch --- the obvious place to
    /// look for a wood block. It is the wrong place here. The JVM ships no soundbank of
    /// its own, so `MidiSystem.getSynthesizer()` usually falls back to Gervill's
    /// "Emergency GM sound set", which contains 129 melodic instruments and *no drum
    /// kit at all*. Every percussion key then collapses to the same synthesised noise
    /// hit, which is why a wood block played on channel 9 comes out as a snare drum.
    /// The melodic "Woodblock" program does exist in that set, so the clicks use it.
    ///
    pub enum Device(MidiChannel, MidiChannel)

    /////////////////////////////////////////////////////////////////////////////
    // Scores --- pure, and therefore testable.                                //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// The General MIDI program number for the melodic woodblock --- a struck, pitched
    /// block of wood, which is what makes a click read as placing a piece.
    ///
    def woodblock(): Int32 = 115

    ///
    /// The General MIDI program number for a trumpet: bright enough to cut through
    /// as a fanfare.
    ///
    def trumpet(): Int32 = 56

    ///
    /// Returns a single step.
    ///
    def note(pitch: Int32, velocity: Int32, millis: Int64): Step =
        Step.Step(pitch :: Nil, velocity, millis)

    ///
    /// Returns a step sounding several pitches together.
    ///
    def chord(pitches: List[Int32], velocity: Int32, millis: Int64): Step =
        Step.Step(pitches, velocity, millis)

    ///
    /// The click when the human places a piece: a high wood block, struck firmly.
    /// 84 is C6.
    ///
    pub def humanClick(): List[Step] = note(84, 112, 30i64) :: Nil

    ///
    /// The click when the computer answers: the same block a major sixth lower (77 is
    /// F4) and softer, so the two players are audibly different without the computer
    /// dominating.
    ///
    pub def computerClick(): List[Step] = note(77, 96, 30i64) :: Nil

    ///
    /// The fanfare when the human wins.
    ///
    /// Three repeated notes and a leap to the octave --- the shape of a bugle call ---
    /// landing on a held major triad.
    ///
    pub def winFanfare(): List[Step] =
        note(67, 118, 110i64) ::                       // G4  .
        note(67, 118, 110i64) ::                       // G4  .
        note(67, 118, 150i64) ::                       // G4  ..
        note(72, 124, 200i64) ::                       // C5  --
        chord(72 :: 76 :: 79 :: Nil, 127, 700i64) ::   // C major, held
        Nil

    ///
    /// The sound when the computer wins.
    ///
    /// A sagging chromatic descent onto a held minor triad: the same rhetorical shape
    /// as the fanfare, turned downwards.
    ///
    pub def loseSound(): List[Step] =
        note(63, 104, 150i64) ::                       // Eb4
        note(62, 100, 150i64) ::                       // D4
        note(61, 96, 190i64) ::                        // Db4
        chord(60 :: 63 :: 67 :: Nil, 100, 750i64) ::   // C minor, held
        Nil

    ///
    /// The sound on a draw: two notes, going nowhere.
    ///
    pub def drawSound(): List[Step] =
        note(64, 100, 170i64) ::                       // E4
        note(60, 96, 420i64) ::                        // C4
        Nil

    ///
    /// Returns the given score played at `percent` of its written loudness.
    ///
    /// MIDI velocity is not a decibel scale, so this is a rough gesture rather than a
    /// calibrated attenuation --- enough to push a sound into the background. Velocity
    /// is floored at 1, because velocity 0 is a note *off* in MIDI and would silence
    /// the score entirely rather than merely quieten it.
    ///
    pub def atVolume(percent: Int32, score: List[Step]): List[Step] =
        List.map(s -> match s {
            case Step.Step(pitches, velocity, millis) =>
                Step.Step(pitches, Order.max(1, velocity * percent / 100), millis)
        }, score)

    ///
    /// Returns how long the given score takes to play, in milliseconds.
    ///
    pub def duration(score: List[Step]): Int64 =
        List.foldLeft((acc, s) -> match s { case Step.Step(_, _, millis) => acc + millis }, 0i64, score)

    /////////////////////////////////////////////////////////////////////////////
    // Playback --- the only part that touches Java, and the only part with IO. //
    /////////////////////////////////////////////////////////////////////////////

    ///
    /// Opens the synthesizer, or returns `None` if the machine has no working MIDI
    /// output --- a headless build server, typically. The game is still playable
    /// without sound, so every function below quietly does nothing when given `None`.
    ///
    /// Open the device once and keep it: `MidiSystem.getSynthesizer()` builds a fresh
    /// synthesizer each time it is called, and opening one costs long enough to be
    /// heard as lag if it happens on every click.
    ///
    pub def open(): Option[Device] \ IO = {
        try {
            let synth: Synthesizer = MidiSystem.getSynthesizer();
            if (not synth.isOpen()) synth.open() else ();
            let channels = synth.getChannels();
            OutOfBounds.runWithResult(() -> {
                let clicks: MidiChannel = channels[1];
                let melody: MidiChannel = channels[0];
                clicks.programChange(woodblock());
                melody.programChange(trumpet());
                Device.Device(clicks, melody)
            }) |> Result.toOption
        } catch {
            case _: Exception => None
        }
    }

    ///
    /// Plays the given score on the given channel, blocking for its duration.
    ///
    def playOn(ch: MidiChannel, score: List[Step]): Unit \ IO = match score {
        case Nil => ()
        case Step.Step(pitches, velocity, millis) :: rest =>
            List.forEach(p -> ch.noteOn(p, velocity), pitches);
            Thread.sleep(millis);
            List.forEach(p -> ch.noteOff(p), pitches);
            playOn(ch, rest)
    }

    ///
    /// Plays a click. Clicks are short and must not delay the next frame, so they are
    /// struck and left to ring rather than waited on.
    ///
    pub def click(device: Option[Device], score: List[Step]): Unit \ IO = match device {
        case None => ()
        case Some(Device.Device(clicks, _)) =>
            try {
                List.forEach(s -> match s {
                    case Step.Step(pitches, velocity, _) =>
                        List.forEach(p -> clicks.noteOn(p, velocity), pitches)
                }, score)
            } catch {
                case _: Exception => ()
            }
    }

    ///
    /// Plays a score on the melodic channel, on a daemon thread so that the window
    /// keeps redrawing while it sounds, and so that it cannot keep the JVM alive
    /// after the window closes.
    ///
    pub def playAsync(device: Option[Device], score: List[Step]): Unit \ IO = match device {
        case None => ()
        case Some(Device.Device(_, melody)) =>
            let task = new Runnable {
                def $run(_this: Runnable): Unit \ IO =
                    try {
                        playOn(melody, score)
                    } catch {
                        case _: Exception => ()
                    }
            };
            let t = new Thread(task);
            t.setDaemon(true);
            t.start()
    }
}
