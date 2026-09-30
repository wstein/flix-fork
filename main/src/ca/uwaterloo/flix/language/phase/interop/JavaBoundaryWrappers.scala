/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.CompilationMessage
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{SecurityContext, SourceName}
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
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
  case class Declaration(className: String, members: List[Member])
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
    emit(flix, prepareContract(flix, contract, sctx))

  /** Frontend-only entry point for editor diagnostics: no bytecode or disk output. */
  def checkContract(flix: Flix, contract: JavaBoundaryContract.Contract, sctx: SecurityContext): Result[JavaBoundaryApi.Plan, Error] =
    prepareContract(flix, contract, sctx).map(_.plan)

  private def prepareContract(flix: Flix, contract: JavaBoundaryContract.Contract, sctx: SecurityContext): Result[Prepared, Error] = {
    val traits = Traits(Symbol.mkTraitSym("Java.Boundary.JavaResult"), Symbol.mkTraitSym("Java.Boundary.JavaArgument"))
    prepareValidated(flix, contract.declaration, traits, sctx,
      plan => JavaBoundaryContract.verify(contract, plan).mapErr(ContractError.apply))
  }

  private def emit(flix: Flix, prepared: Result[Prepared, Error]): Result[Output, Error] = prepared.flatMap { checked =>
    flix.codeGenWithJavaApi(checked.root, checked.declaration).mapErr(FacadeError.apply)
      .map(compiled => Output(compiled, checked.plan))
  }

  private def prepareValidated(flix: Flix, api: Declaration, traits: Traits, sctx: SecurityContext,
                               verify: JavaBoundaryApi.Plan => Result[Unit, Error]): Result[Prepared, Error] = {
    implicit val compiler: Flix = flix
    val checked = flix.check()
    if (checked._2.nonEmpty) return Err(InputErrors(checked._2, checked._2.head.loc))
    val root = checked._1.get
    val digest = MessageDigest.getInstance("SHA-256").digest(api.className.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString
    val module = "BoundaryGenerated" + digest
    val uri = URI.create(s"flix-boundary:/$module.flix")
    if (root.modules.keys.exists(_.ns == List(module)) || root.sources.keys.exists(_.sourceName == SourceName.UriName(uri)))
      return Err(Invalid("The generated boundary module or source name is already owned by the caller.", SourceLocation.Unknown))
    if (!SourceVersion.isName(api.className) || api.className.startsWith("java.") || api.className.startsWith("dev.flix.") ||
        api.members.isEmpty || api.members.map(_.name).distinct.size != api.members.size)
      return Err(Invalid("Expected a non-reserved Java class name and distinct API member names.", SourceLocation.Unknown))
    Result.traverse(api.members.zipWithIndex) { case (member, index) =>
      generateWrapper(member, s"w$index", traits, root)
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
      flix.addSource(uri, source, sctx)
      try {
        val augmented = flix.check()
        if (augmented._2.nonEmpty) {
          val diagnostic = augmented._2.head
          val loc = if (diagnostic.source.sourceName != SourceName.UriName(uri)) diagnostic.loc
          else memberLocations.getOrElse(diagnostic.loc.startLine, wrappers.head.member.loc)
          Err(WrapperErrors(augmented._2, loc))
        } else {
          val typed = augmented._1.get
          val members = wrappers.map { wrapper =>
            val sym = typed.defs.keys.find(sym => sym.namespace == List(module) && sym.text == wrapper.name).get
            val names = root.defs(wrapper.member.target).spec.fparams.toList.map(_.bnd.sym.text)
            JavaBoundaryApi.Member(wrapper.member.name, sym, names)
          }
          val declaration = JavaBoundaryApi.Declaration(api.className, members)
          for {
            plan <- JavaBoundaryApi.prepare(declaration, typed).mapErr(FacadeError.apply)
            _ <- verify(plan)
          } yield Prepared(typed, declaration, plan)
        }
      } finally flix.remSource(uri)
    }
  }

  private def generateWrapper(member: Member, name: String, traits: Traits,
                              root: TypedAst.Root)(implicit flix: Flix): Result[Wrapper, Error] = {
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
          args <- Result.traverse(if (params == List(Type.Unit)) Nil else params)(conversion(_, traits.argument, "In", "toFlix", root, member.loc))
          result <- conversion(spec.retTpe, traits.result, "Out", "toJava", root, member.loc)
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
                         root: TypedAst.Root, loc: SourceLocation)(implicit flix: Flix): Result[Conversion, Error] = tpe match {
    case Type.Alias(_, _, expanded, _) => conversion(expanded, trt, associated, method, root, loc)
    case _ if containsRegionBound(tpe, root, Set.empty) =>
      Err(Invalid("Region-bound values cannot cross the boundary, including inside opaque or nominal types.", loc))
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
