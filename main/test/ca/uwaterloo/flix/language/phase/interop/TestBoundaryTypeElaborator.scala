/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package ca.uwaterloo.flix.language.phase.interop

import ca.uwaterloo.flix.TestUtils
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{Kind, SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.{EqualityConstraint, RegionScope, TraitConstraint}
import ca.uwaterloo.flix.language.ast.shared.SymUse.{AssocTypeSymUse, TraitSymUse}
import ca.uwaterloo.flix.language.errors.ResolutionError
import ca.uwaterloo.flix.language.phase.interop.BoundaryTypeElaborator.*
import ca.uwaterloo.flix.language.phase.typer.ConstraintSolver2
import ca.uwaterloo.flix.language.phase.unification.{EqualityEnv, TraitEnv}
import ca.uwaterloo.flix.runtime.JvmLoader
import ca.uwaterloo.flix.util.{Options, Result}
import org.scalatest.funsuite.AnyFunSuite

import java.lang.constant.ClassDesc
import java.nio.file.Paths

class TestBoundaryTypeElaborator extends AnyFunSuite with TestUtils {
  private val loc = SourceLocation.Unknown
  private val resultSym = Symbol.mkTraitSym("Proof.JavaResult")
  private val outSym = new Symbol.AssocTypeSym(resultSym, "Out", loc)
  private val effSym = new Symbol.AssocTypeSym(resultSym, "Aef", loc)

  private val source =
    """pub mod Proof {
      |    import java.lang.Integer
      |    import java.util.{List => JList}
      |
      |    pub trait JavaResult[t: Type] {
      |        type Out[t]: Type
      |        type Aef[t]: Eff = {}
      |        pub def toJava(x: t): JavaResult.Out[t] \ JavaResult.Aef[t]
      |    }
      |    instance JavaResult[Int32] {
      |        type Out = Integer
      |        pub def toJava(x: Int32): Integer = Int32.valueOf(x)
      |    }
      |    instance JavaResult[List[a]] with JavaResult[a] {
      |        type Out = JList[JavaResult.Out[a]]
      |        type Aef = IO + JavaResult.Aef[a]
      |        pub def toJava(xs: List[a]): JList[JavaResult.Out[a]] \ (IO + JavaResult.Aef[a]) =
      |            Adaptor.toList(List.map(JavaResult.toJava, xs))
      |    }
      |    pub enum Token { case Token(Int32) }
      |    instance JavaResult[Token] {
      |        type Out = Integer
      |        type Aef = IO
      |        pub def toJava(x: Token): Integer \ IO = match x {
      |            case Token.Token(n) => println(n); Int32.valueOf(n)
      |        }
      |    }
      |    pub def ints(): List[Int32] = 1 :: 2 :: 3 :: Nil
      |    pub def nested(): List[List[Int32]] = (1 :: Nil) :: Nil
      |    pub def tokens(): List[Token] = Token.Token(1) :: Nil
      |    pub def strings(): List[String] = "missing" :: Nil
      |    pub type alias Ints = List[Int32]
      |    pub def aliased(): Ints = 1 :: Nil
      |}
      |""".stripMargin

  private lazy val fixture: (Flix, TypedAst.Root) = {
    val flix = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("Proof.flix"), source, sctx)
    val checked = flix.check()
    expectSuccess(checked)
    (flix, checked._1.get)
  }

  private def declaredResult(name: String): Type =
    fixture._2.defs.values.find(_.sym.toString == s"Proof.$name").get.spec.retTpe

  private def native(name: String, args: List[Type]): Type =
    Type.mkApply(Type.mkNative(ClassDesc.of(name), args.length, loc), args, loc)

  test("List[Int32] derives List<Integer> through checked instances") {
    implicit val flix: Flix = fixture._1
    val expected = native("java.util.List", List(native("java.lang.Integer", Nil)))
    assert(elaborate(outSym, declaredResult("ints"), fixture._2) == Result.Ok(expected))
    assert(elaborate(effSym, declaredResult("ints"), fixture._2) == Result.Ok(Type.IO))
  }

  test("nested lists retain every Java generic argument") {
    implicit val flix: Flix = fixture._1
    val expected = native("java.util.List", List(native("java.util.List", List(native("java.lang.Integer", Nil)))))
    assert(elaborate(outSym, declaredResult("nested"), fixture._2) == Result.Ok(expected))
    val eff = elaborate(effSym, declaredResult("nested"), fixture._2).unsafeGet
    assert(ConstraintSolver2.isEquivalent(eff, Type.IO)(EqualityEnv.empty, flix))
  }

  test("a wrapper using the derived concrete type rechecks and converts elements at runtime") {
    implicit val flix: Flix = fixture._1
    val derived = elaborate(outSym, declaredResult("ints"), fixture._2).unsafeGet
    val effect = elaborate(effSym, declaredResult("ints"), fixture._2).unsafeGet
    assert(effect == Type.IO)

    // Render native types generically, without a second table selecting conversion types.
    val imports = scala.collection.mutable.LinkedHashMap.empty[ClassDesc, String]
    def render(tpe: Type): String = tpe.baseType match {
      case Type.Cst(TypeConstructor.Native(desc, _), _) =>
        val name = imports.getOrElseUpdate(desc, s"BoundaryNative${imports.size}")
        val args = tpe.typeArguments.map(render)
        if (args.isEmpty) name else args.mkString(s"$name[", ", ", "]")
      case _ => fail(s"unexpected non-native proof result: $tpe")
    }
    val signature = render(derived)
    val importLines = imports.iterator.map { case (desc, name) =>
      val fullName = desc.descriptorString().drop(1).dropRight(1).replace('/', '.')
      val dot = fullName.lastIndexOf('.')
      s"import ${fullName.take(dot)}.{${fullName.drop(dot + 1)} => $name}"
    }.mkString("\n")
    val wrapper = s"""pub mod Wrapper {
                     |    $importLines
                     |    pub def ints(): $signature \\ IO = Proof.JavaResult.toJava(Proof.ints())
                     |    @Test
                     |    pub def converted(): Unit \\ (IO + Assert) = {
                     |        let xs = ints();
                     |        Assert.assertEq(expected = 3, xs.size());
                     |        Assert.assertEq(expected = 1, xs.get(0).intValue());
                     |        Assert.assertEq(expected = 2, xs.get(1).intValue());
                     |        Assert.assertEq(expected = 3, xs.get(2).intValue())
                     |    }
                     |}
                     |""".stripMargin
    val compiler = new Flix().setOptions(Options.TestWithLibAll.copy(xchaosMonkey = false))
      .addSource(Paths.get("Proof.flix"), source, sctx)
      .addSource(Paths.get("Wrapper.flix"), wrapper, sctx)
    val checked = compiler.check()
    expectSuccess(checked)
    val root = checked._1.get
    assert(root.defs.values.find(_.sym.toString == "Wrapper.ints").get.spec.retTpe == derived)
    val tests = JvmLoader.load(compiler.codeGen(root)).tests
    assert(tests.size == 1)
    tests.values.foreach(_.run())
  }

  test("source aliases are transparent to boundary instance selection") {
    implicit val flix: Flix = fixture._1
    assert(elaborate(outSym, declaredResult("aliased"), fixture._2) ==
      elaborate(outSym, declaredResult("ints"), fixture._2))
  }

  test("user-defined instances select the boundary type and conversion effect") {
    implicit val flix: Flix = fixture._1
    val token = declaredResult("tokens").typeArguments.head
    val expected = native("java.lang.Integer", Nil)
    assert(elaborate(outSym, token, fixture._2) == Result.Ok(expected))
    assert(elaborate(effSym, token, fixture._2) == Result.Ok(Type.IO))
    assert(elaborate(outSym, declaredResult("tokens"), fixture._2) ==
      Result.Ok(native("java.util.List", List(expected))))
  }

  test("a missing element instance is rejected before choosing an ABI") {
    implicit val flix: Flix = fixture._1
    assert(elaborate(outSym, declaredResult("strings"), fixture._2) == Result.Err(MissingInstance(resultSym, Type.Str)))
  }

  test("constraints are checked even when an associated type ignores the element") {
    implicit val flix: Flix = fixture._1
    val strings = declaredResult("strings")
    val listInstance = fixture._2.traitEnv.getInstance(resultSym, strings).get
    val eqenv = fixture._2.eqEnv.addAssocTypeDef(outSym, listInstance.tpe, native("java.lang.Integer", Nil))
    assert(elaborate(outSym, strings, fixture._2.copy(eqEnv = eqenv)) == Result.Err(MissingInstance(resultSym, Type.Str)))
  }

  test("an unresolved associated type is rejected rather than erased to Object") {
    implicit val flix: Flix = fixture._1
    assert(elaborate(outSym, Type.Int32, fixture._2.copy(eqEnv = EqualityEnv.empty)) ==
      Result.Err(UnresolvedAssociatedType(outSym, Type.Int32)))
  }

  test("recursive associated types are rejected explicitly") {
    implicit val flix: Flix = fixture._1
    val self = Type.AssocType(AssocTypeSymUse(outSym, loc), Type.Int32, Kind.Star, loc)
    val eqenv = fixture._2.eqEnv.addAssocTypeDef(outSym, Type.Int32, self)
    assert(elaborate(outSym, Type.Int32, fixture._2.copy(eqEnv = eqenv)) ==
      Result.Err(RecursiveAssociatedType(outSym, Type.Int32)))
  }

  test("mutually recursive projections terminate with an error") {
    implicit val flix: Flix = fixture._1
    val other = new Symbol.AssocTypeSym(resultSym, "Other", loc)
    val self = Type.AssocType(AssocTypeSymUse(outSym, loc), Type.Int32, Kind.Star, loc)
    val cycle = Type.AssocType(AssocTypeSymUse(other, loc), Type.Int32, Kind.Star, loc)
    val eqenv = fixture._2.eqEnv.addAssocTypeDef(outSym, Type.Int32, cycle).addAssocTypeDef(other, Type.Int32, self)
    assert(elaborate(outSym, Type.Int32, fixture._2.copy(eqEnv = eqenv)) ==
      Result.Err(RecursiveAssociatedType(outSym, Type.Int32)))
  }

  test("growing associated-type recursion is bounded") {
    implicit val flix: Flix = fixture._1
    val list = declaredResult("ints")
    val defn = fixture._2.eqEnv.getAssocDef(outSym, list).get
    val growing = Type.Apply(list.baseType, defn.arg, loc)
    val query = Type.AssocType(AssocTypeSymUse(outSym, loc), growing, Kind.Star, loc)
    val eqenv = fixture._2.eqEnv.addAssocTypeDef(outSym, defn.arg, query)
    elaborate(outSym, list, fixture._2.copy(eqEnv = eqenv)) match {
      case Result.Err(_: ReductionLimit) => ()
      case other => fail(s"expected a bounded reduction failure, found $other")
    }
  }

  test("recursive instance evidence is rejected") {
    implicit val flix: Flix = fixture._1
    val root = fixture._2
    val contexts = root.traitEnv.toMap
    val context = contexts(resultSym)
    val head = ca.uwaterloo.flix.language.ast.TypeHead.fromType(Type.Int32).get
    val inst = context.instances(head)
    val self = TraitConstraint(TraitSymUse(resultSym, loc), Type.Int32, loc)
    val tenv = TraitEnv(contexts + (resultSym -> context.copy(instances = context.instances + (head -> inst.copy(tconstrs = List(self))))))
    assert(elaborate(outSym, Type.Int32, root.copy(traitEnv = tenv)) ==
      Result.Err(RecursiveInstance(resultSym, Type.Int32)))
  }

  test("unbound type variables are not accepted as a concrete API") {
    implicit val flix: Flix = fixture._1
    val arg = Type.freshVar(Kind.Star, loc)(RegionScope.Top, flix)
    assert(elaborate(outSym, arg, fixture._2) == Result.Err(NonConcreteType(arg)))
  }

  test("instance equality constraints must also hold") {
    implicit val flix: Flix = fixture._1
    val root = fixture._2
    val contexts = root.traitEnv.toMap
    val context = contexts(resultSym)
    val list = declaredResult("ints")
    val head = ca.uwaterloo.flix.language.ast.TypeHead.fromType(list).get
    val inst = context.instances(head)
    val element = inst.tpe.typeArguments.head
    val mismatch = EqualityConstraint(AssocTypeSymUse(effSym, loc), element, Type.IO, loc)
    val tenv = TraitEnv(contexts + (resultSym -> context.copy(instances = context.instances + (head -> inst.copy(econstrs = List(mismatch))))))
    val expected = mismatch.copy(tpe1 = Type.Int32)
    assert(elaborate(outSym, list, root.copy(traitEnv = tenv)) == Result.Err(UnsatisfiedEquality(expected)))
  }

  test("satisfied instance equality constraints are accepted") {
    implicit val flix: Flix = fixture._1
    val root = fixture._2
    val contexts = root.traitEnv.toMap
    val context = contexts(resultSym)
    val list = declaredResult("ints")
    val head = ca.uwaterloo.flix.language.ast.TypeHead.fromType(list).get
    val inst = context.instances(head)
    val equality = EqualityConstraint(AssocTypeSymUse(effSym, loc), inst.tpe.typeArguments.head, Type.Pure, loc)
    val tenv = TraitEnv(contexts + (resultSym -> context.copy(instances = context.instances + (head -> inst.copy(econstrs = List(equality))))))
    assert(elaborate(outSym, list, root.copy(traitEnv = tenv)) ==
      Result.Ok(native("java.util.List", List(native("java.lang.Integer", Nil)))))
  }

  test("kind-invalid associated results are rejected") {
    implicit val flix: Flix = fixture._1
    val eqenv = fixture._2.eqEnv.addAssocTypeDef(outSym, Type.Int32, Type.IO)
    assert(elaborate(outSym, Type.Int32, fixture._2.copy(eqEnv = eqenv)) ==
      Result.Err(UnexpectedKind(Type.IO, Kind.Star)))
  }

  test("surface concrete associated-type applications remain rejected") {
    val input = """trait C[a] { type T[a]: Type }
                  |def foo(): C.T[String] = ???
                  |""".stripMargin
    expectError[ResolutionError.IllegalAssocTypeApplication](check(input, Options.TestWithLibNix))
  }

  test("surface self-referential associated-type definitions remain rejected") {
    val input = """trait C[a] { type T[a]: Type }
                  |instance C[String] { type T[String] = C.T[String] }
                  |""".stripMargin
    expectError[ResolutionError.IllegalAssocTypeApplication](check(input, Options.TestWithLibNix))
  }
}
