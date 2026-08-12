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

import ca.uwaterloo.flix.language.ast.shared.{Input, Source}

import java.net.URI
import java.nio.file.{Path, Paths}

/**
  * The URIs the protocol speaks, and the paths behind them.
  *
  * ==Why this is total==
  *
  * [[ofSource]] returns a `String` and not an `Option[String]`, and that is the whole design. The
  * language server takes the other route -- it builds a document identifier from `Source.name` and
  * then filters the results for `file://` -- and the effect is that a diagnostic in a source that
  * is not a plain file is dropped without a word. `Source.name` is `path.toString` for a real file,
  * so that filter drops ordinary project files too and survives only because documents reach the
  * language server as virtual URIs instead.
  *
  * A build reports on the whole program, including the standard library and the contents of `.fpkg`
  * dependencies, so it has no such escape. Every [[Input]] shape therefore gets a URI here, even
  * where a client may not be able to open it. An error the user cannot see is worse than an error
  * shown against an odd URI; the caller's job is to say so, not to hide it.
  *
  * ==Why not string concatenation==
  *
  * Paths become URIs through `Path.toUri`, which percent-encodes what has to be encoded. A project
  * directory containing a space, a `#` or a non-ASCII character is ordinary, and `"file://" + path`
  * produces something that is not a URI for all three.
  */
object BspUri {

  /** The scheme for Flix code that ships inside the compiler and has no file on disk. */
  private val BundledScheme: String = "flix-lib"

  /** Returns the URI of the file `p`. */
  def ofFile(p: Path): String = p.toAbsolutePath.normalize().toUri.toString

  /**
    * Returns `path` with everything a URI path may not carry percent-encoded.
    *
    * The archive entry and the bundled library's virtual path are the two places this file *builds* a
    * URI from a string instead of asking `Path.toUri`, and they need the same discipline it applies: a
    * space, a `#`, a `%` or anything outside ASCII in an entry name would otherwise produce a string
    * that is not a URI, which a client either rejects or -- worse -- reads as something else, since `#`
    * begins a fragment.
    *
    * `java.net.URI`'s multi-argument constructor quotes for us, and it is given the *decoded* path, so a
    * literal `%` becomes `%25` rather than being mistaken for an escape that was already there.
    */
  private def encodePath(path: String): String =
    new URI(null, null, s"/$path", null).getRawPath.stripPrefix("/")

  /**
    * Returns the URI of the directory `d`, with the trailing slash the protocol expects.
    *
    * `Path.toUri` appends it for a directory that exists and omits it for one that does not, which
    * would make a target's base directory change shape the first time the directory is created.
    */
  def ofDirectory(d: Path): String = {
    val uri = ofFile(d)
    if (uri.endsWith("/")) uri else uri + "/"
  }

  /**
    * Returns the path `uri` names, or `None` if it does not name one.
    *
    * Accepts the `file:/abs` form as well as `file:///abs`: both are legal, `Path.toUri` produces
    * the second, and some clients send the first.
    */
  def toPath(uri: String): Option[Path] =
    try {
      val parsed = new URI(uri)
      if (parsed.getScheme == null || parsed.getScheme != "file") None
      else if (parsed.getAuthority == null && parsed.getPath != null) Some(Paths.get(parsed.getPath).normalize())
      else Some(Paths.get(parsed).normalize())
    } catch {
      // `InvalidPathException` is an `IllegalArgumentException`, so this covers a uri that parses
      // but names nothing this filesystem can express -- a Windows-shaped path on a Unix host, say.
      case _: IllegalArgumentException => None
      case _: java.net.URISyntaxException => None
    }

  /**
    * Returns the URI of the source `s`.
    *
    * Total by construction: every case of [[Input]] is answered, so a caller never has to decide
    * what to do about a source it cannot name.
    */
  def ofSource(s: Source): String = s.input match {
    case Input.RealFile(realPath, _) => ofFile(realPath)

    case Input.VirtualFile(virtualPath, _, _) => ofFile(virtualPath)

    case Input.VirtualUri(virtualUri, _, _) => virtualUri.toString

    // The archive itself, which is a real file even though its contents are not.
    case Input.PkgFile(packagePath, _) => ofFile(packagePath)

    // A `.flix` file inside a `.fpkg`. The archive is a zip, so the JDK's own zip-entry form names
    // the file exactly, and an editor that understands `jar:` can open it. `virtualPath` arrives as
    // `<name>.fpkg:<entry>` from the reader, and only the part after the colon is the entry.
    case Input.FileInPackage(packagePath, virtualPath, _, _) =>
      val entry = virtualPath.indexOf(':') match {
        case -1 => virtualPath
        case i => virtualPath.substring(i + 1)
      }
      s"jar:${ofFile(packagePath)}!/${encodePath(entry.stripPrefix("/"))}"

    // The standard library, which is compiled from text bundled in the compiler and has no path on
    // this machine at all. A scheme of its own says that plainly, where a `file:` URI would name
    // something that is not there.
    case Input.BundledLibraryFile(virtualPath, _, _) =>
      s"$BundledScheme:/${encodePath(virtualPath.toString.replace('\\', '/').stripPrefix("/"))}"

    case Input.Unknown => s"$BundledScheme:/unknown"
  }

  /**
    * Returns `true` if `uri` names something a client can be expected to open.
    *
    * Callers use this to *report* the others rather than to drop them: a compile whose diagnostics
    * are all unopenable would otherwise look like a compile that failed for no reason.
    */
  def isOpenable(uri: String): Boolean = uri.startsWith("file:")
}
