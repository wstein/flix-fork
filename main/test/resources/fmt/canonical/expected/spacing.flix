mod Spacing {

    /// Binary operators are spaced, whatever the input did.
    pub def arithmetic(): Int32 = 1 + 2 * 3 - 4

    /// A minus sign that signs a literal is not an operator.
    pub def negatives(): (Int32, Int64) = (-1, -1i64)

    /// `Set#{ }` is not tight on its left: `elseSet#{ }` would re-lex.
    pub def collections(b: Bool): Set[Int32] = if (b) Set#{1, 2} else Set#{}

    pub def add(x: Int32, y: Int32): Int32 = x + y

    /// Backticks make an ordinary function infix.
    pub def infix(x: Int32, y: Int32): Int32 = x `add` y

    /// The interior of a string literal is never touched.
    pub def interpolated(name: String): String = "hello ${name}  !"

    /// A lambda arrow is spaced; a struct arrow is not (see `structs.flix`).
    pub def apply(f: Int32 -> Int32, x: Int32): Int32 = f(x)

    @Test
    pub def testArithmetic(): Bool = arithmetic() == 3

}
