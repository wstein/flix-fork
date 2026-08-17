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
package ca.uwaterloo.flix

import ca.uwaterloo.flix.api.{Flix, FlixEvent, FlixListener}
import ca.uwaterloo.flix.language.ast.shared.SecurityContext
import ca.uwaterloo.flix.language.ast.{SymId, Symbol}
import ca.uwaterloo.flix.util.{Options, Result, StableName}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Paths
import scala.collection.mutable

/**
  * Verifies the end-to-end guarantee content-addressed naming exists for: the set of
  * generated JVM class names is stable across repeated compiles of identical source, and
  * unaffected by an unrelated addition elsewhere in the source.
  *
  * `TestStableName`, `TestSpecializationKey`, and `TestErasureKey` cover the key-rendering
  * logic in isolation; nothing exercises the actual outcome through the real
  * `Specialization`, `LambdaLift`, and `Eraser` phases together, on real compiled output.
  * This does, by compiling a program that exercises specialized defs, enum/struct
  * specialization, instance members, derived defs, and lifted closures, and comparing the
  * generated class names across compiles.
  */
class TestArtifactStability extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  /** Exercises specialized defs, enum specialization, instance members, a derived instance, and a lifted closure. */
  private val Source: String =
    """
      |enum Wrapper[a] {
      |    case Wrapper(a)
      |}
      |
      |enum Color with Eq, ToString {
      |    case Red, Green, Blue
      |}
      |
      |trait Describable[a] {
      |    pub def describe(x: a): String
      |}
      |
      |instance Describable[Int32] {
      |    pub def describe(x: Int32): String = "Int32(${x})"
      |}
      |
      |instance Describable[String] {
      |    pub def describe(x: String): String = "String(${x})"
      |}
      |
      |def unbox(b: Wrapper[a]): a = match b {
      |    case Wrapper.Wrapper(x) => x
      |}
      |
      |def liftedClosures(n: Int32): (Int32 -> Int32, Int32 -> Int32) =
      |    let f = x -> x + n;
      |    let g = y -> y * n;
      |    (f, g)
      |
      |def main(): Unit \ IO = {
      |    let bi = unbox(Wrapper.Wrapper(1));
      |    let bs = unbox(Wrapper.Wrapper("s"));
      |    let c = Color.Red;
      |    let (f, g) = liftedClosures(3);
      |    println("${Describable.describe(bi)}/${Describable.describe(bs)}/${c}/${f(1)}/${g(1)}")
      |}
      |""".stripMargin

  /**
    * Applies the same stdlib higher-order functions at many distinct element types, so the
    * library defs they call are specialized repeatedly rather than once. One of the ids this
    * produces reduces to a value with a base-36 leading zero digit, which is what makes it a
    * fixture for [[StableName]]'s padding rather than only for specialization.
    */
  private val RepeatedSpecializations: String =
    """
      |def repeatedStdlibDemo(): Int32 = {
      |    let a = List.map(x -> x + 1, 1 :: 2 :: 3 :: Nil) |> List.length;
      |    let b = List.map(x -> x + 1i64, 1i64 :: 2i64 :: Nil) |> List.length;
      |    let c = List.map(x -> "${x}!", "p" :: "q" :: Nil) |> List.length;
      |    let d = List.map(x -> not x, true :: false :: Nil) |> List.length;
      |    let e = List.map(x -> x * 2.0f64, 1.0f64 :: 2.0f64 :: Nil) |> List.length;
      |    let f = List.filter(x -> x > 1, 1 :: 2 :: 3 :: Nil) |> List.length;
      |    let g = List.filter(x -> String.length(x) > 1, "y" :: "zz" :: Nil) |> List.length;
      |    let h = Vector.map(x -> x + 1, Vector#{1, 2, 3}) |> Vector.length;
      |    let i = Vector.map(x -> "${x}", Vector#{'a', 'b'}) |> Vector.length;
      |    a + b + c + d + e + f + g + h + i
      |}
      |
      |def main(): Unit \ IO = println(repeatedStdlibDemo())
      |""".stripMargin

  /** Compiles `source` and returns the content-addressed id of every class the back end emitted. */
  private def emittedIds(source: String): List[String] = {
    val emitted = mutable.ArrayBuffer.empty[Symbol.DefnSym]
    val flix = new Flix()
    flix.setOptions(Options.DefaultTest.copy(incremental = false))
    flix.addListener(new FlixListener {
      // CodeGen emits classes in parallel, so the collection has to be guarded.
      override def notify(e: FlixEvent): Unit = e match {
        case FlixEvent.EmittedClass(sym, _) => emitted.synchronized(emitted += sym)
        case _ => ()
      }
    })
    flix.addVirtualPath(Paths.get("Test.flix"), source)
    flix.compile().toResult match {
      case Result.Ok(_) => emitted.synchronized(emitted.toList).flatMap(_.id).collect { case SymId.Hash(value) => value }
      case Result.Err(errors) => fail(errors.map(_.summary).mkString("\n"))
    }
  }

  /** Compiles `source` and returns the binary names of every generated class. */
  private def classNames(source: String): Set[String] = {
    val flix = new Flix()
    flix.setOptions(Options.DefaultTest.copy(incremental = false))
    flix.addVirtualPath(Paths.get("Test.flix"), source)
    flix.compile().toResult match {
      case Result.Ok(result) => result.getClasses.keySet.map(_.toBinaryName)
      case Result.Err(errors) => fail(errors.map(_.summary).mkString("\n"))
    }
  }

  test("repeatedCompile.01") {
    // Two compiles of identical source must agree on every generated class name.
    val first = classNames(Source)
    val second = classNames(Source)
    // Sanity check against a vacuous pass: TreeShaker1 prunes unreachable stdlib code, but
    // this program's dependency closure (println, string interpolation, Eq/ToString
    // derivation) alone still compiles to well over a hundred classes, so a suspiciously
    // small set means classNames broke, not that stability trivially held.
    assert(first.size > 50, s"expected dozens of classes at least, got ${first.size}")
    assert(first == second)
  }

  test("fixedWidthIds.01") {
    // Every id in the emitted output renders at exactly the configured width. Unpadded, a
    // value with a base-36 leading zero digit renders a digit short, which makes a generated
    // name indistinguishable in shape from a narrower id or from a counter.
    val ids = emittedIds(RepeatedSpecializations)
    assert(ids.length > 20, s"expected the specializations to produce dozens of ids, got ${ids.length}")
    val wrong = ids.filter(_.length != StableName.DefaultWidth).distinct
    assert(wrong.isEmpty, s"ids not ${StableName.DefaultWidth} digits wide: $wrong")
  }

  test("fixedWidthIds.02") {
    // Pins the specific id in the fixture above whose value has a leading zero digit, so the
    // padding is asserted on a real case rather than merely on a likely one. Regenerate both
    // spellings if the fixture source or a naming key changes.
    //
    // The two are the same id: 36^11 <= 0z56ok3gyegs < 36^12, so its twelfth digit is a zero
    // and an unpadded render drops it. Padding changes how the value is spelled and nothing
    // about which value it is.
    val ids = emittedIds(RepeatedSpecializations)
    assert(ids.contains("0z56ok3gyegs"))
    assert(!ids.contains("z56ok3gyegs"))
  }

  /**
    * The same instance, with the trait named two ways: qualified, and by the simple name that
    * is in scope inside its own module. Same program, same meaning, so the same class names.
    */
  private val QualifiedTraitName: String =
    """
      |mod M {
      |    pub trait D[a] {
      |        pub def d(x: a): Bool
      |    }
      |
      |    instance M.D[Int32] {
      |        pub def d(x: Int32): Bool =
      |            let l = List.range(0, x) |> List.map(y -> y + 1) |> List.filter(y -> y > 0);
      |            List.length(l) > 0
      |    }
      |}
      |
      |def useIt(x: a): Bool with M.D[a] = M.D.d(x)
      |
      |def main(): Unit \ IO = println(useIt(1))
      |""".stripMargin

  private val SimpleTraitName: String = QualifiedTraitName.replace("instance M.D[Int32]", "instance D[Int32]")

  test("spellingIndependence.01") {
    // A generated name must describe what a declaration *is*, not how its author spelled it.
    // Qualifying the trait of an instance changes no behaviour, so it must rename nothing.
    val qualified = classNames(QualifiedTraitName)
    val simple = classNames(SimpleTraitName)
    assert(qualified.exists(_.contains("Def$d$")), "expected the instance member to reach a class")
    // Asserting on the difference, not on set equality: the full sets are the whole runtime.
    val renamed = (qualified -- simple) ++ (simple -- qualified)
    assert(renamed.isEmpty, s"renamed by spelling alone: ${renamed.toList.sorted}")
  }

  test("unrelatedEdit.01") {
    // An unrelated addition after the tested code must not rename any of its classes --
    // this is the specific failure mode (renumbering under an unrelated edit) content-
    // addressed naming exists to fix. Asserting on the classes present before the edit,
    // rather than set equality, since the edit legitimately adds one class of its own.
    val before = classNames(Source)
    val after = classNames(Source + "\ndef unrelatedAddition(): Int32 = 42\n")
    assert(before.size > 50, s"expected dozens of classes at least, got ${before.size}")
    val renamed = before -- after
    assert(renamed.isEmpty, s"classes renamed by an unrelated edit: $renamed")
  }

}
