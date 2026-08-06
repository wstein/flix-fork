# The Flix build protocol

**Status: step 2 of 3.** The handshake and one-shot diagnostics exist. The daemon
requests do not.

## What this is, and why it is not an API

A build tool needs three things the console cannot give it: a problem placed in a
file at a line, a stable identity for that problem, and a compiler that does not
start from nothing on every build.

The obvious way to get them is to link against the compiler. That is what the
Gradle plugin originally did, and it was a mistake for a reason worth stating:
**a plugin compiled against a compiler is pinned to its binary version.** `zinc`
solves this by compiling a `compiler-bridge` per Scala version, which is a whole
subsystem. A plugin that reads a documented protocol is pinned to a version
number instead — one we choose, and can negotiate.

It also removes a practical barrier. A published build plugin cannot depend on
Flix, because Flix publishes no Maven artifact; linking forces the plugin's own
build to download a compiler and pin a version to compile against. Reading a
protocol costs nothing at build time.

The cost is real and lands elsewhere: a protocol is a compatibility surface. Once
a released plugin speaks it, changing it breaks builds this repository cannot
see. That is why the handshake comes before the requests.

## Version negotiation

```console
$ flix initialize-build --client-version 1
```

Returns the protocol range this compiler serves, its own version, the input
model, and named capabilities; exits non-zero when the client cannot be served.

- `ProtocolVersion` is what we can do; `MinimumClientVersion` is what we have
  stopped doing. They move independently.
- **Capabilities are named, not inferred from the version.** They will not arrive
  in lockstep — a daemon exists or it does not, regardless of what else changed.
- **A capability is advertised only once implemented.** Advertising ahead is worse
  than omitting: a client trusts it and fails at the point of use, which is
  precisely what the handshake exists to prevent.
- A refusal still reports the range, so a client can fall back or say something a
  person can act on.

The same document is served one-shot and (eventually) over a connection, so the
one-shot path stays a first-class fallback rather than a degraded mode that skips
the handshake.

## The input model, and the decision behind it

`inputModel` is `"project-directory"`. A request names a project directory plus
explicit libraries, output locations, and options. It does **not** enumerate
sources or resolved dependencies.

The alternative — a fully self-describing request — was rejected. A client would
have to resolve `flix.toml`, Maven coordinates, and `.fpkg` files itself: a second
dependency resolver that must agree with `Bootstrap`'s forever, and drift there is
silent. The property it buys is the one a build tool actually needs — knowing what
to declare as an input so its cache is sound.

**That property comes from the response instead.** A build response reports the
inputs it consumed, and a client compares them against what it declared. This
catches under-declaration, which is the failure that makes caching unsafe, rather
than assuming it away. A fully hermetic request would have made under-declaration
impossible by construction; reporting consumption makes it *detectable*, at a
fraction of the cost.

This is a deliberate trade and it has a hole worth naming: a client that ignores
the reported inputs gets no safety from them. The protocol cannot force the check.

## Positions are LSP's

Ranges are **zero-based**, matching the language server, while the text the
compiler prints is one-based as a person expects. The two disagree by one on
purpose: the requests that follow are language-server requests, and a client
converting twice would convert in opposite directions.

Both plugins convert in exactly one place, at the point of rendering, and the Mill
plugin pins it with a test. Getting it backwards puts every marker one line and
one column out, which reads as a rounding error rather than as a bug.

## `code` is the identifier; `kind` is the category

`code` is `E2136`. `kind` is `"Resolution Error"`.

This diverges from LSP's `Diagnostic`, where `code` carries what is here called
`kind`. That reads well in an editor's problem list and is useless to key on:
hundreds of distinct errors share it. A tool suppressing or escalating one
specific error could otherwise only match rendered text.

## What is not built

`flix/build`, `flix/stubs`, `flix/setLibs`, `flix/shutdownBuild` — the daemon
requests, and with them the warm compiler. Flix already carries the incremental
machinery: `Flix` holds the cached ASTs and a `ChangeSet` driven by the typed
AST's dependency graph, and `LspServer` already keeps one instance alive across
requests for exactly this reason. What is missing is that LSP is document-oriented
where a build is not.

Before building it, measure. If a cold `ChangeSet.Everything` build costs seconds
on a real project, a daemon is not yet worth its failure modes — stale processes,
cache keys that must include the compiler version and options, and ownership on
crash. Those are where daemons rot, and the reference class is Kotlin's, not the
happy path.
