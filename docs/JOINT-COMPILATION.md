# Joint Compilation of Flix and Java

**Status: design, not implemented.** This document is the blueprint for the work
and is written to be the front matter of an upstream pull request. Every number
in it was measured against this tree; the commands are given so they can be
re-run rather than trusted.

## 1. What "joint compilation" means, and why Flix does not have it

Gradle's Scala plugin compiles Java and Scala sources **together**, in one pass,
from one source set. `scalac` reads Java *source* to extract signatures, so Scala
code may reference Java classes that have not been compiled yet; `javac` then
compiles those Java sources against the class files Scala produced. The two
languages may therefore reference each other **cyclically** within a source set.

Flix cannot do this. Its two directions are separate mechanisms running in
separate passes:

| Direction | Mechanism | Needs |
| --- | --- | --- |
| Flix → Java | `import java.util.ArrayList`, `obj.method()` | Java **class files** on the classpath |
| Java → Flix | `@Export` facade, `public static` methods | Flix codegen to have run |

So a Java class that calls Flix must be compiled *after* Flix, and a Flix module
that calls Java must be compiled *after* that Java class. When both hold at once
the build has a cycle and no ordering exists.

### This is not hypothetical

It was hit in `wstein/rewrite-fork` and cost a real workaround. `Hello.kt`
contained both helpers that Flix called and a function that called back into
Flix. Flix compiles against `kotlin-interop.jar`, so the second could not exist
before Flix had compiled. The resolution was a runtime-reflective bridge that
reached into the compiler's *generated* classes — `OpenRewrite.Hello$Def$greet`,
`staticApply`, the `Value$` payload fields, plus `@DontInline` to stop the
optimiser deleting the class being looked up. It broke when the backend renamed
those classes, which it is entitled to do.

The eventual fix was to split the Kotlin into two source sets so the dependency
ran one way. **That is the workaround joint compilation exists to remove**: it
forces an author to partition code by build order rather than by meaning.

## 2. Why the obvious fix is the expensive one

The natural design is the Scala one: teach Flix to read Java *sources* for
signatures. That means a Java type could be known to Flix before any class file
for it exists.

It is expensive here because **a Java type in Flix is a live `java.lang.Class`
object**, from resolution all the way to bytecode generation, with no name-based
intermediate representation.

```console
$ grep -rn "Class\[?\]" main/src/ca/uwaterloo/flix/ | wc -l
146
$ grep -rl "Class\[?\]" main/src/ca/uwaterloo/flix/language/ast/*.scala | wc -l
14
```

The 14 files are not peripheral. They include `Type.scala`,
`UnkindedType.scala` and `TypeConstructor.scala` — so `TypeConstructor.Native`
*holds a `Class[?]`*, and every AST stage that carries a type carries the live
class with it: `ResolvedAst`, `KindedAst`, `TypedAst`, `MonoAst`, `SimplifiedAst`,
`LiftedAst`, `ReducedAst`, `ErasedAst`, `JvmAst`, `AtomicOp`, `SimpleType`. A
further 8 sites hold `java.lang.reflect.Method` and `Constructor` directly.

Resolution itself is reflective:

```scala
// Resolver.scala:3252
Result.Ok(Class.forName(className, initialize, flix.jarLoader))
```

`AvailableClasses` is only a *name* index (package → class names, and its
inverse). It carries no members, so every member lookup goes through reflection
on a loaded class.

**Consequence:** supporting source-derived Java types means introducing an
abstraction over "a JVM type" with a reflective and a source-backed
implementation, and threading it through the core type representation and eleven
AST stages. That is a large, invasive change to the heart of the compiler — not
a peripheral feature.

## 3. Two designs

### Option A — Signature stubs, in the build tool (no compiler change)

Break the cycle outside the compiler by giving each side something to compile
against that is *shaped* like the other side but empty.

```
Pass 0   Flix parse only          →  @Export signatures  →  emit Java stubs for facades
Pass 1   javac parse only         →  Java signatures     →  emit Java stubs for Java sources
Pass 2   Flix full compile        (Java stubs on classpath)   →  real classes + facades
Pass 3   javac full compile       (Flix output on classpath)  →  real Java classes
```

Pass 0 is cheap: Flix already stops after parse+weed, and `@Export` signatures
are derivable there. Pass 1 is the technique `kapt` uses for Kotlin — `javac`'s
`JavacTask.parse()` succeeds on sources whose references do not resolve, because
parsing does not resolve, so signatures can be read from the parse tree and
emitted as stubs.

- **Cost:** entirely in the build plugin. Flix is untouched.
- **Buys:** mutual references within one source set, no manual partitioning, and
  the `rewrite-fork` bridge becomes ordinary static calls with no source-set split.
- **Limits:** stubs must be faithful enough for Flix's resolver — signatures,
  supertypes, generics. A Java signature naming a Flix-exported type is handled
  because pass 0 emits those facades first. Anything requiring a Java *body*
  (constant folding of `static final` fields, annotation processors) is not
  covered.

### Option B — Java source signatures in the compiler (the upstream change)

Introduce `JavaTypeRef`, an abstraction with two implementations: `Reflective`
(wrapping `java.lang.Class`, today's behaviour) and `FromSource` (backed by
`javax.lang.model.element.TypeElement` obtained from `JavacTask`). `Resolver`
consults a `JavaTypeIndex` that merges classpath and source-derived types.
`TypeConstructor.Native` holds a `JavaTypeRef` rather than a `Class[?]`, and the
backend demands a `Reflective` — which by then it always has, because codegen
runs after javac in the joint schedule.

- **Cost:** 146 sites, 14 AST files, the core type representation. Needs a
  staged migration (introduce the abstraction with only the reflective case, then
  add the source case) so no single commit is unreviewable.
- **Buys:** true single-pass semantics, no stub generation, and it works for
  *every* build tool rather than once per plugin.

### Recommendation

**Do A first, then B, and use A's tests as B's acceptance criteria.**

A unblocks the Gradle and Mill plugins now, against today's released compiler,
and it produces the thing an upstream PR most needs: an executable specification
of what joint compilation must do. B is the correct end state but is a large
change to the compiler's type representation, and proposing it upstream without
a test suite that pins the semantics would be asking reviewers to take the design
on faith.

**Rejected: doing B only.** It strands every user on a compiler release, and the
plugins would have nothing until it lands.

**Rejected: doing A only.** It duplicates stub generation into every build tool
that wants joint compilation, and the stub fidelity limits above are permanent.

## 4. Acceptance criteria

These are the tests, and they are the same for both options. A build fixture in
which:

1. A Java class calls an `@Export`ed Flix def, **and** a Flix def calls a method
   on that same Java class — in one source set, with no ordering hint from the
   author.
2. The mutual reference crosses a generic type (`List<String>`), to catch stubs
   that erase what the resolver needs.
3. A Java signature names a Flix-exported facade type, which is the case pass 0
   exists for.
4. Deleting the Flix source produces an error naming the *Java* call site, not a
   `ClassNotFoundException` at run time.
5. The `rewrite-fork` case: `Hello.kt`'s helpers and its Flix-calling function in
   one source set, with the `flixBridge` split removed.

Criterion 5 is the one that matters, because it is the real program that forced
the workaround.

## 5. What this changes for the build plugins

Both plugins currently shell out to `flix.jar`, so neither can do any of this.
The prerequisite work is the same for both and is worth doing on its own merits
(see `docs/BUILD-INTEGRATION.md` when written):

- Invoke the compiler through `ca.uwaterloo.flix.api.Flix` in a worker process
  rather than as a subprocess, so diagnostics arrive as `CompilationMessage`
  objects instead of scraped stdout.
- Hold the `Flix` instance in a keep-alive worker. Flix is *already* an
  incremental compiler — `Flix.scala` carries `cachedLexerTokens`,
  `cachedParserCst`, `cachedWeederAst`, `cachedTyperAst` and a `ChangeSet`
  driven by `TypedAst.Root.dependencyGraph` — but that state is per-instance and
  in memory, so every `java -jar flix.jar build` starts from
  `ChangeSet.Everything`. A subprocess per build cannot be incremental no matter
  what the plugin does.

Joint compilation needs the worker anyway: passes 0 and 2 must share one `Flix`
instance, or pass 0's parse is thrown away.

## 6. Open questions

- **Stub fidelity for generics.** Flix's resolver reads generic signatures for
  exported return types (`Optional<String>`). Whether parse-level stubs preserve
  enough is the first thing to test, not to argue about.
- **Annotation processors.** Out of scope for A. Whether B should schedule them
  is undecided.
- **Incremental joint compilation.** Zinc tracks Java→Scala and Scala→Java edges
  in one analysis. Flix's `dependencyGraph` has no Java nodes at all, so the
  first version invalidates the whole Java side when any Flix `@Export` changes.
  Correct, and coarse.
