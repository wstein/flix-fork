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

  test("an exported generic Java return actually returns its value") {
    // Declaring the type arguments must not change what crosses. It did once: the shim read every
    // planned result as a tag, which is right for a converted `Option` and wrong for a Java type
    // that is only being described, so the method verified as returning a tag and threw
    // `VerifyError: Bad return type` on the first call. Nothing static could see it -- the
    // descriptor and the signature were both correct, and a consumer compiled against them fine.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    import java.util.ArrayList
        |
        |    @Export
        |    pub def strings(): ArrayList[String] \ IO =
        |        let l = new ArrayList();
        |        discard l.add("hello");
        |        l
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val result = invoke(facade, "strings").asInstanceOf[java.util.ArrayList[?]]
      assertResult(1)(result.size())
      assertResult("hello")(result.get(0))
    }
  }

  test("an exported List arrives with its elements, in order") {
    // The conversion walks a cons chain with a loop, so order, the empty case, and the boundary
    // between the last element and `Nil` are all things only running it can check.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: "c" :: Nil
        |
        |    @Export
        |    pub def none(): List[String] = Nil
        |
        |    @Export
        |    pub def numbers(): List[Int32] = 1 :: 2 :: 3 :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.List[?]]
      assertResult(java.util.List.of("a", "b", "c"))(names)
      assertResult(java.util.List.of())(invoke(facade, "none"))
      // A `List` holds references, so the element is a real `Integer`, not Flix's own box.
      val numbers = invoke(facade, "numbers").asInstanceOf[java.util.List[?]]
      assertResult(java.util.List.of(1, 2, 3))(numbers)
      assertResult(classOf[java.lang.Integer])(numbers.get(0).getClass)
    }
  }

  test("an exported List cannot be written to") {
    // A Flix list is immutable. Handing back a mutable copy would invite a caller to write to
    // something that looks like the Flix value and is not.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.List[Any]]
      assertThrows[UnsupportedOperationException](names.add("b"))
    }
  }

  test("an exported List honours the java.util.List contract") {
    // `AbstractSequentialList` writes `get`, `indexOf`, `contains`, `equals`, `hashCode`,
    // `subList` and `stream` in terms of the one `listIterator(int)` this view supplies. They are
    // checked together because they are what the choice of `AbstractSequentialList` over
    // `AbstractList` buys -- a wrong iterator satisfies a single traversal and fails these.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: "c" :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.List[Any]]
      assertResult(3)(names.size())
      assertResult("b")(names.get(1))
      assertResult(1)(names.indexOf("b"))
      assertResult(-1)(names.indexOf("z"))
      assert(names.contains("c"))
      assertResult(java.util.List.of("a", "b", "c"))(names)
      assertResult(java.util.List.of("a", "b", "c").hashCode())(names.hashCode())
      assertResult(java.util.List.of("b", "c"))(names.subList(1, 3))
    }
  }

  test("an exported List iterates backwards as well as forwards") {
    // `previous` re-walks from the head, since a cons chain has no back-pointers (J10). That makes
    // it the one part of the iterator whose implementation differs from `next` rather than
    // mirroring it, so the two are checked against each other here.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: "c" :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.List[Any]]
      val it = names.listIterator()
      assert(!it.hasPrevious)
      assertResult(0)(it.nextIndex())
      assertResult(-1)(it.previousIndex())
      assertResult(List("a", "b", "c"))(List(it.next(), it.next(), it.next()))
      assert(!it.hasNext)
      assertResult(3)(it.nextIndex())
      // Walking back yields the same elements in reverse, and lands where it started.
      assertResult(List("c", "b", "a"))(List(it.previous(), it.previous(), it.previous()))
      assert(!it.hasPrevious)
      assertResult(0)(it.nextIndex())
      assertThrows[java.util.NoSuchElementException](it.previous())
    }
  }

  test("an exported List rejects an out-of-range index") {
    // `AbstractSequentialList.get` turns a `NoSuchElementException` from the iterator into an
    // `IndexOutOfBoundsException`, but `listIterator(int)` is also called directly and has to
    // reject a bad index itself rather than walk off the end of the chain.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: "b" :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.List[Any]]
      assertThrows[IndexOutOfBoundsException](names.get(2))
      assertThrows[IndexOutOfBoundsException](names.get(-1))
      assertThrows[IndexOutOfBoundsException](names.listIterator(3))
      assertThrows[IndexOutOfBoundsException](names.listIterator(-1))
      // The position *after* the last element is legal: it is where a forward walk ends.
      assert(!names.listIterator(2).hasNext)
      assert(names.listIterator(2).hasPrevious)
    }
  }

  test("an exported List refuses every list-iterator mutator") {
    // `java.util.ListIterator` declares all nine methods abstract -- there are no defaults to
    // inherit as there are on `Iterator` -- so unlike the set view, immutability here is written
    // rather than inherited, and each of the three has to be checked.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): List[String] = "a" :: Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.List[Any]]
      val it = names.listIterator()
      assertThrows[UnsupportedOperationException](it.add("b"))
      assertThrows[UnsupportedOperationException](it.set("b"))
      assertThrows[UnsupportedOperationException](it.remove())
      assertThrows[UnsupportedOperationException](names.set(0, "b"))
      assertThrows[UnsupportedOperationException](names.remove(0))
      assertThrows[UnsupportedOperationException](names.clear())
    }
  }

  test("an exported empty List is empty at every level") {
    // `Nil` is a nullary tag rather than a `Cons`, so an empty chain is the case where the
    // `instanceof` test that drives the whole walk decides everything.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def none(): List[String] = Nil
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val none = invoke(facade, "none").asInstanceOf[java.util.List[Any]]
      assert(none.isEmpty)
      assertResult(0)(none.size())
      assertResult(java.util.List.of())(none)
      assert(!none.listIterator().hasNext)
      assert(!none.listIterator().hasPrevious)
      assertThrows[java.util.NoSuchElementException](none.listIterator().next())
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

  test("an exported Set arrives with its elements, in ascending order") {
    // A `Set` is a red-black tree and the view walks it lazily, so nothing about this is visible
    // statically: the descriptor says `java.util.Set` whether the walk is right, wrong, or absent.
    // The order is asserted rather than the membership alone, because ascending order is a promise
    // the view makes (J10) and an in-order walk is the only traversal that keeps it -- a pre-order
    // one would pass a membership test and break the promise.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): Set[String] = Set#{"delta", "alpha", "charlie", "bravo"}
        |
        |    @Export
        |    pub def none(): Set[String] = Set#{}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.Set[?]]
      assertResult(List("alpha", "bravo", "charlie", "delta"))(drain(names))
      assertResult(java.util.Set.of())(invoke(facade, "none"))
    }
  }

  test("an exported Set of a primitive arrives boxed, in ascending order") {
    // The element conversion is emitted inside the iterator rather than at construction, so a
    // primitive is boxed once per element per traversal. That the box is a real `Integer` -- and
    // not Flix's own `Value` wrapper -- can only be seen by taking an element out.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def numbers(): Set[Int32] = Set#{5, 3, 9, 1}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val numbers = invoke(facade, "numbers").asInstanceOf[java.util.Set[?]]
      assertResult(List(1, 3, 5, 9))(drain(numbers))
      assertResult(classOf[java.lang.Integer])(numbers.iterator().next().getClass)
    }
  }

  test("an exported Set reports its size and emptiness") {
    // `size()` is computed by walking the tree once and cached, so it is asked for twice here: a
    // cache that stores the wrong value, or that fails to store one, shows up on the second call.
    // `isEmpty` is overridden separately to stay O(1), which means it can disagree with `size()`.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): Set[String] = Set#{"a", "b", "c"}
        |
        |    @Export
        |    pub def none(): Set[String] = Set#{}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.Set[?]]
      assertResult(3)(names.size())
      assertResult(3)(names.size())
      assert(!names.isEmpty)

      val none = invoke(facade, "none").asInstanceOf[java.util.Set[?]]
      assertResult(0)(none.size())
      assert(none.isEmpty)
    }
  }

  test("an exported Set honours the java.util.Set contract") {
    // The view inherits `contains`, `equals`, `hashCode` and `stream` from `AbstractSet`, all of
    // which are written in terms of `iterator()` and `size()`. They are checked together because
    // an iterator that is subtly wrong -- one element short, or not resettable -- satisfies a
    // single traversal and fails these.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): Set[String] = Set#{"a", "b", "c"}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.Set[Any]]
      assert(names.contains("b"))
      assert(!names.contains("z"))
      assertResult(java.util.Set.of("a", "b", "c"))(names)
      assertResult(java.util.Set.of("a", "b", "c").hashCode())(names.hashCode())
      // Each `iterator()` walks from the root independently, so a second traversal sees everything
      // the first one did.
      assertResult(drain(names))(drain(names))
    }
  }

  test("an exported Set cannot be written to") {
    // Immutability is not implemented by the view; it is what the view gets by never overriding
    // `Iterator.remove`, which every inherited mutator is written in terms of. That makes it worth
    // checking rather than assuming, since it holds by omission.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): Set[String] = Set#{"a"}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val names = invoke(facade, "names").asInstanceOf[java.util.Set[Any]]
      assertThrows[UnsupportedOperationException](names.add("b"))
      assertThrows[UnsupportedOperationException](names.remove("a"))
      assertThrows[UnsupportedOperationException](names.clear())
      assertThrows[UnsupportedOperationException](names.iterator().remove())
    }
  }

  test("an exhausted Set iterator throws NoSuchElementException") {
    // Required of every `Iterator`. The view gets it from `ArrayDeque.pop` rather than from a
    // check of its own, so it is worth confirming that the exception a caller sees is the one the
    // interface specifies.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def names(): Set[String] = Set#{"a"}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val it = invoke(facade, "names").asInstanceOf[java.util.Set[Any]].iterator()
      assertResult("a")(it.next())
      assert(!it.hasNext)
      assertThrows[java.util.NoSuchElementException](it.next())
    }
  }

  test("an exported Map arrives with its entries, in ascending key order") {
    // The same tree walk as a `Set`, handing out `Map.Entry` instead of a key. Order is asserted
    // because it is a promise (J10) that membership alone would not catch, and the entries are
    // drained through `entrySet` rather than compared as a map so that the order is visible.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def ages(): Map[String, Int32] =
        |        Map#{"delta" => 4, "alpha" => 1, "charlie" => 3, "bravo" => 2}
        |
        |    @Export
        |    pub def none(): Map[String, Int32] = Map#{}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val ages = invoke(facade, "ages").asInstanceOf[java.util.Map[?, ?]]
      assertResult(List("alpha", "bravo", "charlie", "delta"))(drain(ages.keySet()))
      assertResult(List(1, 2, 3, 4))(drain(ages.entrySet()).map(_.asInstanceOf[java.util.Map.Entry[?, ?]].getValue))
      assert(invoke(facade, "none").asInstanceOf[java.util.Map[?, ?]].isEmpty)
    }
  }

  test("an exported Map honours the java.util.Map contract") {
    // `AbstractMap` writes `get`, `containsKey`, `keySet`, `values`, `equals` and `hashCode` in
    // terms of `entrySet()`. They are checked together because an entry set that is subtly wrong
    // -- entries in the wrong order of key and value, say -- still satisfies a single traversal.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def ages(): Map[String, Int32] = Map#{"a" => 1, "b" => 2}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val ages = invoke(facade, "ages").asInstanceOf[java.util.Map[Any, Any]]
      assertResult(2)(ages.size())
      assertResult(1)(ages.get("a"))
      assertResult(2)(ages.get("b"))
      assertResult(null)(ages.get("z"))
      assert(ages.containsKey("a"))
      assert(ages.containsValue(2))
      assertResult(java.util.Map.of("a", 1, "b", 2))(ages)
      assertResult(java.util.Map.of("a", 1, "b", 2).hashCode())(ages.hashCode())
      // A `Map` holds references, so a primitive value is a real `Integer`, not Flix's own box.
      assertResult(classOf[java.lang.Integer])(ages.get("a").getClass)
    }
  }

  test("an exported Map of primitives boxes both key and value") {
    // Key and value are converted by separate plans, so a boxing applied to one and not the other
    // is a shape only a map with primitives on both sides can show.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def squares(): Map[Int32, Float64] = Map#{2 => 4.0f64, 1 => 1.0f64}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val squares = invoke(facade, "squares").asInstanceOf[java.util.Map[Any, Any]]
      assertResult(List(1, 2))(drain(squares.keySet()))
      assertResult(classOf[java.lang.Integer])(drain(squares.keySet()).head.getClass)
      assertResult(classOf[java.lang.Double])(squares.get(1).getClass)
      assertResult(1.0)(squares.get(1))
    }
  }

  test("an exported Map cannot be written to") {
    // As for the set view, immutability follows from never overriding `Iterator.remove` -- plus
    // `AbstractMap.put`, which throws on its own. `Map.Entry.setValue` is the third way in, and it
    // is closed by using the JDK's immutable entry rather than a mutable one.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def ages(): Map[String, Int32] = Map#{"a" => 1}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val ages = invoke(facade, "ages").asInstanceOf[java.util.Map[Any, Any]]
      assertThrows[UnsupportedOperationException](ages.put("b", 2))
      assertThrows[UnsupportedOperationException](ages.remove("a"))
      assertThrows[UnsupportedOperationException](ages.clear())
      val entry = ages.entrySet().iterator().next()
      assertThrows[UnsupportedOperationException](entry.setValue(2))
    }
  }

  test("an exported Map reports the same size on every call") {
    // The entry set is built once in the constructor rather than per call, so its size cache
    // survives. A fresh entry set per call would still be correct and would walk the tree every
    // time, which is the mistake this pins.
    withFacade(
      """mod Pkg { }
        |mod Pkg.Mod {
        |    @Export
        |    pub def ages(): Map[String, Int32] = Map#{"a" => 1, "b" => 2, "c" => 3}
        |}
        |
        |def main(): Unit \ IO = println("built")
        |""".stripMargin, "Pkg.Mod") { facade =>
      val ages = invoke(facade, "ages").asInstanceOf[java.util.Map[Any, Any]]
      assertResult(3)(ages.size())
      assertResult(3)(ages.size())
      assert(!ages.isEmpty)
      assert(ages.entrySet() eq ages.entrySet())
    }
  }

  /** Drains `set` into a list, so a traversal can be compared in order rather than as a set. */
  private def drain(set: java.util.Set[?]): List[Any] = {
    val it = set.iterator()
    val buffer = scala.collection.mutable.ListBuffer.empty[Any]
    while (it.hasNext) buffer += it.next()
    buffer.toList
  }
}
