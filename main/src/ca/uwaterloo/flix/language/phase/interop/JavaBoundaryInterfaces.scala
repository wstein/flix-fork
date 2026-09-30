/*
 * Copyright 2026 Werner Stein
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.jvm.JavaType
import ca.uwaterloo.flix.language.jvm.{JavaLookupError, JavaMemberResolver}
import java.lang.constant.ConstantDescs.CD_Object
import ca.uwaterloo.flix.language.phase.jvm.JavaBoundaryApi
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}

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
            } yield if (target.ref.descriptor.descriptorString() == method.descriptor) None
            else Some(JavaBoundaryApi.Bridge(method, target.ref.descriptor))
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

  /** Fail closed rather than erasing type variables, wildcards, or arrays. */
  private def signature(tpe: JavaType): Result[String, String] = tpe match {
    case JavaType.NonGeneric(desc) if !desc.isArray => Ok(desc.descriptorString())
    case JavaType.Parameterized(desc, args) => Result.traverse(args)(signature)
      .map(_.mkString(desc.descriptorString().dropRight(1) + "<", "", ">;"))
    case _ => Err("Java interface methods require concrete boundary types; generic methods, wildcards and arrays are unsupported.")
  }
}
