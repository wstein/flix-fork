/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.api

import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.shared.{Origin, SecurityContext, Source, SourceName}
import ca.uwaterloo.flix.language.errors.JavaBoundaryError
import ca.uwaterloo.flix.language.fmt.FormatType
import ca.uwaterloo.flix.language.phase.interop.{BoundaryTypeElaborator, JavaBoundaryContract, JavaBoundaryRuntime, JavaBoundaryWrappers}
import ca.uwaterloo.flix.language.phase.jvm.JvmClass
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}

import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Experimental source-level entry point. A contract is the bootstrap API, never inferred evidence. */
object JavaBoundary {
  def read(path: Path): Result[JavaBoundaryContract.Contract, BootstrapError] = try {
    val source = Source.fromString(SourceName.PathName(path), Origin.User, SecurityContext.Unrestricted, Files.readString(path))
    parse(source)
  } catch { case NonFatal(error) => Err(BootstrapError.FileError(s"Unable to read Java API '$path': ${error.getMessage}")) }

  def parse(source: Source): Result[JavaBoundaryContract.Contract, BootstrapError] =
    JavaBoundaryContract.parse(source).mapErr(error => BootstrapError.CompilationErrors(List(JavaBoundaryError(error.message, error.loc)), None))

  def compile(flix: Flix, contract: JavaBoundaryContract.Contract): Result[JavaBoundaryWrappers.Output, BootstrapError] = {
    implicit val compiler: Flix = flix
    JavaBoundaryWrappers.compileContract(flix, contract, SecurityContext.Unrestricted).mapErr(diagnostic(_, contract))
  }

  def check(flix: Flix, contract: JavaBoundaryContract.Contract): Result[JavaBoundaryApi.Plan, BootstrapError] = {
    implicit val compiler: Flix = flix
    JavaBoundaryWrappers.checkContract(flix, contract, SecurityContext.Unrestricted).mapErr(diagnostic(_, contract))
  }

  private def diagnostic(error: JavaBoundaryWrappers.Error, contract: JavaBoundaryContract.Contract)(implicit compiler: Flix): BootstrapError = {
    val messages: List[CompilationMessage] = error match {
      case JavaBoundaryWrappers.InputErrors(errors, _) => errors
      case JavaBoundaryWrappers.WrapperErrors(errors, loc) => List(JavaBoundaryError(errors.map(_.summary).mkString("\n"), loc))
      case JavaBoundaryWrappers.Invalid(message, loc) => List(JavaBoundaryError(message, loc))
      case JavaBoundaryWrappers.FacadeError(cause) => List(JavaBoundaryError(cause.message,
        if (cause.loc.isReal) cause.loc else contract.loc))
      case JavaBoundaryWrappers.ContractError(cause) => List(JavaBoundaryError(cause.message, cause.loc))
      case JavaBoundaryWrappers.BoundaryError(cause, loc) =>
        val detail = cause match {
          case BoundaryTypeElaborator.MissingInstance(sym, arg) => s"Missing '${sym.toString}' instance for '${FormatType.formatType(arg)}'."
          case other => s"Unable to elaborate Java boundary: $other"
        }
        List(JavaBoundaryError(detail, loc))
    }
    BootstrapError.CompilationErrors(messages, None)
  }

  def writeStubs(contract: JavaBoundaryContract.Contract, directory: Path): Result[Unit, BootstrapError] =
    writeClasses(JavaBoundaryContract.stub(contract) :: JavaBoundaryRuntime.classes, directory)

  /** Never delete outputs or overwrite a non-class file. The build tool owns stale-output cleanup. */
  def writeClasses(classes: Iterable[JvmClass], directory: Path): Result[Unit, BootstrapError] = try {
    val root = directory.toAbsolutePath.normalize()
    Result.traverse(classes.toList) { clazz =>
      val file = root.resolve(clazz.name.descriptorString().drop(1).dropRight(1) + ".class").normalize()
      if (!file.startsWith(root)) Err(BootstrapError.FileError(s"Class path escapes output directory: '$file'."))
      else if (Files.exists(file) && !isClassFile(file)) Err(BootstrapError.FileError(s"Refusing to overwrite non-class file: '$file'."))
      else { Files.createDirectories(file.getParent); Files.write(file, clazz.bytecode); Ok(()) }
    }.map(_ => ())
  } catch { case NonFatal(error) => Err(BootstrapError.FileError(s"Unable to write Java API classes: ${error.getMessage}")) }

  private def isClassFile(path: Path): Boolean = {
    val stream = Files.newInputStream(path)
    try stream.readNBytes(4).sameElements(Array(0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte)) finally stream.close()
  }
}
