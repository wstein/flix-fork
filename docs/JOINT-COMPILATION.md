# Joint Flix/JVM Compilation

## Status

The compiler can derive compile-only Java facade stubs from Flix source before name resolution.
The CLI and build-tool client described below expose that operation so a build can break a mutual
Flix/Java dependency cycle without reflecting over generated implementation classes.

This is staged joint compilation, not a single mixed-language compiler pass.

## Why stubs are necessary

A source set may contain both directions of dependency:

- Java calls an `@Export`ed Flix definition and therefore needs the Flix facade class.
- Flix calls a method on that Java class and therefore needs the compiled Java class.

Neither language can be compiled first. The supported build schedule is:

1. Derive Java facade stubs from Flix syntax.
2. Compile the Java sources against those stubs.
3. Package the Java classes as a jar and compile Flix with `build --lib <java.jar>` (and include
   the facade stubs when Java signatures name them).
4. Put the Java classes from step 2 and the real Flix classes on the runtime classpath.

Java does not need a second compilation: the stub and real facade have the same binary signature.
The regression fixture proves this by executing the original Java class against the real facade
after removing every stub class from the runtime classpath.

The generated stubs always throw. They are compile-time scaffolding and must never be packaged or
placed on a runtime classpath.

Generate them with:

```console
java -jar flix.jar stubs --out build/flix-stubs
```

With no positional files, the command reads every `.flix` file below `src/`. Positional `.flix`
files may be supplied for non-project layouts. The command does not bootstrap or resolve the
project: that would recreate the dependency cycle it exists to break. It replaces the destination
directory only after every exported definition has a supported signature. A project-mode invocation
without a `src/` directory is an input error; it does not report a misleading successful zero-stub
generation.

## Export ABI boundary

### What crosses the boundary

An `@Export`ed def becomes a `public static` method on its module's facade class. Its result and
parameters cross as follows; a type not listed is refused with `IllegalExportType`.

| Flix type | Java type | Result | Parameter |
|---|---|---|---|
| `Bool`, `Char`, `Int8`–`Int64`, `Float32`, `Float64` | `boolean`, `char`, `byte`–`long`, `float`, `double` | yes | yes |
| `String` | `java.lang.String` | yes | yes |
| `Unit` | `void` (result), no parameter (a lone `Unit` parameter) | yes | yes |
| an imported Java class, generic or not | that class, with its type arguments | yes | yes |
| `Option[t]` | `java.util.Optional<T>` | yes | yes |
| `List[t]` | `java.util.List<T>` (an unmodifiable copy) | yes | yes |
| `Vector[t]` | `java.util.List<T>` (an unmodifiable copy) | yes | yes |
| `Chain[t]` | `java.util.Collection<T>` (an unmodifiable copy) | yes | yes |
| `Set[t]` | `java.util.Set<T>` (an unmodifiable copy) | yes | no |
| `Map[k, v]` | `java.util.Map<K, V>` (an unmodifiable copy) | yes | no |
| `(t1, ..., tn)` | a generated record `dev.flix.gen.Tuple$T1$...$Tn` with `component0()`... | yes | yes |
| `{l1 = t1, ...}` (closed) | a generated record `dev.flix.gen.Record$...` with accessors named by label | yes | yes |
| `enum E` without data | a generated Java `enum E`, named like the module `E` | yes | yes |
| `enum E` with data | a generated `sealed interface E` with one nested `record` per case | yes | yes |

- A container's type argument may be any type in the table; a primitive is boxed there.
- A tuple element, record field, or enum case field may be any type in the table except a
  container or a generic Java class, since the generated record class is shared by shape.
- An enum with a type parameter, a recursive enum, an open record, an `Array`, a function, and any
  other Flix type are refused.
- An exported enum's companion module (`mod E`) has its exported defs as static methods on the
  enum's own Java type, so none of them may be named like a method every Java enum has (`name`,
  `ordinal`, `values`, `valueOf`, ...): that is refused with `IllegalExportEnumMember`.
- Record components are in label order. `null` is never a valid argument.

### How each conversion works

Stub generation and codegen follow the same boundary, and the notes below explain each case:

- `Bool`, `Char`, `Int8`, `Int16`, `Int32`, `Int64`, `Float32`, and `Float64`.
- `String`, whose exported descriptor is `java.lang.String` rather than erased `Object`.
- `Option[t]` results as `java.util.Optional<T>` when `t` is otherwise exportable; primitive
  elements are boxed and the emitted facade retains `T` in its generic signature.
- `List[t]` results as eager, unmodifiable `java.util.List<T>` copies under the same element and
  generic-signature rules.
- `Vector[t]` results as eager, unmodifiable `java.util.List<T>` copies read directly off the
  underlying array, under the same element and generic-signature rules. `Array[t, r]` erases to
  the same representation and stays refused; only `EntryPoints`, working from the pre-erasure
  type, keeps a mutable, region-scoped array from reaching this conversion.
- `Chain[t]` results as eager, unmodifiable `java.util.Collection<T>` copies, under the same
  element and generic-signature rules. `Collection`, not `List`: a `Chain` has no efficient
  indexed access to advertise. Its `Empty | One(t) | Chain(l, r)` binary-tree shape is walked
  with an explicit stack rather than `List`'s single cursor.
- `Set[t]` and `Map[k, v]` results as eager, unmodifiable `java.util.Set<T>` / `java.util.Map<K, V>`
  copies. Both wrap a `RedBlackTree`, walked with the same explicit-stack technique as `Chain`
  (branching on being its `Node` case, since it also has a transient `DoubleBlackLeaf` case
  besides `Leaf`). The tree's field types are computed from the exported key/value types by
  ordinary erasure rather than looked up: the wrapper's own field is itself erased to `Object` by
  the time `EntryPoints`' retention reaches it, one level short of the tree it names.
- Tuple results as a real, generated `java.lang.Record` -- not an existing JDK type, unlike every
  other conversion here. `GenExportedTuple` emits one record class per distinct shape of
  Java-facing element types (synthetic component names, a canonical constructor, and hand-written
  `equals`/`hashCode`/`toString`, since extending `Record` makes all three abstract), shared by
  every exported tuple with that shape the way the compiler's own internal tuple class is shared
  by shape. An element may be any convertible type but a container (see "Nesting" below).
- Closed structural-record results as a real, generated `java.lang.Record`, built by the same
  `GenExportedProduct` engine as tuples -- the only difference is that a record names its
  components after its own field labels instead of `component0`-style synthetic names, since
  `SimpleType.RecordExtend` carries the label already. An open record, one still carrying a row
  variable, is refused: `EntryPoints.unapplyRecord` only accepts a row that peels down to
  `RecordRowEmpty`. The compiler's own internal record representation exposes fields one lookup
  at a time by label (`GenRecord.lookupField`), not by index, so each field is read that way
  rather than by position.
- Data-free, non-polymorphic enum results as a real, generated Java `enum` (`GenExportedEnum`):
  one constant per case in case-ordinal order, with `values()`, `valueOf(String)`, and the
  `Enum<E>` generic superclass javac needs to `switch` over it. The class is named like the
  namespace class of the enum's companion module, so `Mod.Color` becomes the Java class
  `Mod.Color`, and the exported defs of `mod Mod.Color` become static methods of that same enum
  class instead of a second class of the same name. Because of that, a companion export named
  like a method every Java enum has (`name`, `ordinal`, `values`, `valueOf`, ...) is refused with
  `IllegalExportEnumMember`. An enum with a type parameter is refused.
- Non-polymorphic enum results whose cases carry data as a `sealed interface` named the same way,
  permitting one nested `record` per case (`Shape.Circle(int component0)`), so Java 21 can
  `switch` over it exhaustively with record patterns. A field may be anything a tuple element may
  be; a recursive enum is refused. Specialization erases a
  reference-typed case field to `Object`, so `Eraser` records the declared field types of every
  exported enum in `exportedEnumFields` for the record to declare them. A case whose record class
  would share its name with a module (`Shape.Circle`) is a compiler crash, not yet an error.
- Explicitly imported Java classes, including nested generic arguments in parameters and results.

### Nesting

Conversions nest. A container's type argument may be any type a result may be, so
`List[List[Int32]]`, `Option[(Int32, String)]`, `Map[String, Vector[Color]]` and `List[Shape]`
cross as `List<List<Integer>>`, `Optional<Tuple$int$String>`, `Map<String, List<Color>>` and
`List<Shape>`. A tuple element, record field, or enum case field may be a tuple, record, or enum,
but not a container: the record class generated for a product is shared by the erased shape of its
components, so a `List<Integer>` component would reach Java as a raw `List`. For the same reason
an applied Java type such as `ArrayList[String]` is refused as a component, and a Java type's own
type arguments must be exact, since the contents of a Java object are never converted.
`EntryPoints` checks, `ExportPlan` converts, and `ExportStubs` describes these three positions --
result, type argument, component -- by the same rules.

Every conversion is planned from the declared type, never from a specialized enum: a value nested
inside another has no specialization recorded, only its declared type. A tag class's fields follow
from erasure alone (a reference-typed field is `Object`, a primitive field stays primitive), and a
case's ordinal from its declaration, which every specialization shares. Every element is read at
its erased type and cast to its plan's Flix type before it is converted.

It refuses other Flix algebraic data types, other containers, functions, and unaccounted reference
types because `EntryPoints` refuses those exports on this branch. Refusal is intentional:
a missing stub fails the build at generation time, while an incorrect stub compiles and later fails
with a linkage error in innocent calling code.

### Parameters

Parameters convert from Java by the same rules and to the same Java types results convert to, so
a caller passes back exactly what it was given: `java.util.Optional<T>` for `Option[t]`,
`java.util.List<T>` for `List[t]` and `Vector[t]`, `java.util.Collection<T>` for `Chain[t]`, the
generated records for tuples, records, and enum cases, and the generated Java enum constants.
`ArgumentPlan` is the reverse of `ExportPlan`: the shim converts each argument before storing it
in the def's argument field. `null` is never a valid argument.

The shim builds Flix values directly -- tags, tuples, record extensions -- rather than calling
Flix code, so `Set[t]` and `Map[k, v]` are refused anywhere in a parameter: their balanced
`RedBlackTree`s are the standard library's to build. A `List` is consed up from its last element,
a `Chain` built as `Chain(One(x1), Chain(One(x2), ... One(xn)))`, and a `Vector` copied into a new
array.

Building a value needs classes Flix code may never name, since a def need not look inside what it
is passed. A nullary case such as `None` is the singleton of one enum specialization, so `Eraser`
registers the specialization of every enum nested in an exported parameter and records them all
in `enumSpecializations`; and `CodeGen` generates the internal tuple and record classes of every
tuple and record nested in an exported parameter. The declared parameter types themselves survive
erasure as `exportedParamTypes`, as the declared result type does as `exportedReturnType`.

Stubs declare every class an exported signature names that the compiler generates too: a tuple or
record result's class becomes a Java `record` with the same components, a data-free enum becomes a
Java `enum`, and any other enum a `sealed interface` of nested records, each carrying its companion
module's methods, as the real type does. A record's
components are in label order, since monomorphisation sorts a record's row and the generated class
is named after it. Stub generation cannot resolve names, so it finds an enum through a `use` alias,
the exporting module, or the root, and refuses a def whose enum it cannot find that way.

The tests compare generated stub declarations with the method descriptors on the facade bytecode.
The staged tests go further: they compile a Java caller against the stubs alone and run it against
the real classes with no stub on the classpath.
This pins the source-facing stub contract to the backend contract and catches drift between them.

## JVM names

Stubs use the same `Mangle.namespaceFacadeDesc` function as bytecode generation. With the sibling
layout:

| Flix namespace | Java facade |
| --- | --- |
| root namespace | `Root$` |
| `PublicApi` | `dev.flix.gen.PublicApi` |
| `Acme.Api` | `Acme.Api` |
| `Acme.Api.Deep` | `Acme.Api$Deep` |

Implementation classes remain siblings of these facades and are not part of the public interop
contract.

## Migrating JVM consumers

Rebuild Java and Flix outputs together when moving from the old generated-class layout. A Java or
Kotlin caller should import the exported facade named above, not an implementation `Def$...` class.
The facade's source signature comes from `stubs`; the real class comes from the Flix build. Keep
the stub classes off the runtime classpath.

| Old assumption | Current layout or contract | Consumer action |
| --- | --- | --- |
| A root definition lives in `Def$foo` | It lives in `dev.flix.gen.Def$foo`; the root facade remains `Root$` | Recompile and remove direct implementation-class references |
| A one-segment definition lives in `List.Def$map` | It lives in `dev.flix.gen.List$Def$map`; an exported facade for that namespace would be `dev.flix.gen.List` | Use the facade for exported calls; use emitted metadata for debugger targets |
| A Java caller needs the real Flix classes at compile time | `stubs` emits compile-only facades before Java compilation | Generate stubs, compile and jar Java, then compile Flix with `build --lib <java.jar>` |
| A launch classpath can be assembled from guessed output directories | `build/development/build.json` format 4 records `launch.runtimeClasspath` | Read that ordered list after `build`; do not infer paths from generated names |

For a debugger, the binary class names in `debug-index.json`, `debug-scopes.json`, and
`debug-calls.json` describe the current build. Treat them as emitted values rather than translating
old `Def$` prefixes. Rebuild the sidecars with `build --Xdebug` before consuming them; see
[the debugger guide](idea-debugging.md#build-sidecars) for their versions and source identities.
The build manifest remains format 4, but a cached sidecar or class name from a prior build is not
a migration input.

## Stale output

The stub writer owns its destination directory. Each run replaces the generated set, so deleting an
export removes its stub before Java compilation. This makes stale Java calls fail at their source
location instead of surviving until runtime.

The destination itself must be a directory (or not exist yet). If it is an ordinary file, `stubs`
refuses the operation with a concise diagnostic and leaves that file untouched.

## Acceptance criteria

- Stub generation succeeds when unresolved Java references in non-exported Flix bodies prevent a
  normal compilation.
- Generated primitive signatures match the real emitted facade descriptors.
- Deep namespaces use the exact sibling facade binary name.
- Generated sources compile with `javac`.
- A regression fixture compiles Java against a stub, compiles Flix against that Java jar, removes
  the stub from the runtime classpath, and invokes Java through the real emitted facade.
- Unsupported boundary types are reported and no partial stub set is published.
- The CLI and Java client negotiate their contract version before a build tool invokes commands.

Generic and converted-container acceptance tests belong to a future export-ABI expansion; they must
not be claimed by this branch until the real generated facades support them.

The caller-visible spelling is centralized in `ExportSignature`: it retains generic arguments for
Java source and signature attributes while exposing their erased JVM descriptor. This model does
not itself admit a type at the boundary. `EntryPoints` and code generation must gain and test the
matching runtime conversion before stub generation may use a new signature shape.

`--lib` is repeatable on `check` and `build`. It supplements dependencies from `flix.toml` without
writing build output into package-manager-owned cache directories. Dependencies are fixed when a
`Flix` instance is constructed, so project builds combine these paths with the bootstrapped project
jars before compilation begins.

Build tools should add `--diagnostics-json` to `check` or `build`. The command then writes one
versioned JSON document to standard output containing success, the compiler version, and structured
diagnostics. Source ranges use the same zero-based convention as LSP. Compiler errors remain
structured inside `BootstrapError` until the caller chooses human or machine rendering; they are
not reconstructed from console text. For `check`, explicitly named `.flix` files remain the complete
input set when `--diagnostics-json` is present; selecting a machine-readable output format never
switches the command back to whole-project discovery.

Build plugins can depend on the standalone `flixClient` Java module instead of parsing that JSON
themselves. `FlixCompiler` exposes only capability negotiation, checking, building, and stub
generation. It deliberately exposes no compiler AST or `Flix` instance and has no Scala runtime
dependency; `CliFlixCompiler` is one subprocess transport behind that stable surface.

`CliFlixCompiler` accepts a `FlixProcessRunner`. Gradle, Mill, and other hosts can therefore retain
their own process lifecycle, logging, cancellation, and sandbox integration while sharing the
contract parser. The default runner uses `ProcessBuilder`, inherits stderr, and reads stdout before
waiting so a full pipe cannot deadlock the compiler process.
The convenience constructor locates `java` below the running JVM and uses `java.exe` on Windows.
