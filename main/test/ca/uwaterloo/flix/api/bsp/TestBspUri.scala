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
package ca.uwaterloo.flix.api.bsp

import ca.uwaterloo.flix.language.ast.shared.{Input, SecurityContext, Source}
import org.scalatest.funsuite.AnyFunSuite

import java.net.URI
import java.nio.file.Paths

/**
  * Holds the property [[BspUri]] exists for: every source gets a URI, and no source is dropped.
  *
  * The language server does the opposite -- it builds an identifier from `Source.name`, which is
  * `path.toString`, and then filters the results for `file://`. Every diagnostic in a source that is
  * not a plain file disappears there without a word, and ordinary project files would too if
  * documents did not happen to reach it as virtual URIs. A build reports on the whole program,
  * standard library and packaged dependencies included, so it has no such luck.
  */
class TestBspUri extends AnyFunSuite {

  private implicit val sctx: SecurityContext = SecurityContext.Unrestricted

  test("an archive entry with awkward characters is encoded, not pasted in") {
    val pkg = Paths.get("/tmp/p/lib/dep.fpkg")
    val awkward = "src/a dir/Odd#Name %s ünïcode.flix"
    val input = Input.FileInPackage(pkg, s"dep.fpkg:$awkward", "", SecurityContext.Plain)
    val uri = BspUri.ofSource(Source(input, Array.emptyCharArray))

    // Built by hand rather than by `Path.toUri`, so it needs the same discipline: a raw `#` would turn
    // the rest of the entry into a fragment, a raw space is not legal in a URI at all, and a raw `%`
    // reads as the start of an escape.
    assert(!uri.contains(' '), s"a space survived: $uri")
    assert(!uri.contains('#'), s"a '#' survived, which would begin a fragment: $uri")
    assert(uri.contains("%20"), s"the space was not encoded: $uri")
    assert(uri.contains("%23"), s"the '#' was not encoded: $uri")
    assert(uri.contains("%25s"), s"the '%' was not encoded: $uri")

    // And the whole thing is still a URI whose decoded path is the entry we started from.
    val parsed = new java.net.URI(uri)
    assert(parsed.getScheme == "jar", s"unexpected scheme: ${parsed.getScheme}")
    val entry = uri.substring(uri.indexOf("!/") + 2)
    assert(java.net.URLDecoder.decode(entry, java.nio.charset.StandardCharsets.UTF_8) == awkward,
      s"the entry did not survive the round trip: $entry")
  }

  test("every kind of input gets a parseable uri") {
    // A table rather than a case each, so that adding an `Input` case and forgetting it here is the
    // visible kind of omission. `ofSource` is total, so there is no `None` to forget to handle.
    val inputs = List(
      "a real file" -> Input.RealFile(Paths.get("/tmp/p/src/Main.flix"), sctx),
      "a virtual file" -> Input.VirtualFile(Paths.get("/tmp/p/src/Virtual.flix"), "def f(): Unit = ()", sctx),
      "a virtual uri" -> Input.VirtualUri(new URI("file:///tmp/p/src/Open.flix"), "def f(): Unit = ()", sctx),
      "a package archive" -> Input.PkgFile(Paths.get("/tmp/p/lib/dep-1.0.0.fpkg"), sctx),
      "a file inside a package" -> Input.FileInPackage(
        Paths.get("/tmp/p/lib/dep-1.0.0.fpkg"), "dep-1.0.0.fpkg:src/Dep.flix", "def g(): Unit = ()", sctx),
      "a bundled library file" -> Input.BundledLibraryFile(Paths.get("Array.flix"), "", sctx),
      "an unknown input" -> Input.Unknown
    )

    for ((what, input) <- inputs) {
      val uri = BspUri.ofSource(Source(input, Array.emptyCharArray))
      assert(uri.nonEmpty, s"$what produced no uri")
      // Parseable and absolute, which is what a client needs to do anything with it at all.
      val parsed = new URI(uri)
      assert(parsed.isAbsolute, s"$what produced a relative uri: $uri")
      assert(parsed.getScheme != null, s"$what produced a uri with no scheme: $uri")
    }
  }

  test("a real file becomes a file uri, not a path") {
    val uri = BspUri.ofSource(Source(Input.RealFile(Paths.get("/tmp/p/src/Main.flix"), sctx), Array.emptyCharArray))
    assert(uri.startsWith("file:///"), s"not a file uri: $uri")
    // The trap this whole object exists for: `Source.name` is the bare path, and using it would
    // produce something a client silently discards.
    assert(!uri.startsWith("/"), s"the path was used as a uri: $uri")
  }

  test("a file inside a package is named as a zip entry") {
    val input = Input.FileInPackage(
      Paths.get("/tmp/p/lib/dep-1.0.0.fpkg"), "dep-1.0.0.fpkg:src/Dep.flix", "", sctx)
    val uri = BspUri.ofSource(Source(input, Array.emptyCharArray))
    assert(uri.startsWith("jar:file:///"), s"not a jar uri: $uri")
    assert(uri.endsWith("!/src/Dep.flix"), s"the entry is wrong: $uri")
    // Not openable, and that is the point of asking: the caller reports it rather than dropping it.
    assert(!BspUri.isOpenable(uri))
  }

  test("a bundled library file says it is not on this machine") {
    val uri = BspUri.ofSource(Source(Input.BundledLibraryFile(Paths.get("Array.flix"), "", sctx), Array.emptyCharArray))
    // A `file:` uri here would name something that is not there, which is worse than a scheme a
    // client does not know: the standard library is compiled from text inside the compiler.
    assert(uri == "flix-lib:/Array.flix", s"unexpected uri: $uri")
    assert(!BspUri.isOpenable(uri))
  }

  test("a path survives becoming a uri and coming back") {
    // The characters that break string concatenation. A project directory containing any of them is
    // ordinary, and `"file://" + path` produces something that is not a uri for all three.
    val awkward = List(
      Paths.get("/tmp/with a space/src/Main.flix"),
      Paths.get("/tmp/with#hash/src/Main.flix"),
      Paths.get("/tmp/with%percent/src/Main.flix"),
      Paths.get("/tmp/wíth-ünïcode/src/Main.flix"),
      Paths.get("/tmp/with'quote/src/Main.flix")
    )
    for (p <- awkward) {
      val uri = BspUri.ofFile(p)
      assert(BspUri.toPath(uri).contains(p.toAbsolutePath.normalize()), s"$p did not survive $uri")
    }
  }

  test("both spellings of a file uri name the same path") {
    // `file:///abs` is what `Path.toUri` writes; `file:/abs` is legal and some clients send it.
    // Refusing the second would refuse a correct client.
    val triple = BspUri.toPath("file:///tmp/p/src/Main.flix")
    val single = BspUri.toPath("file:/tmp/p/src/Main.flix")
    assert(triple.isDefined && triple == single, s"$triple and $single disagree")
  }

  test("something that is not a file uri is not a path") {
    assert(BspUri.toPath("flix-lib:/Array.flix").isEmpty)
    assert(BspUri.toPath("jar:file:///tmp/p/lib/dep.fpkg!/src/Dep.flix").isEmpty)
    assert(BspUri.toPath("not a uri at all").isEmpty)
    assert(BspUri.toPath("").isEmpty)
  }

  test("a directory uri ends with a slash whether or not it exists") {
    // A client joins paths onto this. Whether the directory exists changes what `Path.toUri`
    // returns, which would otherwise make a target's base directory change shape the first time a
    // build created it.
    val missing = BspUri.ofDirectory(Paths.get("/tmp/definitely/not/there"))
    val present = BspUri.ofDirectory(Paths.get(System.getProperty("java.io.tmpdir")))
    assert(missing.endsWith("/"), s"no trailing slash: $missing")
    assert(present.endsWith("/"), s"no trailing slash: $present")
    assert(!missing.endsWith("//"), s"doubled slash: $missing")
  }
}
