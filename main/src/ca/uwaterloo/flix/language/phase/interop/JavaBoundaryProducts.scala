/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.language.phase.jvm.JvmClass
import org.objectweb.asm.{ClassWriter, Handle, Opcodes, Type}

import java.lang.constant.ClassDesc
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The same real record classfiles describe synthetic metadata, bootstrap stubs and runtime output. */
object JavaBoundaryProducts {
  def module(contract: JavaBoundaryContract.Contract): String = {
    val hash = MessageDigest.getInstance("SHA-256").digest(contract.className.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x").mkString
    "BoundaryTypes" + hash
  }

  def classes(contract: JavaBoundaryContract.Contract): List[JvmClass] =
    contract.products.map(record) ++ JavaBoundaryNominals.classes(contract)

  private def record(product: JavaBoundaryContract.Product): JvmClass = record(product, None)

  private[interop] def record(product: JavaBoundaryContract.Product, outer: Option[String]): JvmClass = {
    val desc = ClassDesc.of(product.className)
    val name = desc.descriptorString().drop(1).dropRight(1)
    val cw = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_RECORD,
      name, null, "java/lang/Record", outer.map(_.replace('.', '/')).toArray)
    outer.foreach { owner =>
      cw.visitNestHost(owner.replace('.', '/'))
      cw.visitInnerClass(name, owner.replace('.', '/'), product.className.drop(owner.length + 1),
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
    }
    product.components.foreach { field =>
      val descriptor = field.tpe.desc.descriptorString()
      val signature = if (descriptor == field.tpe.signature) null else field.tpe.signature
      cw.visitRecordComponent(field.name, descriptor, signature).visitEnd()
      cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, field.name, descriptor, signature, null).visitEnd()
      val accessor = cw.visitMethod(Opcodes.ACC_PUBLIC, field.name, "()" + descriptor,
        if (signature == null) null else "()" + signature, null)
      accessor.visitCode()
      accessor.visitVarInsn(Opcodes.ALOAD, 0)
      accessor.visitFieldInsn(Opcodes.GETFIELD, name, field.name, descriptor)
      accessor.visitInsn(Type.getType(descriptor).getOpcode(Opcodes.IRETURN))
      accessor.visitMaxs(0, 0)
      accessor.visitEnd()
    }
    val constructorDescriptor = product.components.map(_.tpe.desc.descriptorString()).mkString("(", "", ")V")
    val constructorSignature = product.components.map(_.tpe.signature).mkString("(", "", ")V")
    val constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructorDescriptor,
      if (constructorDescriptor == constructorSignature) null else constructorSignature, null)
    product.components.foreach(field => constructor.visitParameter(field.name, 0))
    constructor.visitCode()
    constructor.visitVarInsn(Opcodes.ALOAD, 0)
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Record", "<init>", "()V", false)
    var slot = 1
    product.components.foreach { field =>
      val descriptor = field.tpe.desc.descriptorString()
      val tpe = Type.getType(descriptor)
      constructor.visitVarInsn(Opcodes.ALOAD, 0)
      constructor.visitVarInsn(tpe.getOpcode(Opcodes.ILOAD), slot)
      constructor.visitFieldInsn(Opcodes.PUTFIELD, name, field.name, descriptor)
      slot += tpe.getSize
    }
    constructor.visitInsn(Opcodes.RETURN)
    constructor.visitMaxs(0, 0)
    constructor.visitEnd()

    // JDK's record bootstrap supplies exactly Java record equality, hashing and display semantics.
    val bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/runtime/ObjectMethods", "bootstrap",
      "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;", false)
    val arguments: List[AnyRef] = List(Type.getObjectType(name), product.components.map(_.name).mkString(";")) ++
      product.components.map(field => new Handle(Opcodes.H_GETFIELD, name, field.name, field.tpe.desc.descriptorString(), false))
    List(("equals", "(Ljava/lang/Object;)Z", "Z"), ("hashCode", "()I", "I"),
      ("toString", "()Ljava/lang/String;", "Ljava/lang/String;")).foreach { case (method, descriptor, result) =>
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, method, descriptor, null, null)
      mv.visitCode()
      mv.visitVarInsn(Opcodes.ALOAD, 0)
      if (method == "equals") mv.visitVarInsn(Opcodes.ALOAD, 1)
      val parameters = if (method == "equals") "Ljava/lang/Object;" else ""
      mv.visitInvokeDynamicInsn(method, s"(L$name;$parameters)$result", bootstrap, arguments*)
      mv.visitInsn(Type.getType(result).getOpcode(Opcodes.IRETURN))
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }
    cw.visitEnd()
    JvmClass(desc, cw.toByteArray)
  }

  /** Private nominal heads avoid changing global instance legality, selection or overlap rules. */
  def source(contract: JavaBoundaryContract.Contract): String = source(contract, validationOnly = false)

  def source(contract: JavaBoundaryContract.Contract, validationOnly: Boolean): String = {
    source(contract, validationOnly, Map.empty)
  }

  def source(contract: JavaBoundaryContract.Contract, validationOnly: Boolean, shapes: Map[String, String]): String = {
    val owner = module(contract)
    val imports = contract.products.zipWithIndex.map { case (product, index) =>
      val dot = product.className.lastIndexOf('.')
      s"    import ${product.className.take(dot)}.{${product.className.drop(dot + 1)} => J$index}"
    } :+ "    import dev.flix.runtime.{OpaqueHandleBridge => BoundaryChecks}"
    val definitions = contract.products.zipWithIndex.map { case (product, index) =>
      val parameters = product.components.indices.map(i => s"p$i").toList
      val from = if (product.tuple) s"let ${parameters.mkString("(", ", ", ")")} = x; " else ""
      val original = if (product.tuple) parameters else product.components.map(field => s"x#${field.name}")
      val out = original.zip(product.components).map { case (value, field) =>
        if (field.tpe.desc.isPrimitive) value else s"Java.Boundary.JavaResult.toJava($value)"
      }
      val in = product.components.map { field =>
        val value = s"x.${field.name}()"
        if (field.tpe.desc.isPrimitive) value else s"Java.Boundary.JavaArgument.toFlix($value)"
      }
      val rebuilt = if (product.tuple) in.mkString("(", ", ", ")")
      else product.components.zip(in).map { case (field, value) => s"${field.name} = $value" }.mkString("{ ", ", ", " }")
      val checks = product.components.filterNot(_.tpe.desc.isPrimitive).map { field =>
        val path = s"${product.className}.${field.name}"
        s"BoundaryChecks.checkArgument(x.${field.name}(), \"$path\", \"${shapes.getOrElse(path, "!")}\"); "
      }.mkString
      val outBody = if (validationOnly) "checked_ecast(bug!(\"validation-only boundary declaration\"))" else s"match a { case A$index.A$index(x) => ${from}new J$index${out.mkString("(", ", ", ")")} }"
      val inBody = if (validationOnly) "checked_ecast(bug!(\"validation-only boundary declaration\"))" else s"${checks}A$index.A$index($rebuilt)"
      s"""    enum A$index { case A$index(${product.target}) }
         |    instance Java.Boundary.JavaResult[A$index] {
         |        type Out = J$index
         |        type Aef = IO
         |        pub def toJava(${if (validationOnly) "_a" else "a"}: A$index): J$index \\ IO = $outBody
         |    }
         |    instance Java.Boundary.JavaArgument[A$index] {
         |        type In = J$index
         |        type Aef = IO
         |        pub def toFlix(${if (validationOnly) "_x" else "x"}: J$index): A$index \\ IO = $inBody
         |    }
         |    pub def out$index(x: ${product.target}): J$index \\ IO = Java.Boundary.JavaResult.toJava(A$index.A$index(x))
         |    pub def in$index(x: J$index): ${product.target} \\ IO = match Java.Boundary.JavaArgument.toFlix(x) {
         |        case A$index.A$index(value) => value
         |    }
         |""".stripMargin
    }
    (List(s"pub mod $owner {") ++ imports ++ definitions ++ List("}")).mkString("\n") + JavaBoundaryNominals.source(contract, validationOnly, shapes)
  }
}
