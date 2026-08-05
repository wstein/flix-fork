/*
 * Copyright 2022 Matthew Lutze
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
package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.errors.EntryPointError
import ca.uwaterloo.flix.util.Options
import org.scalatest.funsuite.AnyFunSuite

class TestEntryPoints extends AnyFunSuite with TestUtils {

  test("Test.IllegalEntryPointArg.Main.01") {
    val input =
      """
        |def main(_blah: Array[String, _]): Unit \ IO = checked_ecast(())
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalEntryPointArg.Main.02") {
    val input =
      """
        |def main(_blah: Array[a, Static]): Unit \ IO = checked_ecast(())
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalEntryPointArg.Main.03") {
    val input =
      """
        |trait C[a]
        |
        |def main(_blah: Array[a, Static]): Unit \ IO with C[a] = checked_ecast(())
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalEntryPointArg.Main.04") {
    val input =
      """
        |def main(_arg1: Array[String, _], _arg2: Array[String, _]): Unit = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalRunnableEntryPointArgs.Main.05") {
    val input =
      """
        |def main(arg1: String, arg2: String): Unit = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
  }

  test("Test.IllegalRunnableEntryPointArgs.Other.01") {
    val input =
      """
        |def f(x: Bool): Unit = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin.copy(entryPoint = Some(Symbol.mkDefnSym("f"))))
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
  }

  test("Test.IllegalRunnableEntryPointArgs.Test.01") {
    val input =
      """
        |@Test
        |def f(x: Int32): Int32 = x
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
  }

  test("Test.IllegalRunnableEntryPointArgs.Test.02") {
    val input =
      """
        |@Test
        |def g(x: Int32, _y: Int32, _a: Float64): Int32 = x
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
  }

  test("Test.IllegalRunnableEntryPointArgs.Test.03") {
    val input =
      """
        |@Test
        |def f(_x: Int32, _y: Int32, a: Float64): Float64 = a
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
  }

  test("Test.IllegalRunnableEntryPointArgs.Test.04") {
    val input =
      """
        |@Test
        |def f(_x: Int32, _y: Int32, _a: Float64): Float64 = 1.0f64
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
  }

  test("Test.TestNonUnitReturnType.01") {
    val input =
      """
        |@Test
        |def testFoo(): Bool = true
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.TestNonUnitReturnType](result)
  }

  test("Test.TestNonUnitReturnType.02") {
    val input =
      """
        |@Test
        |def testBar(): Int32 = 42
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.TestNonUnitReturnType](result)
  }

  test("Test.TestNonUnitReturnType.03") {
    val input =
      """
        |@Test
        |def testBaz(): String = "hello"
      """.stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.TestNonUnitReturnType](result)
  }

  test("Test.IllegalEntryPointTypeVariables.Test.01") {
    val input =
      """
        |@Test
        |def testFoo[a](): Unit = ()
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalEntryPointTypeVariables.Test.02") {
    val input =
      """
        |trait C[a]
        |
        |@Test
        |def testFoo(x: a): Unit with C[a] = ()
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalEntryPointTypeVariables.Test.03") {
    val input =
      """
        |@Test
        |def testFoo[a, b](): Unit = ()
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalEntryPointEffect.Main.01") {
    val input =
      """
        |eff Exc {
        |    pub def raise(): Unit
        |}
        |
        |def main(): Unit \ Exc = Exc.raise()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalEntryPointEffect.Main.02") {
    val input =
      """
        |eff Print {
        |    pub def print(): Unit
        |}
        |
        |eff Exc {
        |    pub def raise(): Unit
        |}
        |
        |def main(): Unit \ Print + Exc  =
        |    Print.print();
        |    Exc.raise()
        |
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalEntryPointEffect.Main.03") {
    val input =
      """
        |eff Print {
        |    pub def print(): Unit
        |}
        |
        |def main(): Unit \ Print + IO  =
        |    Print.print();
        |    println("Hello, World!")
        |
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalEntryPointEffect.Test.01") {
    val input =
      """
        |eff E {
        |    pub def op(): Unit
        |}
        |
        |@Test
        |def testFoo(): Unit \ E = E.op()
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalEntryPointEffect.Test.02") {
    val input =
      """
        |eff E {
        |    pub def op(): Unit
        |}
        |
        |eff F {
        |    pub def op(): Unit
        |}
        |
        |@Test
        |def testFoo(): Unit \ E + F = {
        |    E.op();
        |    F.op()
        |}
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalEntryPointEffect.Test.03") {
    val input =
      """
        |eff E {
        |    pub def op(): Unit
        |}
        |
        |@Test
        |def testFoo(): Unit \ E + IO = {
        |    E.op();
        |    checked_ecast(())
        |}
      """.stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalEntryPointEffect.Test.04") {
    val input =
      """
        |eff Print {
        |    pub def println(): Unit
        |}
        |
        |@Test
        |def foo(): Unit \ Print = Print.println()
        |
      """.stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.MainNonUnitReturnType.Main.01") {
    val input =
      """
        |def main(): a \ IO = checked_ecast(???)
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.MainNonUnitReturnType.Main.02") {
    val input =
      """
        |enum E
        |def main(): E = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.MainNonUnitReturnType](result)
  }

  test("Test.MainNonUnitReturnType.Main.03") {
    // A non-Unit return type is rejected even when it has a ToString instance.
    val input =
      """
        |def main(): Int64 \ IO = checked_ecast(42i64)
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.MainNonUnitReturnType](result)
  }

  test("Test.MainNonUnitReturnType.Main.04") {
    val input =
      """
        |def main(): String = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.MainNonUnitReturnType](result)
  }

  test("Test.MainNonUnitReturnType.Other.01") {
    val input =
      """
        |enum E
        |def f(): E = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin.copy(entryPoint = Some(Symbol.mkDefnSym("f"))))
    expectError[EntryPointError.MainNonUnitReturnType](result)
  }

  test("Test.MainNonUnitReturnType.Other.02") {
    // A non-Unit return type with a ToString instance is rejected for an explicit entry point too.
    val input =
      """
        |def f(): Int64 \ IO = checked_ecast(42i64)
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin.copy(entryPoint = Some(Symbol.mkDefnSym("f"))))
    expectError[EntryPointError.MainNonUnitReturnType](result)
  }

  test("Test.IllegalSignature.Main.01") {
    val input =
      """
        |enum E
        |eff Exc {
        |    pub def raise(): Unit
        |}
        |def main(a: Int32): E \ Exc = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectError[EntryPointError.IllegalRunnableEntryPointArgs](result)
    expectError[EntryPointError.MainNonUnitReturnType](result)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.MainEntryPointNotFound.01") {
    val input =
      """
        |def notF(): String = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin.copy(entryPoint = Some(Symbol.mkDefnSym("f"))))
    expectError[EntryPointError.EntryPointNotFound](result, allowUnknown = true)
  }

  test("Test.MainEntryPointNotFound.02") {
    val input =
      """
        |def main(): String = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin.copy(entryPoint = Some(Symbol.mkDefnSym("f"))))
    expectError[EntryPointError.EntryPointNotFound](result, allowUnknown = true)
  }

  test("Test.ValidEntryPoint.Main.01") {
    val input =
      """
        |def main(): Unit = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.Main.02") {
    val input =
      """
        |def main(): Unit \ IO = checked_ecast(())
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.Main.03") {
    val input =
      """
        |def main(): Unit \ NonDet = checked_ecast(())
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.Main.04") {
    val input =
      """
        |def main(): Unit \ {NonDet, IO} = checked_ecast(())
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.Other.01") {
    val input =
      """
        |def f(): Unit = ???
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin.copy(entryPoint = Some(Symbol.mkDefnSym("f"))))
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.LibNix.01") {
    val input =
      """
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.01") {
    val input =
      """
        |pub eff E {
        |   def op(): Unit
        |}
        |
        |mod E {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E) + IO =
        |            run {
        |                f()
        |            } with handler E {
        |                def op(k) = {
        |                    println("Default behaviour");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit = ()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.02") {
    val input =
      """
        |pub eff E {
        |   def op(): Unit
        |}
        |
        |mod E {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E) + IO =
        |            run {
        |                f()
        |            } with handler E {
        |                def op(k) = {
        |                    println("Default behaviour");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit \ E = E.op()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.03") {
    val input =
      """
        |pub eff E1 {
        |   def op(): Unit
        |}
        |
        |pub eff E2 {
        |   def op(): Unit
        |}
        |
        |mod E1 {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E1) + IO =
        |            run {
        |                f()
        |            } with handler E1 {
        |                def op(k) = {
        |                    println("Default behaviour 1");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E2 {
        |    @DefaultHandler
        |    pub def runWithIO(g: Unit -> b \ ef): b \ (ef - E2) + IO =
        |            run {
        |                g()
        |            } with handler E2 {
        |                def op(k) = {
        |                    println("Default behaviour 2");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit \ E1 + E2 = E1.op();E2.op()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.04") {
    val input =
      """
        |pub eff E1 {
        |   def op(): Unit
        |}
        |
        |pub eff E2 {
        |   def op(): Unit
        |}
        |
        |mod E1 {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E1) + IO =
        |            run {
        |                f()
        |            } with handler E1 {
        |                def op(k) = {
        |                    println("Default behaviour 1");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E2 {
        |    @DefaultHandler
        |    pub def runWithIO(g: Unit -> b \ ef): b \ (ef - E2) + IO =
        |            run {
        |                g()
        |            } with handler E2 {
        |                def op(k) = {
        |                    println("Default behaviour 2");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit \ E2 + E1 = E2.op();E1.op()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.05") {
    val input =
      """
        |pub eff E1 {
        |   def op1(): Unit
        |}
        |
        |pub eff E2 {
        |   def op2(): Unit
        |}
        |
        |pub eff E3 {
        |   def op3(): Unit
        |}
        |
        |mod E1 {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E1) + IO =
        |            run {
        |                f()
        |            } with handler E1 {
        |                def op1(k) = {
        |                    println("Default behaviour 1");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E2 {
        |    @DefaultHandler
        |    pub def runWithIO(g: Unit -> b \ ef): b \ (ef - E2) + IO =
        |            run {
        |                g()
        |            } with handler E2 {
        |                def op2(k) = {
        |                    println("Default behaviour 2");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E3 {
        |    @DefaultHandler
        |    pub def runWithIO(h: Unit -> c \ ef): c \ (ef - E3) + IO =
        |            run {
        |                h()
        |            } with handler E3 {
        |                def op3(k) = {
        |                    println("Default behaviour 3");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit \ E1 + E2 + E3 = E1.op1();E2.op2();E3.op3()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.06") {
    val input =
      """
        |pub eff E1 {
        |   def op1(): Unit
        |}
        |
        |pub eff E2 {
        |   def op2(): Unit
        |}
        |
        |pub eff E3 {
        |   def op3(): Unit
        |}
        |
        |mod E1 {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E1) + IO =
        |            run {
        |                f()
        |            } with handler E1 {
        |                def op1(k) = {
        |                    println("Default behaviour 1");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E2 {
        |    @DefaultHandler
        |    pub def runWithIO(g: Unit -> b \ ef): b \ (ef - E2) + IO =
        |            run {
        |                g()
        |            } with handler E2 {
        |                def op2(k) = {
        |                    println("Default behaviour 2");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E3 {
        |    @DefaultHandler
        |    pub def runWithIO(h: Unit -> c \ ef): c \ (ef - E3) + IO =
        |            run {
        |                h()
        |            } with handler E3 {
        |                def op3(k) = {
        |                    println("Default behaviour 3");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit \ E1 + E3 = E1.op1();E3.op3()
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.ValidEntryPoint.DefaultHandler.07") {
    val input =
      """
        |pub eff E1 {
        |   def op1(): Unit
        |}
        |
        |pub eff E2 {
        |   def op2(): Unit
        |}
        |
        |pub eff E3 {
        |   def op3(): Unit
        |}
        |
        |mod E1 {
        |    @DefaultHandler
        |    pub def runWithIO(f: Unit -> a \ ef): a \ (ef - E1) + IO =
        |            run {
        |                f()
        |            } with handler E1 {
        |                def op1(k) = {
        |                    println("Default behaviour 1");
        |                    k()
        |                }
        |            }
        |}
        |
        |mod E2 {
        |    @DefaultHandler
        |    pub def runWithIO(g: Unit -> b \ ef): b \ (ef - E2) + IO =
        |            run {
        |                g()
        |            } with handler E2 {
        |                def op2(k) = {
        |                    println("Default behaviour 2");
        |                    k()
        |                }
        |            }
        |}
        |mod E3 {
        |    @DefaultHandler
        |    pub def runWithIO(h: Unit -> c \ ef): c \ (ef - E3) + IO =
        |            run {
        |                h()
        |            } with handler E3 {
        |                def op3(k) = {
        |                    println("Default behaviour 3");
        |                    k()
        |                }
        |            }
        |}
        |
        |def main(): Unit \ E1 + E2 + E3 + IO = E1.op1();E2.op2();E3.op3();println("Hello World")
        |""".stripMargin
    val result = check(input, Options.TestWithLibMin)
    expectSuccess(result)
  }

  test("Test.IllegalExportFunction.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export def id(x: Int32): Int32 = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.NonPublicExport](result)
  }

  test("Test.IllegalExportFunction.02") {
    val input =
      """
        |@Export pub def id(x: Int32): Int32 = x
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportNamespace](result)
  }

  test("Test.IllegalExportFunction.03") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def <><(x: Int32, _y: Int32): Int32 = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportName](result)
  }

  test("Test.IllegalExportFunction.04") {
    val input =
      """
        |eff Print
        |def println(x: t): t \ Print = ???()
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Int32): Int32 \ Print = println(x) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointEffect](result)
  }

  test("Test.IllegalExportFunction.05") {
    // An enum other than `Option` has no Java counterpart to be converted into, so it stays
    // unexportable in every position.
    val input =
      """
        |enum Colour {
        |  case Red
        |  case Green
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def red(): Colour = Colour.Red }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.IllegalExportFunction.06") {
    // `Option` is exportable in return position only. As a parameter it would need the reverse
    // conversion, which has no answer for a Java caller passing `Optional.empty()`.
    val input =
      """
        |enum Option[t] {
        |  case Some(t)
        |  case None
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Int32, _y: Option[Int32]): Int32 = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportOption.01") {
    // `Option` in return position is converted to `java.util.Optional` by the shim method.
    val input =
      """
        |enum Option[t] {
        |  case Some(t)
        |  case None
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Int32): Option[Int32] = Some(x) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportOption.02") {
    // The element type still has to be exportable: there is no conversion for a nested `Option`.
    val input =
      """
        |enum Option[t] {
        |  case Some(t)
        |  case None
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Int32): Option[Option[Int32]] = Some(Some(x)) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportList.01") {
    // `List` in return position is converted to an unmodifiable `java.util.List` by the shim.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def names(): List[String] = "a" :: Nil }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
  }

  test("Test.ExportList.02") {
    // The element must itself be exportable. An element with no plan would fall through to a shim
    // returning the internal tag class, which is the failure J16 exists to prevent, so the gate
    // and the solver admit exactly the same set.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): List[List[String]] = ("a" :: Nil) :: Nil }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportList.03") {
    // Nested containers are rejected for the same reason, and the error points at the element
    // rather than at the `List` that contains it.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): List[Option[String]] = None :: Nil }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportList.04") {
    // Return position only: a parameter would need the reverse conversion, which does not exist.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(xs: List[String]): Int32 = List.length(xs) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportPolymorphic.01") {
    // An unconstrained type variable is exported as `java.lang.Object`. The monomorpher defaults
    // it to `AnyType`, which is represented as `Object`, so the boundary needs no special case.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: t): t = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportPolymorphic.02") {
    // Several occurrences of one variable, and a variable beside a concrete type.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    @Export pub def firstOf(x: t, _y: t): t = x
        |    @Export pub def countOf(_x: t, n: Int32): Int32 = n
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportPolymorphic.03") {
    // A constrained variable has no `Object` instantiation: Flix picks a trait implementation from
    // the concrete type while compiling, and no instance exists for the defaulted `AnyType`. Left
    // unchecked this crashes the monomorpher rather than failing, so the error is the thing that
    // keeps that unreachable.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def describe(x: t): String with ToString[t] = ToString.toString(x) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportConstrainedTypeVariable](result)
  }

  test("Test.ExportPolymorphic.04") {
    // A variable that is not itself the boundary type. Defaulting the region of `S[Int32, r]`
    // would pick a representation the exported signature never mentions.
    val input =
      """
        |struct S[t, r] {
        |    v: t
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: t): S[Int32, r] = ??? }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.ExportPolymorphic.05") {
    // An effect variable is not a boundary type either, and defaulting it to pure would silently
    // change what the function is allowed to do.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def apply1(f: Int32 -> Int32 \ ef, x: Int32): Int32 \ ef = f(x) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalExportFunction.08") {
    val input =
      """
        |struct S[t, r] {
        |    v: t
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Int32): S[Int32, r] = ??? }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalExportFunction.09") {
    val input =
      """
        |struct S[t, r] {
        |    v: t
        |}
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Int32, _y: S[Int32, r]): Int32 = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalEntryPointTypeVariables](result)
  }

  test("Test.IllegalExportFunction.11") {
    // A top-level module has no enclosing module, so its class would be in the unnamed package,
    // which Java code in a named package cannot import.
    val input =
      """
        |mod Mod { @Export pub def id(x: Int32): Int32 = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportUnnamedPackage](result)
  }

  test("Test.ExportFunction.String.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: String): String = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportFunction.BigInt.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: BigInt): BigInt = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportFunction.BigDecimal.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: BigDecimal): BigDecimal = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportFunction.Regex.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(x: Regex): Regex = x }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportFunction.Native.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    import java.io.File
        |    @Export pub def id(x: File): File = x
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportFunction.Nullary.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def answer(): Int32 = 42 }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.ExportFunction.UnitReturn.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def ignore(_x: Int32): Unit = () }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }

  test("Test.IllegalExportFunction.10") {
    // `Unit` is only exportable as the return type or as the lone parameter of a nullary
    // function. In any other parameter position it has no Java form.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def id(_x: Unit, y: Int32): Int32 = y }
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportFunction.GenericNative.01") {
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    @Export pub def id(x: ArrayList[String]): ArrayList[String] = x
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibNix)
    expectSuccess(result)
  }
}
