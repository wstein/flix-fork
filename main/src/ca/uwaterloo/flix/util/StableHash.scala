/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ca.uwaterloo.flix.util

import com.dynatrace.hash4j.hashing.Hashing

import java.nio.charset.StandardCharsets.UTF_8

/** Stable, identifier-safe hashes for compiler-generated names. */
object StableHash {
  private val Base58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
  private val HashLength = 13

  /** Returns a fixed-width Base58 encoding of an XXH3-64 hash. */
  def xxh3_64Base58(fields: List[String]): String = {
    val hash = Hashing.xxh3_64().hashBytesToLong(canonicalKey(fields).getBytes(UTF_8))
    base58(hash)
  }

  /** Length prefixes make the canonical serialization unambiguous. */
  private def canonicalKey(fields: List[String]): String =
    fields.map(field => s"${field.length}:$field").mkString("|")

  private def base58(hash: Long): String = {
    val encoded = new Array[Char](HashLength)
    var value = BigInt(java.lang.Long.toUnsignedString(hash))
    var index = encoded.length - 1
    while (index >= 0) {
      val division = value /% Base58Alphabet.length
      encoded(index) = Base58Alphabet.charAt(division._2.toInt)
      value = division._1
      index -= 1
    }
    new String(encoded)
  }
}
