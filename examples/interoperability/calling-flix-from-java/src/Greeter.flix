// Functions marked `@Export` are compiled to ordinary `public static` methods,
// so Java can call them without knowing that Flix was involved.
//
// An exported function must be `pub`, must not be polymorphic, must live in a
// module (not the root namespace), and must use only types that have an exact
// Java representation. Note that the module is nested: `mod Acme.Greeter`
// compiles to the class `Greeter` in the package `Acme`, which is what lets
// Java code in a named package import it.
mod Acme.Greeter {
    import java.util.ArrayList

    @Export
    pub def greet(name: String): String = "Hello, ${name}!"

    @Export
    pub def lengthOf(s: String): Int32 = String.length(s)

    // Java types may be used directly, including generic ones. Java erases the
    // type argument, so this is `int sizeOf(java.util.ArrayList)` from Java.
    @Export
    pub def sizeOf(l: ArrayList[String]): Int32 \ IO = l.size()
}

def main(): Unit \ IO = println("Build me with `flix build-jar`, then call me from Java.")
