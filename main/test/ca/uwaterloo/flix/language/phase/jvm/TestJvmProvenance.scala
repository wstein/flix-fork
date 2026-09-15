package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol}
import ca.uwaterloo.flix.util.InternalCompilerException
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

class TestJvmProvenance extends AnyFunSuite {

  private def symbol(id: Int): Symbol.DefnSym =
    new Symbol.DefnSym(Some(id), List("Example"), "map", SourceLocation.Unknown)

  private def key(name: String): GeneratedJvmKey = GeneratedJvmKey("specialization", List(name))

  test("parallel registration preserves every origin") {
    val registry = new JvmProvenance()
    val executor = Executors.newFixedThreadPool(4)
    val entries = (1 to 100).map(id => symbol(id) -> key(s"origin-$id"))
    try {
      val tasks = entries.map { case (sym, origin) =>
        new Callable[Unit] {
          override def call(): Unit = registry.register(sym, origin)
        }
      }
      executor.invokeAll(tasks.asJava).asScala.foreach(_.get())
      val table = registry.freeze(entries.map(_._1))
      val expected = JvmNameTable.build(entries)
      entries.foreach { case (sym, _) => assert(table.suffix(sym) == expected.suffix(sym)) }
    } finally {
      executor.shutdownNow()
      assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    }
  }

  test("conflicting registration is rejected immediately") {
    val registry = new JvmProvenance()
    val sym = symbol(1)
    registry.register(sym, key("first"))
    intercept[InternalCompilerException] { registry.register(sym, key("second")) }
    assert(registry.origin(sym) == key("first"))
  }

  test("origin lookup fails for unregistered symbols") {
    intercept[InternalCompilerException] { new JvmProvenance().origin(symbol(1)) }
  }

  test("freeze requires provenance for every requested symbol") {
    intercept[InternalCompilerException] { new JvmProvenance().freeze(List(symbol(1))) }
  }

  test("freeze includes only surviving symbols") {
    val registry = new JvmProvenance()
    val live = symbol(1)
    val dead = symbol(2)
    registry.register(live, key("live"))
    registry.register(dead, key("dead"))
    val table = registry.freeze(List(live))
    assert(table.suffix(live).nonEmpty)
    intercept[InternalCompilerException] { table.suffix(dead) }
  }

  test("registration after freeze fails") {
    val registry = new JvmProvenance()
    registry.freeze(Nil)
    intercept[InternalCompilerException] { registry.register(symbol(1), key("late")) }
  }

  test("a registry cannot be reused for a second freeze") {
    val registry = new JvmProvenance()
    registry.freeze(Nil)
    intercept[InternalCompilerException] { registry.freeze(Nil) }
  }

  test("separate compilation registries do not share origins") {
    val first = new JvmProvenance()
    val second = new JvmProvenance()
    val sym = symbol(1)
    first.register(sym, key("first"))
    second.register(sym, key("second"))
    assert(first.freeze(List(sym)).suffix(sym) != second.freeze(List(sym)).suffix(sym))
  }

  test("identical registration is idempotent") {
    val registry = new JvmProvenance()
    val sym = symbol(1)
    registry.register(sym, key("same"))
    registry.register(sym, key("same"))
    assert(registry.freeze(List(sym)).suffix(sym) == JvmNameTable.build(List(sym -> key("same"))).suffix(sym))
  }

  test("a failed freeze also closes registration") {
    val registry = new JvmProvenance()
    val sym = symbol(1)
    intercept[InternalCompilerException] { registry.freeze(List(sym)) }
    intercept[InternalCompilerException] { registry.register(sym, key("late")) }
    intercept[InternalCompilerException] { registry.freeze(Nil) }
  }

  test("dead symbols do not claim live names") {
    val registry = new JvmProvenance()
    val live = symbol(1)
    registry.register(live, key("same"))
    registry.register(symbol(2), key("same"))
    assert(registry.freeze(List(live)).suffix(live).nonEmpty)
  }

  test("live symbols with identical provenance fail during freeze") {
    val registry = new JvmProvenance()
    val first = symbol(1)
    val second = symbol(2)
    registry.register(first, key("same"))
    registry.register(second, key("same"))
    intercept[InternalCompilerException] { registry.freeze(List(first, second)) }
  }

  test("phase pruning removes dead origins while preserving live origins") {
    val registry = new JvmProvenance()
    val live = symbol(1)
    val dead = symbol(2)
    registry.register(live, key("live"))
    registry.register(dead, key("dead"))
    registry.retainLive(Set(live))
    assert(registry.origin(live) == key("live"))
    intercept[InternalCompilerException] { registry.origin(dead) }
    registry.register(symbol(3), key("new"))
    assert(registry.freeze(List(live)).suffix(live).nonEmpty)
  }

  test("pruning all origins does not close registration") {
    val registry = new JvmProvenance()
    val sym = symbol(1)
    registry.register(sym, key("old"))
    registry.retainLive(Set.empty)
    intercept[InternalCompilerException] { registry.origin(sym) }
    registry.register(sym, key("new"))
    assert(registry.origin(sym) == key("new"))
  }

  test("successful freeze releases both live and dead registry entries") {
    val registry = new JvmProvenance()
    val live = symbol(1)
    val dead = symbol(2)
    registry.register(live, key("live"))
    registry.register(dead, key("dead"))
    val table = registry.freeze(List(live))
    intercept[InternalCompilerException] { registry.origin(live) }
    intercept[InternalCompilerException] { registry.origin(dead) }
    assert(table.suffix(live) == JvmNameTable.build(List(live -> key("live"))).suffix(live))
  }

  test("failed freeze releases registry entries") {
    val registry = new JvmProvenance()
    val sym = symbol(1)
    registry.register(sym, key("registered"))
    intercept[InternalCompilerException] { registry.freeze(List(sym, symbol(2))) }
    intercept[InternalCompilerException] { registry.origin(sym) }
  }

  test("pruning after freeze fails") {
    val registry = new JvmProvenance()
    registry.freeze(Nil)
    intercept[InternalCompilerException] { registry.retainLive(Set.empty) }
  }
}
