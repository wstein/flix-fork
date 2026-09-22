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

Stub generation follows the export ABI implemented by this branch, not the richer ABI on other
development lineages. It currently supports:

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
- Explicitly imported Java classes, including nested generic arguments in parameters and results.

It refuses other Flix algebraic data types, other containers, functions, and unaccounted reference
types because `EntryPoints` refuses those exports on this branch. Refusal is intentional:
a missing stub fails the build at generation time, while an incorrect stub compiles and later fails
with a linkage error in innocent calling code.

Converted containers are result-only. `Option[t]`, `List[t]`, and `Vector[t]` parameters remain
refused because the shim currently passes parameters straight into Flix and therefore has no
reverse conversion.

The tests compare generated stub declarations with the method descriptors on the facade bytecode.
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
