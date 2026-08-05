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

**Building it shortened it.** Criterion 1 needs only passes 0, 2 and 3 — javac
compiling the real Java sources against the facade stub *is* the producer of the
Java signatures Flix then compiles against, so pass 1 has nothing left to do.
Pass 1 is required only when a Java *signature* names something that does not
exist yet, which is criterion 3. Scheduling it unconditionally would double the
javac invocations on every build for a case most projects do not have.

Pass 0 is cheap: Flix already stops after parse+weed, and `@Export` signatures
are derivable there. Pass 1 is the technique `kapt` uses for Kotlin — `javac`'s
`JavacTask.parse()` succeeds on sources whose references do not resolve, because
parsing does not resolve, so signatures can be read from the parse tree and
emitted as stubs.

Pass 0 must name Java types the same way the backend does, or a caller compiles
against a stub the real facade does not match. That mapping lived inside
`ExportPlan`, which pass 0 cannot construct — it reads tag ordinals off compiled
enums, and at pass 0 nothing is compiled. `ExportSignature` is the half that
depends on nothing but the type, extracted so both use it; `ExportPlan` derives
one rather than holding one, so the two cannot disagree.

- **Cost:** almost entirely in the build plugin. The one compiler change is that
  extraction, which is a refactor with no behaviour change.
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

1. **Met.** A Java class calls an `@Export`ed Flix def, **and** a Flix def calls a
   method on that same Java class — in one source set, with no ordering hint from
   the author. `TestExportStubs`, "criterion 1", runs the whole scheme: pass 0
   derives the facade, javac compiles `Helper` against it, the stub classes are
   **deleted**, Flix compiles against the real `Helper`, javac recompiles against
   the real facade, and both directions are then called. Deleting the stubs is
   what makes it evidence rather than a demonstration — whatever links afterwards
   is the real facade.
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

The Mill plugin shells out to `flix.jar`, so it can do none of this. The Gradle
plugin no longer does: it calls `Bootstrap` inside a Gradle worker process, which
is what surfaced the embedding blocker in §7. The prerequisite work is the same
for both and is worth doing on its own merits:

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

### Two constraints the implementation ran into

- **`Flix.addJar` takes a jar, never a directory of class files.** So a build tool
  must package the Java side between passes, which is real per-build cost for no
  benefit — javac wrote those classes to a directory a moment earlier. Accepting a
  directory is a small, self-contained upstream improvement.
- **Nothing stops after the weeder.** `Flix.check` runs the whole pipeline, and
  the cached parse and weeded roots it exposes are filled in only when the
  *resolver* succeeded — so in the one case pass 0 exists for, they are empty.
  `ExportStubs` therefore drives `Reader`/`Lexer`/`Parser2`/`Weeder2` itself. That
  needs the fork-join pool, which only `check` and `compile` set up, so
  `Flix.withThreadPool` was added to make the lifecycle available without
  duplicating it.

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

## 7. Blocker found by running it: Flix did not resolve its own runtime when embedded

**Fixed.** The diagnosis is kept because it is a prerequisite an upstream reviewer
will want to see argued, and because the first reading of it was wrong.

The first consumer build against the new Gradle plugin failed while compiling the
*standard library*, before reaching any user code:

```
-- Resolution Error [E1803] ------------------------------- Sys/Env.flix
>> Undefined Java class 'dev.flix.runtime.Global'.
21 |     import dev.flix.runtime.Global
```

The compiler ran — `Bootstrap` and `Flix` loaded and reported a normal Flix
diagnostic — so this is not a classpath mistake in the plugin. It is the
embedding path itself.

Two facts locate it:

```console
$ grep -c "dev.flix.runtime" main/src/ca/uwaterloo/flix/util/ClassList.txt
0
```

```scala
// ExternalJarLoader.scala:26
class ExternalJarLoader extends URLClassLoader(Array.empty, ClassLoader.getPlatformClassLoader)
```

`AvailableClasses` is seeded from `ClassList.txt`, which holds JDK platform
classes only and names none of `dev.flix.runtime`. Java classes are then loaded
through `ExternalJarLoader`, whose parent is the **platform** class loader.

The obvious reading is that the platform parent is the bug, since it cannot see
the application classpath. That reading is wrong, and the next section says why.

**This blocks every embedding, not just joint compilation.** Any build tool that
calls the compiler API instead of spawning a process hits it on the first
compile, which is a plausible reason no build tool does.

### Diagnosis, and the fix taken

The first reading above was wrong about the cause, and two experiments settled it
before anything was changed. Running the *identical* project through the CLI
succeeded; re-running Gradle after that still failed. So the project and its
classpath were fine and the difference was in how the compiler was loaded.

The platform parent is not the cause. It is deliberate — it stops compiled Flix
code reaching the compiler's classes — and a whitelist for exactly these names
already existed beside it. The cause was what the whitelist resolved *against*:

```scala
// ExternalJarLoader.findClass, before
if (name == "dev.flix.runtime.Global") super.findSystemClass(name)
```

`findSystemClass` searches the loader built from `java.class.path`. That holds
the compiler only under `java -jar flix.jar`. Embedded — a Gradle worker, an IDE,
a test harness — the system loader holds the *host's* classpath and the compiler
sits in a child loader, so the whitelist found nothing.

Fixed in `fix: resolve the Flix runtime from the compiler's own loader, not the
system one` by resolving against `classOf[ExternalJarLoader].getClassLoader`,
which is correct in both cases because `dev.flix.runtime` ships in the same
artifact as that class. The platform parent is untouched; this widens only the
two names already whitelisted, and only to the artifact they are already part of.

Verified by the full suite — 88 suites, 0 aborted, 16,946 tests, 0 failures. That
is the relevant evidence rather than a smoke test: `dev.flix.test.*` is the other
whitelisted prefix, so `StandardLibrarySuite` and every Flix test file naming a
test Java class exercise the changed path directly.

**Still worth proposing upstream on top of it:** letting an embedder supply the
loader explicitly. The host knows its own classpath and the compiler should not
have to infer it. The fix above is the right default and removes the blocker; an
explicit hook is the honest interface.
