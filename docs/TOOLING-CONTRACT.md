# The Flix tooling contract

How a **build tool** drives `flix.jar`: `--diagnostics-json`, `--lib`, `stubs`,
and a version handshake.

> **Being revisited.** The section below concludes that BSP is "none of the
> compiler's business". The boundary analysis in it is right; the inference is
> being reopened, because for a plain `flix.toml` project **`flix` is the build
> tool** — it resolves dependencies, owns `build/`, packages, and runs the tests —
> so "leave it to the build tool" leaves it to nobody. Work on a `flix bsp`
> endpoint has started: the `ch.epfl.scala:bsp4j` dependency and its linkage tests
> are in `build.mill` and `main/test/ca/uwaterloo/flix/api/bsp/`.
>
> One thing this document says is *not* being reopened, and it constrains that
> work: build requests do not belong in the language server. `flix bsp` is a
> separate endpoint, which is the same paragraph's own suggested alternative.
>
> This notice stays until the section is rewritten, so that the repository does not
> assert the opposite of what it does.

## This is not BSP, and not an LSP extension

An earlier draft of this document called itself a build protocol and proposed
adding build requests to the language server. Both were wrong, and the correction
is the most useful thing here.

The [Build Server Protocol](https://build-server-protocol.github.io/) already
standardises build orchestration — but a different boundary. In BSP the IDE or
language server is the **client** and the build tool (sbt, Gradle, Bazel) is the
**server**. There are two boundaries and BSP covers the upper one:

```
editor  --BSP-->  build tool  --this contract-->  flix.jar
```

| Boundary | Standard | Server is |
| --- | --- | --- |
| editor ↔ build tool | BSP | the build tool |
| build tool ↔ compiler | none | ad hoc — `zinc`, `javac`'s API, a CLI |

So BSP is not a replacement for this; it sits above it. In the Scala ecosystem
the lower boundary is `zinc`, which is exactly the in-process linkage this
contract exists to avoid.

**Build requests do not belong in the language server.** That `LspServer` already
holds a warm `Flix` instance is a fact about implementation, not an argument
about where a boundary goes. If a warm compiler is ever wanted here it is a
compiler daemon on its own endpoint.

**If editor build integration is the goal, that is BSP, implemented by the build
tool.** Mill already has BSP support; for a Mill project that story is largely
told and none of the compiler's business.

## Why a contract and not a linked API

A plugin compiled against the compiler is pinned to its binary version — the
problem `zinc` solves by building a `compiler-bridge` per Scala version. A plugin
that reads a documented contract is pinned to a version number instead.

It also removes a practical barrier: a published plugin cannot depend on Flix,
because Flix publishes no Maven artifact, so linking forces the plugin's own
build to download and pin a compiler.

The cost is real and lands elsewhere. Once a released plugin reads this, changing
it breaks builds this repository cannot see.

## What exists

| | |
| --- | --- |
| `flix capabilities --contract-version N` | the handshake; non-zero if the caller cannot be served |
| `flix check --diagnostics-json` | structured diagnostics on stdout |
| `flix build --diagnostics-json --lib J` | the same, plus build-produced jars on the classpath |
| `flix stubs --out D` | Java facades for `@Export`ed defs, before anything is compiled |

Four rules the handshake follows:

- `ProtocolVersion` says what we can do; `MinimumClientVersion` says what we have
  stopped doing. They move independently.
- Capabilities are **named, not inferred from the version** — they will not
  arrive in lockstep.
- A capability is advertised **only once implemented**. Advertising ahead is
  worse than omitting: a caller trusts it and fails at the point of use, which is
  what the handshake exists to prevent.
- A refusal still reports the range, so a caller can fall back or say something a
  person can act on.

## Positions, and `code` versus `kind`

Ranges are **zero-based**, matching LSP, while the compiler's printed text is
one-based. They disagree by one on purpose: BSP's `build/publishDiagnostics`
carries LSP `Diagnostic` values, so these pass through untranslated to anything
that ever speaks BSP. Both plugins convert in exactly one place and the Mill
plugin pins it with a test.

`code` is `E2136`; `kind` is `"Resolution Error"`. This diverges from LSP, where
`code` carries what is here called `kind` — which reads well in an editor's
problem list and is useless to key on, since hundreds of distinct errors share
it.

## The input model, and what it still lacks

`inputModel` is `"project-directory"`: an invocation names a project directory
plus explicit libraries, outputs and options. It does not enumerate sources or
resolved dependencies.

A fully self-describing invocation was rejected. A caller would have to resolve
`flix.toml`, Maven coordinates and `.fpkg` files itself — a second dependency
resolver that must agree with `Bootstrap`'s forever, where drift is silent.

**What that would have bought is not yet provided by anything else.** A build
tool needs to know what to declare as an input so its cache is sound, and nothing
here reports what the compiler actually read. An earlier draft claimed the
response did; it did not, and does not.

BSP answers this with `buildTarget/sources` and `buildTarget/dependencyModules` —
as *queries*, not as a report attached to every build. That is the shape to copy
if this is needed: a subcommand a build tool can ask once, rather than a field on
every result.

## What is not built, and what to measure first

No daemon, so no warm compiler. Flix carries the incremental machinery already —
`Flix` holds the cached ASTs and a `ChangeSet` driven by the typed AST's
dependency graph.

Before building one, measure: cold build wall-time on a real project, and JVM
starts per build. If a cold build costs seconds, a daemon is not worth stale
processes, cache keys that must carry compiler version and options, and ownership
on crash. The reference class is Kotlin's daemon, not the happy path.
