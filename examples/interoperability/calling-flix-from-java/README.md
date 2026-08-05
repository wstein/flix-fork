# Calling Flix from Java

The other examples in this directory call *Java from Flix*. This one goes the
other way: it builds a Flix library and calls it from a Java program.

## Build

```bash
flix build-jar
javac -cp artifact/calling-flix-from-java.jar -d classes java/com/example/App.java
java -cp "classes:artifact/calling-flix-from-java.jar" com.example.App
```

Output:

```
Hello, Java!
length = 12
size = 2
called from Java
callback!!
```

## Layout

```
src/Acme.flix           pub mod Acme { }
src/Acme/Greeter.flix   pub mod Acme.Greeter { ... }
```

A module `Acme.Greeter` requires its parent `Acme` to exist, and a public
module declared as `Acme.Greeter` must live in the file `Acme/Greeter.flix`.
The nesting is what puts the generated class in the package `Acme` — a
top-level `mod Greeter` would land in the unnamed package, which Java code in a
named package cannot import.

## What the compiler emits

`javap -cp artifact/calling-flix-from-java.jar Acme.Greeter` shows the generated facade:

```
public final class Acme.Greeter {
  public static final int lengthOf(java.lang.String);
  public static final void announce();
  public static final int sizeOf(java.util.ArrayList);
  public static final java.lang.String greet(java.lang.String);
  public static final java.lang.String applyTwice(java.util.function.UnaryOperator, java.lang.String);
}
```

Plain static methods with the declared types — no wrapper objects, no casts,
no runtime initialisation step.

The classes the backend generates for the module sit *beside* that facade rather
than beneath it — `Acme.Greeter$Def$greet`, not `Acme.Greeter.Def$greet`. A
namespace class is named after its namespace, so a package of the same name
would make `Acme.Greeter` denote both a class and a package. Java tolerates
that, but Scala rejects the classpath outright and Kotlin resolves the package
and never sees the class. This is the convention the other JVM languages follow:
Scala emits `acme.Api$`, Kotlin `acme.ApiKt`, Groovy `acme.Api$_use_closure1`,
and Clojure compiles the namespace `acme.api` to `acme.api$get_it`.

That removes the clash for a module whose submodules have no code of their own.
It does not remove it in general: the facade of `Acme.Greeter.Deep` is the class
`Deep` in package `Acme.Greeter`, which is the class `Acme.Greeter` again. A
module tree three levels deep therefore still emits `Acme/Greeter.class` beside
a directory `Acme/Greeter/`, and Scala and Kotlin still reject it. Keep exported
modules two levels deep until the facade adopts a suffix of its own.

## Other JVM languages

Nothing here is Java-specific — an exported function is an ordinary static
method. Java is what the test suite checks; Scala 3, Kotlin, Groovy, Clojure and
JRuby were run against this example by hand, on a two-level module.

Each language converts with its own standard bridge, so Flix needs no
per-language support:

```scala
import scala.jdk.OptionConverters.*
Acme.Greeter.find("a").toScala        // scala.Option
```

```ruby
java_import "Acme.Greeter"            # JRuby lowercases a bare Java::Acme
```

## Rules for `@Export`

An exported function must:

- be `pub`;
- have no type variables (no polymorphism, no trait constraints);
- live in a *nested* module — the class of a def in `A.B` is `B` in package
  `A`, so a top-level `mod B` would put the facade in the unnamed package,
  which Java code in a named package cannot import;
- have a name that is a valid Java identifier (`[a-z][a-zA-Z0-9]*`);
- have an effect that is primitive or has a default handler.

## Which types can cross the boundary

Exportable: `Bool`, `Char`, `Int8`, `Int16`, `Int32`, `Int64`, `Float32`,
`Float64`, `String`, `BigInt`, `BigDecimal`, `Regex`, and any Java type
(including generic ones, which Java erases to their raw class).

`Unit` is exportable in the two places where it can be rendered away: as a
return type it becomes `void`, and the `Unit` parameter Flix gives a nullary
function is dropped, so `def announce(): Unit` is `void announce()` in Java.

`Option[t]` is exportable **as a return type**, where the shim converts it to a
`java.util.Optional`:

```flix
@Export
pub def find(k: String): Option[String] =
    if (k == "a") Some("alpha") else None
```

```java
Optional<String> hit = Acme.Greeter.find("a");   // Optional[alpha]
Optional<String> miss = Acme.Greeter.find("z");  // Optional.empty
```

The element type is declared in the method's `Signature`, so Java, Scala and
Kotlin all see `Optional<String>` rather than a raw `Optional`. Without it only
Java would still compile, by naming the type at the use site and taking an
unchecked conversion; Scala 3 and Kotlin both reject a raw return. The signature
carries the element type, not nullability — Kotlin still reads the result as the
platform type `Optional<String!>!`, since the shim emits no nullness
annotations.

An element that is a primitive is boxed, since an `Optional` holds references:
`Option[Int32]` is `Optional<Integer>`. The element must itself be exportable,
so a nested `Option[Option[t]]` is rejected.

One conversion is lossy. The shim uses `Optional.ofNullable`, so a `Some` whose
payload is a Java `null` arrives as `Optional.empty()` — the same value `None`
produces. `Optional.of` would raise a `NullPointerException` at the boundary
instead; absence and a null payload cannot both be represented.

`Option` is *not* exportable as a parameter. That would need the reverse
conversion, and there is no answer for a Java caller passing `Optional.empty()`
to a function whose Flix type is not optional.

## Callbacks

A Java functional interface is an ordinary Java type, so Java can pass a lambda
into an exported function with no special support on either side.

Three names are an exception. Flix resolves `java.util.function.Function`,
`Consumer` and `Predicate` to its *own* function type rather than to the Java
interface, so they cannot be written with type arguments — `Function[String,
String]` is a kind error. Use `UnaryOperator`, `BinaryOperator`, `BiFunction`,
`Supplier`, one of the primitive-specialised interfaces, or your own
`@FunctionalInterface`.

A Flix closure cannot travel the other way: a function type is not exportable,
so an exported function cannot return one.

Not exportable: Flix enums other than `Option`, tuples, records, functions,
`Array`, and anything polymorphic. Their JVM representation is an implementation
detail of the compiler — a `Some(x)` is a class called `Tag$Obj` distinguished
only by an `int ordinal` field — so exposing them would freeze names the backend
needs to stay free to change.

That is also why `Option` is *converted* rather than exposed: the shim reads the
tag and builds an `Optional`, so Java never names the tag class. To hand any
other such value to Java, do the same at the boundary — return a `String`, a
Java collection, or a Java object you construct yourself.
