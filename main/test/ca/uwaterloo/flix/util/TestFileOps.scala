package ca.uwaterloo.flix.util

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class TestFileOps extends AnyFunSuite {

  private def fileHolding(bytes: Array[Byte]): Path = {
    val p = Files.createTempFile("flix-fileops-", "")
    Files.write(p, bytes)
    p
  }

  test("sha256 of the empty file") {
    assertResult("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")(
      FileOps.sha256(fileHolding(Array.emptyByteArray)))
  }

  test("sha256 of a known vector") {
    assertResult("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")(
      FileOps.sha256(fileHolding("abc".getBytes(StandardCharsets.UTF_8))))
  }

  test("sha256 reads past the block it buffers") {
    // The file is read in 8 KB blocks, so a file larger than one block is what shows that every
    // block reaches the digest rather than only the first.
    val big = Array.tabulate[Byte](100_000)(i => (i % 251).toByte)
    val expected = {
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      java.util.HexFormat.of().formatHex(digest.digest(big))
    }
    assertResult(expected)(FileOps.sha256(fileHolding(big)))
  }

  test("sha256 is stable across calls") {
    val p = fileHolding("the same bytes".getBytes(StandardCharsets.UTF_8))
    assertResult(FileOps.sha256(p))(FileOps.sha256(p))
  }

  test("sha256 distinguishes a truncated file") {
    // This is the case the download path cares about: a short read must not hash to the same thing.
    val whole = fileHolding("0123456789".getBytes(StandardCharsets.UTF_8))
    val short = fileHolding("01234".getBytes(StandardCharsets.UTF_8))
    assert(FileOps.sha256(whole) != FileOps.sha256(short))
  }
}
