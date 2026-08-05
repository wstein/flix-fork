// Nothing here is Scala-specific. An `@Export`ed function is an ordinary
// `public static` method, and Scala calls it the way it calls any Java class.
//
// What this example is really about is the *converted* types. `Option` and
// `List` have no public Flix representation, so the shim converts them to
// `java.util.Optional` and an unmodifiable `java.util.List` — and Scala has a
// standard bridge for each. See the README for the one that bites.
pub mod Acme.Greeter {

    @Export
    pub def greet(name: String): String = "Hello, ${name}!"

    // Crosses as `Optional<String>`, not a raw `Optional`. The element type is
    // declared in the method's generic `Signature`, and Scala 3 needs it:
    // against a raw return it reports "Found: java.util.Optional[?]#T,
    // Required: String" and refuses to compile. Java would have accepted it.
    @Export
    pub def find(key: String): Option[String] =
        if (key == "a") Some("alpha") else None

    // Crosses as an unmodifiable `List<String>`.
    @Export
    pub def names(): List[String] = "a" :: "b" :: Nil

    // A primitive element is boxed, because a `java.util.List` holds
    // references: this is `List<Integer>` on the Scala side, not `List<int>`.
    @Export
    pub def counts(): List[Int32] = 1 :: 2 :: 3 :: Nil

    // An unconstrained type variable is exported as `Object`, so Scala gets
    // `Object` back and casts. A *constrained* one cannot be exported at all.
    @Export
    pub def identity(x: t): t = x
}
