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
package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.tools.pkg.Manifest
import ca.uwaterloo.flix.util.Build

import java.nio.file.Path

/**
  * What a project is configured to be, as of one moment.
  *
  * ==Why a snapshot==
  *
  * [[Bootstrap]]'s source and dependency lists are mutable: a file watcher, a rescan or a reload
  * rewrites them. A caller given accessors instead could read one field before a reload and another
  * after, and answer a single request from two different projects. Copying makes that impossible,
  * and the discipline it asks for -- one view per request -- is then something a reader can check.
  *
  * The other reason is narrower: the layout functions on the [[Bootstrap]] companion stay private,
  * because this is built where they are already visible. Nothing outside has to define a second
  * opinion about where `src/` or `build/development/class/` live, which is how two definitions of a
  * layout start.
  *
  * ==Why "configured", and what is deliberately absent==
  *
  * Everything here is known from `flix.toml` and the directory layout, without compiling anything.
  * That matters because the questions this answers -- what are the targets, what are the sources --
  * arrive before the first build and while the project is broken, which is exactly when a client
  * needs them most.
  *
  * So there is nothing here about generated output: no product set, no runtime classpath, no
  * `main`. Those are facts about a build that succeeded, they are unavailable until one has, and
  * mixing them in would make discovery depend on compilation.
  *
  * @param projectPath      the project's root, absolute and normalised.
  * @param packageName      the name from `flix.toml`, or the directory's own name outside project mode.
  * @param manifest         the parsed `flix.toml`, or `None` in directory mode.
  * @param sourcePaths      every `.flix` file the project declares, in `src/`, `test/` and the root.
  * @param flixPackagePaths the `.fpkg` dependencies. Flix source, compiled into the output.
  * @param mavenPackagePaths the jars resolved from Maven coordinates.
  * @param jarPackagePaths  the jars downloaded from a url.
  */
case class ProjectView(projectPath: Path,
                       packageName: String,
                       manifest: Option[Manifest],
                       sourcePaths: List[Path],
                       flixPackagePaths: List[Path],
                       mavenPackagePaths: List[Path],
                       jarPackagePaths: List[Path],
                       sourceDirectory: Path,
                       testDirectory: Path,
                       resourcesDirectory: Path,
                       libraryDirectory: Path,
                       artifactDirectory: Path,
                       jarFile: Path,
                       outputDirectories: Map[Build, Path],
                       classDirectories: Map[Build, Path]) {

  /** Every dependency the project resolves against, whatever its kind. */
  def dependencyPaths: List[Path] = flixPackagePaths ::: mavenPackagePaths ::: jarPackagePaths

  /** Returns `true` if `p` is one of the project's declared sources. */
  def declaresSource(p: Path): Boolean = {
    val target = p.toAbsolutePath.normalize()
    sourcePaths.exists(_.toAbsolutePath.normalize() == target)
  }

  /**
    * The directories the project keeps sources in, whether or not they exist yet.
    *
    * Reported as roots rather than derived from the source list, because a client uses them to
    * decide which directories to watch -- and a project whose `test/` is empty today still wants
    * the file created there tomorrow to be noticed.
    */
  def sourceRoots: List[Path] = List(sourceDirectory, testDirectory)
}
