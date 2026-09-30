/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */

package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.language.ast.{SourceLocation, SourcePosition, Symbol}
import ca.uwaterloo.flix.language.ast.shared.Source
import ca.uwaterloo.flix.language.phase.jvm.{JavaBoundaryApi, JvmClass}
import ca.uwaterloo.flix.util.Result
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import org.objectweb.asm.{ClassWriter, Opcodes}

import java.lang.constant.ClassDesc
import javax.lang.model.SourceVersion
import java.util.Locale

/** Syntax-only, explicit bootstrap ABI. Checked instance reductions must agree before codegen. */
object JavaBoundaryContract {
  case class Member(name: String, target: Symbol.DefnSym, args: List[JavaBoundaryApi.JavaType],
                    result: JavaBoundaryApi.JavaType, loc: SourceLocation) {
    def descriptor: String = args.map(_.desc.descriptorString()).mkString("(", "", ")") + result.desc.descriptorString()
    def signature: String = args.map(_.signature).mkString("(", "", ")") + result.signature
  }
  case class Component(name: String, tpe: JavaBoundaryApi.JavaType)
  case class Product(className: String, components: List[Component], target: String, tuple: Boolean, loc: SourceLocation)
  case class Variant(name: String, components: List[Component])
  case class Nominal(className: String, target: String, variants: List[Variant], sealedType: Boolean, loc: SourceLocation) {
    def adapted: Boolean = target.contains('[')
    def classNames: List[String] = className :: (if (sealedType) variants.map(v => s"$className$$${v.name}") else Nil)
  }
  case class Contract(className: String, members: List[Member], loc: SourceLocation, products: List[Product], nominals: List[Nominal]) {
    def declaration: JavaBoundaryWrappers.Declaration = JavaBoundaryWrappers.Declaration(className,
      members.map(member => JavaBoundaryWrappers.Member(member.name, member.target, member.loc)), loc)
  }
  object Contract {
    def apply(className: String, members: List[Member], loc: SourceLocation): Contract =
      new Contract(className, members, loc, Nil, Nil)
    def apply(className: String, members: List[Member], loc: SourceLocation, products: List[Product]): Contract =
      new Contract(className, members, loc, products, Nil)
  }
  case class Error(message: String, loc: SourceLocation)

  /** A separate .flix-api source reserves no ordinary Flix syntax and requires no Java classpath. */
  def parse(source: Source): Result[Contract, Error] = new Parser(source).parse()

  /** This is the same ABI gate for bootstrap stubs and the recorded experimental API. */
  def verify(contract: Contract, plan: JavaBoundaryApi.Plan): Result[Unit, Error] = {
    val actualClass = plan.name.descriptorString().drop(1).dropRight(1).replace('/', '.')
    val classDiff = if (contract.className == actualClass) Nil else List(s"class: expected ${contract.className}, actual $actualClass")
    val actual = plan.methods.map(method => method.member.name -> method).toMap
    val methodDiffs = contract.members.flatMap { member => actual.get(member.name) match {
      case None => List(s"${member.name}: missing actual method")
      case Some(method) =>
        List("descriptor" -> (member.descriptor, method.descriptor), "signature" -> (member.signature, method.signature))
          .collect { case (field, (expected, found)) if expected != found => s"${member.name} $field: expected $expected, actual $found" }
    }}
    val extra = actual.keySet.diff(contract.members.map(_.name).toSet).toList.sorted.map(name => s"$name: unexpected actual method")
    val differences = classDiff ++ methodDiffs ++ extra
    if (differences.isEmpty) Ok(()) else {
      val loc = contract.members.find(member => actual.get(member.name).forall(method =>
        member.descriptor != method.descriptor || member.signature != method.signature)).map(_.loc).getOrElse(contract.loc)
      Err(Error("Java API contract mismatch:\n" + differences.mkString("\n"), loc))
    }
  }

  /** API-only bytecode from syntax, usable even when the Flix program's Java imports do not exist. */
  def stub(contract: Contract): JvmClass = {
    val cw = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    val name = ClassDesc.of(contract.className)
    cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
      name.descriptorString().drop(1).dropRight(1), null, "java/lang/Object", null)
    contract.members.foreach { member =>
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, member.name, member.descriptor, member.signature, null)
      mv.visitCode()
      mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException")
      mv.visitInsn(Opcodes.DUP)
      mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false)
      mv.visitInsn(Opcodes.ATHROW)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }
    cw.visitEnd()
    JvmClass(name, cw.toByteArray)
  }

  private case class Token(text: String, offset: Int)
  private class ParseFailure(val error: Error) extends RuntimeException
  private class Parser(source: Source) {
    private val text = new String(source.data)
    private val tokenPattern = "//[^\\r\\n]*|\\s+|\"[^\"\\r\\n]*\"|[A-Za-z_$][A-Za-z0-9_.$]*|->|[{}():,;=\\[\\]]|.".r
    private val tokens = tokenPattern.findAllMatchIn(text).filterNot(m => m.matched.startsWith("//") || m.matched.forall(_.isWhitespace))
      .map(m => Token(m.matched, m.start)).toVector :+ Token("<eof>", text.length)
    private var index = 0
    private def current: Token = tokens(index)
    private def take(): Token = { val token = current; if (index < tokens.size - 1) index += 1; token }
    private def accept(value: String): Boolean = if (current.text == value) { take(); true } else false
    private def expect(value: String): Unit = if (!accept(value)) abort(s"Expected '$value', found '${current.text}'.")
    private def location(token: Token): SourceLocation = {
      val prefix = text.take(token.offset)
      val line = prefix.count(_ == '\n') + 1
      val col = token.offset - prefix.lastIndexOf('\n')
      val start = SourcePosition(line, math.min(col, Short.MaxValue.toInt).toShort)
      val end = SourcePosition(line, math.min(col + token.text.length, Short.MaxValue.toInt).toShort)
      SourceLocation(isReal = true, source, start, end)
    }
    private def abort(message: String): Nothing = throw new ParseFailure(Error(message, location(current)))
    private def identifier(): String = {
      val value = current.text
      if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) abort("Expected a Flix name.")
      take(); value
    }
    def parse(): Result[Contract, Error] = try {
      val loc = location(current)
      expect("export"); expect("mod")
      val module = identifier()
      expect("as")
      val literal = take().text
      if (!literal.startsWith("\"") || !literal.endsWith("\"")) abort("Expected a quoted Java class name.")
      val className = literal.drop(1).dropRight(1)
      if (!SourceVersion.isName(className) || className.startsWith("java.") || className.startsWith("dev.flix."))
        abort("Invalid or reserved Java API class name.")
      expect("{")
      val members = List.newBuilder[Member]
      val products = List.newBuilder[Product]
      val nominals = List.newBuilder[Nominal]
      while (current.text != "}" && current.text != "<eof>") {
        val memberLoc = location(current)
        if (current.text == "record" || current.text == "tuple") {
          val tuple = take().text == "tuple"
          val name = take().text
          if (!SourceVersion.isName(name) || !name.contains('.') || name.startsWith("java.") || name.startsWith("dev.flix."))
            abort("Expected a non-reserved, fully qualified generated Java class name.")
          expect("(")
          val fields = List.newBuilder[Component]
          def field(): Unit = {
            val label = take().text
            val reserved = Set("clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait")
            if (!SourceVersion.isIdentifier(label) || SourceVersion.isKeyword(label) || reserved.contains(label))
              abort("Invalid Java record component name.")
            expect(":")
            fields += Component(label, javaType(false, false, 0))
          }
          if (!accept(")")) {
            field()
            while (accept(",")) field()
            expect(")")
          }
          expect("=")
          val target = flixType(module, 0)
          expect(";")
          val components = fields.result()
          if (components.map(_.name).distinct.size != components.size) abort("Duplicate record component name.")
          if (tuple && components.size < 2) abort("A tuple declaration needs at least two components.")
          products += Product(name, components, target, tuple, memberLoc)
        } else if (current.text == "enum" || current.text == "sealed") {
          val sealedType = take().text == "sealed"
          val name = take().text
          if (!SourceVersion.isName(name) || !name.contains('.') || name.contains('$') || name.startsWith("java.") || name.startsWith("dev.flix."))
            abort("Expected a non-reserved, fully qualified generated Java class name.")
          expect("=")
          val target = flixType(module, 0)
          if (target.startsWith("(")) abort("An enum declaration requires a nominal Flix type.")
          expect("{")
          val variants = List.newBuilder[Variant]
          while (current.text != "}" && current.text != "<eof>") {
            expect("case")
            val variant = identifier()
            if (variant.contains('.') || !variant.head.isUpper || !SourceVersion.isIdentifier(variant) || SourceVersion.isKeyword(variant))
              abort("Expected a simple, capitalized Java variant name.")
            val fields = List.newBuilder[Component]
            if (accept("(")) {
              if (!sealedType) abort("A data-free Java enum cannot have payload components.")
              if (!accept(")")) {
                def field(): Unit = {
                  val label = take().text
                  if (!SourceVersion.isIdentifier(label) || SourceVersion.isKeyword(label) ||
                      Set("clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait").contains(label))
                    abort("Invalid Java record component name.")
                  expect(":")
                  fields += Component(label, javaType(false, false, 0))
                }
                field()
                while (accept(",")) field()
                expect(")")
              }
            }
            expect(";")
            val components = fields.result()
            if (components.map(_.name).distinct.size != components.size) abort("Duplicate variant component name.")
            variants += Variant(variant, components)
          }
          expect("}"); expect(";")
          val cases = variants.result()
          if (cases.isEmpty || cases.map(_.name).distinct.size != cases.size) abort("Expected distinct, nonempty enum cases.")
          nominals += Nominal(name, target, cases, sealedType, memberLoc)
        } else {
        expect("def")
        val name = identifier()
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) abort("Expected a Java method name.")
        val target = if (accept("=")) identifier() else name
        expect(":"); expect("(")
        val args = List.newBuilder[JavaBoundaryApi.JavaType]
        if (!accept(")")) {
          args += javaType(false, false, 0)
          while (accept(",")) args += javaType(false, false, 0)
          expect(")")
        }
        expect("->")
        val result = javaType(true, false, 0)
        expect(";")
        val targetName = if (target.contains('.')) target else s"$module.$target"
        members += Member(name, Symbol.mkDefnSym(targetName), args.result(), result, memberLoc)
        }
      }
      expect("}"); expect("<eof>")
      val result = members.result()
      if (result.isEmpty || result.map(_.name).distinct.size != result.size) abort("Expected distinct, nonempty API members.")
      val types = products.result()
      val enums = nominals.result()
      val names = (className :: (types.map(_.className) ++ enums.flatMap(_.classNames))).map(_.toLowerCase(Locale.ROOT))
      if (names.distinct.size != names.size) abort("Generated Java class names collide, including case-only collisions.")
      val targets = types.map(_.target) ++ enums.map(_.target)
      if (targets.distinct.size != targets.size) abort("Each Flix type may have only one declared Java representation per contract.")
      Ok(Contract(className, result, loc, types, enums))
    } catch { case failure: ParseFailure => Err(failure.error) }

    /** A small, injection-free concrete Flix type grammar, not arbitrary generated source text. */
    private def flixType(module: String, depth: Int): String = {
      if (depth >= 128) abort("Flix boundary type nesting exceeds 128 levels.")
      if (accept("(")) {
        val args = List.newBuilder[String]
        args += flixType(module, depth + 1)
        while (accept(",")) args += flixType(module, depth + 1)
        expect(")")
        val values = args.result()
        if (values.size < 2) abort("Expected a concrete tuple type.")
        values.mkString("(", ", ", ")")
      } else {
        val name = identifier()
        if (!name.split('.').last.head.isUpper) abort("Expected a concrete Flix type, not a type variable.")
        val builtins = Set("Unit", "Bool", "Char", "Int8", "Int16", "Int32", "Int64", "Float32", "Float64",
          "String", "BigInt", "BigDecimal", "List", "Option", "Vector", "Chain", "Set", "Map")
        val qualified = if (name.contains('.') || builtins.contains(name)) name else s"$module.$name"
        if (!accept("[")) qualified else {
          val args = List.newBuilder[String]
          args += flixType(module, depth + 1)
          while (accept(",")) args += flixType(module, depth + 1)
          expect("]")
          args.result().mkString(s"$qualified[", ", ", "]")
        }
      }
    }

    private def javaType(result: Boolean, generic: Boolean, depth: Int): JavaBoundaryApi.JavaType = {
      if (depth >= 128) abort("Java API type nesting exceeds 128 levels.")
      val name = take().text
      val primitive = Map("boolean" -> "Z", "char" -> "C", "byte" -> "B", "short" -> "S", "int" -> "I",
        "long" -> "J", "float" -> "F", "double" -> "D", "void" -> "V").get(name)
      primitive match {
        case Some(descriptor) =>
          if (generic || (descriptor == "V" && !result)) abort("Primitives cannot be generic arguments; void is result-only.")
          JavaBoundaryApi.JavaType(ClassDesc.ofDescriptor(descriptor), descriptor)
        case None =>
          if (!SourceVersion.isName(name) || !name.contains('.')) abort("Expected a fully qualified Java class name or primitive.")
          val desc = ClassDesc.of(name)
          val signature = if (!accept("[")) desc.descriptorString() else {
            val args = List.newBuilder[JavaBoundaryApi.JavaType]
            args += javaType(false, true, depth + 1)
            while (accept(",")) args += javaType(false, true, depth + 1)
            expect("]")
            desc.descriptorString().dropRight(1) + args.result().map(_.signature).mkString("<", "", ">;")
          }
          JavaBoundaryApi.JavaType(desc, signature)
      }
    }
  }
}
