/*
 * Copyright 2022 Matthew Lutze
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
package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.ops.TypedAstOps
import ca.uwaterloo.flix.language.ast.shared.*
import ca.uwaterloo.flix.language.ast.{Kind, SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.dbg.AstPrinter.*
import ca.uwaterloo.flix.language.errors.EntryPointError
import ca.uwaterloo.flix.runtime.shell.Shell
import ca.uwaterloo.flix.util.collection.CofiniteSet
import ca.uwaterloo.flix.util.{InternalCompilerException, ParOps, Result}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
  * Processes all entry points of the program.
  *
  * A function is an entry point if:
  *   - It is the main function (called `main` by default, but can configured to an arbitrary name).
  *   - It is a test (annotated with `@Test`).
  *   - It is an exported function (annotated with `@Export`).
  *
  * This phase has these sub-phases:
  *   - Resolve the entrypoint option so that there is no implicit default entry point.
  *   - Check that all entry points have valid signatures, where rules differ from main, tests, and
  *     exports. If an entrypoint does not have a valid signature, its related annotation is
  *     removed to allow further compilation to continue with valid assumptions.
  *   - Compute the set of all entry points and store it in Root.
  *
  * (Wrapping entry points with their default effect handlers happens later, in `Lowering`.)
  */
object EntryPoints {

  private case object ErrorOrMalformed

  // We don't use regions, so we are safe to use the global scope everywhere in this phase.
  private implicit val S: RegionScope = RegionScope.Top

  def run(root: TypedAst.Root)(implicit flix: Flix): (TypedAst.Root, List[EntryPointError]) = flix.phaseNew("EntryPoints") {
    val (root1, errs1) = resolveMain(root)
    val (root2, errs2) = checkEntryPoints(root1)
    val root3 = findEntryPoints(root2)
    (root3, errs1 ++ errs2)
  }

  /**
    * Converts [[TypedAst.Root.mainEntryPoint]] to be explicit instead of implicit and checks that a
    * given entry point exists.
    *
    * In the input, a None entrypoint means to use `main` if it exists.
    * In the output, None means no entrypoint and Some is an entrypoint, guaranteed to be in defs.
    */
  private def resolveMain(root: TypedAst.Root): (TypedAst.Root, List[EntryPointError]) = {
    val defaultMainName = Symbol.mkDefnSym("main")

    root.mainEntryPoint match {
      case None =>
        root.defs.get(defaultMainName) match {
          case None =>
            // No main is given and default does not exist - no main.
            (root, Nil)
          case Some(entryPoint) =>
            // No main is given but default exists - use default.
            (root.copy(mainEntryPoint = Some(entryPoint.sym)), Nil)
        }
      case Some(sym) => root.defs.get(sym) match {
        case Some(shell) if shell.sym.name == Shell.ShellEntryPointName =>
          // A main is given and it is the shell's main - transform it.
          val newShell = rewriteShellEntryPoint(shell)
          (root.copy(defs = root.defs + (shell.sym -> newShell)), Nil)
        case Some(_) =>
          // A main is given and it exists - use it.
          (root, Nil)
        case None =>
          // A main is given and it does not exist - no main and give an error.
          (root.copy(mainEntryPoint = None), List(EntryPointError.EntryPointNotFound(sym)))
      }
    }
  }

  /**
    * Returns a new shell function that instead of returning unit, calls the local function _f.
    * This function _f simply prints the expression to be evaluated in the shell.
    *
    * Takes
    * {{{
    *   def main(): Unit \ IO + NonDet + Chan =
    *     def _f()={ println(exp) }
    *     checked_ecast(())
    * }}}
    *
    * Returns
    * {{{
    *   def main(): Unit \ expEffs + IO =
    *     def _f()={ println(exp) }
    *     _f()
    * }}}
    *
    * This is necessary because the expression being evaluated may have non primitive effects
    * that have default handlers. The set of effects with default handlers is not known from the start,
    * unlike the set of all primitive effects, so we have to add them after typing.
    *
    * In order to support the current way of creating a shell (create some simple wrapping code around exp and
    * execute it), we need to:
    *
    *   - Replace checked_ecast(()) by a call to _f.
    *   - Substitute (IO + NonDet + Chan) with the real set of effects generated by exp at EntryPoints plus an
    *     additional IO due to `println`.
    *
    */
  private def rewriteShellEntryPoint(oldShell: TypedAst.Def): TypedAst.Def = {
    val exp = oldShell.exp.asInstanceOf[TypedAst.Expr.LocalDef]
    val tpe = exp.bnd.tpe.asInstanceOf[Type.Apply]
    val spec = oldShell.spec.copy(
      // Replace main's effects with the effects of _f
      eff = tpe.tpe2,
    )
    // Substitute checked_ecast(()) for the contents of _f
    // Namely, println(exp)
    val newExp = exp.copy(exp2 = exp.exp1)
    oldShell.copy(spec = spec, exp = newExp)
  }

  /** Returns `true` if `tpe` is equivalent to Unit (via type aliases). */
  @tailrec
  private def isUnitType(tpe: Type): Result[Boolean, ErrorOrMalformed.type] = tpe match {
    case Type.Cst(TypeConstructor.Unit, _) => Result.Ok(true)
    case Type.Cst(_, _) => Result.Ok(false)
    case Type.Apply(_, _, _) => Result.Ok(false)
    case Type.Alias(_, _, t, _) => isUnitType(t)
    case Type.Var(_, _) => Result.Err(ErrorOrMalformed)
    case Type.AssocType(_, _, _, _) => Result.Err(ErrorOrMalformed)
    case Type.JvmToType(_, _) => Result.Err(ErrorOrMalformed)
    case Type.JvmToEff(_, _) => Result.Err(ErrorOrMalformed)
    case Type.UnresolvedJvmType(_, _) => Result.Err(ErrorOrMalformed)
  }

  /**
    * CheckEntryPoints checks that all entry points (main/test/export) have valid signatures.
    *
    * Because of resilience, invalid entry points are not discarded. Its entry point marker is
    * removed (removed as the main function in root or have its annotation removed).
    */
  private def checkEntryPoints(root: TypedAst.Root)(implicit flix: Flix): (TypedAst.Root, List[EntryPointError]) = {
    implicit val sctx: SharedContext = SharedContext.mk()
    implicit val r: TypedAst.Root = root

    ParOps.parMapValues(root.defs)(defn => flix.profile(defn.sym, defn.loc)(visitDef(defn)))

    // Remove the entrypoint if it is not valid.
    val root1 = if (sctx.invalidMain.get()) root.copy(mainEntryPoint = None) else root
    val errs = sctx.errors.asScala.toList
    (root1, errs)
  }

  /**
    * Checks `defn` with relevant checks for its entry point kind (main/test/export).
    *
    * Because of resilience, invalid entry points are not discarded. Its entry point marker is
    * removed (removed as the main function in root or have its annotation removed).
    *
    * A function can be main, a test, and exported at the same time.
    */
  private def visitDef(defn: TypedAst.Def)(implicit sctx: SharedContext, root: TypedAst.Root, flix: Flix): TypedAst.Def = {
    // checkMain is different than the other two because the entry point designation exists on
    // root and invalid main functions are communicated via SharedContext.
    if (TypedAstOps.isMain(defn)) checkMain(defn)
    val defn1 = if (TypedAstOps.isTest(defn)) visitTest(defn) else defn
    val defn2 = if (TypedAstOps.isExport(defn)) visitExport(defn1) else defn1
    defn2
  }

  /**
    * Rules for main - it has:
    *   - No type variables.
    *   - One parameter of type Unit.
    *   - An effect that is a subset of the primitive effects.
    *   - Return type Unit.
    */
  private def checkMain(defn: TypedAst.Def)(implicit sctx: SharedContext, root: TypedAst.Root, flix: Flix): Unit = {
    val errs = checkNoTypeVariables(defn) match {
      case Some(err) => List(err)
      case None =>
        // Only run these on functions without type variables.
        // A main function should have:
        //  - A single Unit argument
        //  - A Unit return value
        //  - An effect set containing only primitive effects or effects that have default handlers
        checkUnitArg(defn) ++ checkUnitResult(defn) ++ checkEffects(defn, Symbol.PrimitiveEffs ++ root.defaultHandlers.map(_.handledSym))
    }
    if (errs.nonEmpty) {
      // Invalidate main and add errors.
      sctx.invalidMain.set(true)
      errs.foreach(sctx.errors.add)
    }
  }

  /**
    * Rules for tests - a test has:
    *   - No type variables.
    *   - One parameter of type Unit.
    *   - An effect that is a subset of the primitive effects.
    */
  private def visitTest(defn: TypedAst.Def)(implicit sctx: SharedContext, root: TypedAst.Root, flix: Flix): TypedAst.Def = {
    val errs = checkNoTypeVariables(defn) match {
      case Some(err) => List(err)
      case None =>
        // A test function should have:
        //  - A single Unit argument
        //  - An effect set containing only primitive effects or effects that have default handlers
        checkUnitArg(defn) ++ checkUnitReturnType(defn) ++ checkEffects(defn, Symbol.PrimitiveEffs ++ root.defaultHandlers.map(_.handledSym))
    }
    if (errs.isEmpty) {
      defn
    } else {
      errs.foreach(sctx.errors.add)
      removeTestAnnotation(defn)
    }
  }

  /** Returns `defn` without a test annotation. */
  private def removeTestAnnotation(defn: TypedAst.Def): TypedAst.Def =
    defn.copy(
      spec = defn.spec.copy(
        ann = defn.spec.ann.copy(
          annotations = defn.spec.ann.annotations.filterNot(_.isInstanceOf[Annotation.Test])
        )
      )
    )

  /**
    * Rules for exported functions - an exported function has:
    *   - No type variables.
    *   - An effect that is a subset of the primitive effects.
    *   - Is not in the root namespace.
    *   - Is `pub`.
    *   - Has a name that is valid in Java.
    *   - Has types that are valid in Java (not Flix types like `List[Int32]`).
    */
  private def visitExport(defn: TypedAst.Def)(implicit sctx: SharedContext, root: TypedAst.Root, flix: Flix): TypedAst.Def = {
    val errs = (checkExportedTypeVariables(defn) match {
      case Some(err) => List(err)
      case None =>
        // Only run these on functions whose type variables are ones the boundary can represent.
        // An exported function should have:
        //  - Only valid Java types
        //  - An effect set containing only primitive effects or effects that have default handlers
        checkEffects(defn, Symbol.PrimitiveEffs ++ root.defaultHandlers.map(_.handledSym)).toList ++ checkJavaTypes(defn)
    }) ++
      checkNonRootNamespace(defn) ++
      checkPub(defn) ++
      checkValidJavaName(defn)
    if (errs.isEmpty) {
      defn
    } else {
      errs.foreach(sctx.errors.add)
      removeExportAnnotation(defn)
    }
  }

  /** Returns `defn` without a test annotation. */
  private def removeExportAnnotation(defn: TypedAst.Def): TypedAst.Def =
    defn.copy(
      spec = defn.spec.copy(
        ann = defn.spec.ann.copy(
          annotations = defn.spec.ann.annotations.filterNot(_.isInstanceOf[Annotation.Export])
        )
      )
    )

  /**
    * Returns an error if `defn` has type variables.
    *
    * If a function has no type variables in surface syntax we know that:
    *   - Traits and trait constraints do not occur since their syntax is limited to `Trait[var]`.
    *   - Associated types do not occur since their syntax is limited to `Trait.Assoc[var]`.
    *   - Equality constraints do not occur since their syntax is limited to
    *     `Trait.Assoc[var] ~ type`
    */
  private def checkNoTypeVariables(defn: TypedAst.Def): Option[EntryPointError] = {
    // `tparams` lies sometimes when explicit tparams are given and _ are used.
    val monomorphic = defn.spec.tparams.isEmpty && typesOf(defn).forall(_.typeVars.isEmpty)
    if (monomorphic) None
    else Some(EntryPointError.IllegalEntryPointTypeVariables(defn.sym.loc))
  }

  /**
    * Returns an error if `defn` has a type variable the export boundary cannot represent.
    *
    * A variable is allowed only where it *is* the whole type of a parameter or of the return
    * value, and only if nothing constrains it. Such a variable is exported as `java.lang.Object`:
    * the monomorpher defaults an unconstrained `Kind.Star` variable to `AnyType`, which the
    * backend represents exactly as `Object`, so the shim needs no special case at all.
    *
    * Everything else stays an error, for two different reasons:
    *
    *   - A *constrained* variable has no such instantiation. Flix resolves a trait to an instance
    *     while compiling, keyed on the concrete type constructor, and no instance exists -- or can
    *     be declared -- for `AnyType`. Reaching the monomorpher with one crashes it rather than
    *     failing, so this check is what keeps that unreachable. See [[IllegalExportConstrainedTypeVariable]].
    *   - A variable *nested* inside another type, such as the region of `S[Int32, r]`, is not the
    *     whole boundary type, so defaulting it would silently pick a representation the signature
    *     never mentions.
    */
  private def checkExportedTypeVariables(defn: TypedAst.Def): Option[EntryPointError] = {
    if (defn.spec.tconstrs.nonEmpty || defn.spec.econstrs.nonEmpty)
      Some(EntryPointError.IllegalExportConstrainedTypeVariable(defn.sym.loc))
    else {
      val boundaryTypes = defn.spec.fparams.map(_.tpe) :+ defn.spec.retTpe
      val leftover = boundaryTypes.filterNot(isBareTypeVariable).flatMap(_.typeVars) ++ defn.spec.eff.typeVars
      if (leftover.isEmpty) None
      else Some(EntryPointError.IllegalEntryPointTypeVariables(defn.sym.loc))
    }
  }

  /** Returns `true` if `tpe` is a type variable of kind `Star`, and so is represented as `Object`. */
  @tailrec
  private def isBareTypeVariable(tpe: Type): Boolean = tpe match {
    case Type.Var(sym, _) => sym.kind == Kind.Star
    case Type.Alias(_, _, t, _) => isBareTypeVariable(t)
    case _ => false
  }

  /** Returns all the types in the signature of `defn`. */
  private def typesOf(defn: TypedAst.Def): List[Type] = {
    defn.spec.fparams.map(_.tpe) ++
      List(defn.spec.retTpe) ++
      List(defn.spec.eff) ++
      defn.spec.tconstrs.map(_.arg) ++
      defn.spec.econstrs.flatMap(ec => List(ec.tpe1, ec.tpe2))
  }

  /** Returns `None` if `defn` has a single parameter of type Unit. Returns an error otherwise. */
  private def checkUnitArg(defn: TypedAst.Def): Option[EntryPointError] = {
    defn.spec.fparams match {
      // One parameter of type Unit - valid.
      case List(arg) =>
        isUnitType(arg.tpe) match {
          case Result.Ok(true) => None
          case Result.Ok(false) =>
            Some(EntryPointError.IllegalRunnableEntryPointArgs(defn.sym.loc))
          case Result.Err(ErrorOrMalformed) =>
            // Do not report an error, since previous phases should have done already.
            None
        }
      // One parameter of a non-Unit type or more than two parameters - invalid.
      case _ :: _ =>
        Some(EntryPointError.IllegalRunnableEntryPointArgs(defn.sym.loc))
      // Zero parameters.
      case Nil => throw InternalCompilerException(s"Unexpected main with zero parameters ('${defn.sym}'", defn.sym.loc)
    }
  }

  /** Returns `None` if `defn` has a Unit return type. Returns an error otherwise. */
  private def checkUnitReturnType(defn: TypedAst.Def): Option[EntryPointError] = {
    val returnType = defn.spec.retTpe
    if (returnType == Type.Unit)
      None
    else
      Some(EntryPointError.TestNonUnitReturnType(returnType.loc))
  }

  /**
    * Returns `None` if `defn` has return type Unit. Returns an error otherwise.
    *
    * The main function must return Unit. Tools that want to run-and-print an arbitrary function
    * (e.g. the shell's `:eval` or the editor's run button) are responsible for wrapping the call
    * in `println(...)` themselves, so the compiler does not need to special-case ToString here.
    */
  private def checkUnitResult(defn: TypedAst.Def)(implicit flix: Flix): Option[EntryPointError] = {
    val resultType = defn.spec.retTpe
    isUnitType(resultType) match {
      case Result.Ok(true) =>
        None
      case Result.Ok(false) =>
        Some(EntryPointError.MainNonUnitReturnType(resultType, resultType.loc))
      case Result.Err(ErrorOrMalformed) =>
        // Do not report an error, since previous phases should have done already.
        None
    }
  }

  /**
    * Returns `None` if `defn` has an effect that is a subset of `allowed`.
    *
    * Returns `None` if `defn` has a malformed effect.
    *
    * Otherwise returns `Some(err)`. The error names only the effects that are not
    * in `allowed`, e.g. effect `IO + Ask` against allowed `IO` reports just `Ask`.
    */
  private def checkEffects(defn: TypedAst.Def, allowed: SortedSet[Symbol.EffSym])(implicit flix: Flix): Option[EntryPointError] = {
    val eff = defn.spec.eff
    val residual = residualEffects(eff, allowed)
    if (residual.isEmpty) {
      // Either `eff` is a subset of `allowed`, or it is malformed - in which case a
      // previous phase has already reported an error. Either way, report nothing here.
      None
    } else {
      Some(EntryPointError.IllegalEntryPointEffect(toEffType(residual, eff.loc), eff.loc))
    }
  }

  /**
    * Returns the effects of `tpe` that are not in `allowed` (i.e. `tpe - allowed`).
    *
    * Returns the empty set for any effect containing type variables, associated
    * types, or error types, since such effects are reported by an earlier phase.
    */
  private def residualEffects(tpe: Type, allowed: SortedSet[Symbol.EffSym]): CofiniteSet[Symbol.EffSym] =
    Type.eval(tpe) match {
      case Result.Ok(s) => CofiniteSet.difference(s, CofiniteSet.mkSet(allowed))
      case Result.Err(_) => CofiniteSet.empty
    }

  /** Reconstructs an effect [[Type]], located at `loc`, from a set of effect symbols. */
  private def toEffType(s: CofiniteSet[Symbol.EffSym], loc: SourceLocation): Type = {
    def union(syms: SortedSet[Symbol.EffSym]): Type =
      Type.mkUnion(syms.toList.map(sym => Type.Cst(TypeConstructor.Effect(sym, Kind.Eff), loc)), loc)

    s match {
      case CofiniteSet.Set(syms) => union(syms)
      case CofiniteSet.Compl(syms) => Type.mkDifference(Type.Univ, union(syms), loc)
    }
  }

  /**
    * Returns an error if the class holding `defn` would be in the unnamed package.
    *
    * The class of a def in module `A.B` is `B` in package `A`, so a def needs at least two
    * enclosing module names to land in a package Java can name. The root namespace has no class at
    * all, and a top-level module has one in the unnamed package; neither is reachable from Java
    * code in a named package.
    */
  private def checkNonRootNamespace(defn: TypedAst.Def): Option[EntryPointError] = {
    defn.sym.namespace match {
      case Nil => Some(EntryPointError.IllegalExportNamespace(defn.sym.loc))
      case name :: Nil => Some(EntryPointError.IllegalExportUnnamedPackage(name, defn.sym.loc))
      case _ => None
    }
  }

  /** Returns an error if `defn` is not a public function. */
  private def checkPub(defn: TypedAst.Def): Option[EntryPointError] = {
    val isPub = defn.spec.mod.isPublic
    if (isPub) None
    else Some(EntryPointError.NonPublicExport(defn.sym.loc))
  }

  /** Returns `None` if `defn` has a name that is valid in Java. Returns an error otherwise. */
  private def checkValidJavaName(defn: TypedAst.Def): Option[EntryPointError] = {
    val validName = defn.sym.name.matches("[a-z][a-zA-Z0-9]*")
    if (validName) None
    else Some(EntryPointError.IllegalExportName(defn.sym.loc))
  }

  /** Returns an error for each type in `defn` that is not valid in Java. */
  private def checkJavaTypes(defn: TypedAst.Def)(implicit root: TypedAst.Root, flix: Flix): List[EntryPointError] = {
    // `Unit` has no Java type of its own, but it is exportable in the two positions where the
    // shim method can render it away: a `Unit` return type becomes `void`, and the lone `Unit`
    // parameter that Flix gives a nullary function is dropped. A `Unit` anywhere else -- say the
    // first of two parameters -- has no sensible Java form and stays an error.
    val paramTypes = defn.spec.fparams.map(_.tpe) match {
      case List(tpe) if isUnitType(tpe) == Result.Ok(true) => Nil
      case tpes => tpes
    }
    // `Option[t]`, `List[t]`, `Set[t]`, `Map[k, v]`, tuples and enums are exportable in return
    // position, where the shim marshals them into `java.util.Optional`, an unmodifiable
    // `java.util.List`, unmodifiable `java.util.Set` and `java.util.Map` views, a
    // `dev.flix.runtime.TupleN` record, and either a real Java enum or a sealed interface with one
    // record per case, depending on whether every case is data-free. None is exportable as a
    // parameter, which would need the reverse conversion. What must be exportable is what they
    // contain, so that is what is checked, and an error points at it rather than at the container.
    //
    // Only the element, and only one level: an element that is itself a container has no plan, so
    // admitting `List[Option[t]]` here would produce a shim returning the internal tag class. The
    // gate and the solver are widened together, never one ahead of the other.
    val retTpe = defn.spec.retTpe
    val returnTypes =
      if (isUnitType(retTpe) == Result.Ok(true)) Nil
      else unapplyContainer(retTpe).getOrElse(List(retTpe))
    val types = returnTypes ::: paramTypes
    types.flatMap(tpe => {
      isExportableType(tpe) match {
        case Result.Ok(true) =>
          None
        case Result.Ok(false) =>
          Some(EntryPointError.IllegalExportType(tpe, tpe.loc))
        case Result.Err(ErrorOrMalformed) =>
          // Do not report an error, since previous phases should have done already.
          None
      }
    })
  }

  /**
    * Returns the element type of `tpe`, if `tpe` is the standard library's `Option`.
    *
    * `Option` is identified by symbol rather than by name alone, so a user-defined `Foo.Option` is
    * an ordinary enum and stays unexportable.
    */
  private def unapplyOption(tpe: Type): Option[Type] = tpe match {
    case Type.Apply(Type.Cst(TypeConstructor.Enum(sym, _), _), elm, _)
      if sym.namespace.isEmpty && sym.text == "Option" => Some(elm)
    case Type.Alias(_, _, t, _) => unapplyOption(t)
    case _ => None
  }

  /** Returns the element type of `tpe` if it is the standard library's `List[t]`. */
  private def unapplyList(tpe: Type): Option[Type] = unapplyStdEnum(tpe, "List")

  /** Returns the element type of `tpe` if it is the standard library's `Set[t]`. */
  private def unapplySet(tpe: Type): Option[Type] = unapplyStdEnum(tpe, "Set")

  /**
    * Returns the key and value types of `tpe` if it is the standard library's `Map[k, v]`.
    *
    * Separate from [[unapplyStdEnum]] because a binary application is a different shape, not
    * because `Map` is treated differently: both of its arguments must be exportable, exactly as a
    * `Set`'s one must.
    */
  @tailrec
  private def unapplyMap(tpe: Type): Option[List[Type]] = tpe match {
    case Type.Apply(Type.Apply(Type.Cst(TypeConstructor.Enum(sym, _), _), k, _), v, _)
      if sym.namespace.isEmpty && sym.text == "Map" => Some(List(k, v))
    case Type.Alias(_, _, t, _) => unapplyMap(t)
    case _ => None
  }

  /**
    * Returns the argument of `tpe` if it is the standard library's unary enum named `name`.
    *
    * By symbol rather than by name alone, so a user-defined `Foo.Set` stays an ordinary enum and
    * unexportable. This has to agree with `ExportPlan`, which asks the same question of the
    * backend type.
    */
  @tailrec
  private def unapplyStdEnum(tpe: Type, name: String): Option[Type] = tpe match {
    case Type.Apply(Type.Cst(TypeConstructor.Enum(sym, _), _), elm, _)
      if sym.namespace.isEmpty && sym.text == name => Some(elm)
    case Type.Alias(_, _, t, _) => unapplyStdEnum(t, name)
    case _ => None
  }

  /**
    * Returns the types inside `tpe` if it is a container the shim converts.
    *
    * This must admit exactly what `ExportPlan.of` can build, no more: a type accepted here without
    * a plan compiles into a shim that returns the internal tag class instead of failing.
    */
  private def unapplyContainer(tpe: Type)(implicit root: TypedAst.Root): Option[List[Type]] =
    unapplyOption(tpe).map(List(_))
      .orElse(unapplyList(tpe).map(List(_)))
      .orElse(unapplySet(tpe).map(List(_)))
      .orElse(unapplyMap(tpe))
      .orElse(unapplyVector(tpe).map(List(_)))
      .orElse(unapplyChain(tpe).map(List(_)))
      .orElse(unapplyTuple(tpe))
      .orElse(unapplyEnum(tpe))

  /**
    * Returns the element type of `tpe` if it is the standard library's `Chain[t]`.
    *
    * `Chain` follows the same `pub mod Chain { pub enum Chain[t] { ... } }` declaration idiom
    * `List`/`Set`/`Map`/`Option` already use, so [[unapplyStdEnum]] recognizes it exactly the way
    * it recognizes those four -- this has to agree with `ExportPlan`'s own `isStdEnum(sym,
    * "Chain")`, which asks the same question of the backend type.
    */
  private def unapplyChain(tpe: Type): Option[Type] = unapplyStdEnum(tpe, "Chain")

  /**
    * Returns the element type of `tpe` if it is `Vector[t]`.
    *
    * Deliberately not `Array[t, r]`, which stays unexportable: it is mutable, where every other
    * converted collection presents an unmodifiable view over data that is itself immutable, and it
    * is region-scoped, where a view could outlive the region that owns its storage. `Vector` has
    * neither problem -- `TypeConstructor.Vector` has kind `Star -> Star`, no region argument -- so
    * nothing here needs to reject it on either ground.
    *
    * This is the one place in the gate that matters for `ExportPlan.of`'s own soundness: by the
    * time a def's type reaches the backend, `Simplifier` has erased `Vector[t]` and `Array[t, r]`
    * to the identical `SimpleType.Array(t)`, so the solver cannot itself tell them apart and simply
    * trusts that this function is what kept `Array` from ever reaching it.
    */
  @tailrec
  private def unapplyVector(tpe: Type): Option[Type] = tpe match {
    case Type.Alias(_, _, t, _) => unapplyVector(t)
    case Type.Apply(Type.Cst(TypeConstructor.Vector, _), elm, _) => Some(elm)
    case _ => None
  }

  /**
    * Returns the types inside the cases of `tpe`, if `tpe` is a Flix enum the shim can present at
    * the boundary -- either as a real Java enum, when every case is data-free, or as a sealed
    * interface with one record per case otherwise.
    *
    * A data-free case holds no types, so a wholly nullary enum returns `Nil`: there is nothing
    * inside to check, but the enum is still a type whose representation the boundary replaces.
    * Returning `None` would send it to `isExportableType`, which rejects every enum. A
    * data-carrying case's elements are checked exactly as a tuple's are -- one level deep, never
    * recursively -- because `ExportPlan.of`'s solver has no plan for a nested container yet.
    */
  @tailrec
  private def unapplyEnum(tpe: Type)(implicit root: TypedAst.Root): Option[List[Type]] = tpe match {
    case Type.Alias(_, _, t, _) => unapplyEnum(t)
    // Unapplied: a generic enum reaches here as a `Type.Apply` and so does not match, which is the
    // rejection `isExportableEnum`'s type-parameter test then states in its own right.
    case Type.Cst(TypeConstructor.Enum(sym, _), _) =>
      root.enums.get(sym).filter(isExportableEnum).map(_.cases.values.flatMap(_.tpes).toList)
    case _ => None
  }

  /**
    * Returns `true` if `enm` has an exportable form at the boundary.
    *
    * Three conditions, each of which is a way the mapping would otherwise be wrong rather than
    * merely unimplemented:
    *
    *   - There is at least one case. An enum with none has no values, so nothing can be returned.
    *   - No type parameters. `Color[Int32]` and `Color[String]` erase to one JVM class, so a
    *     generic enum could only cross raw, losing the argument the caller asked about.
    *   - At least two namespace segments, the same requirement an exported *function* already
    *     meets. The class is named beside its namespace, so `mod Acme.Api` gives `Acme.Api$Color`
    *     while one segment or none give a name in `dev.flix.gen` -- the package J0 keeps private,
    *     and one Java cannot import from in the unnamed-package case.
    *
    * Whether every case is data-free is not one of these: it decides *which* form the enum takes
    * (`ExportPlan.of` builds a real `java.lang.Enum` when it is, a sealed interface with one
    * record per case otherwise), not whether it has one at all.
    */
  private def isExportableEnum(enm: TypedAst.Enum): Boolean =
    enm.cases.nonEmpty &&
      enm.tparams.isEmpty &&
      enm.sym.namespace.lengthIs >= 2

  /**
    * Returns the element types of `tpe` if it is a tuple.
    *
    * A tuple is a container here in the sense that matters: its own representation is replaced at
    * the boundary -- by `dev.flix.runtime.TupleN` -- and what has to be exportable is therefore
    * what it holds. Unlike the collections above it is not identified by a symbol, because it has
    * no declaration to name; a tuple type is a type constructor applied to its elements.
    *
    * A one-element tuple does not exist in Flix, and `Unit` is the empty one and is handled as its
    * own type, so every tuple reaching here has at least two elements.
    */
  @tailrec
  private def unapplyTuple(tpe: Type): Option[List[Type]] = tpe match {
    case Type.Alias(_, _, t, _) => unapplyTuple(t)
    case _ => tpe.typeConstructor match {
      case Some(TypeConstructor.Tuple(_)) => Some(tpe.typeArguments)
      case _ => None
    }
  }

  /**
    * Returns `true` if `tpe` is a valid Java type that can be exported.
    *
    * A type is exportable if the backend gives it an exact and stable JVM representation: the
    * primitive types, the Flix types that are themselves Java types (e.g. `String` is
    * `java.lang.String`), and any Java type. Types whose representation is an implementation
    * detail — enums, tuples, records, closures — are not exportable, because a Java caller would
    * then depend on generated, erased class names such as `Tag$Obj` that the backend is free to
    * change.
    *
    *   - `isExportableType(Int32) = true`
    *   - `isExportableType(Bool) = true`
    *   - `isExportableType(String) = true`
    *   - `isExportableType(List[String]) = false`
    *   - `isExportableType(java.lang.Object) = true`
    */
  private def isExportableType(tpe: Type): Result[Boolean, ErrorOrMalformed.type] = {
    tpe match {
      case Type.Cst(TypeConstructor.Bool, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Char, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Float32, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Float64, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Int8, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Int16, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Int32, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Int64, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Str, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.BigInt, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.BigDecimal, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Regex, _) => Result.Ok(true)
      case Type.Cst(TypeConstructor.Native(_), _) => Result.Ok(true)
      case Type.Cst(_, _) => Result.Ok(false)
      // A type application is exportable exactly when its head is: a generic Java type such as
      // `ArrayList[String]` is erased to the raw class, whereas `List[Int32]` is headed by a Flix
      // enum and stays unexportable.
      // A type application is exportable when its head *and every argument* are. Checking only the
      // head is not enough, even though the JVM erases the arguments away: `ArrayList[Colour]`
      // then compiles into a method that hands a Java caller `dev.flix.gen.Colour$Red`, a
      // generated class the backend renames freely, which is exactly what J0 exists to prevent.
      // The erasure is what makes it *look* safe -- the descriptor says `java.util.ArrayList` and
      // nothing in it mentions Flix at all -- so the leak is in the values rather than the types.
      // Note the asymmetry this removes: `List[Colour]` was already rejected, because a Flix
      // container is headed by an enum, so only the Java containers had the hole.
      case Type.Apply(t, arg, _) =>
        isExportableType(t).flatMap(head => isExportableType(arg).map(head && _))
      case Type.Alias(_, _, t, _) => isExportableType(t)
      // An unconstrained type variable is exported as `java.lang.Object`, which is exactly what
      // the monomorpher's `AnyType` default is represented as. Reported as exportable rather than
      // as malformed: `checkJavaTypes` discards a malformed result without an error, so relying on
      // that would admit a type variable by accident instead of by decision.
      // `checkExportedTypeVariables` has already rejected any variable that is constrained or that
      // is nested inside another type, so only the boundary-shaped case reaches here.
      case Type.Var(sym, _) if sym.kind == Kind.Star => Result.Ok(true)
      case Type.Var(_, _) => Result.Err(ErrorOrMalformed)
      case Type.AssocType(_, _, _, _) => Result.Err(ErrorOrMalformed)
      case Type.JvmToType(_, _) => Result.Err(ErrorOrMalformed)
      case Type.JvmToEff(_, _) => Result.Err(ErrorOrMalformed)
      case Type.UnresolvedJvmType(_, _) => Result.Err(ErrorOrMalformed)
    }
  }

  /** Returns a new root where [[TypedAst.Root.entryPoints]] contains all entry points (main/test/export). */
  private def findEntryPoints(root: TypedAst.Root): TypedAst.Root = {
    val s = mutable.Set.empty[Symbol.DefnSym]
    for ((sym, defn) <- root.defs if TypedAstOps.isEntryPoint(defn)(root)) {
      s += sym
    }
    val entryPoints = s.toSet
    root.copy(entryPoints = entryPoints)
  }

  private object SharedContext {
    /** Returns a fresh shared context. */
    def mk(): SharedContext = new SharedContext(
      new ConcurrentLinkedQueue(),
      new AtomicBoolean(false)
    )
  }

  /**
    * A global shared context. Must be thread-safe.
    *
    * @param errors      the [[EntryPointError]]s in the AST, if any.
    * @param invalidMain marks the main entrypoint as invalid.
    */
  private case class SharedContext(errors: ConcurrentLinkedQueue[EntryPointError], invalidMain: AtomicBoolean)

}
