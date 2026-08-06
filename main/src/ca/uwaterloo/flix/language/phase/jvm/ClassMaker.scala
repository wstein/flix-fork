/*
 * Copyright 2021 Jonathan Lindegaard Starup
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

package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.api.{CompilerConstants, Flix}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.*
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Abstract.{IsAbstract, NotAbstract}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Final.*
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Interface.{IsInterface, NotInterface}
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Static.*
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Visibility.*
import ca.uwaterloo.flix.language.phase.jvm.ClassMaker.Volatility.*
import ca.uwaterloo.flix.language.ast.shared.JvmAnnotation
import ca.uwaterloo.flix.language.phase.jvm.JvmName.MethodDescriptor
import org.objectweb.asm.{ClassWriter, MethodVisitor, Opcodes}


// TODO: There are further things you can constrain and assert, e.g. final classes have implicitly final methods.
sealed trait ClassMaker {
  def mkStaticConstructor(c: StaticConstructorMethod, ins: MethodVisitor => Unit): Unit = {
    makeMethod(Nil, Some(ins), c.name, c.d, IsDefault, NotFinal, IsStatic, NotAbstract)
  }

  /**
    * Closes the class maker.
    * This should be the last function called on the class maker.
    */
  def closeClassMaker(): Array[Byte] = {
    visitor.visitEnd()
    visitor.toByteArray
  }

  protected val visitor: ClassWriter

  protected def makeField(fieldName: String, fieldType: BackendType, v: Visibility, f: Final, vol: Volatility, s: Static, signature: Option[String], extraFlags: Int = 0): Unit = {
    val m = v.toInt + f.toInt + s.toInt + vol.toInt + extraFlags
    val field = visitor.visitField(m, fieldName, fieldType.toDescriptor, signature.orNull, null)
    field.visitEnd()
  }

  def mkField(field: Field, v: Visibility, f: Final, vol: Volatility, signature: Option[String] = None): Unit = field match {
    case InstanceField(_, name, tpe) => makeField(name, tpe, v, f, vol, NotStatic, signature)
    case StaticField(_, name, tpe) => makeField(name, tpe, v, f, vol, IsStatic, signature)
  }

  /**
    * Declares `field` to be one of the constants of an enum class.
    *
    * `ACC_ENUM` is what tells reflection that this field is a constant rather than an ordinary
    * static of the same type -- `Field.isEnumConstant` reads it, and so does a Java compiler
    * deciding whether the field may appear as a `switch` label. The constant is otherwise an
    * ordinary `public static final`, so omitting the flag produces a class that works until
    * someone reflects over it or switches on it.
    */
  def mkEnumConstant(field: StaticField): Unit =
    makeField(field.name, field.tpe, IsPublic, IsFinal, NotVolatile, IsStatic, None, Opcodes.ACC_ENUM)

  /** Declares a field the compiler generated rather than the source named, such as an enum's `$VALUES`. */
  def mkSyntheticField(field: Field, v: Visibility, f: Final): Unit = field match {
    case InstanceField(_, name, tpe) => makeField(name, tpe, v, f, NotVolatile, NotStatic, None, Opcodes.ACC_SYNTHETIC)
    case StaticField(_, name, tpe) => makeField(name, tpe, v, f, NotVolatile, IsStatic, None, Opcodes.ACC_SYNTHETIC)
  }

  /**
    * Declares `field` to be a record component of this class.
    *
    * A record class is not merely a class with final fields: the `Record` attribute this writes is
    * what `java.lang.Class.isRecord` reports and what `ObjectMethods.bootstrap` validates before it
    * will derive `equals`, `hashCode` or `toString`. Without it the three bootstrapped methods fail
    * to link at first call rather than at class load, so omitting it produces a class that verifies
    * and then throws.
    */
  def mkRecordComponent(field: InstanceField, signature: Option[String] = None): Unit =
    visitor.visitRecordComponent(field.name, field.tpe.toDescriptor, signature.orNull).visitEnd()

  protected def makeMethod(ann: List[JvmAnnotation], i: Option[MethodVisitor => Unit], methodName: String, d: MethodDescriptor, v: Visibility, f: Final, s: Static, a: Abstract, signature: Option[String] = None): Unit = {
    val m = v.toInt + f.toInt + s.toInt + a.toInt
    // The signature is the descriptor plus the type arguments the descriptor erases. Java only
    // warns when it is absent, but Scala and Kotlin reject the raw type it leaves behind.
    val mv = visitor.visitMethod(m, methodName, d.toDescriptor, signature.orNull, null)
    for (a <- ann) {
      val descriptor = JvmName.ofClass(a.clazz).toDescriptor
      val retention = a.clazz.getAnnotation(classOf[java.lang.annotation.Retention])
      val visible = retention != null && retention.value() == java.lang.annotation.RetentionPolicy.RUNTIME
      val av = mv.visitAnnotation(descriptor, visible)
      av.visitEnd()
    }
    i match {
      case None => ()
      case Some(ins) =>
        mv.visitCode()
        ins(mv)
        mv.visitMaxs(999, 999)
    }
    mv.visitEnd()
  }

  protected def makeAbstractMethod(methodName: String, d: MethodDescriptor): Unit = {
    makeMethod(Nil, None, methodName, d, IsPublic, NotFinal, NotStatic, IsAbstract)
  }
}

object ClassMaker {

  class InstanceClassMaker(cw: ClassWriter) extends ClassMaker {
    protected val visitor: ClassWriter = cw

    def mkStaticMethod(m: StaticMethod, v: Visibility, f: Final, ins: MethodVisitor => Unit, signature: Option[String] = None): Unit = {
      makeMethod(Nil, Some(ins), m.name, m.d, v, f, IsStatic, NotAbstract, signature)
    }

    def mkConstructor(c: ConstructorMethod, v: Visibility, ins: MethodVisitor => Unit, signature: Option[String] = None): Unit = {
      makeMethod(Nil, Some(ins), JvmName.ConstructorMethod, c.d, v, NotFinal, NotStatic, NotAbstract, signature)
    }

    def mkMethod(ann: List[JvmAnnotation], m: InstanceMethod, v: Visibility, f: Final, ins: MethodVisitor => Unit, signature: Option[String] = None): Unit = {
      makeMethod(ann, Some(ins), m.name, m.d, v, f, NotStatic, NotAbstract, signature)
    }
  }

  class AbstractClassMaker(cw: ClassWriter) extends ClassMaker {
    protected val visitor: ClassWriter = cw

    def mkConstructor(c: ConstructorMethod, v: Visibility, ins: MethodVisitor => Unit): Unit = {
      makeMethod(Nil, Some(ins), c.name, c.d, v, NotFinal, NotStatic, NotAbstract)
    }

    def mkStaticMethod(m: StaticMethod, v: Visibility, f: Final, ins: MethodVisitor => Unit): Unit = {
      makeMethod(Nil, Some(ins), m.name, m.d, v, f, IsStatic, NotAbstract)
    }

    def mkMethod(m: InstanceMethod, v: Visibility, f: Final, ins: MethodVisitor => Unit): Unit = {
      makeMethod(Nil, Some(ins), m.name, m.d, v, f, NotStatic, NotAbstract)
    }

    def mkAbstractMethod(m: AbstractMethod): Unit = {
      makeAbstractMethod(m.name, m.d)
    }
  }

  class InterfaceMaker(cw: ClassWriter) extends ClassMaker {
    protected val visitor: ClassWriter = cw

    def mkInterfaceMethod(m: InterfaceMethod): Unit = {
      makeAbstractMethod(m.name, m.d)
    }

    def mkStaticInterfaceMethod(m: StaticInterfaceMethod, v: Visibility, f: Final, ins: MethodVisitor => Unit): Unit = {
      makeMethod(Nil, Some(ins), m.name, m.d, v, f, IsStatic, NotAbstract)
    }

    def mkDefaultMethod(m: DefaultMethod, v: Visibility, f: Final, ins: MethodVisitor => Unit): Unit = {
      makeMethod(Nil, Some(ins), m.name, m.d, v, f, NotStatic, NotAbstract)
    }
  }

  def mkClass(className: JvmName, f: Final, superClass: JvmName = JvmName.Object, interfaces: List[JvmName] = Nil, signature: Option[String] = None)(implicit flix: Flix): InstanceClassMaker = {
    new InstanceClassMaker(mkClassWriter(className, IsPublic, f, NotAbstract, NotInterface, superClass, interfaces, signature))
  }

  def mkAbstractClass(className: JvmName, superClass: JvmName = JvmName.Object, interfaces: List[JvmName] = Nil, signature: Option[String] = None)(implicit flix: Flix): AbstractClassMaker = {
    new AbstractClassMaker(mkClassWriter(className, IsPublic, NotFinal, IsAbstract, NotInterface, superClass, interfaces, signature))
  }

  /**
    * Writes an enum class, i.e. a final subclass of `java.lang.Enum` carrying `ACC_ENUM`.
    *
    * The flag is not decoration. `Class.isEnum` is defined as having it *and* extending
    * `java.lang.Enum`, and `Enum.valueOf`, `EnumSet` and `EnumMap` all refuse a class that fails
    * that test. Writing the constants and the `values()` method without it produces a class that
    * looks like an enum to a reader and is not one to the runtime.
    *
    * `signature` is where the self-referential type argument goes: an enum `Color` extends
    * `Enum<Color>`, which the descriptor cannot say.
    */
  def mkEnumClass(className: JvmName, signature: Option[String])(implicit flix: Flix): InstanceClassMaker = {
    new InstanceClassMaker(mkClassWriter(className, IsPublic, IsFinal, NotAbstract, NotInterface, JvmName.Enum, Nil, signature, Opcodes.ACC_ENUM))
  }

  def mkInterface(interfaceName: JvmName, interfaces: List[JvmName] = Nil, signature: Option[String] = None)(implicit flix: Flix): InterfaceMaker = {
    new InterfaceMaker(mkClassWriter(interfaceName, IsPublic, NotFinal, IsAbstract, IsInterface, JvmName.Object, interfaces, signature))
  }

  /**
    * Writes the class header.
    *
    * `signature` is the class's generic signature, and exists for the same reason a method's does:
    * a descriptor cannot express a type argument, so a class implementing `java.util.List<T>`
    * without one implements it *raw*, which is a hard error in Scala 3 and Kotlin rather than a
    * warning. The virtual machine ignores it; compilers and reflection read it.
    *
    * `None` for almost every class the backend generates, and that is not an oversight. These
    * classes exist *after* erasure, so every type argument they could declare is already `Object`
    * -- `Fn1$Obj$Obj` implements `Function`, and saying `Function<Object, Object>` instead adds
    * nothing a caller can use.
    *
    * The exception is a class that declares type *parameters* rather than consuming arguments:
    * `dev.flix.runtime.Tuple2<T1, T2>` is generic because the shim returning it supplies the
    * arguments in its own signature, and a class with no signature cannot be parameterized at all.
    * The rule is therefore not "after erasure, never" but "wherever an argument is still known" --
    * which is here and in the method signature of an exported shim.
    */
  private def mkClassWriter(name: JvmName, v: Visibility, f: Final, a: Abstract, i: Interface, superClass: JvmName, interfaces: List[JvmName], signature: Option[String], extraFlags: Int = 0)(implicit flix: Flix): ClassWriter = {
    val cw = AsmOps.mkClassWriter()
    val m = v.toInt + f.toInt + a.toInt + i.toInt + extraFlags
    cw.visit(CompilerConstants.JvmTargetVersion, m, name.toInternalName, signature.orNull, superClass.toInternalName, interfaces.map(_.toInternalName).toArray)
    cw.visitSource(name.toInternalName, null)
    cw
  }

  sealed trait Visibility {
    val toInt: Int = this match {
      case IsPrivate => Opcodes.ACC_PRIVATE
      case IsDefault => 0
      case IsPublic => Opcodes.ACC_PUBLIC
    }
  }

  object Visibility {
    case object IsPrivate extends Visibility

    case object IsDefault extends Visibility

    case object IsPublic extends Visibility
  }


  sealed trait Final {
    val toInt: Int = this match {
      case IsFinal => Opcodes.ACC_FINAL
      case NotFinal => 0
    }
  }

  object Final {
    case object IsFinal extends Final

    case object NotFinal extends Final
  }

  sealed trait Static {
    val toInt: Int = this match {
      case IsStatic => Opcodes.ACC_STATIC
      case NotStatic => 0
    }
  }

  object Static {
    case object IsStatic extends Static

    case object NotStatic extends Static
  }

  sealed trait Volatility {
    val toInt: Int = this match {
      case IsVolatile => Opcodes.ACC_VOLATILE
      case NotVolatile => 0
    }
  }

  object Volatility {
    case object IsVolatile extends Volatility

    case object NotVolatile extends Volatility
  }

  sealed trait Abstract {
    val toInt: Int = this match {
      case Abstract.IsAbstract => Opcodes.ACC_ABSTRACT
      case Abstract.NotAbstract => 0
    }
  }

  object Abstract {
    case object IsAbstract extends Abstract

    case object NotAbstract extends Abstract
  }

  sealed trait Interface {
    val toInt: Int = this match {
      case Interface.IsInterface => Opcodes.ACC_INTERFACE
      case Interface.NotInterface => 0
    }
  }

  object Interface {
    case object IsInterface extends Interface

    case object NotInterface extends Interface
  }

  sealed trait Field {
    def clazz: JvmName

    def name: String

    def tpe: BackendType
  }

  sealed case class InstanceField(clazz: JvmName, name: String, tpe: BackendType) extends Field

  sealed case class StaticField(clazz: JvmName, name: String, tpe: BackendType) extends Field

  sealed trait Method {
    def clazz: JvmName

    def name: String

    def d: MethodDescriptor
  }

  sealed case class ConstructorMethod(clazz: JvmName, args: List[BackendType]) extends Method {
    override def name: String = JvmName.ConstructorMethod

    override def d: MethodDescriptor = MethodDescriptor(args, VoidableType.Void)
  }

  case class StaticConstructorMethod(clazz: JvmName) extends Method {
    override def name: String = JvmName.StaticConstructorMethod

    override def d: MethodDescriptor = MethodDescriptor.NothingToVoid
  }

  sealed case class InstanceMethod(clazz: JvmName, name: String, d: MethodDescriptor) extends Method {
    def implementation(clazz: JvmName): InstanceMethod = InstanceMethod(clazz, name, d)
  }

  sealed case class DefaultMethod(clazz: JvmName, name: String, d: MethodDescriptor) extends Method

  sealed case class InterfaceMethod(clazz: JvmName, name: String, d: MethodDescriptor) extends Method {
    def implementation(clazz: JvmName): InstanceMethod = InstanceMethod(clazz, name, d)
  }

  sealed case class AbstractMethod(clazz: JvmName, name: String, d: MethodDescriptor) extends Method {
    def implementation(clazz: JvmName): InstanceMethod = InstanceMethod(clazz, name, d)
  }

  sealed case class StaticMethod(clazz: JvmName, name: String, d: MethodDescriptor) extends Method

  sealed case class StaticInterfaceMethod(clazz: JvmName, name: String, d: MethodDescriptor) extends Method

}
