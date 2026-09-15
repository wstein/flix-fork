For Flix, the best answer is two-tiered:

| Goal | Best solution | Rating |
|---|---|---:|
| Browser playground / REPL soon | Run the existing JVM compiler under **CheerpJ** | 4/5 |
| Small, fast production Flix applications in browsers | Build a **native Flix → WasmGC backend** | 5/5 |
| Compile Flix JVM bytecode with TeaVM/JWebAssembly | Avoid | 1/5 |
| Run Wasm inside a JVM | Irrelevant to browser execution | 0/5 |

CheerpJ is the only credible short-term bridge because Flix generates JVM bytecode at runtime and loads it with a custom `ClassLoader`; CheerpJ explicitly supports reflection and dynamic class loading. It can run existing JARs in-browser without source transformation. [CheerpJ overview](https://cheerpj.com/docs/overview.html), [architecture](https://cheerpj.com/docs/explanation/architecture)

There is an important qualification: this checkout uses JDK 21, while CheerpJ
documents Java 8, 11, and 17 support. Flix currently emits Java 21 class files
(class-file major version 65), so Java 17 compatibility is not an out-of-the-box
compiler option. Nor does setting `--release 17` in the compiler build solve
that problem: it controls compilation of the compiler's own Java sources, not
the class files generated for Flix programs.

### Java 17 compatibility implementation

A Java 17 generated-program target is feasible as an experimental feature. It
is useful for an ahead-of-time pipeline that runs Flix on Java 21 in CI or on a
server, then hands Java-17-compatible program classes to a WebAssembly
translator. It does **not** by itself make the Flix compiler runnable inside a
Java 17 browser JVM; that separately requires the compiler, its dependencies,
and every compiler-side Java API use to be Java-17-compatible.

The first implementation should be deliberately narrow:

| Concern | Java 17 target requirement |
|---|---|
| Class-file format | Add an experimental `--Xjvm-target=17` backend choice; parameterize the ASM class writer and emit `V17` (major version 61). Keep Java 21 as the default. |
| Runtime APIs | Emit no references to Java 21 virtual-thread APIs (`Thread.ofVirtual`, `Thread.startVirtualThread`, or their builder types). |
| Semantics | Initially require `--Xsequential`: preserve its sequential lowering of `par (...) yield`, reject `spawn`, and do not silently replace virtual threads with platform threads. |
| Tests | Assert class-file major version 61, execute the sequential runtime and reachability suites on a real Java 17 runtime, and scan generated constant pools for forbidden virtual-thread references. |
| Compatibility | Retain the existing Java 21 parallel runtime unchanged when the target is omitted. |

`--Xsequential` is the practical enabler: it eliminates the generated region,
lazy-value, global-counter, and parallel-expression paths that otherwise reach
Java 21 virtual-thread APIs. The target must nevertheless prove this by testing
the emitted bytecode, rather than assuming tree shaking removed every reference.

For CheerpJ specifically, do a one-week spike only after deciding whether the
goal is artifact translation or running the compiler in-browser. For the latter,
package a minimal Java-17-compatible compiler/JAR, run a `main` and a `@Test`
through CheerpJ, and test dynamic generated-class loading,
ASM/ByteBuddy-dependent Java interop, worker/thread behavior, and download
size. If any fails, stop rather than patching around the JVM emulation.

TeaVM and JWebAssembly are poor fits for the existing compiler. They are ahead-of-time Java-to-Wasm translators and require a browser-compatible, closed-world Java subset. Flix’s compiler uses class loading, runtime bytecode generation/loading, JVM metadata, and broad Java interop. JWebAssembly itself notes that browser sandbox restrictions and native JVM features need replacements. [JWebAssembly limitations](https://github.com/i-net-software/JWebAssembly)

For a real browser target, compile Flix’s lowered/monomorphized IR directly to WasmGC:

- Browser-safe standard library profile; reject JVM interop, file/network APIs, reflection, and unsupported `unsafe IO`.
- WasmGC objects/arrays for Flix heap values; JS imports for DOM, timers, fetch, storage, and console.
- Map Flix effects to explicit imports/capabilities.
- Use Web Workers only as an explicit parallel runtime; make sequential execution the baseline.
- Keep the compiler server-side initially; ship only application Wasm to users.

Do not make “JVM → Wasm” the production architecture. It ships a JVM plus much of the JDK to execute a language that Flix already compiles itself. CheerpJ is valuable as a compatibility prototype, not as Flix’s final web backend.
