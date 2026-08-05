# Calling Flix from Scala

`calling-flix-from-java` shows the boundary itself: what `@Export` accepts, which
types cross, and what the compiler emits. **Read that one first** — everything it
says applies here unchanged, because an exported function is an ordinary
`public static` method and Scala calls it the way it calls any Java class.

This example is about the part that is *not* the same: the two types the shim
converts, `Option` and `List`, arrive as `java.util.Optional` and an unmodifiable
`java.util.List`, and Scala has a standard bridge for each. One of them is a trap.

## Build and run

```bash
flix build-jar
scala-cli run --jvm 21 --jar artifact/calling-flix-from-scala.jar scala/App.scala
```

Output:

```
Hello, Scala!
hit = Some(alpha), miss = None
names = List(a, b)
counts = List(1, 2, 3), sum = 6
round-trip
mutating the view threw = true
```

`--jvm 21` is not optional. Flix emits class-file version 65, and a Scala runner
that defaults to JDK 17 fails with `UnsupportedClassVersionError: Acme/Greeter
has been compiled by a more recent version of the Java Runtime` — which names
the Flix class and reads like a Flix problem, but is entirely about the JVM the
Scala side chose.

With `scalac` directly, the Scala runtime has to be on the classpath by hand:

```bash
scalac -cp artifact/calling-flix-from-scala.jar -d classes scala/App.scala
java -cp "classes:artifact/calling-flix-from-scala.jar:$SCALA3_LIB:$SCALA2_LIB" com.example.App
```

## The two conversions

```scala
import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*

val hit: Option[String] = Greeter.find("a").toScala        // Some(alpha)
val names: List[String] = Greeter.names().asScala.toList   // List(a, b)
```

One import and one call each. That is the whole cost of the boundary from Scala,
and it is why Flix ships no Scala-specific support: there is nothing left for it
to do that `scala.jdk` does not already do better.

### `.asScala` alone is a trap

```scala
val view = Greeter.names().asScala   // scala.collection.mutable.Buffer[String]
view += "c"                          // compiles; throws UnsupportedOperationException
```

The list that crossed is unmodifiable, but `.asScala` gives a **`mutable.Buffer`**
view over it. The mutation type checks and fails at run time. Finish with
`.toList` (or `.toSeq`) so the immutability is in the type rather than in the
exception.

This is the one place the Java-shaped boundary reads worse in Scala than in Java,
where `List<String>` is at least honest about being a `java.util.List`.

### Boxing is visible

```scala
val counts: List[Int] = Greeter.counts().asScala.toList.map(_.intValue)
```

`List[Int32]` crosses as `List<Integer>`, because a `java.util.List` holds
references. Scala will not silently unbox a `List[Integer]` into a `List[Int]`.

## Why the generic signature matters more here than in Java

`find` returns `Option[String]`, and the shim declares the element type in the
method's generic `Signature`. Java would compile against a *raw* `Optional`
anyway, taking an unchecked conversion. Scala 3 will not:

```
Found:    java.util.Optional[?]#T
Required: String
```

So the signature that looks like a nicety from Java is load-bearing from Scala.
The same holds for `List`.

It carries the element type and nothing else — in particular not nullability, so
a Kotlin caller still sees a platform type. See `docs/JAVA-INTEROP-DECISIONS.md`.

## Why the class is `Acme.Greeter` and not something nested

```scala
import Acme.Greeter
```

Scala is the reason this works at all. The compiler generates a def's classes
*beside* its facade (`Acme.Greeter$Def$greet`) rather than beneath it, because a
class and a package of the same name make Scala reject the entire classpath:

```
package Acme contains object and package with same name: Greeter
```

Every namespace with an exported def had both, so before that layout change
**every export was unreachable from Scala** — while Java compiled against the
same jar without complaint. Only the first segment of a module path becomes a
package, so this holds at any depth.

The rule and the alternatives rejected are `J1` in
`docs/JAVA-INTEROP-DECISIONS.md`.

## No converter library, by decision

Scala's own `scala.jdk.javaapi.CollectionConverters` exists because Java cannot
use Scala's implicit extension methods. The mirror image — a
`dev.flix.javaapi.Converters` for Flix — was considered and rejected: it would
require handing out a raw Flix `Option` for a converter to act on, which means
publishing a representation the compiler needs to keep private.

Conversion therefore happens *in the shim*, before the value is ever named by the
caller. That is `J3`, and it is why this example needs no Flix-side library.
