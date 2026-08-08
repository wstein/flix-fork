/*
 * Canonical Flix formatting blueprint.
 *
 * This file is intentionally broad: it is the reviewed reference for how modern,
 * idiomatic Flix should look. Each section demonstrates a language construct in
 * realistic code. Keep examples small, coherent, and canonical; do not add
 * malformed input or formatter stress noise here.
 */

mod FormatterBlueprint {

    use FormatterBlueprint.Model.{Color, Tree}
    use FormatterBlueprint.Model.Color.{Blue, Green, Red}
    use FormatterBlueprint.Model.UserId

    import java.lang.{ArithmeticException, Exception, Object, StringBuilder => JStringBuilder}
    import java.lang.System
    import java.io.PrintStream
    import java.awt.Point

    /// A mutable struct whose companion is this enclosing module.
    pub struct FormatterBlueprint[r] {
        value:      Int32,
        mut writes: Int32
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Modules, declarations, documentation, annotations, and modifiers           //
    ////////////////////////////////////////////////////////////////////////////////

    mod Model {

        /// A simple sum type with nullary and payload cases.
        pub enum Color with Eq {
            case Red,
            case Green,
            case Blue
        }

        /// A recursive, polymorphic algebraic data type.
        pub enum Tree[a] with Eq, ToString {
            case Leaf(a)
            case Branch(Tree[a], Tree[a])
        }

        /// The singleton-enum shorthand.
        pub enum UserId(Int64)

        /// A phantom, explicitly kinded parameter, as used by Prelude witnesses.
        pub enum TypeWitness[_: Type] {
            case TypeWitness
        }

        /// A restrictable enum used for extensible computations.
        pub restrictable enum Expr[_][a] {
            case Cst(a)
            case Add(a, a)
            case Neg(a)
        }

        /// An immutable alias with a row-polymorphic record payload.
        pub type alias Named[a] = {name = String, value = a}

        /// A higher-kinded alias with explicit kind ascriptions.
        pub type alias Endo[f: Type -> Type, a: Type] = f[a] -> f[a]

        /// Type-level values, row aliases, schemas, and restrictable case sets.
        pub type alias Kinded[a: Type] = (a: Type)
        pub type alias Truth[b: Bool] = b and true
        pub type alias Falsehood = false
        pub type alias BooleanFormula[b: Bool] = b xor true
        pub type alias Negated[b: Bool] = not b
        pub type alias AnyEffect = Univ
        pub type alias EffectIntersection[ef1: Eff, ef2: Eff] = ef1 & ef2
        pub type alias NamedRow = (name = String, value = Int32)
        pub type alias Facts = #{ Input(Int32), Summary(String; Set[Int32]) }
        pub type alias FactRow = #(Input(Int32))
        pub type alias UnaryPredicate[a] = #{ Item(a) }
        pub type alias ExpressionCases = < Expr.Cst, Expr.Add, Expr.Neg >

        /// A trait with a supertrait, an associated type, a signature, and a default definition.
        pub trait Render[t] with Eq[t] {
            type Output = String
            type RenderEff: Eff = {}

            pub def render(x: t): Render.Output[t] \ Render.RenderEff[t]

            pub def renderAgain(x: t): Render.Output[t] \ Render.RenderEff[t] = Render.render(x)
        }

        instance Render[Color] {
            type Output = String
            type RenderEff = {}

            pub def render(c: Color): String =
                match c {
                    case Color.Red   => "red"
                    case Color.Green => "green"
                    case Color.Blue  => "blue"
                }

            redef renderAgain(c: Color): String = "again: ${Render.render(c)}"
        }

        /// A user-defined algebraic effect.
        pub eff Console {
            def read(): String
            def write(message: String): Unit
        }

        /// An uninterpretable effect declaration has no operation body.
        pub eff Ambient

    }

    ////////////////////////////////////////////////////////////////////////////////
    // Functions, constraints, types, literals, operators, and application        //
    ////////////////////////////////////////////////////////////////////////////////

    @CompileTest
    pub def identity(x: a): a = x

    pub def compose(f: b -> c \ ef1, g: a -> b \ ef2, x: a): c \ {ef1, ef2} = f(g(x))

    pub def stringifyAll(xs: List[a]): List[String] with ToString[a] =
        xs
            |> List.map(ToString.toString)
            |> List.filter(s -> not String.isEmpty(s))

    pub def sameElements(xs: List[a], ys: List[a]): Bool with Eq[a] = xs == ys

    pub def renderString(x: t): String \ Model.Render.RenderEff[t] with Model.Render[t] where Model.Render.Output[t] ~ String =
        Model.Render.render(x)

    pub def literalValues(): (Unit, Bool, Char, String, Int8, Int16, Int32, Int64, Float32, Float64, BigInt, BigDecimal) =
        ((), true, 'λ', "Flix", 1i8, 2i16, 3i32, 4i64, 5.0f32, 6.0f64, 7ii, 8.0ff)

    pub def unsuffixedFloat(): Float64 = 3.14

    pub def regexLiteral(): Regex = regex"[a-z]+"

    pub def collectionLiterals(): (List[Int32], Vector[Int32], Set[Int32], Map[String, Int32]) =
        (List#{1, 2, 3}, Vector#{1, 2, 3}, Set#{1, 2, 3}, Map#{"one" => 1, "two" => 2})

    pub def precedence(a: Int32, b: Int32, c: Int32): Bool =
        a + b * c >= 0 and a != b or c == 0

    pub def compareValues(x: Int32, y: Int32): Comparison = x <=> y

    pub def appendLists(xs: List[Int32], ys: List[Int32]): List[Int32] = xs ::: ys

    /// Binary continuations indent from their owning `let`.
    pub def containsPositiveTriple(): Bool =
        let patterns = (0, 1, 2)
            :: (3, 4, 5)
            :: (6, 7, 8)
            :: (0, 3, 6)
            :: Nil;
        List.exists((pattern: (Int32, Int32, Int32)) -> {
            match pattern {
                case (a, b, c) => a > 0 and b > 0 and c > 0
            }
        }, patterns)

    pub def √(x: Int32): Int32 = x

    pub def lambdas(): (Int32 -> Int32, (Int32, Int32) -> Int32) =
        (x -> x + 1, (x, y) -> x + y)

    pub def namedArguments(): Unit \ Assert = Assert.assertEq(expected = 42, 42)

    pub def operatorSections(): (Int32 -> Int32, Int32) =
        let increment = (+)(1);
        let infix = 1 `Order.max` 2;
        (increment, infix)

    /// Prelude's semigroup and forward-composition operators are ordinary expressions.
    pub def preludeOperators(): (String, Int32, Int32 -> Int32) =
        let combined = "Flix" ++ "!";
        let commutative = 20 |+| 22;
        let composed = (x -> x + 1) >> (x -> x * 2);
        (combined, commutative, composed)

    /// A bang suffix conventionally marks a definition that can terminate the program.
    pub def requirePositive!(x: Int32): Int32 =
        if (x > 0) x else bug!("expected a positive integer")

    /// The standard value, tap, and tuple pipeline operators share one layout.
    pub def pipelineFamilies(): (List[Int32], Int32, Int32) =
        let ordinary = List#{1, 2, 3}
            |> List.reverse
            |> List.map(x -> x + 1);
        let tapped = 40
            !> (_ -> ())
            !> (_ -> ());
        let tupled = (20, 22)
            ||> (x -> y -> (x + y, 0))
            ||> (x -> y -> x + y);
        (ordinary, tapped, tupled)

    pub def interpolation(name: String, n: Int32): String =
        "Hello ${name}; next year you will be ${n + 1}."

    pub def debugInterpolation(xs: List[Int32]): String = d"values = ${xs}"

    pub def ascriptions(x: Int32): Int32 =
        let y: Int32 = x;
        ((y + 1): Int32)

    ////////////////////////////////////////////////////////////////////////////////
    // Pattern matching and destructuring                                         //
    ////////////////////////////////////////////////////////////////////////////////

    pub def treeSize(t: Tree[a]): Int32 =
        match t {
            case Tree.Leaf(_)             => 1
            case Tree.Branch(left, right) => treeSize(left) + treeSize(right)
        }

    pub def guardedMatch(x: Int32): String =
        match x {
            case -1         => "minus one"
            case n if n < 0 => "negative"
            case 0          => "zero"
            case _          => "positive"
        }

    pub def listPattern(xs: List[Int32]): Int32 =
        match xs {
            case Nil    => 0
            case x :: _ => x
        }

    pub def tupleAndRecordPatterns(p: (Int32, {name = String, value = Int32})): String =
        let (fallback, {name, value}) = p;
        if (value > fallback) name else "small"

    pub def matchLambda(xs: List[Int32]): List[Int32] =
        List.map(match n -> if (n == 0) 1 else n * 2, xs)

    pub def matchLambdaArgument(xs: List[(Int32, Int32)]): List[Int32] =
        List.map(match (x, y) -> x + y, xs)

    pub def extMatchLambda(): #| Value(Int32) |# -> Int32 = ematch Value(x) -> x

    ////////////////////////////////////////////////////////////////////////////////
    // Records and immutable data                                                 //
    ////////////////////////////////////////////////////////////////////////////////

    pub def recordOperations(r: {name = String, count = Int32 | row}): {name = String, count = Int32 | row} =
        let selected = r#count;
        let updated = {count = selected + 1 | r};
        {name = String.toUpperCase(updated#name) | updated}

    pub def extendRecord(r: {name = String}): {active = Bool, name = String} =
        {+ active = true | r}

    pub def restrictRecord(r: {active = Bool, name = String}): {active = Bool} =
        {- name | r}

    /// An explicit block keeps its opening brace beside the definition's `=`.
    pub def recordBlock(origin: {x = Int32, y = Int32}): {x = Int32, y = Int32} = {
        let column = 20;
        let row = 22;
        {x = origin#x + column, y = origin#y + row}
    }

    ////////////////////////////////////////////////////////////////////////////////
    // Local control flow and comprehensions                                      //
    ////////////////////////////////////////////////////////////////////////////////

    pub def localDefinition(xs: List[Int32]): List[Int32] =
        def positive(x: Int32): Bool = x > 0;
        List.filter(positive, xs)

    pub def localUse(xs: List[Int32]): List[Int32] =
        use List.map;
        map(x -> x + 1, xs)

    pub def conditions(x: Int32): String =
        if (x < 0) {
            "negative"
        } else if (x == 0) {
            "zero"
        } else {
            "positive"
        }

    /// A guard clause backed by a function-call condition, not just a comparison.
    pub def guardedCall(xs: List[Int32]): Bool =
        if (List.isEmpty(xs)) false else List.exists(x -> x > 0, xs)

    /// An `if` nested inside a lambda body stays tight after its own paren,
    /// independent of how deeply the lambda itself is nested.
    pub def guardedLambda(xs: List[Int32]): List[Bool] =
        List.map(x -> if (x > 0) true else false, xs)

    /// An `if`/`else` with explicit braces, nested inside a `match` case's own
    /// block, stays tight after its own paren regardless of how many
    /// enclosing constructs separate it from the top of the declaration.
    pub def guardedMatchBlock(x: Option[Int32]): Bool =
        match x {
            case Some(n) => {
                if (n > 0) {
                    true
                } else {
                    false
                }
            }
            case None    => false
        }

    pub def foreachExample(xs: List[String]): Unit \ IO =
        foreach (x <- xs; if not String.isEmpty(x)) {
            println(x)
        }

    pub def monadicComprehension(xs: List[Int32]): List[(Int32, Int32)] =
        forM (
            x <- xs;
            doubled = x * 2;
            if x > 0;
            y <- List#{x, x + 1}
        ) yield (doubled, y)

    pub def applicativeComprehension(xs: List[Int32], ys: List[Int32]): List[Int32] =
        forA (
            x <- xs;
            y <- ys
        ) yield x + y

    pub def parallelComprehension(): Int32 =
        par (
            x <- 20;
            y <- 22
        ) yield x + y

    ////////////////////////////////////////////////////////////////////////////////
    // Effects and handlers                                                       //
    ////////////////////////////////////////////////////////////////////////////////

    pub def greet(): Unit \ {Model.Console} =
        let name = Model.Console.read();
        Model.Console.write("Hello ${name}")

    pub def discardResult(): Unit \ {Model.Console} = discard Model.Console.read()

    pub def handledGreeting(): String =
        run {
            greet();
            "handled"
        } with handler Model.Console {
            def read(resume) = resume("Ada")
            def write(message, resume) = {
                let _ = message;
                resume()
            }
        }

    ////////////////////////////////////////////////////////////////////////////////
    // Regions, arrays, structs, references, laziness, and concurrency            //
    ////////////////////////////////////////////////////////////////////////////////

    pub def scopedMutation(): Int32 =
        region rc {
            let ref = Ref.fresh(rc, 40);
            let array = Array#{1, 2, 3} @ rc;
            let cell = new FormatterBlueprint @ rc {value = Array.get(0, array), writes = 0};
            cell->writes = cell->writes + 1;
            Ref.put(Ref.get(ref) + cell->value + cell->writes, ref);
            Array.put(0, 2, array);
            Ref.get(ref) + Array.get(0, array)
        }

    pub def indexedArray(): Int32 \ OutOfBounds =
        region rc {
            let array = Array#{40, 2} @ rc;
            array[0] = array[0] + array[1];
            array[0]
        }

    pub def staticArray(): Array[Int32, Static] \ IO = Array#{1, 2, 3} @ Static

    pub def lazyValue(): Int32 =
        let delayed = lazy (20 + 22);
        force delayed

    pub def spawnedMessage(): Unit \ IO =
        region rc {
            spawn println("worker") @ rc;
            println("parent")
        }

    pub def channelSelection(rx1: Receiver[String], rx2: Receiver[String]): String \ {Chan, NonDet} =
        select {
            case message <- recv(rx1) => message
            case message <- recv(rx2) => message
            case _ => "closed"
        }

    ////////////////////////////////////////////////////////////////////////////////
    // Restrictable enums and extensible variants                                 //
    ////////////////////////////////////////////////////////////////////////////////

    pub def chooseExpression(expr: Model.Expr[s][Int32]): Int32 =
        choose expr {
            case Model.Expr.Cst(x) => x
            case Model.Expr.Add(x, y) => x + y
            case Model.Expr.Neg(x) => - x
        }

    pub def openedExpression(): Int32 =
        choose open_variant Model.Expr.Cst(42) {
            case Model.Expr.Cst(x) => x
        }

    pub def openedExpressionAs(): Int32 =
        choose open_variant_as Model.Expr Model.Expr.Cst(42) {
            case Model.Expr.Cst(x) => x
        }

    pub def narrowCases(expr: Model.Expr[s rvand < Model.Expr.Cst, Model.Expr.Add >][Int32]): Model.Expr[(s rvsub < Model.Expr.Add >) rvadd < Model.Expr.Neg >][Int32] =
        choose* expr {
            case Model.Expr.Cst(x) => Model.Expr.Cst(x)
            case Model.Expr.Add(x, _) => Model.Expr.Neg(x)
        }

    pub def complementCases(_expr: Model.Expr[rvnot s][Int32]): Unit = ()

    pub def chooseOpenExpression(): Int32 =
        let expr = choose* Model.Expr.Cst(42) {
            case Model.Expr.Cst(x) => Model.Expr.Neg(x)
        };
        choose expr {
            case Model.Expr.Neg(x) => - x
        }

    pub def extensibleVariant(): Int32 =
        let variant = xvar Value(42);
        ematch variant {
            case Value(n) => n
            case _ => 0
        }

    ////////////////////////////////////////////////////////////////////////////////
    // Embedded Datalog                                                           //
    ////////////////////////////////////////////////////////////////////////////////

    /// A multiline schema result keeps its closing brace, `=`, and literal together.
    pub def database(): #{
        Input(Int32),
        Output(Int32)
    } = #{
        Input(42).
        Output(x) :- Input(x).
    }

    pub def reachable(edges: List[(Int32, Int32)]): Set[Int32] =
        let facts = inject edges into Edge/2;
        let rules = #{
            Reachable(1).
            Reachable(y) :- Reachable(x), Edge(x, y).
        };
        let solved = solve facts, rules project Reachable;
        query solved select x from Reachable(x) |> Vector.toSet

    pub def datalogLambda(): Bool =
        let rules = #(Input, Output) -> #{
            Local(x) :- Input(x).
            Output(x) :- Local(x).
        };
        let facts = #{
            Input(42).
        };
        query rules, facts select x from Output(x) |> Vector.memberOf(42)

    pub def datalogBodies(): Vector[(Int32, Int32)] =
        let rules = #{
            Pair(x, y) :- Left(x), Right(y), if (x <= y).
            Pair(x, y) :- Left(x), let y = Vector#{x + 1}.
            Unpaired(x) :- Left(x), not Right(x).
            Left(1).
            Left(2).
            Right(2).
            Right(3).
        };
        query rules select (x, y) from Pair(x, y) where x < y

    /// Multiple injected collections map positionally to predicate/arity names.
    pub def datalogInjection(xs: Vector[Int32], ys: Vector[(Int32, String)]): Vector[(Int32, String)] =
        let facts = inject xs, ys into Number/1, Label/2;
        query facts select (n, label) from Number(n), Label(n, label) where n > 0

    /// Lattice predicates separate relational keys from their lattice term with `;`.
    pub def datalogLattice(): Vector[Set[Int32]] =
        let rules = #{
            Input(1).
            Summary(; Set#{x}) :- Input(x).
            Result(xs) :- fix Summary(; xs).
        };
        query rules select xs from Result(xs)

    /// Typed fixpoint-lambda parameters document relational and lattice signatures.
    pub def typedDatalogLambda(): Bool =
        let rules = #(Input(Int32), Summary(String; Set[Int32])) -> #{
            Output(x) :- Input(x).
            Seen(label) :- Summary(label; _).
        };
        query rules, #{ Input(42). } select x from Output(x) |> Vector.memberOf(42)

    /// Composition, solving without projection, and projecting several predicates.
    pub def datalogSolving(): Vector[Int32] =
        let facts = #{ Input(1). } <+> #{ Input(2). };
        let rules = #{
            Output(x) :- Input(x).
            Copy(x) :- Output(x).
        };
        let database = solve facts, rules;
        let projected = solve database project Output, Copy;
        query projected select x from Output(x)

    pub def provenance(): Vector[Int32] =
        let rules = #{
            Result(42).
        };
        let values = pquery rules select Result(42) with {Result};
        Vector.map(v -> ematch v {
            case Result(x) => x
        }, values)

    /// `psolve` materializes provenance once for subsequent `pquery` expressions.
    pub def solvedProvenance(): Vector[Int32] =
        let database = psolve #{
            Source(42).
            Result(x) :- Source(x).
        };
        let values = pquery database select Result(42) with {Source};
        Vector.map(v -> ematch v {
            case Source(x) => x
        }, values)

    ////////////////////////////////////////////////////////////////////////////////
    // Java interoperation, casts, exceptions, and unsafe effects                 //
    ////////////////////////////////////////////////////////////////////////////////

    pub def javaCalls(): String \ IO =
        let builder = new JStringBuilder();
        builder.append("Flix");
        builder.append('!');
        builder.toString()

    pub def anonymousObject(): Object \ IO =
        new Object {
            def new (): Object \ IO = super ()
            def toString(_this: Object): String \ IO = "FormatterBlueprint: " + super.toString()
        }

    pub def checkedCast(x: String): Object = checked_cast (x)

    pub def checkedEffectCast(x: Int32): Int32 \ IO = checked_ecast (x)

    pub def uncheckedCast(x: a): String = unchecked_cast (x as String)

    pub def javaTypeTest(x: Object): Bool = x instanceof JStringBuilder

    pub def nullValue(): Null = null

    pub def staticField(): String = System.lineSeparator()

    pub def javaField(): PrintStream = System.out

    pub def instanceField(): Int32 \ IO =
        let point = new Point(20, 22);
        point.x + point.y

    pub def exceptionExample(x: Int32): Result[String, Int32] \ IO =
        try {
            if (x == 0) throw new ArithmeticException("zero") else Ok(42 / x)
        } catch {
            case e: ArithmeticException => Err(e.getMessage())
            case _: Exception => Err("unexpected")
        }

    pub def unsafeEffect(): String =
        unsafe IO {
            new JStringBuilder("safe boundary").toString()
        }

    pub def maskedEffect(): String =
        unsafe IO as {} {
            new JStringBuilder("masked boundary").toString()
        }

}
