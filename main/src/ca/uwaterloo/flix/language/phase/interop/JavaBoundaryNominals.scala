/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.language.phase.jvm.JvmClass
import org.objectweb.asm.{ClassWriter, Opcodes, Type}

import java.lang.constant.ClassDesc

/** Explicit nominal Java representations; conversion bodies remain ordinary checked Flix. */
object JavaBoundaryNominals {
  def helperOwner(contract: JavaBoundaryContract.Contract, index: Int): String =
    s"${JavaBoundaryProducts.module(contract)}.Nominal$index"

  def classes(contract: JavaBoundaryContract.Contract): List[JvmClass] = contract.nominals.flatMap { nominal =>
    if (!nominal.sealedType) List(enumClass(nominal)) else {
      val owner = nominal.className.replace('.', '/')
      val cw = new ClassWriter(ClassWriter.COMPUTE_MAXS)
      cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
        owner, null, "java/lang/Object", null)
      nominal.variants.foreach { variant =>
        val name = owner + "$" + variant.name
        cw.visitPermittedSubclass(name)
        cw.visitNestMember(name)
        cw.visitInnerClass(name, owner, variant.name, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
      }
      cw.visitEnd()
      JvmClass(ClassDesc.of(nominal.className), cw.toByteArray) :: nominal.variants.map { variant =>
        val product = JavaBoundaryContract.Product(s"${nominal.className}$$${variant.name}",
          variant.components, nominal.target, tuple = false, nominal.loc)
        JavaBoundaryProducts.record(product, Some(nominal.className))
      }
    }
  }

  private def enumClass(nominal: JavaBoundaryContract.Nominal): JvmClass = {
    val desc = ClassDesc.of(nominal.className)
    val name = nominal.className.replace('.', '/')
    val signature = "L" + name + ";"
    val array = "[" + signature
    val cw = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM,
      name, s"Ljava/lang/Enum<$signature>;", "java/lang/Enum", null)
    nominal.variants.foreach(v => cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
      v.name, signature, null, null).visitEnd())
    cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
      "$VALUES", array, null, null).visitEnd()
    val constructor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null)
    constructor.visitCode()
    constructor.visitVarInsn(Opcodes.ALOAD, 0)
    constructor.visitVarInsn(Opcodes.ALOAD, 1)
    constructor.visitVarInsn(Opcodes.ILOAD, 2)
    constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false)
    constructor.visitInsn(Opcodes.RETURN)
    constructor.visitMaxs(0, 0)
    constructor.visitEnd()
    val values = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()" + array, null, null)
    values.visitCode()
    values.visitFieldInsn(Opcodes.GETSTATIC, name, "$VALUES", array)
    values.visitMethodInsn(Opcodes.INVOKEVIRTUAL, array, "clone", "()Ljava/lang/Object;", false)
    values.visitTypeInsn(Opcodes.CHECKCAST, array)
    values.visitInsn(Opcodes.ARETURN)
    values.visitMaxs(0, 0)
    values.visitEnd()
    val valueOf = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)" + signature, null, null)
    valueOf.visitCode()
    valueOf.visitLdcInsn(Type.getObjectType(name))
    valueOf.visitVarInsn(Opcodes.ALOAD, 0)
    valueOf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
      "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false)
    valueOf.visitTypeInsn(Opcodes.CHECKCAST, name)
    valueOf.visitInsn(Opcodes.ARETURN)
    valueOf.visitMaxs(0, 0)
    valueOf.visitEnd()
    val init = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
    init.visitCode()
    nominal.variants.zipWithIndex.foreach { case (variant, ordinal) =>
      init.visitTypeInsn(Opcodes.NEW, name)
      init.visitInsn(Opcodes.DUP)
      init.visitLdcInsn(variant.name)
      init.visitLdcInsn(ordinal)
      init.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(Ljava/lang/String;I)V", false)
      init.visitFieldInsn(Opcodes.PUTSTATIC, name, variant.name, signature)
    }
    init.visitLdcInsn(nominal.variants.size)
    init.visitTypeInsn(Opcodes.ANEWARRAY, name)
    nominal.variants.zipWithIndex.foreach { case (variant, ordinal) =>
      init.visitInsn(Opcodes.DUP)
      init.visitLdcInsn(ordinal)
      init.visitFieldInsn(Opcodes.GETSTATIC, name, variant.name, signature)
      init.visitInsn(Opcodes.AASTORE)
    }
    init.visitFieldInsn(Opcodes.PUTSTATIC, name, "$VALUES", array)
    init.visitInsn(Opcodes.RETURN)
    init.visitMaxs(0, 0)
    init.visitEnd()
    cw.visitEnd()
    JvmClass(desc, cw.toByteArray)
  }

  def source(contract: JavaBoundaryContract.Contract, validationOnly: Boolean, shapes: Map[String, String]): String = contract.nominals.zipWithIndex.map { case (nominal, index) =>
    val jname = s"BoundaryNominal$index"
    val dot = nominal.className.lastIndexOf('.')
    val imports = List(s"import ${nominal.className.take(dot)}.{${nominal.className.drop(dot + 1)} => $jname}",
      "import dev.flix.runtime.{OpaqueHandleBridge => BoundaryChecks}", "import java.lang.IllegalArgumentException") ++
      (if (nominal.sealedType) nominal.variants.zipWithIndex.map { case (variant, vi) =>
        s"import ${nominal.className.take(dot)}.{${nominal.className.drop(dot + 1)}$$${variant.name} => ${jname}Case$vi}"
      } else Nil)
    val prefix = nominal.target.takeWhile(_ != '[')
    val outCases = nominal.variants.zipWithIndex.map { case (variant, vi) =>
      val params = variant.components.indices.map(i => s"p$i").toList
      val pattern = s"$prefix.${variant.name}" + (if (params.isEmpty) "" else params.mkString("(", ", ", ")"))
      val body = if (!nominal.sealedType) s"$jname.valueOf(\"${variant.name}\")" else {
        val args = params.zip(variant.components).map { case (value, field) =>
          if (field.tpe.desc.isPrimitive) value else s"Java.Boundary.JavaResult.toJava($value)"
        }
        s"checked_cast(new ${jname}Case$vi${args.mkString("(", ", ", ")")})"
      }
      s"case $pattern => $body"
    }.mkString("\n")
    val inBody = if (!nominal.sealedType) {
      val cases = nominal.variants.map(v => s"case \"${v.name}\" => $prefix.${v.name}").mkString("\n")
      s"match x.name() { $cases case _ => throw new IllegalArgumentException(\"Unknown ${nominal.className} constant\") }"
    } else {
      val cases = nominal.variants.zipWithIndex.map { case (variant, vi) =>
        val checks = variant.components.filterNot(_.tpe.desc.isPrimitive).map { field =>
          val path = s"${nominal.className}.${variant.name}.${field.name}"
          s"BoundaryChecks.checkArgument(value.${field.name}(), \"$path\", \"${shapes.getOrElse(path, "!")}\");"
        }.mkString
        val args = variant.components.map { field =>
          val value = s"value.${field.name}()"
          if (field.tpe.desc.isPrimitive) value else s"Java.Boundary.JavaArgument.toFlix($value)"
        }
        val body = s"$prefix.${variant.name}" + (if (args.isEmpty) "" else args.mkString("(", ", ", ")"))
        s"if (x instanceof ${jname}Case$vi) { let value = unchecked_cast(x as ${jname}Case$vi); $checks $body } else "
      }.mkString
      cases + s"throw new IllegalArgumentException(\"Unknown ${nominal.className} variant\")"
    }
    val head = if (nominal.adapted) s"Adapter$index" else nominal.target
    val outBody = if (nominal.adapted) s"match a { case Adapter$index.Adapter$index(x) => match x { $outCases } }"
      else s"match a { $outCases }"
    val argumentBody = if (nominal.adapted) s"Adapter$index.Adapter$index($inBody)" else inBody
    val definitions = s"""
      |instance Java.Boundary.JavaResult[$head] {
      |    type Out = $jname
      |    type Aef = IO
      |    pub def toJava(${if (validationOnly) "_a" else "a"}: $head): $jname \\ IO = ${if (validationOnly) "checked_ecast(bug!(\"validation-only boundary declaration\"))" else outBody}
      |}
      |instance Java.Boundary.JavaArgument[$head] {
      |    type In = $jname
      |    type Aef = IO
      |    pub def toFlix(${if (validationOnly) "_x" else "x"}: $jname): $head \\ IO = ${if (validationOnly) "checked_ecast(bug!(\"validation-only boundary declaration\"))" else argumentBody}
      |}
      |""".stripMargin
    val helpers = if (!nominal.adapted) "" else s"""
      |pub def out(x: ${nominal.target}): $jname \\ IO = Java.Boundary.JavaResult.toJava(Adapter$index.Adapter$index(x))
      |pub def in(x: $jname): ${nominal.target} \\ IO = match Java.Boundary.JavaArgument.toFlix(x) {
      |    case Adapter$index.Adapter$index(value) => value
      |}
      |""".stripMargin
    // Keep generated instances and helpers separate from caller-owned companion modules.
    val owner = helperOwner(contract, index)
    val adapter = if (nominal.adapted) s"enum Adapter$index { case Adapter$index(${nominal.target}) }" else ""
    val payload = s"pub def boundaryPayload(x: ${nominal.target}): ${nominal.target} = x"
    s"\npub mod $owner {\n${imports.mkString("\n")}\n$adapter\n$definitions\n$helpers\n$payload\n}\n"
  }.mkString
}
