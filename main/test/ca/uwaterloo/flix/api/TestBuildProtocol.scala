/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.api

import org.json4s.JsonAST.{JBool, JInt, JObject, JString, JValue}
import org.scalatest.funsuite.AnyFunSuite

/**
  * Tests the handshake a build client performs before it trusts anything else.
  *
  * This is a compatibility contract rather than an internal API: once a released plugin speaks it,
  * changing it breaks builds that this repository cannot see. These tests are where that contract
  * is written down.
  */
class TestBuildProtocol extends AnyFunSuite {

  private def field(document: JValue, name: String): Option[JValue] = document match {
    case JObject(fields) => fields.find(_._1 == name).map(_._2)
    case _ => None
  }

  test("a client that states no version is served") {
    // A client may reasonably ask what is on offer before committing to a version.
    val (compatible, document) = BuildProtocol.initialize(None)
    assert(compatible)
    assertResult(Some(JBool(true)))(field(document, "success"))
  }

  test("a client speaking the current version is served") {
    val (compatible, _) = BuildProtocol.initialize(Some(BuildProtocol.ProtocolVersion))
    assert(compatible)
  }

  test("a client from the future is refused, by number and in words") {
    // The point of the handshake. Without it the mismatch surfaces as a missing field midway
    // through a build, which a client reports as a compiler error rather than as its own.
    val (compatible, document) = BuildProtocol.initialize(Some(BuildProtocol.ProtocolVersion + 1))
    assert(!compatible)
    assertResult(Some(JBool(false)))(field(document, "success"))
    val error = field(document, "error").collect { case JString(s) => s }.getOrElse("")
    assert(error.contains((BuildProtocol.ProtocolVersion + 1).toString), error)
  }

  test("a client older than we still serve is refused") {
    val (compatible, _) = BuildProtocol.initialize(Some(BuildProtocol.MinimumClientVersion - 1))
    assert(!compatible)
  }

  test("a refusal still says what this compiler does speak") {
    // A client that is told only "no" can do nothing useful. Told the range, it can fall back,
    // or report something a person can act on.
    val (_, document) = BuildProtocol.initialize(Some(BuildProtocol.ProtocolVersion + 1))
    assertResult(Some(JInt(BuildProtocol.ProtocolVersion)))(field(document, "protocolVersion"))
    assertResult(Some(JInt(BuildProtocol.MinimumClientVersion)))(field(document, "minimumClientVersion"))
  }

  test("capabilities report only what is implemented") {
    // A capability advertised ahead of its implementation is worse than an absent one: a client
    // trusts it and fails at the point of use, which is exactly what the handshake exists to
    // prevent. `daemon` is the live case -- there is no `flix/build` yet.
    val (_, document) = BuildProtocol.initialize(None)
    val capabilities = field(document, "capabilities").getOrElse(fail("no capabilities reported"))
    assertResult(Some(JBool(true)))(field(capabilities, "diagnostics"))
    assertResult(Some(JBool(true)))(field(capabilities, "exportStubs"))
    assertResult(Some(JBool(false)))(field(capabilities, "daemon"))
  }

  test("the input model is stated rather than left to be guessed") {
    // A client has to know whether a request describes the whole build or names a project. It
    // decides what the client must declare as an input, and therefore whether its cache is sound.
    val (_, document) = BuildProtocol.initialize(None)
    assertResult(Some(JString("project-directory")))(field(document, "inputModel"))
  }

  test("the minimum client version never exceeds the current one") {
    // Would refuse every client, including one written against this exact compiler.
    assert(BuildProtocol.MinimumClientVersion <= BuildProtocol.ProtocolVersion)
  }
}
