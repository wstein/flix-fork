package flix

import ca.uwaterloo.flix.util.{ExecutionMode, FlixSuite, Options}

/**
  * Runs the whole corpus as `--Xsequential` compiles it.
  *
  * The lock elision this configuration adds over [[CompilerSequentialSuite]] is a compile-time
  * change whose effect is only observable at run time, so the corpus has to be executed and not
  * merely compiled.
  */
class CompilerFullySequentialSuite extends FlixSuite(incremental = true) {
  implicit val options: Options = Options.TestWithLibAll.copy(
    xdatalogExecution = ExecutionMode.Sequential,
    xcollectionExecution = ExecutionMode.Sequential,
    xassumeSingleThreaded = true
  )

  mkTestDir("main/test/flix/", prelude = Some("main/test/flix/Prelude.flix"))

}
