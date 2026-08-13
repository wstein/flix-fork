# The Flix build server

**Status: complete for the scope below — lifecycle, discovery, sources, compiling, the
project queries, running, the JVM environments, testing, reload and cache cleaning.**
This document describes what `flix bsp` does today, what it
deliberately does not do, and why each decision was taken. It is written to be the
front matter of a pull request, in the manner of `docs/JOINT-COMPILATION.md`.

## 1. Which boundary this is

The [Build Server Protocol](https://build-server-protocol.github.io/) standardises
the boundary between an *editor* and a *build tool*, with the editor as client and
the build tool as server. `docs/TOOLING-CONTRACT.md` describes the boundary one layer
down — between a build tool and the compiler — and concluded that BSP was therefore
none of this repository's business, because some other build tool would serve it.

That conclusion does not hold for a plain `flix.toml` project. There, **`flix` is the
build tool**: it resolves dependencies from `flix.toml`, owns `build/`, packages the
jar, and runs the tests. "Leave it to the build tool" leaves it to nobody.

```
editor  --BSP-->  flix bsp        (this document)
editor  --BSP-->  Mill or Gradle  --tooling contract-->  flix.jar
```

Both remain true at once, and neither supersedes the other. A project built by Mill
or Gradle has a build tool above the compiler, and that tool talks to `flix.jar`
through `--diagnostics-json`; a project built by `flix` itself has no such layer, and
`flix bsp` is what an editor connects to.

One constraint from that document is unchanged and binding: **build requests do not
belong in the language server.** `flix bsp` is a separate endpoint with a separate
lifetime. The same paragraph that forbade the language server named the acceptable
alternative — "a compiler daemon on its own endpoint" — and this is it.

## 2. Using it

```
flix bsp-install      # writes .bsp/flix.json so a client can find the server
flix bsp              # serves the protocol on stdin/stdout
```

`bsp-install` is a separate command rather than a flag on `bsp`, because a flag on
`bsp` would also parse while serving, and writing a JSON document onto the protocol
stream is precisely the failure this endpoint is arranged to avoid. It writes
absolutely-qualified paths, since a client runs the command with the *workspace* as
its working directory. A `.bsp/flix.json` that some other tool wrote is refused
rather than replaced; `--force` overrides that, and `--jar` names a compiler jar
explicitly.

`.bsp/` is gitignored: a connection file names a jar on one machine.

## 3. What is served

| Request | Status |
| --- | --- |
| `build/initialize`, `build/initialized`, `build/shutdown`, `build/exit` | served |
| `workspace/buildTargets` | served |
| `buildTarget/sources` | served |
| `buildTarget/compile` | served |
| `buildTarget/inverseSources`, `resources`, `outputPaths` | served |
| `buildTarget/dependencySources`, `dependencyModules` | served |
| `buildTarget/run` | served |
| `buildTarget/jvmRunEnvironment`, `jvmTestEnvironment` | served |
| `buildTarget/test` | served |
| `workspace/reload`, `buildTarget/cleanCache` | served |
| `debugSessionStart` | never (see §12) |

Anything not served is refused with `MethodNotFound`, never answered with an empty
result: an empty answer is indistinguishable from a real one, so a client would draw a
conclusion from it, where a refusal is a fact it can act on.

`BspCapabilities.Implemented` is the single source of truth. It drives both the
advertised capability flags and the refusals, so a request and its advertisement
cannot drift apart, and `TestBspCapabilities` holds them in step in both directions. A
capability is advertised only once the request behind it works — the rule
`CliContract` already states, for the reason it states: a client believes an
advertisement and fails at the point of use, which is the moment a handshake exists to
get ahead of.

## 4. One build target

A Flix project has exactly one target, and this is forced by the compiler rather than
chosen for simplicity.

`src/`, `test/` and the project root are compiled together as a single whole program,
and `@Test` definitions are entry points whose classes are written into the same
output as everything else. A `src`/`test` split would therefore not be a refinement
but a misstatement with a specific consequence: `buildTarget/compile` on `src` would
still typecheck `test/`, so a diagnostic in a test file would be published against the
`src` target, while `buildTarget/inverseSources` would tell the client that file
belongs to `test`. The next compile of `test` would publish against a target that had
never heard of the file, and the original marker would never be cleared. Stale
diagnostics are the failure BSP is most used for preventing.

Development and production are not two targets either. They are two *modes* of one
target — the mode reaches the typer, so they do not compile the same program — and a
client would render them as siblings and compile both, thrashing two output
directories for one set of sources. Only development is exposed.

```
id            file:///abs/path/to/project/?id=main
displayName   the name from flix.toml, else the directory's own name
baseDirectory file:///abs/path/to/project/
languageIds   ["flix"]
tags          ["library"]
dataKind      "jvm", with javaHome and javaVersion
```

The tag is `library`, not `application`: whether the program has a `main` is unknown
until it compiles, and discovery has to answer for a project that does not compile at
all. It is not `test` either — a client turns that into a test source root, which with
one target would place every source in the project under test scope. `canTest` carries
that affordance without the misfiling.

`?id=` is kept even with a single target, so that adding a second one later does not
change the identity of the first. A client stores target ids, and an id that changes is
a target that vanished.

## 5. Compiling

`buildTarget/compile` is a **build**, not a typecheck: it writes class files into
`build/development/class/`, through the same `Bootstrap` path `flix build` uses --
reconciliation of the class directory and the build manifest included. A second
compile path is how a build server's idea of a build drifts from the command's.

It is bracketed by `build/taskStart` and `build/taskFinish` carrying a `compile-task`
and a `compile-report`, because a compile takes seconds and one that reports nothing
looks like a hang. Every start has exactly one finish, including when the body throws
-- a missing finish leaves a progress indicator turning forever and reports no error
anywhere, so `BspTasks.bracket` makes forgetting it impossible.

The result's status is `OK` when the program compiled and `ERROR` when it did not, and
diagnostics are published in both cases. That is the case a `Result` cannot express and
`Bootstrap.CompileOutcome` exists for: a compile can succeed and still have something
to say.

Three properties are worth stating because each is a way to get diagnostics wrong:

- **Positions are zero-based, and the conversion is borrowed rather than repeated.**
  `lsp.Position` is one-*indexed* internally; only `toJSON` and `toLsp4j` subtract. Reading
  `range.start.line` directly reports every diagnostic one line low, which this used to
  do. It now converts through `toLsp4j`.
- **`code` is the stable identifier**, `E2136`, not the category. The language server's
  `code` field carries the *kind* -- "Resolution Error" -- which hundreds of distinct
  errors share and nothing can key on. `CliContract` draws the same distinction.
- **A fixed file is explicitly cleared.** A client shows what it was last told, so a
  document that stops having diagnostics needs an empty report or the marker stays until
  the editor restarts. `DiagnosticLedger` remembers what was reported. After a *failed*
  compile nothing is cleared: a document the compiler did not reach has not been shown
  to be clean, and clearing it would hide a real error.

Source membership is reconciled before every compile. Modification-time polling answers
which *known* files changed, and a file created since the last build is not among them --
so without the rescan a long-lived session never compiles a new source and never stops
compiling a deleted one.

## 6. The project queries

All of them are answered from `ProjectView`, which holds only what is known *without*
compiling — because a client asks them before the first build and while the project is
broken, which is when it needs them most. An implementation that reached for a typed
program would fail exactly then.

- **`inverseSources`** claims the project's own files and returns an empty list for
  anything else. An unknown *document* is an ordinary question — a client asks about
  whatever the user opened — so it is not an error, unlike an unknown *target*.
- **`outputPaths`** names `build/development/class/`, not `build/`. That directory also
  holds generated documentation and coverage reports, and a client told to exclude the
  lot would exclude more than the build's output.
- **`resources`** names `resources/` whether or not it exists, because a client uses it
  to decide what to watch.
- **`dependencySources`** lists the `.fpkg` archives and nothing else. A Maven or url jar
  is compiled Java with no Flix source to show, and the standard library has no file on
  this machine at all; reporting either would name something a client cannot open and
  call it a source.
- **`dependencyModules`** reads the *manifest*, not the resolved jars, because the
  manifest is what names a dependency — a jar in `lib/cache` has a file name where a
  client wants a coordinate. Maven dependencies carry their coordinate under the `maven`
  data kind; a Flix package and a url jar have no such standard shape and are reported
  plainly rather than dressed as something they are not.

Every one of them refuses an unknown target rather than answering emptily, for the
reason given in §3.

## 7. Running

`buildTarget/run` builds the program and then starts it **in a JVM of its own**.
`Bootstrap.run` runs `main` in the compiler's process by reflection, which is right for a
command that exits afterwards and wrong for a server that does not: a `System.exit` in
user code would take the connection with it, an infinite loop would wedge every later
request, and stray threads would outlive the run. Forking makes each of those the
program's own problem and gives a client the exit status it asked for. A run that does
not finish within ten minutes is stopped, because a client cannot cancel a process it
cannot see.

The program's output arrives as `run/printStdout`, the channel this protocol version gives it, so a
client shows it in a run console rather than beside dependency resolution in a build log. The two
streams are merged before they get there, so a program's writes to standard error arrive on that one
as well — the price of preserving the program's own interleaving, which is what someone reading the
output actually needs.

**But only to a client that can receive it.** `run/printStdout` arrived in BSP 2.2, and the
specification still marks it unstable. `BuildClient` in bsp4j 2.1.1 declares `onBuildLogMessage` and
no `onRunPrintStdout` at all, so a 2.1 client does not ignore the notification politely — it has no
method to receive it, lsp4j reports an unsupported method on its side, and the output is lost. To a
user that is a program that printed nothing. The channel is therefore chosen from the version the
client declares in `build/initialize`: 2.2 and later get the print, everything else — including a
client that declared no version — gets `build/logMessage`, which has existed in every version of the
protocol. JetBrains' BSP client declares bsp4j `2.2.0-M2`, the same milestone this fork takes, so it
gets the print; a client still on 2.1 gets its output rather than nothing.

A test's own printing stays on `build/logMessage` regardless. It is not a run, no client is known to
render it better elsewhere, and moving it would put a second thing behind an unstable notification
for no measured gain. A project with no `main` is refused with a message rather than treated as a
run that did nothing: "nothing happened" and "there was nothing to happen" call for different words.

**`workingDirectory` and `environmentVariables` are honoured.** A directory that is not one is
refused by name, because `ProcessBuilder` reports a missing working directory as a generic failure to
start the program, which a user reads as the build server being unable to run their code. The
variables are added to the environment this process inherited rather than replacing it: the protocol
calls them variables to *set*, and a program that suddenly had no `PATH` would fail for reasons
nobody asked for. A client that wants one gone sets it empty.

The classpath is `ProjectView.runtimeClasspath`: the mode's class directory, `resources/`
(because `buildJar` copies it to the jar root, so a program calling
`getResourceAsStream` behaves the same forked as jarred), then the Maven and url jars.
Not the `.fpkg` packages or the standard library — both are Flix *source*, compiled into
the class directory, and a zip of `.flix` files on a JVM classpath is inert.

**And not `flix.jar`.** That is the exclusion that looks like an oversight. The compiler
ships a *mock* `dev.flix.runtime.Global` whose `setArgs` throws "should not be called on
the mock class", so a program that finds the compiler ahead of its own classes dies
before reaching `main`:

```
$ java -cp flix.jar:build/development/class Main
Exception in thread "main" java.lang.RuntimeException: Global.setArgs should not be
called on the mock class
```

The command itself — which `java`, which classpath, which class — is `ProgramRunner`, shared
with `flix run`, which forks for the same reason: a program has to be startable from what a
build left behind, or the command can never skip a compile.

`buildTarget/jvmRunEnvironment` reports that classpath so a client can fork the program
itself — its own console, its own environment, its own debugger. It is the escape hatch
that makes the limits above acceptable. `jvmOptions` is empty because the compiler's own
flags are about the compiler, and `environmentVariables` is empty because a server that
quietly injected its own `PATH` would make a run unreproducible; a client that wants the
parent environment already has it. `jvmTestEnvironment` reports the same list, and that
is the truth rather than laziness: `@Test` definitions are entry points compiled into the
same output, so this compiler has no test-only classpath to report.

`mainClasses` is answered from the *last* compile rather than by compiling, because these
are queries — one that compiled would make describing a project as expensive as building
it.

## 8. Testing

`buildTarget/test` builds the project and runs its tests, reporting **each test
individually** — a `taskStart`/`taskFinish` pair carrying `test-start` and `test-finish`
under one parent task for the run, with a `test-report` on the parent's finish. That is
what a client renders as a test tree, and `Symbol.DefnSym.loc` is what makes each row
clickable.

**There is one runner, and the renderings are sinks over its events.** `Tester` decides
what a test outcome *is* — a `false` result is an assertion failure, a non-false result
that wrote to standard error is also one, a `@Skip`ped test never starts the clock — and
that rule is written down in exactly one place. `flix test` attaches a console rendering;
the server attaches `BspTestSink`. Neither is privileged and neither reimplements the
loop, which is the structural reason the command line and the editor cannot come to
disagree about whether a test passed. `TestTesterSink` pins the events themselves.

The console rendering is the one that must *not* be attached here: it builds a JLine
system terminal and writes to the real file descriptor, which in a server carries the
protocol. §13.

**The tests run in a JVM of their own.** They used to run in the server's, which made three
failures possible and only those three: a test that called `System.exit` **took the server with
it**, a test that looped forever occupied it, and a test that leaked a thread leaked it into a
process that lives for hours. All three are now the fork's problem, and stopping a process is an
ordinary operation where stopping a thread is not one at all — `Thread.stop` was removed from the
JVM, `Process.destroy` was not.

**Nothing had to be generated for it.** The class files are on disk and `tests.json` records where
each test's shim went, so the fork is this same compiler, invoked as `flix test --events-json
--reuse-build` in the project. It reports each test as one line of JSON, which the server parses
back into the events `Tester` emits and hands to the same sink an in-process run used — so there
is still one opinion about what a test outcome is, which a bespoke path from JSON to notifications
would have given up.

Two details are load-bearing:

  - **`--reuse-build` is not an optimisation.** The server has just compiled and is the authority on
    the build; the fork must not ask again, because it would ask under its own options, compute a
    different fingerprint, and write a manifest the server then reads as stale. Two processes each
    invalidating the other's build is two full compiles per test run, forever, with nothing
    reporting it.
  - **The events own standard output.** A test's own `println` would land between two JSON objects
    and end the conversation, which is the hazard `flix bsp` has on its own output. The runner takes
    the real descriptor for the events and points `System.out` at a stream that turns a program's
    writing into `output` events, which reach the client as log messages. A line that is not an event
    at all — a JVM warning — is reported the same way rather than dropped, and emphatically not as a
    failed test called `<runner>`, which is what the first version of this did.

What is still true: the *interleaving* of a program's output against test results is approximate,
because `Tester` runs tests on one thread and reports them from another, so a test's printing can
arrive slightly ahead of the event for the test that produced it. That predates the fork.

`TestParams.arguments` is read as regular expressions selecting which tests to run,
which is what `Tester` already accepts; a client that sends none runs them all. A project
with no tests is a run that succeeded — a project may legitimately have none yet, and
failing would leave an editor's test button permanently red. A project that does not
compile is a run that failed, with the diagnostics saying why and no test events at all.

## 9. Reloading, and clearing the cache

`workspace/reload` re-reads `flix.toml` and the project layout. It is not a rescan of the
sources — every compile already reconciles those (§5). It is for the manifest, which can
add a dependency, change a version or drop one, so the answer to every question this
server serves may change.

**It is transactional.** A fresh `Bootstrap` and a fresh `Flix` are built, and only a
complete one is installed; a `Bootstrap` mutated halfway through re-resolution would
answer some questions from the old project and some from the new. **A reload that fails
changes nothing** — the previous session keeps serving and the request fails — because a
typo in a manifest must not leave an editor connected to a dead server. The generation is
bumped, so a compile already running finishes into a project that no longer exists and
its diagnostics are discarded rather than published. `buildTarget/didChange` follows a
successful reload, so a client re-reads what it cached.

Markers are cleared before they are forgotten. A file the reload dropped from the project
cannot be spoken for by any later compile, so its marker would otherwise stay until the
editor restarted; the next compile republishes whatever is still wrong. A momentary clean
slate after an explicit user action is the better of the two errors.

`buildTarget/cleanCache` empties the target's output — `build/development/`, its build
manifest, and the compiler's in-memory caches — and **nothing else**. It is deliberately
not `Bootstrap.clean`, which resets the whole project: every build mode, plus `doc/`,
`stubs/` and the coverage reports. A client asking to clear a target's cache has not asked
for the API documentation to be deleted. Both halves are needed and neither is sufficient:
emptying the directory alone leaves the cached ASTs that the next build actually reuses,
and discarding those alone leaves the previous build's class files behind.

A failure is reported as `cleaned: false` with a message rather than as a failed request —
the protocol has a field for exactly this, and a client told the cache was not cleaned can
act on it, where a request error would leave it guessing whether anything was deleted. The
state a failure leaves is one the next build recovers from rather than trusts: the manifest
goes first, and a build with no manifest treats everything as new.

`buildTarget/cleanCache` is the one request driven by neither
`BspCapabilities.Implemented` nor a flag, because the protocol gives it no capability
field — there is nothing for it to be out of step with.

## 10. What a client will get wrong

Stated plainly, because each is a real limitation rather than an oversight:

- **There is no test module.** Test sources appear in the ordinary source set and
  `@Test` definitions look like ordinary definitions. Nothing is lost — Flix has no
  test-only dependency scope — but an IDE's "test sources" concept is simply absent.
- **A compile is always the whole program.** A client cannot compile `src` without
  `test`.
- **Development mode only.** `build/production/` and the packaged jar are not
  reachable over BSP, so a client will never see an artifact.
- **`languageIds` is `["flix"]`**, which no stock client knows, so none will route
  Flix files to semantic features. Claiming `"java"` would invite
  `buildTarget/javacOptions`, which is not implemented — a worse trade.
- **`run/readStdin` is not supported.** Honouring it means keeping a program's standard input open
  for the length of a run, and it is closed immediately on purpose: a program that reads input then
  sees end-of-stream and proceeds, where one waiting on input a client may never send would hang
  until the run's timeout. The notification reports itself unsupported on the client's log, which is
  all a notification can do.
- **`buildTarget/jvmCompileClasspath` is refused.** The protocol asks for the jars a *compilation*
  needs, which for a JVM language is where the javac-visible dependencies are. A Flix build resolves
  its own from `flix.toml` and compiles Flix; the only classpath a client can act on is the runtime
  one, and answering with that under this name would describe something else.
- **`originId` appears on the reports, not on the task notifications.** `CompileReport` and
  `TestReport` carry it and do; `TaskStartParams` and `TaskFinishParams` have no such field in this
  protocol version, so a client correlates a task through its `taskId` and the report payload.
- **Some sources cannot be opened.** A diagnostic in the standard library or inside a
  `.fpkg` dependency is reported against a `flix-lib:` or `jar:` URI. That is
  deliberate; see §14.

## 11. Debugging, without a debug adapter

`canDebug` is false and `debugSessionStart` always fails, because that request must return the
address of a **DAP** server and Flix has none. What it does not mean is that a Flix program cannot
be debugged: this compiler emits JSR-45 `SourceDebugExtension` tables and line numbers under
`--Xdebug`, so a JVM debugger that attaches to a running program can show Flix source and stop on
Flix lines. IntelliJ's "Remote JVM Debug" and the VS Code Java extension both attach to a JDWP port
directly, which covers the case a DAP would.

The recipe, for a program:

```
flix build --Xdebug
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 \
     -cp "$(the classpath from buildTarget/jvmRunEnvironment)" Main
```

and for the tests, the same with the runner the server itself uses:

```
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 \
     -cp flix.jar ca.uwaterloo.flix.Main test --reuse-build
```

Then attach the editor to port 5005. `buildTarget/jvmRunEnvironment` reports the classpath for the
first, which is what it is for; the second needs only the compiler's own jar, since the runner reads
`tests.json` and the class directory.

This is deliberately documentation rather than a capability. Advertising `canDebug` and returning a
JDWP address would be answering a question about DAP with something that is not DAP, and a client
would fail at the point of use — the exact mistake `BspCapabilities` exists to prevent.

## 12. What is not built, and why

- **`debugSessionStart`.** Flix has no debug adapter, so there is no address to return.
  `canDebug` is false and the request always fails. Unlike everything else that was once
  in this list, it is not waiting for anything: it is refused permanently, and asserted
  to be.
- **Running is forked, so a program cannot be debugged through the server.** Use
  `jvmRunEnvironment` and start it yourself.
- **A per-test timeout.** The fork bounds a whole test run at thirty minutes, so one test that never
  returns can no longer hold a session; it cannot yet say *which* test hung. Doing it properly means
  running each test on its own thread inside the runner and abandoning one that overruns, which is
  safe in a process that is about to exit and is a change to `flix test` for everyone.
- **Watcher-driven recompilation.** `buildTarget/didChange` is announced on a reload, but
  nothing watches the filesystem: a client compiles when it decides to. A watcher needs
  debounce, and the one in this repository is wired only to the REPL.
- **A `src`/`test` target split.** §4. It needs a source-set concept in the compiler,
  and the argument against faking one is a correctness argument.
- **TCP transport and concurrent clients.** stdio only. One `Flix` instance per
  session is the concurrency ceiling regardless.

## 13. Standard output belongs to the protocol

This is the invariant most easily broken by an unrelated edit, so it is stated as a
rule: **never `println` on a code path a BSP request can reach.**

`BspServer.run` takes `FileDescriptor.out` for the launcher before anything else can
write to it, then points `System.out` at `BspLogStream`, which turns each line into a
`build/logMessage` notification. The compiler prints from more places than can be
audited once and trusted afterwards — a crash report, the progress bar, a test
program's own output, and `Bootstrap`'s narration of dependency resolution, which runs
on every start. One stray line between two frames ends the connection.

Redirecting to the client's log rather than to `/dev/null` is deliberate: a crash
report that vanishes is worse than one that arrives somewhere unexpected. Lines
written before a client connects are buffered, bounded and oldest-dropped, because the
interesting failures happen while the project is loading.

`TestBspProcess` asserts that the first byte of a real server's standard output is the
`C` of `Content-Length`, which is the only assertion that can catch a `println` added
anywhere on the initialize path.

## 14. URIs, and why nothing is dropped

`BspUri.ofSource` returns a `String`, not an `Option[String]`. There is no filter, so
there is nothing to drop.

| Input | URI |
| --- | --- |
| `RealFile`, `VirtualFile` | `file:///…` |
| `VirtualUri` | as given |
| `PkgFile` | `file:///…` — the archive is a real file |
| `FileInPackage` | `jar:file:///…!/src/Dep.flix` |
| `BundledLibraryFile` | `flix-lib:/Array.flix` |
| `Unknown` | `flix-lib:/unknown` |

The language server does the opposite and it costs it correctness: it builds an
identifier from `Source.name`, which is `path.toString`, and then filters for
`file://`. Diagnostics from bundled and packaged code disappear there silently, and
ordinary project files would too if documents did not happen to arrive as virtual
URIs. A build reports on the whole program and has no such luck.

Two details are load-bearing and were each established by a failing test:

- **A target id needs an empty URI authority, not a null one.** `new URI(scheme, null,
  path, query, null)` renders `file:/path`, which is legal and is not what
  `Path.toUri` or any other build server produces. A client that computes the id
  itself and compares strings would see a different target.
- **`rootUri` is compared with `toRealPath`, not `normalize`.** `normalize` is
  textual and cannot see a symbolic link, and on macOS every temporary directory is
  under `/var`, itself a link to `/private/var`. The path the JVM reports and the path
  the user opened are then two spellings of one directory, and a correct client is
  refused.

## 15. Session model

One `Bootstrap` and one `Flix` per connection, held by `BspSession`, which also owns
the lifecycle state machine: `Uninitialized`, `Initialized`, `ShutDown`. Requests
before `build/initialize` are refused with `ServerNotInitialized` (`-32002`), which a
client distinguishes from a real failure and retries; requests after `build/shutdown`
are refused with `InvalidRequest`. Both are transitions a server can appear to
survive while leaving a client acting on a reply it should never have received, so
each is asserted in `TestBspLifecycle`.

The project is loaded during `build/initialize` rather than lazily, because that is
the request whose reply a client waits for, and because a project that cannot load
should be reported as a failed initialize rather than as a failed compile. A failure
leaves the connection uninitialized, so a retry gets an honest second attempt.

`ProjectView` is an immutable snapshot, taken once per request. `Bootstrap`'s source
and dependency lists are mutable — a rescan, a watcher event or a reload rewrites them
— so accessors would let one request be answered from two different projects. The
snapshot carries only what is known *without* compiling, which is what lets discovery
answer before the first build and while the project is broken.

The session carries a generation counter, incremented on reload, so that work which
outlives a reload can be recognised as stale rather than published against the project
that replaced it. Nothing produces such work yet; the counter is one field, and the
alternative is to add it after the first stale-publish bug.

**Requests do not run on the connection's thread.** lsp4j reads and dispatches messages
on one thread, so a handler that ran there would stop the connection being read for as
long as it took — and a whole-program compile takes seconds. Nothing else could be
answered in the meantime, including `build/shutdown` and `$/cancelRequest`, which is to
say the server would look wedged exactly when a client most wants to talk to it. Handlers
run on the connection's executor instead; builds are still serialised, by a lock rather
than by the transport.

**A compile with nothing to do does nothing.** `BuildManifest` records a content digest of
every source the build read, so a compile whose sources all hash-match it, whose fingerprint
matches, and whose class directory holds exactly the products the build wrote answers `OK`
without running the pipeline. On the command line that is 4.4 s against 0.44 s for `flix
build` on a fresh project; over a connection it is the difference between an editor's compile
button being instant and being a whole-program compile.

It is a content hash and not a modification time, and that is the whole point: `Source`
equality is by path, mtimes are whole seconds on some filesystems, and a build that trusted a
clock would report success over the previous program's class files. A file touched but not
changed therefore still has nothing to do. Three conditions have to hold and each closes a way
of being wrong — a changed source, a missing product, or a *stray* product all mean a rebuild,
because a class directory holding something no build wrote is the state the manifest exists to
prevent. What is not checked is whether the class files are the bytes this compiler would
emit; a hand-edited class file of the right name is not detected.

`buildTarget/run` takes that path too — it forks against the class directory — which is why
`hasMain` is recorded in the manifest: a build that was skipped produces no typed AST to ask
about the entry point, and a client asking what to run must still be told.

`buildTarget/test` takes it as well, and needs one more thing to do so. A test is a function
this process reflects and calls, so reaching it means knowing which generated class and method
carry its shim — which only a run that loaded the classes knows. That table is written to
`build/development/tests.json` after a test run, and it is deliberately *not* in
`BuildManifest`: that manifest is a record of products, and this is a description of the
program. A wrong manifest costs a rebuild; a wrong test table means the tests someone believes
ran did not.

So the table is confirmed rather than believed. Beyond the up-to-date conditions above, every
method it names must resolve in the class files that are on disk now, or the run compiles — and
says so, rather than testing whatever it could resolve. A table with no tests is refused
outright while the project has test sources, because "no tests" and "the tests were not
recorded" look identical from here and only one of them should report a green run.

**Concurrent compiles are coalesced, under one condition.** An editor compiles on save, and
a person saving repeatedly used to queue one whole-program compile per keystroke — each
taking seconds, each already obsolete before it started. A request may now share another's
build, but **only if that build has not started yet**: the claim is registered before the
build lock is taken and released once it is held, so every sharer arrived before the compile
it shares began reading the sources. Nobody is told about a build that predates their edit.
A request arriving while a build is *running* takes the next slot instead, so two concurrent
saves cost two builds — and so do twenty. Only the request whose build ran publishes the
diagnostics; a second publication of the same reports would resend every marker and clear
nothing. `buildTarget/run` and `buildTarget/test` are not coalesced: they have effects their
caller asked for, so sharing one between two requests would answer a question nobody asked.

**Cancellation reaches the work, and how far depends on the request.**

  - **A compile** is soft-cancelled: the build finishes and its result is dropped. The compiler's
    ForkJoin pool and `JvmWriter`'s writes are not interrupt-safe, and the class directory must be
    reconciled and its manifest written or the build directory describes nothing. A late answer is
    recoverable; a half-reconciled output directory is the failure `compileProject` exists to
    prevent.
  - **A run** is killed, and so is everything it started. `Process.destroyForcibly` kills one
    process; a program that spawned a child leaves it alive, holding the pipes the run was reading
    and writing output after the task that owned it reported finished. The descendants are
    snapshotted before the root is killed, because once it is gone they are reparented and cannot be
    found. Dropping the reply and leaving the program running would hold the build
    lock, the output stream and the program's own resources until it happened to end, which is not
    cancellation in any sense a user would recognise. `Cancellation` carries the signal to the
    process handle, and it handles the race where the client gives up while the process is starting.
  - **A test run** stops between tests. The test in flight finishes, because a test is a compiled
    function called by reflection and a JVM cannot safely stop a method in the middle -- `Thread.stop`
    was removed because it left locks in states nothing could reason about. So the guarantee is that
    no *further* test starts, and it is a real one: a run of a thousand tests stops in milliseconds
    rather than minutes. A client that needs a hard stop forks with `jvmTestEnvironment`.

In every case lsp4j answers the cancelled request with `RequestCancelled`, and the task pair
finishes as `CANCELLED` so a client's progress display agrees with what happened.

**There is a limit, it says no rather than queueing forever, and it is taken before the work is
submitted.** At most 32 build requests may be in flight; beyond that a request is refused with a
message rather than parked. The ordering is the substance: a permit acquired *inside* the work has
already cost the thread it was meant to prevent, so the refusals arrive after the damage. Requests are
dispatched off the connection's thread and builds are serialised, so surplus work costs a platform
thread each -- and the pool is deliberately unbounded, because a ten-minute run must not starve a
query. Something has to be the bound, and a refusal a client can read is more useful than
discovering the limit as an `OutOfMemoryError`. No editor comes close; the coalescing above already
collapses a burst of compiles into two builds.

**A log line is bounded too.** `BspLogStream` reports a line longer than 32 KB truncated, once, and
discards the rest of it. A program writing megabytes without a newline -- a progress bar, a stack
trace rendered without breaks -- would otherwise grow the buffer without limit, and turning it into
a hundred notifications would replace a memory problem with a traffic problem.

**Nothing is published after `build/shutdown`.** One accessor decides whether a client may
still be told anything, and every notification goes through it, because asynchronous
dispatch makes "work that outlives the shutdown that cancelled it" a real window rather
than a theoretical one. Shutdown also bumps the generation, so an in-flight build's result
is discarded rather than published.

**The handshake has three states, not two.** `build/initialize` moves to *awaiting
acknowledgement*, and requests are refused with `ServerNotInitialized` until `build/initialized`
arrives — which is what the specification requires of a client, and collapsing it into "ready"
would accept a sequence no client may send. A duplicate acknowledgement cannot be refused, since a
notification has no reply; it is reported on the client's log and changes nothing, so a stray one
cannot revive a session that has been shut down.

**`build/shutdown` is a request, and answers to the same state machine.** Before the handshake it
is `ServerNotInitialized`, before the acknowledgement likewise, and a second one is an
`InvalidRequest`. It used to be the single request that bypassed the model, which made it possible
to shut down a session that had never started.

**A client that was offered no target cannot operate on one.** The target's id is derived from the
project path, so a client filtered out by the language negotiation can still compute it and ask for
a compile. Every target-scoped request therefore checks that a target was offered, not merely that
the id is one this server knows — otherwise the filter would shape one reply and guard nothing.

**The exit status is the client's, not a constant.** `build/exit` after `build/shutdown` exits 0;
`build/exit` without one exits 1. A connection that simply ended asked for nothing and exits 0.
`TestBspProcess` asserts both, against real processes, because nothing smaller can.

## 16. Acceptance criteria

Each names the test that pins it.

1. **A request before `build/initialize` is refused as not initialized, by code.**
   `TestBspLifecycle`, "a request before initialize is refused as not initialized" —
   `-32002`, not an empty result, because a client retries the first and believes the
   second.
2. **A request after `build/shutdown` is refused.** `TestBspLifecycle`, "a request
   after shutdown is refused".
3. **Advertisement equals implementation, in both directions.**
   `TestBspCapabilities`, "every advertised capability is one the server implements" —
   read back out of the result object, so a flag wired to the wrong feature is caught
   rather than confirmed.
4. **A client that does not speak Flix is told about no targets.**
   `TestBspLifecycle`, "a client that does not speak flix is told about no targets" —
   the specification forbids answering otherwise.
5. **An unknown target id is refused, not answered emptily.** `TestBspLifecycle`, "an
   unknown target is refused rather than answered with nothing".
6. **Every kind of source gets a parseable URI.** `TestBspUri`, "every kind of input
   gets a parseable uri", plus round trips over paths containing a space, `#`, `%`, a
   non-ASCII character and a quote.
7. **A `rootUri` that spells the project differently is still the project.**
   `TestBspLifecycle`, "a rootUri that spells the project differently is still the
   project" — initialised through a symbolic link.
8. **The connection file names a command that really starts a server.**
   `TestBspProcess`, "the connection file names a command that really starts a
   server" — the `argv` is executed and a handshake completed through it, because a
   discovery file naming a command that does not work is invisible to any assertion
   about the document's shape.
9. **Standard output carries protocol frames and nothing else.** `TestBspProcess`,
   "standard output carries protocol frames and nothing else".
10. **A connection file this server did not write is left alone.** `TestBspProcess`,
    "a connection file this server did not write is left alone".
11. **bsp4j works against the `lsp4j.jsonrpc` this compiler runs.**
    `TestBspLinkage` — two real launchers over pipes, an initialize round trip, a
    populated `publishDiagnostics`, and an int-valued enum read off the wire as a
    number rather than a name.
12. **The assembled jar can render a protocol object with nothing else on its
    classpath.** `TestBspAssembly` — `jshell --class-path` naming only the jar.
13. **A compile writes class files.** `TestBspCompile`, "a compile writes class files,
    because a compile means class files" — answering from `check` alone would be cheaper
    and would leave a client with nothing to run.
14. **One task start, one task finish, with the right data kinds.** `TestBspCompile`,
    "a clean project compiles, and says so in one task start and one finish".
15. **An error carries its stable code at a zero-based range.** `TestBspCompile`, "an
    error is reported at its own range, keyed by its stable code".
16. **Fixing an error clears its marker.** `TestBspCompile`, "fixing an error clears the
    marker it left" — the assertion a naive implementation fails.
17. **A failed compile clears nothing it could not speak for.** `TestBspCompile`, "a
    failed compile does not clear markers it could not speak for".
18. **A created or deleted source is seen by the next compile.** `TestBspCompile`, "a
    compile after a source is created sees it" and "…is deleted sees that too".
19. **Discovery answers for a project that does not compile.** `TestBspQueries`,
    "discovery answers for a project that does not compile" — the property that makes
    answering from `ProjectView` rather than from a typed program the right choice.
20. **`inverseSources` claims its own files and disclaims others.** `TestBspQueries`,
    "inverseSources claims the project's own files and disclaims others".
21. **`outputPaths` names the class directory, not `build/`.** `TestBspQueries`,
    "outputPaths names the class directory, not the whole build directory".
22. **A Maven dependency is reported with its coordinate.** `TestBspQueries`, "a maven
    dependency is reported as a maven module with its coordinate".
23. **Every query refuses an unknown target.** `TestBspQueries`, "every query refuses a
    target it does not have".
24. **The reported classpath actually starts the program.** `TestBspRun`, "the reported
    classpath actually starts the program" — executed in a fresh JVM, because a path list
    that looks right is exactly the artifact that rots.
25. **The compiler's own jar is not on it.** `TestBspRun`, "the compiler's own jar is not
    on the program's classpath".
26. **A program's failure is reported as one.** `TestBspRun`, "a program that fails
    reports a failure" — a program that exits 3 must not be reported as `OK`.
27. **A project with no main is refused, not silently ignored.** `TestBspRun`, "a project
    with no main is refused, not silently ignored".
28. **A program that does not compile is not run.** `TestBspRun`, "a program that does not
    compile is not run".
29. **Every test is reported individually, with the right status.** `TestBspTest`, "a run
    reports every test, individually and in a tree" — a pass, a failure and a `@Skip` in
    one fixture, three `test-finish` notifications, a `test-report` counting 1/1/1, a
    failure that carries its output, a clickable location on each, and every one of them
    nested under the run's own task.
30. **A filter selects which tests run.** `TestBspTest`, "a filter selects which tests
    run" — and the run then succeeds despite the project containing a failing test.
31. **A project that does not compile runs no tests.** `TestBspTest`, "a project that does
    not compile runs no tests" — an error, with diagnostics, and no test events.
32. **A project with no tests is not a failure.** `TestBspTest`, "a project with no tests
    succeeds and reports nothing".
33. **The command line and the editor cannot disagree about an outcome.**
    `TestTesterSink` — one runner, and the events it emits are pinned: every test
    reported, a skip announced without being started, a failure carrying its output, one
    terminal event, and success decided by the runner rather than by a rendering.
34. **A manifest change takes effect on reload, and only then.** `TestBspReload`, "a
    manifest change takes effect on reload, and the client is told" — including the
    `didChange` event naming the target the client has already seen.
35. **A reload that fails changes nothing.** `TestBspReload`, "a reload that fails leaves
    the previous configuration serving" — the target still answers and the project still
    builds after a manifest that is not TOML at all.
36. **A reload clears what it can no longer speak for.** `TestBspReload`, "a reload clears
    the markers it can no longer speak for".
37. **Cleaning the cache is scoped to the target's output.** `TestBspReload`, "cleaning the
    cache empties the class directory and nothing else" — the class files go, the build
    manifest goes, `build/doc/` stays, and the next compile rebuilds. That distinction from
    `Bootstrap.clean` is the whole point of the request having its own path.
38. **A session survives every way a client can get it wrong.** `TestBspMatrix`, "a session
    survives every way a client can get it wrong" — an unknown target, a duplicated target,
    a cancelled build, a source that does not compile and a manifest that is not TOML, in
    sequence against one session, with ordinary work asserted to work after each. No
    single-condition test can see this, and it is the failure an editor user experiences.
39. **A slow build does not stop the connection being read.** `TestBspMatrix`, "a slow build
    does not stop the connection being read" — a query is answered while a compile is still
    running. If this fails by timing out, dispatch has moved back onto lsp4j's thread.
40. **Nothing is published after shutdown.** `TestBspMatrix`, "nothing is published after
    shutdown" — asserted against a build that was already running when the shutdown arrived.
41. **Concurrent compiles share a build that has not started.** `TestBspCompile`, "concurrent
    compiles that arrive before a build starts share it" — six requests, two builds, six
    answers and six task pairs. And its converse, "a compile issued after a build started
    gets its own build", which is the condition that makes sharing honest.
42. **A compile with nothing to do does nothing, and still clears what it must.**
    `TestBspCompile`, "a compile with nothing changed does no work" — the class files are not
    rewritten — and "a compile with nothing to do still clears the markers of a failure", which
    is the case a naive skip gets wrong: a failed compile writes no manifest, so restoring the
    sources puts the project back into the recorded state with the failure's markers still on
    screen. `TestBspRun`, "a run after a compile that had nothing to do still finds main".
43. **A second test run does no work and reports the same thing.** `TestBspTest`, "a second test
    run does no work, and reports the same thing" — no class file rewritten, the same three
    outcomes reported again, and every result still clickable, which is what the recorded
    location is for.
44. **A cancelled run stops its program.** `TestBspRun`, "a cancelled run stops its program" — and
    the evidence is not a timing measurement: the program's timeout is ten minutes and it holds the
    build lock, so a compile that answers at all proves the process was killed.
45. **A run that never ends is stopped by its timeout.** `TestBspRun`, "a program that never ends is
    stopped by the timeout", and "output with no newline is reported, and does not hold the timeout
    open" — the two shapes that made the previous supervision unable to ever fire.
46. **A cancelled test run starts no further test and still ends.** `TestTesterSink`, "a cancelled run
    starts no further test, and still ends" — including the terminal event, without which a reporter
    waits forever and a cancellation becomes a hang.
47. **The handshake and the exit status are wire-exact.** `TestBspLifecycle`, "a request before the
    client acknowledges initialize is refused", "a duplicate acknowledgement is reported and changes
    nothing", "a client that advertises no language at all is told about no targets";
    `TestBspProcess`, "exit after shutdown is a success, and exit without one is not".
48. **A flood of requests is refused, not queued forever.** `TestBspMatrix`, "a flood of requests is
    refused rather than exhausting the server".
49. **A package entry with awkward characters is encoded.** `TestBspUri`, "an archive entry with
    awkward characters is encoded, not pasted in" — a raw `#` would turn the rest of an entry into a
    fragment.
50. **A framed cancel request stops the run it names.** `TestBspProcess`, "a framed cancel request
    stops the run it names" — the real wire against a real process, which is the only place the
    lsp4j wiring between a client's `$/cancelRequest` and this server's handler is exercised rather
    than one side of it.
51. **Stopping a program stops what it started.** `TestBspRun`, "stopping a program stops what it
    started" — a shell whose child keeps writing to a file, so that a surviving descendant is
    observable rather than inferred.
52. **Shutdown answers to the state machine.** `TestBspLifecycle`, "shutdown answers to the state
    machine like any other request" — before initialize, before the acknowledgement, and twice.
53. **A client offered no target cannot operate on one.** `TestBspLifecycle`, "a client offered no
    target cannot operate on one".
54. **The request bound is measured, not assumed.** `TestBspMatrix`, "a flood of requests is refused
    rather than exhausting the server" — eighty requests, and an assertion on how many threads the
    connection's executor ever held.
55. **A test that exits the JVM does not take the server with it.** `TestBspTest`, "a test that exits
    the JVM does not take the server with it" — the run reports a failure and the session still
    compiles and still runs tests. This is the assertion the fork exists for.
56. **A real server process completes the whole cycle.** `TestBspProcess`, "a scripted
    session drives a real server through the whole cycle" — the assembled jar, started from
    the connection file it wrote, driven over hand-written frames through initialize,
    targets, sources, compile, run, test, shutdown and exit. The only case where nothing is
    stubbed, and so the only one that can see a packaging fault.

## 17. Running the tests

```
./mill flix.test        # the in-process tests, with the rest of the suite
./mill flix.testBsp     # the ones needing a built assembly or a process of their own
```

`flix.testBsp` builds the assembly itself. The suites it runs are `@DoNotDiscover`
and gathered in `BspSuite`: a test that cancels itself when the jar is missing
reports green while proving nothing. `.github/workflows/bsp.yaml` runs it on every
pull request, and needs no token because nothing there reaches the network.
