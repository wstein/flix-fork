/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

import java.lang.constant.ClassDesc
import scala.collection.mutable

/** Builds the JSR-45 source map for one generated class. */
class Smap(primary: Source) {

  private val primaryLines: Int = countLines(primary)
  private val files: mutable.LinkedHashMap[Source, Int] = mutable.LinkedHashMap(primary -> 1)
  private val foreign: mutable.LinkedHashMap[(Source, Int), Int] = mutable.LinkedHashMap.empty

  /** Returns the output line stored in the JVM line table for `loc`. */
  def register(loc: SourceLocation): Int = {
    if (loc.source == primary) loc.startLine
    else {
      files.getOrElseUpdate(loc.source, files.size + 1)
      foreign.getOrElseUpdate((loc.source, loc.startLine), primaryLines + foreign.size + 1)
    }
  }

  /** Returns no attribute when every registered line belongs to the class's primary source. */
  def build(className: ClassDesc): Option[String] = {
    if (foreign.isEmpty || primaryLines + foreign.size > Smap.MaxLineNumber) return None

    val result = new mutable.StringBuilder()
    result.append("SMAP\n")
    result.append(s"${className.displayName()}.flix\n")
    result.append(s"${Smap.Stratum}\n")
    result.append(s"*S ${Smap.Stratum}\n")
    result.append("*F\n")
    for ((source, id) <- files) {
      result.append(s"+ $id ${baseName(source)}\n")
      result.append(s"${source.name}\n")
    }
    result.append("*L\n")
    result.append(s"1#1,$primaryLines:1\n")
    for (((source, line), output) <- foreign) {
      result.append(s"$line#${files(source)},1:$output\n")
    }
    result.append("*E\n")
    Some(result.toString())
  }

  private def countLines(source: Source): Int = {
    var lines = 1
    for (char <- source.data if char == '\n') lines += 1
    lines
  }

  private def baseName(source: Source): String = {
    val name = source.name
    val separator = math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'))
    if (separator < 0) name else name.substring(separator + 1)
  }
}

object Smap {
  private val Stratum: String = "Flix"
  private val MaxLineNumber: Int = 65535
}
