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

  test("Test.ExportTypeArgument.01") {
    // A Java container whose element is a Flix enum used to be accepted, because only the *head*
    // of a type application was checked. Erasure made it look safe -- the descriptor says
    // `java.util.ArrayList` and mentions nothing of Flix -- while the values crossing were
    // `dev.flix.gen.Colour$Red`, a generated class the backend renames freely.
    val input =
      """
        |enum Colour { case Red, case Green }
        |mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    @Export pub def f(): ArrayList[Colour] \ IO = new ArrayList()
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTypeArgument.02") {
    // Every argument, not just the first.
    val input =
      """
        |enum Colour { case Red, case Green }
        |mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.HashMap
        |    @Export pub def f(): HashMap[String, Colour] \ IO = new HashMap()
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTypeArgument.03") {
    // Parameter position too: a Java container is as leaky going in as coming out.
    val input =
      """
        |enum Colour { case Red, case Green }
        |mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    @Export pub def f(l: ArrayList[Colour]): Int32 \ IO = l.size()
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTypeArgument.04") {
    // The legitimate cases must be unaffected, including nesting and arity two. A *Java* enum has
    // a stable Java type of its own and is the way to give a Flix enum a representation that can
    // cross.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |    import java.util.HashMap
        |    import java.time.DayOfWeek
        |
        |    @Export pub def a(): ArrayList[String] \ IO = new ArrayList()
        |    @Export pub def b(): HashMap[String, Int32] \ IO = new HashMap()
        |    @Export pub def c(): ArrayList[ArrayList[String]] \ IO = new ArrayList()
        |    @Export pub def d(): ArrayList[DayOfWeek] \ IO = new ArrayList()
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
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

  test("Test.ExportSet.01") {
    // `Set` in return position is presented as an unmodifiable `java.util.Set` by a lazy view over
    // the red-black tree, rather than copied.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def names(): Set[String] = Set#{"a"} }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
  }

  test("Test.ExportSet.02") {
    // One level, as for `List`: an element that is itself a container has no plan, and the view
    // would hand back the internal tag class rather than fail.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): Set[List[String]] = Set#{"a" :: Nil} }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportSet.03") {
    // A Flix enum element is rejected here rather than at the view, since a `Set[Colour]` would
    // otherwise iterate into `dev.flix.gen` class instances -- a leak in the values, which the
    // descriptor `java.util.Set` does nothing to reveal.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(): Set[Colour] = Set#{}
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportSet.04") {
    // Return position only. A parameter would need a Java set turned back into a red-black tree,
    // which needs the element's `Order` instance and so has no conversion at all.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(xs: Set[String]): Int32 = Set.size(xs) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportSet.05") {
    // Identified by symbol, so a user-defined `Set` stays an ordinary enum and unexportable. The
    // gate and `ExportPlan` ask this question separately, of different representations, so a
    // disagreement here would admit a type the backend cannot build.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Set[t] { case Set(t) }
        |    @Export pub def f(): Pkg.Mod.Set[String] = Pkg.Mod.Set.Set("a")
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportMap.01") {
    // `Map` in return position is presented as an unmodifiable `java.util.Map` over the same tree
    // view a `Set` uses, differing only in what each node hands out.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def ages(): Map[String, Int32] = Map#{"a" => 1} }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
  }

  test("Test.ExportMap.02") {
    // *Both* arguments are checked, not just the first. Checking only the key would admit a map
    // whose values iterate into generated classes, which is the leak in the values that a
    // `java.util.Map` descriptor does nothing to reveal.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(): Map[String, Colour] = Map#{}
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportMap.03") {
    // And the key, symmetrically.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(): Map[Colour, String] = Map#{}
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportMap.04") {
    // One level, as for `List` and `Set`: a value that is itself a container has no plan.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): Map[String, List[Int32]] = Map#{"a" => 1 :: Nil} }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportMap.05") {
    // Return position only. A parameter would need a Java map turned back into a red-black tree,
    // which needs the key's `Order` instance and so has no conversion at all.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(m: Map[String, Int32]): Int32 = Map.size(m) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTuple.01") {
    // A tuple in return position is presented as a `dev.flix.runtime.TupleN` record, so what has
    // to be exportable is what it holds rather than the tuple itself.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): (Int32, String) = (1, "a") }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
  }

  test("Test.ExportTuple.02") {
    // Arity is not bounded: the record class is generated per arity, on demand.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): (Bool, Float64, String, Int32) = (true, 1.0f64, "a", 1) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
  }

  test("Test.ExportTuple.03") {
    // Every element is checked, not just the first.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(): (String, Colour) = ("a", Colour.Red)
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTuple.04") {
    // One level, as for every other container: an element that is itself converted has no plan,
    // so admitting this would compile a shim that hands back the internal representation.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): (Int32, List[String]) = (1, "a" :: Nil) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTuple.05") {
    // Nor a tuple inside a tuple, which is the same rule seen from the other side.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): (Int32, (String, Bool)) = (1, ("a", true)) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTuple.06") {
    // Return position only. A parameter would need the reverse conversion, and a Java caller
    // constructing a `Tuple2` says nothing about which Flix tuple class it should become.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(t: (Int32, String)): Int32 = fst(t) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportTuple.07") {
    // `Unit` is exportable as a whole return type, where it becomes `void`, and as the lone
    // parameter Flix gives a nullary function, where it is dropped. Neither renders it away here,
    // and its representation is `dev.flix.runtime.Unit` -- which the leak test in
    // `TestExportedShims` would *not* catch, because it greps for `dev.flix.gen`. So this is
    // pinned directly: widening that special case to elements is the mistake it guards.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): (Int32, Unit) = (1, ()) }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportEnum.01") {
    // An enum whose cases all carry no data is presented as a real Java enum.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(): Colour = Colour.Red
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectSuccess(result)
  }

  test("Test.ExportEnum.02") {
    // A case carrying data has no constant to be. This is the sealed-interface shape, which does
    // not exist yet, so admitting it would compile a shim handing back the internal tag class.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Shape { case Circle(Int32), case Square }
        |    @Export pub def f(): Shape = Shape.Square
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportEnum.03") {
    // A generic enum erases to one JVM class whatever it is applied to, so it could only cross
    // raw -- losing exactly the argument the caller asked about.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Box[t] { case Empty }
        |    @Export pub def f(): Box[Int32] = Box.Empty
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportEnum.04") {
    // Declared in the root namespace, so the generated class would land in `dev.flix.gen` -- the
    // package J0 keeps private. The same requirement the exported function itself already meets.
    val input =
      """
        |pub enum Colour { case Red, case Green }
        |mod Pkg { }
        |mod Pkg.Mod { @Export pub def f(): Colour = Colour.Red }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportEnum.05") {
    // One namespace segment is not enough either: it leaves the class in `dev.flix.gen` just as
    // the root namespace does, because the first segment is what becomes the Java package.
    val input =
      """
        |mod Pkg {
        |    pub enum Colour { case Red, case Green }
        |}
        |mod Pkg.Mod { @Export pub def f(): Pkg.Colour = Pkg.Colour.Red }
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportEnum.06") {
    // Return position only, like every other converted type: a parameter needs the reverse
    // conversion, which would have to map a Java constant back onto a Flix tag singleton.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(c: Colour): Int32 = match c { case Colour.Red => 0, case Colour.Green => 1 }
        |}
        |""".stripMargin
    val result = check(input, Options.TestWithLibAll)
    expectError[EntryPointError.IllegalExportType](result)
  }

  test("Test.ExportEnum.07") {
    // And not nested, per J17: the element of a container has no plan of its own yet.
    val input =
      """
        |mod Pkg { }
        |mod Pkg.Mod {
        |    pub enum Colour { case Red, case Green }
        |    @Export pub def f(): List[Colour] = Colour.Red :: Nil
        |}
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
