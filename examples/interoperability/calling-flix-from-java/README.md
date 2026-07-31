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
}
```

Plain static methods with the declared types — no wrapper objects, no casts,
no runtime initialisation step.

## Rules for `@Export`

An exported function must:

- be `pub`;
- have no type variables (no polymorphism, no trait constraints);
- live in a module — a function in the root namespace cannot be exported,
  since Java cannot import from the unnamed package;
- have a name that is a valid Java identifier (`[a-z][a-zA-Z0-9]*`);
- have an effect that is primitive or has a default handler.

## Which types can cross the boundary

Exportable: `Bool`, `Char`, `Int8`, `Int16`, `Int32`, `Int64`, `Float32`,
`Float64`, `String`, `BigInt`, `BigDecimal`, `Regex`, and any Java type
(including generic ones, which Java erases to their raw class).

`Unit` is exportable in the two places where it can be rendered away: as a
return type it becomes `void`, and the `Unit` parameter Flix gives a nullary
function is dropped, so `def announce(): Unit` is `void announce()` in Java.

Not exportable: Flix enums, tuples, records, functions, `Array`, and anything
polymorphic. Their JVM representation is an implementation detail of the
compiler — a `Some(x)` is a class called `Tag$Obj` distinguished only by an
`int ordinal` field — so exposing them would freeze names the backend needs to
stay free to change. To hand such a value to Java, convert it at the boundary:
return a `String`, a Java collection, or a Java object you construct yourself.
