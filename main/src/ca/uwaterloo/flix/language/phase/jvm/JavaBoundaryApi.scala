/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.{ConstructorMethod, InstanceField}
import ca.uwaterloo.flix.language.phase.jvm.Instructions.*
import ca.uwaterloo.flix.language.phase.jvm.classes.{GenResult, GenUnit}
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.collection.CofiniteSet
import org.objectweb.asm.{MethodVisitor, Opcodes}

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.*
import java.util.Locale
import javax.lang.model.SourceVersion

/**
  * Experimental named API for already checked, concrete boundary wrappers (ADR 3).
  * No conversions are implemented in bytecode. Source syntax, automatic wrapper generation,
  * default effect handlers, and pre-type-check cyclic-build stubs are separate integration gates.
  */
object JavaBoundaryApi {
  case class Member(name: String, wrapper: Symbol.DefnSym, argumentNames: List[String] = Nil)
  case class Declaration(className: String, members: List[Member], loc: SourceLocation = SourceLocation.Unknown)
  case class Error(message: String, loc: SourceLocation)
  case class JavaType(desc: ClassDesc, signature: String)
  case class Method(member: Member, defn: TypedAst.Def, args: List[JavaType], result: JavaType, nullary: Boolean) {
    def descriptor: String = args.map(_.desc.descriptorString()).mkString("(", "", ")") + result.desc.descriptorString()
    def signature: String = args.map(_.signature).mkString("(", "", ")") + result.signature
  }
  final class Plan private[jvm] (val name: ClassDesc, val methods: List[Method], val loc: SourceLocation) {
    def entryPoints: Set[Symbol.DefnSym] = methods.map(_.member.wrapper).toSet
  }

  /** Requires a successfully checked root, including instance validation. Records types before erasure. */
  def prepare(api: Declaration, root: TypedAst.Root)(implicit flix: Flix): Result[Plan, Error] = {
    if (!SourceVersion.isName(api.className) || api.className.startsWith("dev.flix.") || api.className.startsWith("java."))
      return Err(Error("Invalid or reserved Java API class name.", api.loc))
    if (api.members.isEmpty || api.members.map(_.name).distinct.size != api.members.size)
      return Err(Error("An API needs members with distinct Java method names.", api.loc))
    Result.traverse(api.members) { member =>
      root.defs.get(member.wrapper) match {
        case None => Err(Error("Unknown boundary wrapper.", member.wrapper.loc))
        case Some(defn) => prepareMethod(member, defn)
      }
    }.map(methods => new Plan(ClassDesc.of(api.className), methods, api.loc))
  }

  private def prepareMethod(member: Member, defn: TypedAst.Def)(implicit flix: Flix): Result[Method, Error] = {
    val spec = defn.spec
    if (!SourceVersion.isIdentifier(member.name) || SourceVersion.isKeyword(member.name))
      return Err(Error("Invalid Java API method name.", defn.loc))
    if (!spec.mod.isPublic || spec.tparams.nonEmpty || spec.tconstrs.nonEmpty || spec.econstrs.nonEmpty)
      return Err(Error("A boundary wrapper must be public, monomorphic, and unconstrained.", defn.loc))
    Type.eval(spec.eff) match {
      case Ok(CofiniteSet.Set(effects)) if effects.subsetOf(Symbol.PrimitiveEffs) => ()
      case _ => return Err(Error("API wrappers must handle non-primitive effects before forwarding to Java.", defn.loc))
    }
    val params = spec.fparams.toList.map(_.tpe)
    val nullary = params == List(Type.Unit)
    for {
      args <- Result.traverse(if (nullary) Nil else params)(javaType(_, false))
      ret <- if (spec.retTpe == Type.Unit) Ok(JavaType(CD_void, "V")) else javaType(spec.retTpe, false)
    } yield Method(member, defn, args, ret, nullary)
  }

  /** Shared fail-closed representation check for generated wrapper signatures. */
  private[flix] def validateBoundaryType(tpe: Type): Result[Unit, Error] =
    if (tpe == Type.Unit) Ok(()) else javaType(tpe, false).map(_ => ())

  /** Java type arguments must already be boxed. Never silently erase unsupported types to Object. */
  private def javaType(tpe: Type, argument: Boolean): Result[JavaType, Error] = tpe match {
    case Type.Alias(_, _, expanded, _) => javaType(expanded, argument)
    case _ =>
      val prim = tpe.typeConstructor.flatMap {
        case TypeConstructor.Bool => Some(CD_boolean)
        case TypeConstructor.Char => Some(CD_char)
        case TypeConstructor.Int8 => Some(CD_byte)
        case TypeConstructor.Int16 => Some(CD_short)
        case TypeConstructor.Int32 => Some(CD_int)
        case TypeConstructor.Int64 => Some(CD_long)
        case TypeConstructor.Float32 => Some(CD_float)
        case TypeConstructor.Float64 => Some(CD_double)
        case _ => None
      }
      prim match {
        case Some(desc) if !argument => Ok(JavaType(desc, desc.descriptorString()))
        case Some(_) => Err(Error("A Java generic argument must be boxed by its boundary instance.", tpe.loc))
        case None if tpe == Type.Str => Ok(JavaType(CD_String, CD_String.descriptorString()))
        case None if tpe == Type.BigInt =>
          val desc = ClassDesc.of("java.math.BigInteger")
          Ok(JavaType(desc, desc.descriptorString()))
        case None if tpe == Type.BigDecimal =>
          val desc = ClassDesc.of("java.math.BigDecimal")
          Ok(JavaType(desc, desc.descriptorString()))
        case None => tpe.baseType match {
          case Type.Cst(TypeConstructor.Native(desc, arity), _) if tpe.typeArguments.size == arity =>
            Result.traverse(tpe.typeArguments)(javaType(_, true)).map { args =>
              val signature = if (args.isEmpty) desc.descriptorString()
              else desc.descriptorString().dropRight(1) + args.map(_.signature).mkString("<", "", ">;")
              JavaType(desc, signature)
            }
          case _ => Err(Error("Expected a concrete Java boundary type; generate and check a conversion wrapper first.", tpe.loc))
        }
      }
  }

  /** Uses exactly the recorded descriptor and signature, but has no dependency on Flix runtime classes. */
  def stub(plan: Plan)(implicit flix: Flix): JvmClass = generate(plan, true)

  /** Call only within the compilation's JVM-origin scope, after wrapper code generation. */
  def facade(plan: Plan, classes: Map[ClassDesc, JvmClass])(implicit flix: Flix): Result[JvmClass, Error] = {
    val folded = plan.name.descriptorString().toLowerCase(Locale.ROOT)
    if (classes.keys.exists(_.descriptorString().toLowerCase(Locale.ROOT) == folded))
      Err(Error("The Java API class collides with a generated class (including case-only collisions).", plan.loc))
    else Ok(generate(plan, false))
  }

  private def generate(plan: Plan, stubOnly: Boolean)(implicit flix: Flix): JvmClass = {
    val cw = ClassMaker.mkClassWriter()
    val owner = plan.name.descriptorString().drop(1).dropRight(1)
    cw.visit(CompilerConstants.JvmTargetVersion, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
      owner, null, "java/lang/Object", null)
    plan.methods.foreach { method =>
      implicit val mv: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
        method.member.name, method.descriptor, method.signature, null)
      mv.visitCode()
      if (stubOnly) {
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException")
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false)
        mv.visitInsn(Opcodes.ATHROW)
      } else {
        var offset = 0
        method.args.zipWithIndex.foreach { case (arg, index) =>
          if (!arg.desc.isPrimitive) {
            xLoad(arg.desc, offset)
            mv.visitLdcInsn(method.member.argumentNames.lift(index).getOrElse(s"p$index"))
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/flix/runtime/OpaqueHandleBridge", "checkArgument",
              "(Ljava/lang/Object;Ljava/lang/String;)V", false)
          }
          offset += (if (arg.desc == CD_long || arg.desc == CD_double) 2 else 1)
        }
        forward(method)
      }
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }
    cw.visitEnd()
    JvmClass(plan.name, cw.toByteArray)
  }

  /** Only calling convention glue: instantiate the compiled wrapper and unwind its normal result. */
  private def forward(method: Method)(implicit mv: MethodVisitor, flix: Flix): Unit = {
    val target = GenFunAndClosureClasses.defnDesc(method.member.wrapper)
    NEW(target)
    DUP()
    INVOKESPECIAL(ConstructorMethod(target, Nil))
    if (method.nullary) {
      DUP()
      GETSTATIC(GenUnit.SingletonField)
      PUTFIELD(InstanceField(target, "arg0", CD_Object))
    } else {
      var offset = 0
      method.args.zipWithIndex.foreach { case (arg, index) =>
        DUP()
        xLoad(arg.desc, offset)
        PUTFIELD(InstanceField(target, s"arg$index", if (arg.desc.isPrimitive) arg.desc else CD_Object))
        offset += (if (arg.desc == CD_long || arg.desc == CD_double) 2 else 1)
      }
    }
    if (method.result.desc == CD_void) {
      GenResult.unwindSuspensionFreeThunk("in Java boundary", method.defn.loc)
      POP()
      RETURN()
    } else {
      GenResult.unwindSuspensionFreeThunkToType(method.result.desc, "in Java boundary", method.defn.loc)
      xReturn(method.result.desc)
    }
  }
}
