/*
 * Copyright 2026 Werner Stein
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.SourceLocation
import ca.uwaterloo.flix.language.ast.jvm.{JavaMethod, JavaType, JavaTypeVariable}
import ca.uwaterloo.flix.language.jvm.{JavaLookupError, JavaMemberResolver}
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}

import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Object
import scala.annotation.tailrec

/** Validate implementations against Java-owned classfiles, without loading an interface. */
object JavaBoundaryInterfaces {
  def verify(plan: JavaBoundaryApi.Plan)(implicit flix: Flix): Result[List[JavaBoundaryApi.Bridge], JavaBoundaryApi.Error] = {
    val owner = plan.interfaceName match {
      case None => return Ok(Nil)
      case Some(desc) => desc
    }
    def invalid(message: String) = JavaBoundaryApi.Error(message, plan.loc)
    for {
      clazz <- flix.javaTypeProvider.lookupClass(owner).mapErr(e => invalid(s"Unable to resolve Java interface: ${e.explanation}"))
      _ <- if (!clazz.isInterface || clazz.isAnnotation || !clazz.isPublic || clazz.isSealed)
        Err(invalid("An implementation requires a public, non-sealed Java interface, not a class or annotation."))
      else if (clazz.typeParameters.nonEmpty) Err(invalid("A Java interface implementation requires a concrete, non-generic interface."))
      else if (owner == plan.name) Err(invalid("The implementation class must differ from its Java interface."))
      else Ok(())
      _ <- flix.javaTypeProvider.lookupClass(plan.name) match {
        case Err(_: JavaLookupError.MissingClass) => Ok(())
        case Ok(_) => Err(invalid("The implementation class already exists on the Java classpath."))
        case Err(cause) => Err(invalid(cause.explanation))
      }
      objectClass <- flix.javaTypeProvider.lookupClass(CD_Object).mapErr(e => invalid(e.explanation))
      hierarchy <- inheritedMethods(owner, plan.loc)
      _ <- checkDefaults(hierarchy, plan)
      methods <- JavaMemberResolver.instanceMethods(owner).mapErr(e => invalid(s"Unable to resolve Java interface methods: ${e.explanation}"))
      _ <- {
        val keys = plan.methods.map(m => m.member.name -> m.args.map(_.desc))
        if (keys.distinct.size != keys.size) Err(invalid("Duplicate Java interface method implementations.")) else Ok(())
      }
      bridges <- Result.traverse(plan.methods) { method =>
        val loc = if (method.member.loc.isReal) method.member.loc else plan.loc
        def error(message: String) = JavaBoundaryApi.Error(message, loc)
        val candidates = methods.filter(m => !m.isFinal && m.ref.name == method.member.name &&
          m.parameterTypes.map(_.erasure) == method.args.map(_.desc))
        candidates match {
          case target :: Nil if target.typeParameters.isEmpty =>
            for {
              args <- Result.traverse(target.parameterTypes)(signature).mapErr(error)
              result <- signature(target.returnType).mapErr(error)
              _ <- if (args.mkString("(", "", ")") + result == method.signature) Ok(())
              else Err(error(s"Java interface signature mismatch for '${method.member.name}': expected ${args.mkString("(", "", ")") + result}, actual ${method.signature}."))
              declarations = (target :: hierarchy.filter(m => !m.isBridge && !m.isSynthetic &&
                methodKey(m) == methodKey(target))).distinctBy(_.ref)
              required <- Result.traverse(declarations) { declaration =>
                for {
                  inheritedArgs <- Result.traverse(declaration.parameterTypes)(signature).mapErr(error)
                  _ <- signature(declaration.returnType).mapErr(error)
                  compatible <- covariantReturn(target.returnType, declaration.returnType).mapErr(error)
                  _ <- if (declaration.typeParameters.isEmpty && inheritedArgs == args && compatible) Ok(())
                  else Err(error(s"Incompatible inherited Java interface signature for '${method.member.name}'."))
                } yield declaration.ref.descriptor
              }
            } yield required.distinct.filterNot(_.descriptorString() == method.descriptor)
              .map(JavaBoundaryApi.Bridge(method, _))
          case _ => Err(error(s"No unique, non-generic Java interface method matches '${method.member.name}${method.descriptor}'."))
        }
      }
      _ <- {
        val implemented = plan.methods.map(m => m.member.name -> m.args.map(_.desc)).toSet
        val inherited = objectClass.declaredMethods.filter(m => m.isPublic && !m.isStatic)
          .map(m => m.ref.name -> m.ref.descriptor).toSet
        val missing = methods.filter(_.isAbstract).filterNot(m =>
          implemented(m.ref.name -> m.parameterTypes.map(_.erasure)) || inherited(m.ref.name -> m.ref.descriptor))
        if (missing.isEmpty) Ok(())
        else Err(invalid("Missing Java interface implementations: " + missing.map(m => m.ref.name + m.ref.descriptor.descriptorString()).sorted.mkString(", ") + "."))
      }
      _ <- {
        val keys = plan.methods.map(m => m.member.name -> m.descriptor) ++
          bridges.flatten.map(b => b.method.member.name -> b.descriptor.descriptorString())
        if (keys.distinct.size == keys.size) Ok(())
        else Err(invalid("Java interface bridge descriptors collide with another implementation."))
      }
    } yield bridges.flatten
  }

  /** Keep declaration provenance and every descriptor; a method-graph representative loses both. */
  private def inheritedMethods(owner: ClassDesc, loc: SourceLocation)(implicit flix: Flix): Result[List[JavaMethod], JavaBoundaryApi.Error] = {
    @tailrec
    def visit(pending: List[(JavaType, Set[ClassDesc])], seen: Map[ClassDesc, JavaType],
              methods: List[JavaMethod]): Result[List[JavaMethod], JavaBoundaryApi.Error] = pending match {
      case Nil => Ok(methods.reverse)
      case (tpe, path) :: rest =>
        val desc = tpe.erasure
        if (path.contains(desc)) return Err(JavaBoundaryApi.Error("Cyclic Java interface inheritance.", loc))
        seen.get(desc) match {
          case Some(previous) if previous == tpe => visit(rest, seen, methods)
          case Some(_) => Err(JavaBoundaryApi.Error("Incompatible inherited Java interface instantiations.", loc))
          case None => flix.javaTypeProvider.lookupClass(desc) match {
            case Err(cause) => Err(JavaBoundaryApi.Error(cause.explanation, loc))
            case Ok(clazz) =>
              if (!clazz.isInterface) return Err(JavaBoundaryApi.Error("A Java interface inherits a non-interface type.", loc))
              val args = tpe match { case JavaType.Parameterized(_, actual) => actual; case _ => Nil }
              if (args.nonEmpty && args.size != clazz.typeParameters.size)
                return Err(JavaBoundaryApi.Error("Invalid inherited Java interface type arguments.", loc))
              val bindings = clazz.typeParameters.map(_.variable).zip(args).toMap
              val declared = clazz.declaredMethods.filter(m => m.isPublic && !m.isStatic).map { method =>
                method.copy(parameterTypes = method.parameterTypes.map(substitute(_, bindings)),
                  returnType = substitute(method.returnType, bindings))
              }
              val parents = clazz.interfaces.map(t => substitute(t, bindings) -> (path + desc))
              visit(parents ::: rest, seen + (desc -> tpe), declared.reverse ::: methods)
          }
        }
    }
    visit(List(JavaType.NonGeneric(owner) -> Set.empty[ClassDesc]), Map.empty, Nil)
  }

  private def substitute(tpe: JavaType, bindings: Map[JavaTypeVariable, JavaType]): JavaType = tpe match {
    case JavaType.Variable(variable, _) => bindings.getOrElse(variable, tpe)
    case JavaType.Parameterized(desc, args) => JavaType.Parameterized(desc, args.map(substitute(_, bindings)))
    case JavaType.GenericArray(component, _) =>
      val actual = substitute(component, bindings)
      JavaType.GenericArray(actual, actual.erasure.arrayType())
    case JavaType.Wildcard(upper, lower, desc) =>
      JavaType.Wildcard(upper.map(substitute(_, bindings)), lower.map(substitute(_, bindings)), desc)
    case _ => tpe
  }

  private def methodKey(method: JavaMethod): (String, List[ClassDesc]) =
    method.ref.name -> method.parameterTypes.map(_.erasure)

  private def checkDefaults(methods: List[JavaMethod], plan: JavaBoundaryApi.Plan)
                           (implicit flix: Flix): Result[Unit, JavaBoundaryApi.Error] = {
    val implemented = plan.methods.map(m => m.member.name -> m.args.map(_.desc)).toSet
    val groups = methods.filter(m => !m.isBridge && !m.isSynthetic).groupBy(methodKey).toList
      .sortBy { case ((name, args), _) => name + args.map(_.descriptorString()).mkString }
    Result.traverse(groups) { case (key, candidates) =>
      if (implemented(key)) Ok(())
      else Result.traverse(candidates) { method =>
        Result.traverse(candidates) { other =>
          if (other.ref.owner == method.ref.owner) Ok(false)
          else flix.javaTypeProvider.isSubtype(other.ref.owner, method.ref.owner)
            .mapErr(e => JavaBoundaryApi.Error(e.explanation, plan.loc))
        }.map(dominated => if (dominated.exists(identity)) None else Some(method))
      }.flatMap { maximal =>
        val defaults = maximal.flatten.filterNot(_.isAbstract).map(_.ref.owner).distinct
        if (defaults.size <= 1) Ok(())
        else Err(JavaBoundaryApi.Error(s"Conflicting inherited defaults require an explicit implementation: ${key._1}.", plan.loc))
      }
    }.map(_ => ())
  }

  /** Reference returns may narrow, but generic arguments remain invariant. */
  private def covariantReturn(actual: JavaType, expected: JavaType)
                             (implicit flix: Flix): Result[Boolean, String] = {
    def visit(current: JavaType, path: Set[ClassDesc]): Result[Boolean, String] = {
      if (current == expected) Ok(true)
      else if (current.erasure.isPrimitive || expected.erasure.isPrimitive) Ok(false)
      else expected match {
        case JavaType.NonGeneric(desc) => flix.javaTypeProvider.isSubtype(current.erasure, desc).mapErr(_.explanation)
        case JavaType.Parameterized(desc, _) if current.erasure == desc || path(current.erasure) => Ok(false)
        case JavaType.Parameterized(_, _) =>
          flix.javaTypeProvider.lookupClass(current.erasure).mapErr(_.explanation).flatMap { clazz =>
            val args = current match { case JavaType.Parameterized(_, values) => values; case _ => Nil }
            val bindings = clazz.typeParameters.map(_.variable).zip(args).toMap
            Result.traverse(clazz.superClass.toList ::: clazz.interfaces) { parent =>
              visit(substitute(parent, bindings), path + current.erasure)
            }.map(_.exists(identity))
          }
        case _ => Ok(false)
      }
    }
    visit(actual, Set.empty)
  }

  /** Fail closed rather than erasing type variables, wildcards, or arrays. */
  private def signature(tpe: JavaType): Result[String, String] = tpe match {
    case JavaType.NonGeneric(desc) if !desc.isArray => Ok(desc.descriptorString())
    case JavaType.Parameterized(desc, args) => Result.traverse(args)(signature)
      .map(_.mkString(desc.descriptorString().dropRight(1) + "<", "", ">;"))
    case _ => Err("Java interface methods require concrete boundary types; generic methods, wildcards and arrays are unsupported.")
  }
}
