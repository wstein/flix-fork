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

import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.language.ast.shared.Source

import scala.collection.mutable

/**
  * Builds a JSR-45 `SourceDebugExtension` (SMAP) attribute for a single generated class.
  *
  * A JVM class carries exactly one `SourceFile` attribute, but after inlining a class body
  * may contain code originating from several `.flix` files. Without JSR-45 the line numbers
  * of inlined code are attributed to the enclosing class's file, so a debugger opens the
  * wrong file at a line that may not even exist.
  *
  * JSR-45 solves this by adding a second, richer mapping: the `LineNumberTable` stores
  * *synthetic* line numbers in a flat "output" line space, and the SMAP translates each
  * synthetic line back to a (file, line) pair.
  *
  * The primary source -- the file the class itself was declared in -- is mapped by the
  * identity function, so its synthetic lines equal its real lines. This keeps stack traces
  * readable even for tools that ignore the SMAP entirely. Lines belonging to any other file
  * are allocated sequentially above the primary file's last line.
  *
  * @param primary the source the enclosing class was declared in.
  */
class Smap(primary: Source) {

  /**
    * The number of lines in the primary source.
    *
    * Synthetic line numbers for foreign sources are allocated above this, so that the
    * primary source can keep the identity mapping.
    */
  private val primaryLines: Int = countLines(primary)

  /** The sources referenced by this class, in the order they were first seen. */
  private val files: mutable.LinkedHashMap[Source, Int] = mutable.LinkedHashMap(primary -> 1)

  /** Maps a foreign `(source, line)` to the synthetic line it was assigned. */
  private val foreign: mutable.LinkedHashMap[(Source, Int), Int] = mutable.LinkedHashMap.empty

  /**
    * Returns the synthetic line number to store in the `LineNumberTable` for `loc`.
    *
    * For the primary source this is just the real line. For any other source a fresh
    * synthetic line is allocated (and reused on subsequent lookups of the same line).
    */
  def register(loc: SourceLocation): Int = {
    val src = loc.source
    val line = loc.startLine
    if (src == primary) {
      line
    } else {
      files.getOrElseUpdate(src, files.size + 1)
      foreign.getOrElseUpdate((src, line), primaryLines + foreign.size + 1)
    }
  }

  /**
    * Returns the SMAP to store in the `SourceDebugExtension` attribute, or `None` if the
    * class draws on a single source and therefore needs no translation.
    *
    * Returns `None` if any synthetic line would exceed the `u2` range of the
    * `LineNumberTable`, since the mapping could not be represented faithfully.
    */
  def build(className: JvmName): Option[String] = {
    if (foreign.isEmpty) return None
    if (primaryLines + foreign.size > Smap.MaxLineNumber) return None

    val sb = new mutable.StringBuilder()
    sb.append("SMAP\n")
    sb.append(s"${className.name}.flix\n")
    sb.append(s"${Smap.Stratum}\n")
    sb.append(s"*S ${Smap.Stratum}\n")

    // The file section. `+` introduces a file whose absolute path follows on the next line.
    sb.append("*F\n")
    for ((src, id) <- files) {
      sb.append(s"+ $id ${baseName(src)}\n")
      sb.append(s"${src.name}\n")
    }

    // The line section. Each entry reads
    //   <inputLine>#<fileId>,<lineCount>:<outputLine>
    sb.append("*L\n")
    sb.append(s"1#1,$primaryLines:1\n")
    for (((src, line), synthetic) <- foreign) {
      sb.append(s"$line#${files(src)},1:$synthetic\n")
    }

    sb.append("*E\n")
    Some(sb.toString)
  }

  /** Returns the number of lines in `src`, counting a trailing partial line. */
  private def countLines(src: Source): Int = {
    var n = 1
    for (c <- src.data if c == '\n') n += 1
    n
  }

  /** Returns the last path segment of the name of `src`. */
  private def baseName(src: Source): String = {
    val name = src.name
    val idx = math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'))
    if (idx < 0) name else name.substring(idx + 1)
  }

}

object Smap {

  /**
    * The name of the stratum the mapping is declared under.
    *
    * Debuggers that understand JSR-45 report locations in the default stratum, which is
    * set to this. Note that a debugger asking explicitly for the `Java` stratum still sees
    * the raw synthetic line numbers.
    */
  private val Stratum: String = "Flix"

  /** The largest line number representable in a `LineNumberTable` entry, which is a `u2`. */
  private val MaxLineNumber: Int = 65535

}
