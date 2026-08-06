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

Only the *first* segment of a module path becomes a package, so this holds at
any depth: `mod Acme.Greeter.Deep` is the class `Acme.Greeter$Deep`, not `Deep`
in a package named after the class `Acme.Greeter`. Two-level names — the ones
above — are unaffected.

```
mod Acme.Greeter             ->  Acme.Greeter
mod Acme.Greeter.Deep        ->  Acme.Greeter$Deep
mod Acme.Greeter.Deep.Inner  ->  Acme.Greeter$Deep$Inner
```

One case is left: a *top-level* `mod Acme` with a `main` or a `@Test` puts a
class `Acme` in the unnamed package, which meets the package `Acme` that its
submodules use. Exports cannot reach it — a one-segment module may not export —
but avoid giving a top-level module entry points if any submodule has them.

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

`calling-flix-from-scala` is the worked version of that, including the one place
the bridge misleads: `.asScala` on an exported `List` gives a mutable view over
an unmodifiable list, which type checks and throws.

```ruby
java_import "Acme.Greeter"            # JRuby lowercases a bare Java::Acme
```

## Rules for `@Export`

An exported function must:

- be `pub`;
- have no *constrained* type variables. An unconstrained one is fine and is
  exported as `java.lang.Object` (below);
- live in a *nested* module — the class of a def in `A.B` is `B` in package
  `A`, so a top-level `mod B` would put the facade in the unnamed package,
  which Java code in a named package cannot import;
- have a name that is a valid Java identifier (`[a-z][a-zA-Z0-9]*`);
- have an effect that is primitive or has a default handler.

## Which types can cross the boundary

Exportable: `Bool`, `Char`, `Int8`, `Int16`, `Int32`, `Int64`, `Float32`,
`Float64`, `String`, `BigInt`, `BigDecimal`, `Regex`, and any Java type.

A generic Java type keeps its type arguments in *return* position:

```flix
@Export
pub def names(): ArrayList[String] \ IO = new ArrayList()
```

```java
ArrayList<String> xs = Acme.Greeter.names();   // not a raw ArrayList
```

Nested and multi-argument types work (`ArrayList[ArrayList[String]]`,
`HashMap[String, Int32]`), and a primitive argument is boxed, so
`ArrayList[Int32]` is `ArrayList<Integer>`. Parameters are declared too, so a
caller passing an `ArrayList<String>` needs no cast and takes no unchecked
conversion.

Every type argument must itself be exportable. `ArrayList[SomeFlixEnum]` is an
error, not a raw `ArrayList`: the elements crossing would be generated classes
such as `dev.flix.gen.Colour$Red`, which the compiler renames freely, and
erasure hides that from the signature entirely.

### Crossing with a Flix enum

Give it a Java representation first. A real Java enum is the natural choice — it
has a stable Java type of its own, so it needs no conversion and stays a proper
enum on the other side:

```flix
mod Acme.Greeter {
    import java.time.DayOfWeek
    import java.util.ArrayList

    @Export
    pub def weekend(): ArrayList[DayOfWeek] \ IO =
        let l = new ArrayList();
        discard l.add(DayOfWeek.valueOf("SATURDAY"));
        discard l.add(DayOfWeek.valueOf("SUNDAY"));
        l
}
```

```java
ArrayList<DayOfWeek> w = Acme.Greeter.weekend();
switch (w.get(0)) { case SATURDAY, SUNDAY -> "weekend"; default -> "weekday"; }
EnumSet.copyOf(w);                                  // [SATURDAY, SUNDAY]
```

A `String` or an `Int32` code works the same way — map the Flix enum to one in a
small `encode` function and export that. Which representation is right is a
question about the API you mean to publish, so the compiler does not choose.

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

## Lists

`List[t]` is exportable **as a return type**, where the shim presents it as an
unmodifiable `java.util.List`:

```flix
@Export
pub def names(): List[String] = "a" :: "b" :: Nil
```

```java
List<String> xs = Acme.Greeter.names();   // [a, b]
xs.add("c");                              // UnsupportedOperationException
```

The element type is declared in the `Signature`, and a primitive element is
boxed, so `List[Int32]` is `List<Integer>`. The element must itself be
exportable: `List[List[t]]` and `List[Option[t]]` are rejected, and the error
points at the element rather than at the list.

The list is not copied: what crosses is a view over the Flix chain, which
allocates O(1) instead of O(n) and holds the Flix value alive for as long as the
view is reachable. `get(i)` walks from the head, so indexed access is O(i) --
the view extends `AbstractSequentialList`, which means iteration stays linear
rather than becoming quadratic. Iterating backwards through a `ListIterator`
re-walks from the head and is O(n) per step.

`List` is not exportable as a parameter, for the same reason `Option` is not.

## Sets

`Set[t]` is exportable **as a return type**, where the shim presents it as an
unmodifiable `java.util.Set`:

```flix
@Export
pub def names(): Set[String] = Set#{"delta", "alpha", "charlie", "bravo"}
```

```java
Set<String> xs = Acme.Greeter.names();   // [alpha, bravo, charlie, delta]
xs.add("echo");                          // UnsupportedOperationException
```

Unlike a `List`, a `Set` is **not** copied: what crosses is a view over the
red-black tree, which allocates O(1) rather than O(n) and holds the Flix value
alive for as long as the view is reachable. Iteration is in ascending key order,
which is the order a Flix `Set` already enumerates in — so the same set walked
from Flix and from Java yields the same sequence.

`size()` walks the tree once and caches the result; `isEmpty()` is O(1) and does
not force it. As with `List`, the element type is declared in the `Signature`, a
primitive element is boxed, the element must itself be exportable, and the type
is return-only.

## Maps

`Map[k, v]` is exportable **as a return type**, where the shim presents it as an
unmodifiable `java.util.Map` over the same view:

```flix
@Export
pub def ages(): Map[String, Int32] = Map#{"delta" => 4, "alpha" => 1}
```

```java
Map<String, Integer> m = Acme.Greeter.ages();   // {alpha=1, delta=4}
m.get("alpha");                                 // 1
m.put("echo", 5);                               // UnsupportedOperationException
```

Not copied, and iterated in ascending key order, exactly as a `Set` is — the
entry set *is* a set view, handing out `Map.Entry` instead of a bare key.
Entries are the JDK's own `AbstractMap.SimpleImmutableEntry`, so `setValue`
throws too.

Both the key and the value must be exportable, and both are declared in the
`Signature`, so `Map[Int32, String]` is `Map<Integer, String>`.

## Tuples

A tuple is exportable **as a return type**, where the shim presents it as a
`dev.flix.runtime.TupleN` record — one class per arity:

```flix
@Export
pub def entry(): (Int32, String) = (1, "alpha")
```

```java
Tuple2<Integer, String> t = Acme.Greeter.entry();
t._1();                       // 1
t._2();                       // "alpha"
t.toString();                 // Tuple2[_1=1, _2=alpha]
```

It is a real Java record, so it has value semantics — two calls returning equal
components are `equals` and share a `hashCode` — and Java 21 can deconstruct
it:

```java
if (Acme.Greeter.entry() instanceof Tuple2(Integer n, String name)) { ... }
```

Unlike a `List` or a `Set`, this one is copied rather than viewed: a tuple has a
fixed, small number of fields that are already in hand, so there is nothing to
walk lazily.

The class varies only in arity. The element types are its type *parameters*,
declared in the `Signature`, so `(Int32, String)` and `(String, Bool)` are both
`Tuple2` — and a primitive element is boxed, because a type argument is a
reference. Every element must itself be exportable, and the type is return-only.

## Enums

An enum whose cases all carry no data is exportable **as a return type**, where
the shim presents it as a real Java enum:

```flix
pub mod Acme.Greeter {
    pub enum Colour { case Red, case Green, case Blue }

    @Export
    pub def favourite(): Colour = Colour.Green
}
```

```java
Greeter$Colour c = Greeter.favourite();   // Green
c.ordinal();                              // 1
Greeter$Colour.valueOf("Blue");           // Blue
```

The class is named beside its module, exactly as the facade is: `enum Colour` in
`mod Acme.Greeter` is the class `Acme.Greeter$Colour`. The constants keep their
Flix names — `Red`, not `RED` — because uppercasing needs a lossy guess that can
make two cases collide.

It is a genuine `java.lang.Enum`, not a class that resembles one, so it works
everywhere the language expects an enum:

```java
switch (Greeter.favourite()) {
    case Red -> ...;
    case Green -> ...;
    case Blue -> ...;
}
EnumSet.of(Greeter.favourite(), Greeter$Colour.Red);
```

The enum must live in a module with at least two segments — `mod Acme.Greeter`,
not `mod Acme` and not the top level — which is the same requirement an exported
function itself has, and for the same reason: the first segment becomes the Java
package.

A case that *carries* data has no constant to be, so it crosses a different
way: as a sealed interface, with one generated record per case.

```flix
pub mod Acme.Greeter {
    pub enum Shape {
        case Circle(Int32, Int32),
        case Square(Int32),
        case Point
    }

    @Export
    pub def bigCircle(): Shape = Shape.Circle(10, 10)
}
```

```java
Greeter$Shape s = Greeter.bigCircle();
switch (s) {
    case Greeter$Shape$Circle c -> c.radius();    // exhaustive, no `default`
    case Greeter$Shape$Square q -> q.side();
    case Greeter$Shape$Point p -> 0;
}
```

Each case is a genuine Java `record`, named one level further under the enum's
own name — `Acme.Greeter$Shape$Circle` — with one accessor per element, `_1`,
`_2`, and so on, since Flix case fields have no names of their own to keep. A
nullary case in a mix like `Point` above is a zero-component record. Every
component keeps its own concrete Java type — `int`, not `Integer` — because
unlike a tuple's record this one is never shared across two different
element-type instantiations. The interface itself is `sealed`: a `switch` over
it is exhaustive without a `default` branch, and adding a case to the Flix enum
without also handling it in Java is a compile error at the call site, not a
silently-missed branch.

An enum whose cases *all* carry no data still crosses as a real `java.lang.Enum`
(above), not a sealed interface of zero-component records — one Flix type keeps
one Java shape, whichever cases it happens to have.

The same restrictions apply as everywhere else in this boundary: the enum must
have no type parameters and must live in a module with at least two segments,
and every case's elements must themselves be directly exportable, one level
deep — a case holding another container (`Circle(List[Int32])`) or another
enum is rejected, the same way a tuple's own elements are.

## Why some conversions are return-only

`Option` is *not* exportable as a parameter. Mapping `Optional.empty()` to
`None` would be unproblematic; what is missing is the machinery. The declared
type is carried to the code generator for the return only, so a parameter
arrives with its element type already erased, and the conversion is described in
one direction.

## Polymorphic functions

An *unconstrained* type variable is exported as `java.lang.Object`:

```flix
@Export
pub def id(x: t): t = x
```

```java
String s = (String) Acme.Greeter.id("round-trip");
```

That is not a special case in the boundary — every Flix reference value is
already represented as `Object`, so the variable needs no conversion at all. The
cast is the one Java performs for its own generics.

A *constrained* variable is rejected, and this is not a temporary restriction.
Flix picks a trait implementation from the concrete type while compiling; a
variable that stayed a variable has no implementation to pick and nothing is
passed at run time to choose one. Export a wrapper per type instead:

```flix
pub def describe(x: a): String with ToString[a] = ToString.toString(x)

@Export
pub def describeInt(x: Int32): String = describe(x)
```

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

Not exportable: Flix records, functions, and `Array`, plus a generic enum or a
case whose own elements are containers. Their JVM representation is an
implementation detail of the compiler — a `Some(x)` is a class called
`Tag$Obj` distinguished only by an `int ordinal` field — so exposing them would
freeze names the backend needs to stay free to change.

That is also why the types above are *converted* rather than exposed: the shim
reads the tag and builds an `Optional`, a collection view, or a tuple record, so
Java never names the tag class. To hand any
other such value to Java, do the same at the boundary — return a `String`, a
Java collection, or a Java object you construct yourself.
