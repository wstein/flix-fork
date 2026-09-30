/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.errors.{InstanceError, JavaBoundaryError, NameError}
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{SecurityContext, SourceName}
import ca.uwaterloo.flix.language.phase.jvm.{JavaBoundaryApi, JvmClass}
import ca.uwaterloo.flix.language.phase.jvm.JvmTypeKey
import ca.uwaterloo.flix.language.fmt.FormatType
import ca.uwaterloo.flix.runtime.CompilationResult
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.collection.CofiniteSet

import java.lang.constant.ClassDesc
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.lang.model.SourceVersion
import scala.collection.mutable

/** Opt-in two-pass wrapper orchestration. No surface associated-type rule is relaxed. */
object JavaBoundaryWrappers {
  case class Member(name: String, target: Symbol.DefnSym, loc: SourceLocation)
  case class Declaration(className: String, members: List[Member], loc: SourceLocation = SourceLocation.Unknown,
                         interfaceName: Option[String] = None)
  case class Traits(result: Symbol.TraitSym, argument: Symbol.TraitSym)
  case class Output(compilation: CompilationResult, plan: JavaBoundaryApi.Plan)
  sealed trait Error { def loc: SourceLocation }
  case class Invalid(message: String, loc: SourceLocation) extends Error
  case class InputErrors(messages: List[CompilationMessage], loc: SourceLocation) extends Error
  case class WrapperErrors(messages: List[CompilationMessage], loc: SourceLocation) extends Error
  case class BoundaryError(cause: BoundaryTypeElaborator.Error, loc: SourceLocation) extends Error
  case class FacadeError(cause: JavaBoundaryApi.Error) extends Error { def loc: SourceLocation = cause.loc }
  case class ContractError(cause: JavaBoundaryContract.Error) extends Error { def loc: SourceLocation = cause.loc }
  private case class Prepared(root: TypedAst.Root, declaration: JavaBoundaryApi.Declaration, plan: JavaBoundaryApi.Plan)
  private case class Conversion(tpe: Type, eff: Type, call: Option[String])
  private case class Wrapper(member: Member, name: String, args: List[Conversion], result: Conversion,
                             effects: List[Symbol.EffSym], handlers: List[Symbol.DefnSym])

  /** Checks caller sources, derives wrappers from validated instances, rechecks, and emits a facade. */
  def compile(flix: Flix, api: Declaration, traits: Traits, sctx: SecurityContext): Result[Output, Error] = {
    emit(flix, prepareValidated(flix, api, traits, sctx, _ => Ok(())))
  }

  /** Rejects a bootstrap/recorded ABI mismatch before invoking code generation. */
  def compileContract(flix: Flix, contract: JavaBoundaryContract.Contract, sctx: SecurityContext): Result[Output, Error] =
    withContractTypes(flix, contract, sctx) {
      emit(flix, prepareContract(flix, contract, sctx), JavaBoundaryProducts.classes(contract))
    }

  /** Frontend-only entry point for editor diagnostics: no bytecode or disk output. */
  def checkContract(flix: Flix, contract: JavaBoundaryContract.Contract, sctx: SecurityContext): Result[JavaBoundaryApi.Plan, Error] =
    withContractTypes(flix, contract, sctx) { prepareContract(flix, contract, sctx).map(_.plan) }

  private def withContractTypes[A](flix: Flix, contract: JavaBoundaryContract.Contract, sctx: SecurityContext)
                                 (body: => Result[A, Error]): Result[A, Error] = flix.synchronized {
    if (contract.products.isEmpty && contract.nominals.isEmpty) return body
    val classes = JavaBoundaryProducts.classes(contract)
    val available = flix.availableClasses.byClass.m.iterator.flatMap { case (name, packages) =>
      packages.map(pkg => (pkg :+ name).mkString(".").toLowerCase(java.util.Locale.ROOT))
    }.toSet
    val collision = classes.find(clazz => available.contains(clazz.name.descriptorString().drop(1).dropRight(1)
      .replace('/', '.').toLowerCase(java.util.Locale.ROOT)) || flix.javaTypeProvider.lookupClass(clazz.name).isInstanceOf[Ok[?, ?]])
    if (collision.nonEmpty) return Err(Invalid("A declared Java type already exists on the dependency classpath.", contract.loc))
    val uri = URI.create(s"flix-boundary:/${JavaBoundaryProducts.module(contract)}.flix")
    if (flix.hasSource(SourceName.UriName(uri)))
      return Err(Invalid("The generated boundary type source is already owned by the caller.", contract.loc))
    flix.withJavaBoundaryTypes(classes.map(clazz => clazz.name -> clazz.bytecode).toMap) {
      val declarations = JavaBoundaryProducts.sourceWithLocations(contract, validationOnly = true, Map.empty)
      var memberLocations = declarations._2
      flix.addJavaBoundarySource(uri, declarations._1, sctx)
      try {
        val checked = flix.check()
        val validated: Result[Unit, Error] = if (checked._2.nonEmpty) Err(InputErrors(checked._2, checked._2.head.loc))
        else JavaBoundaryTypeGate.verify(contract, checked._1.get)(flix).mapErr(ContractError.apply)
        validated.flatMap { _ =>
          val shapes = JavaBoundaryTypeGate.argumentShapes(contract, checked._1.get)
          val conversions = JavaBoundaryProducts.sourceWithLocations(contract, validationOnly = false, shapes)
          memberLocations = conversions._2
          flix.addJavaBoundarySource(uri, conversions._1, sctx)
          body
        }.mapErr {
        case InputErrors(errors, loc) =>
          val mapped = contractDiagnostics(errors, SourceName.UriName(uri), memberLocations, contract.loc)
          if (mapped.exists(_.isInstanceOf[JavaBoundaryError]))
            WrapperErrors(mapped, mapped.collectFirst { case e: JavaBoundaryError => e.loc }.get)
          else InputErrors(errors, loc)
        case WrapperErrors(errors, loc) =>
          val mapped = contractDiagnostics(errors, SourceName.UriName(uri), memberLocations, contract.loc)
          WrapperErrors(mapped, mapped.collectFirst { case e: JavaBoundaryError => e.loc }.getOrElse(loc))
        case other => other
        }
      } finally flix.remSource(uri)
    }
  }

  /** Name clashes have two symmetric reports; either side may be the generated declaration. */
  private def contractDiagnostics(errors: List[CompilationMessage], source: SourceName,
                                  locations: Map[Int, SourceLocation], fallback: SourceLocation): List[CompilationMessage] = {
    errors.map { error =>
      val sites = error match {
        case NameError.DuplicateModule(_, first, second) => List(first, second)
        case NameError.DuplicateLowerName(_, first, second) => List(first, second)
        case InstanceError.OverlappingInstances(_, _, first, second) => List(first, second)
        case _ => error.loc :: error.locs
      }
      sites.filter(_.source.sourceName == source).sortBy(_.startLine).headOption match {
        case Some(loc) => JavaBoundaryError(error.summary, locations.getOrElse(loc.startLine, fallback),
          sites.filterNot(_.source.sourceName == source).distinct.sorted)
        case None => error
      }
    }.distinct
  }

  private def prepareContract(flix: Flix, contract: JavaBoundaryContract.Contract, sctx: SecurityContext): Result[Prepared, Error] = {
    val traits = Traits(Symbol.mkTraitSym("Java.Boundary.JavaResult"), Symbol.mkTraitSym("Java.Boundary.JavaArgument"))
    prepareValidated(flix, contract.declaration, traits, sctx,
      plan => JavaBoundaryContract.verify(contract, plan).mapErr(ContractError.apply),
      contract.products.indices.map(i => s"${JavaBoundaryProducts.module(contract)}.out$i" -> s"${JavaBoundaryProducts.module(contract)}.in$i").toList ++
        contract.nominals.zipWithIndex.collect { case (nominal, i) if nominal.adapted =>
          s"${JavaBoundaryNominals.helperOwner(contract, i)}.out" -> s"${JavaBoundaryNominals.helperOwner(contract, i)}.in"
        })
  }

  private def emit(flix: Flix, prepared: Result[Prepared, Error]): Result[Output, Error] = emit(flix, prepared, Nil)

  private def emit(flix: Flix, prepared: Result[Prepared, Error], classes: List[JvmClass]): Result[Output, Error] = prepared.flatMap { checked =>
    flix.codeGenWithJavaApi(checked.root, checked.declaration, classes).mapErr(FacadeError.apply)
      .map(compiled => Output(compiled, checked.plan))
  }

  private def prepareValidated(flix: Flix, api: Declaration, traits: Traits, sctx: SecurityContext,
                               verify: JavaBoundaryApi.Plan => Result[Unit, Error]): Result[Prepared, Error] =
    prepareValidated(flix, api, traits, sctx, verify, Nil)

  private def prepareValidated(flix: Flix, api: Declaration, traits: Traits, sctx: SecurityContext,
                               verify: JavaBoundaryApi.Plan => Result[Unit, Error], products: List[(String, String)]): Result[Prepared, Error] = {
    implicit val compiler: Flix = flix
    val checked = flix.check()
    if (checked._2.nonEmpty) return Err(InputErrors(checked._2, checked._2.head.loc))
    val root = checked._1.get
    val bindings = products.map { case (out, in) =>
      val result = root.defs(Symbol.mkDefnSym(out)).spec
      val argument = root.defs(Symbol.mkDefnSym(in)).spec
      Type.eraseAliases(result.fparams.head.tpe) -> (Conversion(result.retTpe, result.eff, Some(out)),
        Conversion(argument.fparams.head.tpe, argument.eff, Some(in)))
    }
    if (bindings.map(_._1).distinct.size != bindings.size)
      return Err(Invalid("Declared Java representations have the same checked Flix payload type (including aliases).", api.loc))
    val declared = bindings.toMap
    val digest = MessageDigest.getInstance("SHA-256").digest(api.className.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString
    val module = "BoundaryGenerated" + digest
    val uri = URI.create(s"flix-boundary:/$module.flix")
    if (root.modules.keys.exists(_.ns == List(module)) || root.sources.keys.exists(_.sourceName == SourceName.UriName(uri)))
      return Err(Invalid("The generated boundary module or source name is already owned by the caller.", api.loc))
    if (!SourceVersion.isName(api.className) || api.className.startsWith("java.") || api.className.startsWith("dev.flix.") ||
        api.members.isEmpty || (api.interfaceName.isEmpty && api.members.map(_.name).distinct.size != api.members.size))
      return Err(Invalid("Expected a non-reserved Java class name and distinct API member names.", api.loc))
    Result.traverse(api.members.zipWithIndex) { case (member, index) =>
      generateWrapper(member, s"w$index", traits, root, declared)
    }.flatMap { wrappers =>
      val renderer = new Renderer
      val definitions = wrappers.map { wrapper =>
        val params = wrapper.args.zipWithIndex.map { case (arg, index) => s"p$index: ${renderer.render(arg.tpe)}" }
        val arguments = wrapper.args.zipWithIndex.map { case (arg, index) => arg.call.fold(s"p$index")(name => s"$name(p$index)") }
        val target = (wrapper.member.target.namespace :+ wrapper.member.target.text).mkString(".")
        val call = arguments.mkString(s"$target(", ", ", ")")
        val converted = wrapper.result.call.fold(call)(name => s"$name($call)")
        val body = wrapper.handlers.foldLeft(converted) { (inner, handler) =>
          s"${(handler.namespace :+ handler.text).mkString(".")}(_ -> $inner)"
        }
        val result = renderer.render(wrapper.result.tpe)
        val effect = if (wrapper.effects.isEmpty) "" else wrapper.effects.map(_.toString).mkString(" \\ (", " + ", ")")
        s"    pub def ${wrapper.name}${params.mkString("(", ", ", ")")}: $result$effect = $body"
      }
      val imports = renderer.imports ++ List("    import dev.flix.runtime.{OpaqueHandleBridge => BoundaryOpaqueBridge}",
        "    import dev.flix.runtime.{OpaqueHandle => BoundaryOpaqueHandle}",
        "    import java.lang.{Object => BoundaryObject}")
      val lines = mutable.ArrayBuffer(s"pub mod $module {")
      lines ++= imports
      // Private to this owned, temporary module. A caller-owned module with this name was rejected above.
      if (wrappers.exists(_.args.exists(_.call.exists(_.contains("boundaryUnpack")))))
        lines += "    def boundaryUnpack(key: String, name: String, value: BoundaryOpaqueHandle[BoundaryObject]): Java.Boundary.Opaque[a] \\ IO = unchecked_cast(BoundaryOpaqueBridge.unwrap(key, name, value) as Java.Boundary.Opaque[a])"
      val memberLocations = mutable.Map.empty[Int, SourceLocation]
      definitions.zip(wrappers).foreach { case (definition, wrapper) =>
        definition.linesIterator.foreach { line =>
          lines += line
          memberLocations(lines.size) = wrapper.member.loc
        }
      }
      lines += "}"
      val source = lines.mkString("\n")
      flix.addJavaBoundarySource(uri, source, sctx)
      try {
        val augmented = flix.check()
        if (augmented._2.nonEmpty) {
          val errors = augmented._2.map { diagnostic =>
            if (diagnostic.source.sourceName != SourceName.UriName(uri)) diagnostic
            else JavaBoundaryError(diagnostic.summary, memberLocations.getOrElse(diagnostic.loc.startLine, api.loc))
          }.distinct
          Err(WrapperErrors(errors, errors.head.loc))
        } else {
          val typed = augmented._1.get
          val members = wrappers.map { wrapper =>
            val sym = typed.defs.keys.find(sym => sym.namespace == List(module) && sym.text == wrapper.name).get
            val names = root.defs(wrapper.member.target).spec.fparams.toList.map(_.bnd.sym.text)
            val params = root.defs(wrapper.member.target).spec.fparams.toList.map(_.tpe)
            JavaBoundaryApi.Member(wrapper.member.name, sym, names, params.map(JavaBoundaryApi.argumentShape), wrapper.member.loc)
          }
          val declaration = JavaBoundaryApi.Declaration(api.className, members, api.loc, api.interfaceName)
          for {
            plan <- JavaBoundaryApi.prepare(declaration, typed).mapErr(FacadeError.apply)
            _ <- verify(plan)
          } yield Prepared(typed, declaration, plan)
        }
      } finally flix.remSource(uri)
    }
  }

  private def generateWrapper(member: Member, name: String, traits: Traits,
                              root: TypedAst.Root, declared: Map[Type, (Conversion, Conversion)])(implicit flix: Flix): Result[Wrapper, Error] = {
    root.defs.get(member.target) match {
      case None => Err(Invalid("Unknown API target.", member.loc))
      case Some(defn) =>
        val spec = defn.spec
        if (!spec.mod.isPublic || spec.tparams.nonEmpty || spec.tconstrs.nonEmpty || spec.econstrs.nonEmpty)
          return Err(Invalid("API targets must be public, monomorphic, and unconstrained.", member.loc))
        if (!member.target.text.matches("[A-Za-z_][A-Za-z0-9_]*") || !SourceVersion.isIdentifier(member.name) || SourceVersion.isKeyword(member.name))
          return Err(Invalid("This prototype requires an ordinary function name and a valid Java member name.", member.loc))
        val params = spec.fparams.toList.map(_.tpe)
        for {
          args <- Result.traverse(if (params == List(Type.Unit)) Nil else params)(conversion(_, traits.argument, "In", "toFlix", root, member.loc, declared))
          result <- conversion(spec.retTpe, traits.result, "Out", "toJava", root, member.loc, declared)
          eff = (args.map(_.eff) :+ result.eff).foldLeft(spec.eff)((left, right) => Type.mkUnion(left, right, member.loc))
          wrapper <- Type.eval(eff) match {
            case Ok(CofiniteSet.Set(effects)) =>
              val handlers = root.defaultHandlers.filter(handler => effects.contains(handler.handledSym))
              val remaining = effects -- handlers.map(_.handledSym)
              if (!remaining.subsetOf(Symbol.PrimitiveEffs))
                Err(Invalid("Boundary effects require a default handler or must be primitive.", member.loc))
              else {
                val residual = if (handlers.isEmpty) remaining else remaining + Symbol.IO
                Ok(Wrapper(member, name, args, result, residual.toList, handlers.map(_.handlerSym)))
              }
            case _ => Err(Invalid("Boundary effects must be ground and finite.", member.loc))
          }
        } yield wrapper
    }
  }

  private def conversion(tpe: Type, trt: Symbol.TraitSym, associated: String, method: String,
                         root: TypedAst.Root, loc: SourceLocation, declared: Map[Type, (Conversion, Conversion)])(implicit flix: Flix): Result[Conversion, Error] = tpe match {
    case _ if containsRegionBound(tpe, root, Set.empty) =>
      Err(Invalid("Region-bound values cannot cross the boundary, including inside opaque or nominal types.", loc))
    case _ if declared.contains(Type.eraseAliases(tpe)) =>
      val pair = declared(Type.eraseAliases(tpe))
      Ok(if (associated == "Out") pair._1 else pair._2)
    case _ if containsDeclaredPayload(Type.eraseAliases(tpe), declared.keySet) =>
      Err(Invalid("An adapter-backed declared type cannot cross inside a container or another type. Use a monomorphic nominal enum wrapper for nested representations.", loc))
    case Type.Alias(_, _, expanded, _) => conversion(expanded, trt, associated, method, root, loc, declared)
    case _ if (tpe.baseType match {
      case Type.Cst(TypeConstructor.Enum(sym, _), _) => sym.namespace == List("Java", "Boundary") && sym.text == "Opaque"
      case _ => false
    }) =>
      val valueType = tpe.typeArguments.head
      val key = JvmTypeKey.encode(valueType, Nil)
      val name = FormatType.formatType(valueType).replace("\\", "\\\\").replace("\"", "\\\"")
      val handle = Type.mkApply(Type.mkNative(ClassDesc.of("dev.flix.runtime.OpaqueHandle"), 1, loc),
        List(Type.mkNative(ClassDesc.of("java.lang.Object"), 0, loc)), loc)
      // The unsafe cast is confined to compiler-generated code, not a public polymorphic Flix helper.
      // Unwrapping checks the stable type tag before casting to the inferred target parameter type.
      val call = if (associated == "In")
        s"(value -> boundaryUnpack(\"$key\", \"$name\", value))"
      else s"(value -> BoundaryOpaqueBridge.wrap(\"$key\", \"$name\", unchecked_cast(value as BoundaryObject)))"
      Ok(Conversion(handle, Type.IO, Some(call)))
    case _ if isDirect(tpe) => JavaBoundaryApi.validateBoundaryType(tpe)
      .mapErr(cause => Invalid(cause.message, loc)).map(_ => Conversion(tpe, Type.Pure, None))
    case _ =>
      val out = new Symbol.AssocTypeSym(trt, associated, loc)
      val effect = new Symbol.AssocTypeSym(trt, "Aef", loc)
      for {
        result <- BoundaryTypeElaborator.elaborate(out, tpe, root).mapErr(BoundaryError(_, loc))
        _ <- JavaBoundaryApi.validateBoundaryType(result).mapErr(cause => Invalid(cause.message, loc))
        eff <- BoundaryTypeElaborator.elaborate(effect, tpe, root).mapErr(BoundaryError(_, loc))
      } yield Conversion(result, eff, Some((trt.namespace :+ trt.name :+ method).mkString(".")))
  }

  private def containsDeclaredPayload(tpe: Type, declared: Set[Type]): Boolean =
    tpe.typeArguments.exists(arg => declared.contains(arg) || containsDeclaredPayload(arg, declared))

  /** Conservatively inspect nominal payloads too: Opaque[Model] must not hide a regional array. */
  private def containsRegionBound(tpe: Type, root: TypedAst.Root, visited: Set[Symbol.EnumSym]): Boolean = {
    tpe.typeConstructors.exists {
      case TypeConstructor.Array | TypeConstructor.ArrayWithoutRegion | TypeConstructor.RegionToStar |
           TypeConstructor.RegionWithoutRegion | _: TypeConstructor.Region | _: TypeConstructor.Struct => true
      case TypeConstructor.Enum(sym, _) if !visited.contains(sym) =>
        root.enums.get(sym).exists(_.cases.values.exists(_.tpes.exists(containsRegionBound(_, root, visited + sym))))
      case _ => false
    }
  }

  private def isDirect(tpe: Type): Boolean = tpe.baseType match {
    case Type.Cst(TypeConstructor.Unit | TypeConstructor.Bool | TypeConstructor.Char | TypeConstructor.Int8 |
      TypeConstructor.Int16 | TypeConstructor.Int32 | TypeConstructor.Int64 | TypeConstructor.Float32 |
      TypeConstructor.Float64 | TypeConstructor.Str | TypeConstructor.BigInt | TypeConstructor.BigDecimal | _: TypeConstructor.Native, _) => true
    case _ => false
  }

  /** Renders only validated concrete types. Conversion selection is exclusively instance-driven. */
  private class Renderer {
    private val natives = mutable.LinkedHashMap.empty[ClassDesc, String]
    def imports: List[String] = natives.iterator.map { case (desc, alias) =>
      val name = desc.descriptorString().drop(1).dropRight(1).replace('/', '.')
      val dot = name.lastIndexOf('.')
      if (dot < 0) s"    import $name as $alias"
      else s"    import ${name.take(dot)}.{${name.drop(dot + 1)} => $alias}"
    }.toList
    def render(tpe: Type): String = tpe match {
      case Type.Alias(_, _, expanded, _) => render(expanded)
      case _ => tpe.baseType match {
        case Type.Cst(TypeConstructor.Native(desc, _), _) =>
          val name = natives.getOrElseUpdate(desc, s"BoundaryNative${natives.size}")
          val args = tpe.typeArguments.map(render)
          if (args.isEmpty) name else args.mkString(s"$name[", ", ", "]")
        case Type.Cst(TypeConstructor.Unit, _) => "Unit"
        case Type.Cst(TypeConstructor.Bool, _) => "Bool"
        case Type.Cst(TypeConstructor.Char, _) => "Char"
        case Type.Cst(TypeConstructor.Int8, _) => "Int8"
        case Type.Cst(TypeConstructor.Int16, _) => "Int16"
        case Type.Cst(TypeConstructor.Int32, _) => "Int32"
        case Type.Cst(TypeConstructor.Int64, _) => "Int64"
        case Type.Cst(TypeConstructor.Float32, _) => "Float32"
        case Type.Cst(TypeConstructor.Float64, _) => "Float64"
        case Type.Cst(TypeConstructor.Str, _) => "String"
        case Type.Cst(TypeConstructor.BigInt, _) => "BigInt"
        case Type.Cst(TypeConstructor.BigDecimal, _) => "BigDecimal"
        case _ => throw new IllegalStateException("A validated boundary type has no source renderer.")
      }
    }
  }
}
