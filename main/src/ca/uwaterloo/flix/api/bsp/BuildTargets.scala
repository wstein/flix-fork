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

import ca.uwaterloo.flix.api.ProjectView
import ch.epfl.scala.bsp4j.*

import java.net.URI
import java.nio.file.Paths
import scala.jdk.CollectionConverters.*

/**
  * The build targets a Flix project has, which is one.
  *
  * ==Why one, and not `src` plus `test`==
  *
  * A BSP target is a unit of compilation with its own output and classpath. Flix has exactly one
  * per project: the sources of `src/`, `test/` and the project root are compiled together as one
  * whole program, and `@Test` definitions are entry points whose classes land in the same output as
  * everything else.
  *
  * Advertising two would not be a simplification of that, it would be a lie with a specific
  * consequence. `buildTarget/compile` on a `src` target would still typecheck `test/`, so a
  * diagnostic in a test file would be published against `src` -- while `buildTarget/inverseSources`
  * would tell the client that file belongs to `test`. The next compile of `test` would then publish
  * against a target that had never been told about the file, and the original marker would never be
  * cleared. Stale diagnostics are the failure BSP is most used for avoiding, so a model that
  * generates them is worse than one that admits the compiler's shape.
  *
  * Development and production are not two targets either. They are two *modes* of the same target,
  * a client would render them as siblings, and compiling both would thrash two output directories
  * for the same sources. Only development is exposed; producing a release artifact is not something
  * an editor should hold a target for.
  *
  * ==Why `?id=` with only one target==
  *
  * The path already names the project, so the query looks redundant. It is there so that a second
  * target, if the compiler ever grows source sets, does not have to change the identity of the
  * first -- a client stores target ids, and an id that changes is a target that vanished.
  */
object BuildTargets {

  /** The language id Flix sources are reported under. */
  val LanguageId: String = "flix"

  /** The id of the one target, within a project. */
  private val TargetName: String = "main"

  /**
    * Returns the id of the project's one target.
    *
    * Built with `URI`, not by concatenation: a project directory may contain a space or a `#`, and
    * the query value has to be encoded rather than pasted.
    */
  def id(view: ProjectView): BuildTargetIdentifier = {
    val base = new URI(BspUri.ofDirectory(view.projectPath))
    // An *empty* authority, not a null one. Null renders `file:/path`, which is legal but is not
    // what `Path.toUri` or any other build server produces -- and a client that computes the id
    // itself and compares it as a string would then see a different target from the one it was told
    // about. Empty renders `file:///path`, which is the form everything else uses.
    val withQuery = new URI(base.getScheme, "", base.getPath, s"id=$TargetName", null)
    new BuildTargetIdentifier(withQuery.toString)
  }

  /** Returns `true` if `target` is the project's one target. */
  def isKnown(view: ProjectView, target: BuildTargetIdentifier): Boolean =
    target != null && target.getUri == id(view).getUri

  /**
    * Returns the project's one target, described for `workspace/buildTargets`.
    *
    * The tag is `library` rather than `application`, and that is deliberate: whether the program
    * has a `main` is not known until it compiles, and discovery has to answer for a project that
    * does not. A tag that flickered between builds would be worse than one that is conservative,
    * and `buildTarget/run` can say "no main" precisely when it is asked to run.
    *
    * It is not `test` either. A client that sees the tag turns the target into a test source root,
    * which would put `src/` under test scope -- and since there is only one target, that would be
    * every source in the project. `canTest` carries the affordance without the misfiling.
    *
    * `dataKind = "jvm"` with a `JvmBuildTarget` is what tells a client this is a JVM project at all,
    * and is how it picks a JDK.
    */
  def target(view: ProjectView, capabilities: BuildTargetCapabilities): BuildTarget = {
    val t = new BuildTarget(
      id(view),
      List(BuildTargetTag.LIBRARY).asJava,
      List(LanguageId).asJava,
      // No dependencies: there is one target, and a `.fpkg` dependency is Flix source compiled into
      // this target's own output rather than a target of its own.
      List.empty[BuildTargetIdentifier].asJava,
      capabilities)
    t.setDisplayName(view.packageName)
    t.setBaseDirectory(BspUri.ofDirectory(view.projectPath))
    t.setDataKind(BuildTargetDataKind.JVM)
    t.setData(jvmBuildTarget())
    t
  }

  /** Returns the JDK this server runs on, which is the one a client should run the program on. */
  private def jvmBuildTarget(): JvmBuildTarget = {
    val jvm = new JvmBuildTarget()
    jvm.setJavaHome(BspUri.ofDirectory(Paths.get(System.getProperty("java.home"))))
    jvm.setJavaVersion(System.getProperty("java.specification.version"))
    jvm
  }

  /**
    * Returns `true` if a client that advertised `clientLanguageIds` may be told about Flix targets.
    *
    * The specification requires this: a server must not answer with targets for a language the
    * client did not say it supports. A client that asked for nothing is treated as asking for
    * everything, since several send an empty list and mean "whatever you have".
    */
  def servesClient(clientLanguageIds: List[String]): Boolean =
    clientLanguageIds.isEmpty || clientLanguageIds.contains(LanguageId)
}
