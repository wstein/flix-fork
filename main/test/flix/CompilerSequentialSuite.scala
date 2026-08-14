package flix

import ca.uwaterloo.flix.util.{ExecutionMode, FlixSuite, Options}

class CompilerSequentialSuite extends FlixSuite(incremental = true) {
  implicit val options: Options = Options.TestWithLibAll.copy(xdatalogExecution = ExecutionMode.Sequential)

  mkTestDir("main/test/flix/", prelude = Some("main/test/flix/Prelude.flix"))

}
