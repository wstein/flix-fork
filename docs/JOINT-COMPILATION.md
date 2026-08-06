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
3. Compile Flix against the Java classes (and the facade stubs when Java signatures name them).
4. Compile Java against the real Flix output.
5. Put only the real Java and Flix classes on the runtime classpath.

The generated stubs always throw. They are compile-time scaffolding and must never be packaged or
placed on a runtime classpath.

Generate them with:

```console
java -jar flix.jar stubs --out build/flix-stubs
```

With no positional files, the command reads every `.flix` file below `src/`. Positional `.flix`
files may be supplied for non-project layouts. The command does not bootstrap or resolve the
project: that would recreate the dependency cycle it exists to break. It replaces the destination
directory only after every exported definition has a supported signature.

## Export ABI boundary

Stub generation follows the export ABI implemented by this branch, not the richer ABI on other
development lineages. It currently supports:

- `Bool`, `Char`, `Int8`, `Int16`, `Int32`, `Int64`, `Float32`, and `Float64`.
- An explicitly imported `java.lang.Object`.

It refuses `String`, Flix algebraic data types, generic Java types, containers, functions, and other
reference types because `EntryPoints` refuses those exports on this branch. Refusal is intentional:
a missing stub fails the build at generation time, while an incorrect stub compiles and later fails
with a linkage error in innocent calling code.

The tests compare generated stub declarations with the method descriptors on the facade bytecode.
This pins the source-facing stub contract to the backend contract and catches drift between them.

## JVM names

Stubs use the same `Mangle.namespaceFacadeDesc` function as bytecode generation. With the sibling
layout:

| Flix namespace | Java facade |
| --- | --- |
| `PublicApi` | `PublicApi` |
| `Acme.Api` | `Acme.Api` |
| `Acme.Api.Deep` | `Acme.Api$Deep` |

Implementation classes remain siblings of these facades and are not part of the public interop
contract.

## Stale output

The stub writer owns its destination directory. Each run replaces the generated set, so deleting an
export removes its stub before Java compilation. This makes stale Java calls fail at their source
location instead of surviving until runtime.

## Acceptance criteria

- Stub generation succeeds when unresolved Java references in non-exported Flix bodies prevent a
  normal compilation.
- Generated primitive signatures match the real emitted facade descriptors.
- Deep namespaces use the exact sibling facade binary name.
- Generated sources compile with `javac`.
- Unsupported boundary types are reported and no partial stub set is published.
- The CLI and Java client negotiate their contract version before a build tool invokes commands.

Generic and converted-container acceptance tests belong to a future export-ABI expansion; they must
not be claimed by this branch until the real generated facades support them.
