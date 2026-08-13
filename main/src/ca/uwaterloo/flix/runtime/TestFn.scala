/*
 * Copyright 2022 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.runtime

import ca.uwaterloo.flix.language.ast.Symbol

/**
  * Represents a unit test.
  *
  * The generated class and method are carried alongside the callable, because they are what a *later*
  * process needs to reach the same test: a build that is still current can be tested from its class
  * files, and nothing else records where a test's shim ended up.
  *
  * @param sym        the Flix def symbol.
  * @param skip       true if the test case is marked @Skip.
  * @param run        the function code.
  * @param className  the binary name of the class the test's shim method is on.
  * @param methodName the name of that method.
  */
case class TestFn(sym: Symbol.DefnSym, skip: Boolean, run: () => AnyRef, className: String, methodName: String)
