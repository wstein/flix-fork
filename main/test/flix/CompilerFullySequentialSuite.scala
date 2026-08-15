package flix

import ca.uwaterloo.flix.util.{ExecutionMode, FileOps, FlixSuite, Options}

import java.nio.file.Paths

/**
  * Runs the corpus as `--Xsequential` compiles it.
  *
  * What this configuration adds over [[CompilerSequentialSuite]] are compile-time changes whose
  * effect is only observable at run time, so the corpus has to be executed and not merely compiled.
  */
class CompilerFullySequentialSuite extends FlixSuite(incremental = true) {
  implicit val options: Options = Options.TestWithLibAll.copy(
    xdatalogExecution = ExecutionMode.Sequential,
    xcollectionExecution = ExecutionMode.Sequential,
    xassumeSingleThreaded = true
  )

  private val TestDir = "main/test/flix/"

  private val Prelude = TestDir + "Prelude.flix"

  ///
  /// The files that spawn a thread, which is what this configuration asserts the program does not
  /// do. A `spawn` is compiled into a rejection under it, so these files cannot run here. Every
  /// other suite still runs them.
  ///
  /// `Test.Exp.ParYield.flix` is not among them: `par (...) yield` spawns at the static region,
  /// which is untouched. The option compiles out the standard library's threading, not the
  /// program's own.
  ///
  private val Spawning = Set(
    "Test.Exp.Concurrency.Buffered.flix",
    "Test.Exp.Concurrency.Select.flix",
    "Test.Exp.Concurrency.Spawn.flix",
    "Test.Exp.Concurrency.Unbuffered.flix",
    "Test.Exp.Unsafe.flix",
    "Test.Handler.Spawn.flix",
    "Test.Handler.SpawnContinuation.flix"
  )

  for (p <- FileOps.getFlixFilesIn(Paths.get(TestDir), 1) if !Spawning.contains(p.getFileName.toString)) {
    mkTest(p.toString, prelude = Some(Prelude))
  }

}
