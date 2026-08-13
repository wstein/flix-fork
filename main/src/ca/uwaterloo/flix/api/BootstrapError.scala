/*
 * Copyright 2023 Anna Blume Jakobsen
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

import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.{Scheme, SourceLocation, TypedAst}
import ca.uwaterloo.flix.tools.pkg
import ca.uwaterloo.flix.tools.pkg.{ManifestError, PackageError, SemVer}
import ca.uwaterloo.flix.util.Formatter

sealed trait BootstrapError {
  /**
    * Returns a human-readable and formatted string representation of this error.
    */
  def message(f: Formatter): String
}

object BootstrapError {
  /**
    * Errors reported by the compiler itself, kept as they were produced.
    *
    * The alternative -- and what this used to be -- is to render them here and return the string.
    * That reads the same to a person and destroys the only copy of the structure: source locations,
    * error codes, and the ranges an editor or a build tool needs in order to place a problem. A
    * caller downstream cannot recover them, because by then they are prose.
    *
    * `root` is carried alongside because a full message is rendered against the typed AST when one
    * exists, and rendering is deferred until something asks for it.
    */
  case class CompilationErrors(errors: List[CompilationMessage], root: Option[TypedAst.Root]) extends BootstrapError {
    override def message(f: Formatter): String = CompilationMessage.formatAll(errors)(f, root)
  }

  case class ManifestParseError(e: ManifestError) extends BootstrapError {
    override def message(f: Formatter): String = e.message(f)
  }

  case class FlixPackageError(e: PackageError) extends BootstrapError {
    override def message(f: Formatter): String = e.message(f)
  }

  case class MavenPackageError(e: PackageError) extends BootstrapError {
    override def message(f: Formatter): String = e.message(f)
  }

  case class JarPackageError(e: PackageError) extends BootstrapError {
    override def message(f: Formatter): String = e.message(f)
  }

  case class ReleaseError(e: pkg.ReleaseError) extends BootstrapError {
    override def message(f: Formatter): String = e.message(f)
  }

  case class FileError(e: String) extends BootstrapError {
    override def message(f: Formatter): String = e
  }

  /**
    * An error raised when a package requires a newer compiler than the one running.
    *
    * An error rather than a warning because Flix has no warnings -- every diagnostic is an error --
    * and because the alternative to stopping is compiling a package against a language that does
    * not yet have what it was written for, which fails later and less clearly.
    *
    * @param packageName the package whose `flix` field is not satisfied.
    * @param required    the oldest compiler that package declares itself compatible with.
    * @param current     the version of the running compiler.
    */
  case class IncompatibleFlixVersion(packageName: String, required: SemVer, current: Version) extends BootstrapError {
    override def message(f: Formatter): String =
      s"""The package ${f.bold(packageName)} requires Flix ${f.bold(required.toString)} or newer.
         |This is Flix ${f.bold(current.toString)}.
         |
         |  Either upgrade Flix, or lower the ${f.bold("flix")} field in its ${f.bold("flix.toml")} if the
         |  package does in fact build with this version.
         |""".stripMargin
  }

  case class GeneralError(e: String) extends BootstrapError {
    override def message(f: Formatter): String = e
  }

  case class EffectUpgradeError(e: List[(String, Scheme, List[SourceLocation])]) extends BootstrapError {
    override def message(f: Formatter): String = {
      s"""@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
         |@  WARNING! YOU MAY BE SUBJECT TO A SUPPLY CHAIN ATTACK!  @
         |@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
         |            ~~ Effect signatures have changed! ~~
         |
         |The following potentially harmful changes were detected:
         |$fmtEffectSets
         |
         |The functions are used in these places:
         |$fmtUses
         |""".stripMargin
    }

    /**
      * Returns a formatted string containing each symbol and what new effects it has.
      *
      * E.g.,if `f` has effect set `A, B, C` then the string is formatted as
      *
      * {{{"  + 'f' now uses *{ A, B, C }*"}}}
      */
    private def fmtEffectSets: String = e.map {
      case (sym, upgrade, _) =>
        val effs = upgrade.base.effects.mkString("*{ ", ", ", " }*")
        s"  + '$sym' now uses $effs"
    }.mkString(System.lineSeparator())

    /**
      * Returns a formatted string containing each symbol and where it is used.
      *
      * E.g.,if `f` is used in `main` and `mainHelper` then the string is formatted as
      *
      * {{{
      * "  + 'f':
      *      - main:13:2
      *      - mainHelper:2:42
      * "
      * }}}
      */
    private def fmtUses: String = e.map {
      case (sym, _, uses) =>
        val formattedSym = s"  + '$sym':"
        val formattedUses = uses.map(loc => s"    - $loc").mkString(System.lineSeparator())
        s"$formattedSym${System.lineSeparator()}$formattedUses"
    }.mkString(System.lineSeparator())
  }
}
