# The Flix build server

**Status: lifecycle, discovery, sources, compiling and the project queries are
implemented. Running and testing are not.** This document describes what `flix bsp` does today, what it
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
| `buildTarget/run`, `buildTarget/test` | **not yet** |
| `buildTarget/jvmRunEnvironment`, `jvmTestEnvironment` | **not yet** |
| `workspace/reload`, `buildTarget/cleanCache` | **not yet** |
| `debugSessionStart` | never (see §8) |

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

## 7. What a client will get wrong

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
- **Some sources cannot be opened.** A diagnostic in the standard library or inside a
  `.fpkg` dependency is reported against a `flix-lib:` or `jar:` URI. That is
  deliberate; see §10.

## 8. What is not built, and why

- **`debugSessionStart`.** Flix has no debug adapter, so there is no address to return.
  `canDebug` is false and the request always fails. This one does not become available
  in a later phase; it is refused permanently, and asserted to be.
- **Running and testing.** Later phases. Their capability flags are false until then,
  so no client is told they exist.
- **Watcher-driven recompilation and `buildTarget/didChange`.** Needs debounce, and
  the file watcher is currently wired only to the REPL.
- **A `src`/`test` target split.** §4. It needs a source-set concept in the compiler,
  and the argument against faking one is a correctness argument.
- **TCP transport and concurrent clients.** stdio only. One `Flix` instance per
  session is the concurrency ceiling regardless.

## 9. Standard output belongs to the protocol

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

## 10. URIs, and why nothing is dropped

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

## 11. Session model

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

## 12. Acceptance criteria

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

## 13. Running the tests

```
./mill flix.test        # the in-process tests, with the rest of the suite
./mill flix.testBsp     # the ones needing a built assembly or a process of their own
```

`flix.testBsp` builds the assembly itself. The suites it runs are `@DoNotDiscover`
and gathered in `BspSuite`: a test that cancels itself when the jar is missing
reports green while proving nothing. `.github/workflows/bsp.yaml` runs it on every
pull request, and needs no token because nothing there reaches the network.
