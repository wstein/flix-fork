/*
 * Copyright 2026 Werner Stein
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
package ca.uwaterloo.flix.api.bsp

import org.scalatest.{DoNotDiscover, Suites}

/**
  * The BSP tests that need a built assembly or a process of their own.
  *
  * Reached by `./mill flix.testBsp`, which builds the assembly first. They are kept out of the
  * default suite for two reasons, and the second is the one that matters: the default suite is
  * already at CI's wall, and -- more importantly -- a test that quietly cancels itself when the jar
  * is missing reports green while proving nothing. Depending on `assembly()` in the task is what
  * makes them mean something.
  *
  * Fast, in-process BSP tests deliberately stay in `flix.test`.
  */
@DoNotDiscover
class BspSuite extends Suites(
  new TestBspAssembly,
  new TestBspProcess,
  new TestBspRun
)
