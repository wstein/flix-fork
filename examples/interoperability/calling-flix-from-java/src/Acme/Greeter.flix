// Functions marked `@Export` are compiled to ordinary `public static` methods,
// so Java can call them without knowing that Flix was involved.
//
// An exported function must be `pub`, must not be polymorphic, must live in a
// module (not the root namespace), and must use only types that have an exact
// Java representation. Note that the module is nested: `mod Acme.Greeter`
// compiles to the class `Greeter` in the package `Acme`, which is what lets
// Java code in a named package import it. A public module declared as
// `Acme.Greeter` must live in the file `Acme/Greeter.flix`.
pub mod Acme.Greeter {
    import java.util.ArrayList
    import java.util.function.UnaryOperator

    @Export
    pub def greet(name: String): String = "Hello, ${name}!"

    @Export
    pub def lengthOf(s: String): Int32 = String.length(s)

    // Java types may be used directly, including generic ones. Java erases the
    // type argument, so this is `int sizeOf(java.util.ArrayList)` from Java.
    @Export
    pub def sizeOf(l: ArrayList[String]): Int32 \ IO = l.size()

    // A `Unit` return type becomes `void`, and a nullary Flix function takes no
    // arguments in Java even though Flix gives it a `Unit` parameter.
    @Export
    pub def announce(): Unit \ IO = println("called from Java")

    // Java can pass a lambda in: a functional interface is an ordinary Java
    // type, so it needs no special support on either side. Note that
    // `Function`, `Consumer` and `Predicate` cannot be used here -- Flix
    // resolves those three names to its own function type rather than to the
    // Java interface -- but `UnaryOperator`, `BiFunction` and `Supplier` work.
    @Export
    pub def applyTwice(f: UnaryOperator[String], s: String): String \ IO =
        f.apply(f.apply(s))
}
