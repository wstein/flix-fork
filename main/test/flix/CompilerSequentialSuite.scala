package flix

import ca.uwaterloo.flix.util.{DatalogExecution, FlixSuite, Options}

class CompilerSequentialSuite extends FlixSuite(incremental = true) {
  implicit val options: Options = Options.TestWithLibAll.copy(datalogExecution = DatalogExecution.Sequential)

  mkTestDir("main/test/flix/", prelude = Some("main/test/flix/Prelude.flix"))

}
