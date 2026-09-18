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

import ca.uwaterloo.flix.language.jvm.ClassDescs
import ca.uwaterloo.flix.util.FileOps
import org.objectweb.asm.{ClassReader, ClassVisitor, Opcodes}

import java.nio.file.Path
import scala.collection.mutable

/**
  * Which generated classes carry code from which `.flix` file.
  *
  * ==What it is for==
  *
  * A debugger asked to break on a line has a source file and no way to name the classes that
  * implement it: a generated name -- `Clo$main$626ZYxrpg1N`, `Tag$Obj$Obj` -- encodes the symbol it
  * came from and never the file. So a client watches *every* class the VM prepares and re-checks the
  * source on arrival, which is correct and costs a class-prepare event per class loaded.
  *
  * This is the mapping that removes the search. It is written under `--Xdebug`, beside the build
  * manifest, and a client that does not find one falls back to watching everything.
  *
  * ==Read back rather than collected==
  *
  * The entries come from the `SourceFile` and `SourceDebugExtension` attributes of the classes that
  * were *written*, not from the symbols the generators worked from. Those two can disagree -- a
  * lifted class takes its primary source from its own location, and inlining puts foreign lines into
  * a class through SMAP -- and it is the emitted attributes a debugger will read at runtime. An index
  * built from anything else would be a second opinion about the same question.
  *
  * ==Many-to-many, in both directions==
  *
  * One file yields many classes, and one class may name several files: its own, and every file whose
  * code was inlined into it. Both are recorded. This is why the index is not, and cannot be, an
  * account of which source *owns* a class -- code generation is whole-program, and the build manifest
  * refuses that question for the same reason.
  */
object DebugIndex {

  /** The file a client reads, beside the build manifest. */
  val FileName: String = "debug-index.json"

  /** Bumped when the shape below changes; a client that does not recognise it ignores the file. */
  private val FormatVersion: Int = 1

  /** The `*F` section of an SMAP introduces each file with `+ <id> <name>`, its path on the next line. */
  private val FileEntry = "^\\+ \\d+ (.+)$".r

  /**
    * Returns the sources each class in `classes` carries code from, keyed by source as emitted.
    *
    * The key is the source name exactly as the class file records it, because that is the string a
    * debugger will compare against: for a file on disk the compiler writes an absolute path, and for
    * a library source a bare name.
    */
  def of(classes: Iterable[JvmClass]): Map[String, Set[String]] = {
    val index = mutable.Map.empty[String, mutable.Set[String]]
    for (clazz <- classes) {
      for (source <- sourcesOf(clazz)) {
        index.getOrElseUpdate(source, mutable.Set.empty) += ClassDescs.binaryNameOf(clazz.name)
      }
    }
    index.map { case (source, names) => source -> names.toSet }.toMap
  }

  /** Writes `index` to `path`, replacing whatever was there. */
  def write(path: Path, index: Map[String, Set[String]]): Unit = {
    val sources = index.toList.sortBy(_._1).map { case (source, classes) =>
      s"    ${quote(source)}: [${classes.toList.sorted.map(quote).mkString(",")}]"
    }
    val json =
      s"""{
         |  "formatVersion":$FormatVersion,
         |  "sources":{
         |${sources.mkString(",\n")}
         |  }
         |}
         |""".stripMargin
    FileOps.writeString(path, json)
  }

  /**
    * The `.flix` sources `clazz` records: its own, and every file its SMAP names.
    *
    * A class with neither -- a runtime support class, a shared representation -- belongs to no source
    * and is left out. Nothing is inferred from its name.
    */
  private def sourcesOf(clazz: JvmClass): Set[String] = {
    val found = mutable.Set.empty[String]
    new ClassReader(clazz.bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
      override def visitSource(source: String, debug: String): Unit = {
        Option(source).filter(_.endsWith(".flix")).foreach(found += _)
        Option(debug).foreach(smap => found ++= smapSources(smap))
      }
    }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES)
    found.toSet
  }

  /**
    * The files an SMAP's `*F` section names.
    *
    * Read from the attribute this compiler wrote, in the shape [[Smap]] writes it: `+ <id> <name>`
    * followed by the path. The path is taken, since it is what identifies the file; the name beside
    * the id is its base name and would collide across directories.
    */
  private def smapSources(smap: String): Set[String] = {
    val lines = smap.linesIterator.toList
    val start = lines.indexOf("*F")
    if (start < 0) return Set.empty
    val section = lines.drop(start + 1).takeWhile(!_.startsWith("*"))
    section.sliding(2, 2).collect {
      case List(FileEntry(_), path) if path.endsWith(".flix") => path
    }.toSet
  }

  private def quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
