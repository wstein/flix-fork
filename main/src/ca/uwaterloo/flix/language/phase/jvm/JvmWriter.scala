/*
 * Copyright 2017 Magnus Madsen
 * Copyright 2021 Jonathan Lindegaard Starup
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

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{BytecodeAst, SourceLocation}
import ca.uwaterloo.flix.language.dbg.AstPrinter
import ca.uwaterloo.flix.util.{FileOps, InternalCompilerException}

import java.nio.file.{Files, LinkOption, Path}

object JvmWriter {

  /**
    * Writes `classes` into the `<build>/class/` folder if enabled by [[Flix.options.outputJvm]].
    *
    * Returns the path of every class file written, relative to that folder. The set is empty
    * when JVM output is disabled, since then nothing was written.
    *
    * A caller that maintains the class directory needs to know exactly which files are in it,
    * and the only account of that which cannot drift is the one the writer itself keeps: the
    * returned paths are the ones it resolved and wrote, not names re-derived from the class
    * names afterwards.
    */
  def run(root: BytecodeAst.Root)(implicit flix: Flix): Set[Path] = {
    // Write each class (and interface) to disk if enabled.
    if (flix.options.outputJvm) {
      val classDir = flix.options.outputPath.resolve("class/")
      root.classes.map { case (_, jvmClass) => writeClass(classDir, jvmClass) }.toSet
    } else {
      Set.empty
    }
  }

  /**
    * Writes the given JVM class `clazz` to a sub path under the given `prefixPath`.
    *
    * For example, if the prefix path is `/tmp/` and the class name is Foo.Bar.Baz
    * then the bytecode is written to the path `/tmp/Foo/Bar/Baz.class` provided
    * that this path either does not exist or is already a JVM class file.
    *
    * Returns the written path, relative to `prefixPath`.
    */
  private def writeClass(prefixPath: Path, clazz: JvmClass): Path = {
    // Compute the absolute path of the class file to write.
    val relativePath = clazz.name.toPath
    val path = prefixPath.resolve(relativePath).toAbsolutePath

    // Create all parent directories (in case they don't exist).
    Files.createDirectories(path.getParent)

    // Check if the file already exists.
    if (Files.exists(path)) {
      // Check that the file is a regular file.
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw InternalCompilerException(s"Unable to write to non-regular file: '$path'.", SourceLocation.Unknown)
      }

      // Check if the file is writable.
      if (!Files.isWritable(path)) {
        throw InternalCompilerException(s"Unable to write to read-only file: '$path'.", SourceLocation.Unknown)
      }

      // Check that the file is empty or a class file.
      if (!(FileOps.isEmpty(path) || FileOps.isClassFile(path))) {
        throw InternalCompilerException(s"Refusing to overwrite non-empty, non-class file: '$path'.", SourceLocation.Unknown)
      }
    }

    // Write the bytecode.
    Files.write(path, clazz.bytecode)

    relativePath
  }

}
