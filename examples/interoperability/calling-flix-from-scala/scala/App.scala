package com.example

import Acme.Greeter

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object App:

  def main(args: Array[String]): Unit =
    println(Greeter.greet("Scala"))

    // `Optional` -> `scala.Option`, with one import and one call.
    val hit: Option[String] = Greeter.find("a").toScala
    val miss: Option[String] = Greeter.find("z").toScala
    println(s"hit = $hit, miss = $miss")

    // `java.util.List` -> `scala.List`. Use `.toList`, not `.asScala` alone;
    // see the last case below for why.
    val names: List[String] = Greeter.names().asScala.toList
    println(s"names = $names")

    // The element of `List[Int32]` is boxed, because a `java.util.List` holds
    // references. Scala sees `Integer`, so `.map(_.intValue)` gets `List[Int]`.
    val counts: List[Int] = Greeter.counts().asScala.toList.map(_.intValue)
    println(s"counts = $counts, sum = ${counts.sum}")

    // An unconstrained type variable exports as `Object`, so the cast is ours.
    println(Greeter.identity("round-trip").asInstanceOf[String])

    // The list that crossed is *unmodifiable*, and `.asScala` gives a
    // `mutable.Buffer` view over it. That type checks and throws at run time,
    // which is the one place Scala's bridge is misleading rather than helpful.
    val view = Greeter.names().asScala
    val threw =
      try
        view += "c"
        false
      catch case _: UnsupportedOperationException => true
    println(s"mutating the view threw = $threw")
