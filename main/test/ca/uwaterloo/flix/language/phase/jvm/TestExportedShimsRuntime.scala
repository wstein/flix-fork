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
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path}
import java.util.Optional

/**
  * Tests exported shims by *calling* them.
  *
  * [[TestExportedShims]] reads descriptors and signatures out of the class file, which pins the API
  * a caller compiles against but says nothing about what happens when the caller runs. A descriptor
  * can be right while the shim behind it fails to link, boxes the wrong way, or converts a value
  * into something other than what the signature promised. Those are exactly the mistakes the
  * conversion plan exists to prevent, so at least one of them has to be checked by execution.
  *
  * Each test compiles a fixture to a temporary directory and loads the generated facade in its own
  * [[URLClassLoader]], so the classes under test never reach the suite's own classpath and two
  * fixtures cannot see each other's definitions.
  */
class TestExportedShimsRuntime extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /**
    * Compiles `input`, loads `className` in an isolated loader, and applies `f` to it.
    *
    * The loader's parent is this class's own loader, so the fixture can reach `java.*` and the Flix
    * runtime classes it was compiled against while its own generated classes stay invisible here.
    */
  private def withFacade[A](input: String, className: String)(f: Class[?] => A): A = {
    val out = Files.createTempDirectory("flix-export-runtime-test")
    try {
      val opts = Options.DefaultTest.copy(outputJvm = true, outputPath = out)
      val flix = new Flix().setOptions(opts)
      flix.addVirtualPath(CompilerConstants.VirtualTestFile, input)
      flix.compile().toResult match {
        case Result.Ok(_) => ()
        case Result.Err(errors) => fail(s"the test program must compile, but got: $errors")
      }
      val classes = out.resolve("class")
      val loader = new URLClassLoader(Array[URL](classes.toUri.toURL), getClass.getClassLoader)
      try f(loader.loadClass(className)) finally loader.close()
    } finally {
      deleteRecursively(out)
    }
  }

  /** Invokes the static, no-argument method `name` on `facade`. */
  private def invoke(facade: Class[?], name: String): AnyRef =
    facade.getMethod(name).invoke(null)

  /** Deletes `path` and everything below it. */
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
    ()
  }

  test("an exported shim links and returns the declared value") {
    // The base case the rest of this suite rests on: if a shim cannot be loaded and called at all,
    // every other assertion here would fail for a reason that has nothing to do with conversion.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def greet(): String = "hello"
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      assertResult("hello")(invoke(facade, "greet"))
    }
  }

  test("an exported Option arrives as a populated Optional") {
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def some(): Option[String] = Some("alpha")
        |
        |    @Export
        |    pub def none(): Option[String] = None
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      assertResult(Optional.of("alpha"))(invoke(facade, "some"))
      assertResult(Optional.empty())(invoke(facade, "none"))
    }
  }

  test("an exported Option of a primitive arrives boxed") {
    // The signature says `Optional<Integer>`; this checks the shim actually puts an `Integer`
    // there. Flix's own boxing wraps in `BackendObjType.Value`, which is not a Java box, so
    // handing that to a caller would satisfy the descriptor and break every use of the value.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def size(): Option[Int32] = Some(42)
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val result = invoke(facade, "size").asInstanceOf[Optional[?]]
      assert(result.isPresent)
      assertResult(classOf[java.lang.Integer])(result.get().getClass)
      assertResult(42)(result.get())
    }
  }

  test("Some(null) and None are indistinguishable to a Java caller") {
    // The shim converts with `Optional.ofNullable`, so a `Some` holding a Java null collapses onto
    // the value `None` produces. `Optional.of` would instead throw inside the shim, blaming the
    // export for the caller's data. This is a known loss rather than a bug, and it is pinned here
    // because nothing else can see it: both cases have identical descriptors and signatures.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def someNull(): Option[String] = Some(unchecked_cast(null as String))
        |
        |    @Export
        |    pub def none(): Option[String] = None
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val someNull = invoke(facade, "someNull")
      val none = invoke(facade, "none")
      assertResult(Optional.empty())(someNull)
      assertResult(none)(someNull)
    }
  }

  test("an exported polymorphic def round-trips any reference") {
    // The monomorpher defaults the unconstrained variable to `AnyType`, which is represented as
    // `Object`. The point of calling it rather than reading the descriptor is that the def is
    // specialized only because it is exported -- nothing in the Flix program calls it -- so a
    // seeding mistake produces a class that is missing or empty rather than one that is wrong.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def id(x: t): t = x
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val id = facade.getMethod("id", classOf[Object])
      assertResult(classOf[Object])(id.getReturnType)
      assertResult("string")(id.invoke(null, "string"))
      assertResult(42)(id.invoke(null, Integer.valueOf(42)))
      assertResult(null)(id.invoke(null, null))
    }
  }

  test("a nested module's shim links under its sibling name") {
    // `mod A.B.C` is the class `A.B$C`, not `C` in a package named after the class `A.B`. The
    // names are pinned by TestNamespaceClasses; this checks the class that name refers to is
    // loadable and its shim callable, which a name-shape assertion cannot show.
    withFacade(
      """mod Acme { }
        |mod Acme.Api {
        |    @Export
        |    pub def two(): String = "two"
        |}
        |mod Acme.Api.Deep {
        |    @Export
        |    pub def three(): String = "three"
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Acme.Api$Deep") { facade =>
      assertResult("three")(invoke(facade, "three"))
    }
  }
}
