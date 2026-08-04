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
package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.runtime.CompilationResult
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests which namespace classes reach the emitted jar.
  *
  * A namespace class holds nothing but the shim methods of the namespace's entry points, and Flix
  * code never refers to it. An empty one is therefore dead weight, and worse: it is named after the
  * namespace, so `mod String` in the standard library used to emit a `String` class in the unnamed
  * package. Any Java source file compiled against such a jar resolves `String` to that class rather
  * than to `java.lang.String`, which silently turns `main(String[])` into `main([LString;)V` and
  * makes the program unlaunchable.
  *
  * Being named after the namespace also means the class competes with the package of that name,
  * which is why the backend generates `Acme.Api$Def$…` beside the class rather than
  * `Acme.Api.Def$…` beneath it. The rule these tests pin is that a name the programmer chose
  * denotes a class or a package, never both.
  */
class TestNamespaceClasses extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** Compiles `input` and returns the result, failing the test if it does not compile. */
  private def compile(input: String): CompilationResult = {
    val flix = new Flix().setOptions(Options.DefaultTest)
    flix.addVirtualPath(CompilerConstants.VirtualTestFile, input)
    flix.compile().toResult match {
      case Result.Ok(result) => result
      case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
    }
  }

  test("a namespace without entry points emits no class") {
    val result = compile(
      """mod Alpha {
        |    pub def foo(): Int32 = 1
        |}
        |
        |def main(): Unit \ IO = println(Alpha.foo())
        |""".stripMargin)
    assert(!result.classNames.contains("Alpha"))
  }

  test("a namespace with an exported def emits a class") {
    val result = compile(
      """mod Pkg { }
        |mod Pkg.Beta {
        |    @Export
        |    pub def bar(x: Int32): Int32 = x + 1
        |}
        |
        |def main(): Unit \ IO = println(Pkg.Beta.bar(1))
        |""".stripMargin)
    assert(result.classNames.contains("Pkg.Beta"))
  }

  test("a nested namespace with an exported def emits a package-qualified class") {
    val result = compile(
      """mod Acme { }
        |mod Acme.Gamma {
        |    @Export
        |    pub def baz(x: Int32): Int32 = x + 1
        |}
        |
        |def main(): Unit \ IO = println("hello")
        |""".stripMargin)
    assert(result.classNames.contains("Acme.Gamma"))
  }

  test("standard library namespaces do not shadow java.lang classes") {
    val result = compile("""def main(): Unit \ IO = println("hello")""")
    for (shadowed <- List("String", "Object", "Integer", "Character", "Boolean", "Iterable")) {
      assert(!result.classNames.contains(shadowed), s"'$shadowed' shadows java.lang.$shadowed for Java callers")
    }
  }

  test("no class shares its name with a package") {
    // A namespace class is named after its namespace, so leaving the namespace's defs in the
    // package of the same name made `Acme.Api` denote both a class and a package. The JVM permits
    // that and Java tolerates it, but Scala rejects the classpath ("package Acme contains object
    // and package with same name: Api") and Kotlin resolves the package and never sees the class,
    // reporting every exported function as unresolved. A namespace with an exported def always has
    // both, so this made exports unreachable from those two languages.
    //
    // The check is on the shape of the names rather than on any particular one, because the rule
    // it protects is a property of the whole jar: nothing the programmer named may be both.
    val result = compile(
      """mod Acme { }
        |mod Acme.Api {
        |    @Export
        |    pub def get(x: Int32): Int32 = x + 1
        |}
        |
        |def main(): Unit \ IO = println(Acme.Api.get(1))
        |""".stripMargin)

    val packages = result.classNames.flatMap { name =>
      val segments = name.split('.').toList
      segments.inits.filter(prefix => prefix.nonEmpty && prefix != segments).map(_.mkString("."))
    }
    val clashes = result.classNames.filter(packages.contains)
    assert(clashes.isEmpty, s"these names denote both a class and a package: ${clashes.toList.sorted.mkString(", ")}")
  }

  test("Main is the only class in the unnamed package") {
    // Exercises the shapes the backend synthesises classes for: tuples, closures, records, enums,
    // lazy values, effects, and a def in the root namespace.
    val result = compile(
      """eff Ask {
        |    def ask(): Int32
        |}
        |
        |enum Colour {
        |    case Red
        |    case Custom(Int32, String)
        |}
        |
        |def rootLevel(f: Int32 -> Int32): Int32 = f(1)
        |
        |def main(): Unit \ IO =
        |    let pair = (1, "two");
        |    let record = { x = 1, y = 2 };
        |    let thunk = lazy (1 + 1);
        |    let colour = Colour.Custom(3, "four");
        |    let asked = run { Ask.ask() } with handler Ask { def ask(k) = k(42) };
        |    println((rootLevel(x -> x + 1), pair, record#x, force thunk, asked));
        |    match colour {
        |        case Colour.Red => println("red")
        |        case Colour.Custom(n, s) => println("${n} ${s}")
        |    }
        |""".stripMargin)
    val unnamed = result.classNames.filterNot(_.contains('.'))
    assert(unnamed == Set("Main"), s"expected only Main in the unnamed package, but found: ${unnamed.toList.sorted.mkString(", ")}")
  }

}
