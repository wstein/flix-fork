# The Flix tooling contract

How a **build tool** drives `flix.jar`: `--diagnostics-json`, `--lib`, `stubs`,
and a version handshake.

## This contract and BSP are peers, not layers

An earlier draft of this document called itself a build protocol and proposed
adding build requests to the language server. Both were wrong, and the correction
is the most useful thing here.

The [Build Server Protocol](https://build-server-protocol.github.io/) already
standardises build orchestration — but a different boundary. In BSP the IDE or
language server is the **client** and the build tool is the **server**. There are
two boundaries and BSP covers the upper one:

```
editor  --BSP-->  build tool  --this contract-->  flix.jar
```

| Boundary | Standard | Server is |
| --- | --- | --- |
| editor ↔ build tool | BSP | the build tool |
| build tool ↔ compiler | none | ad hoc — `zinc`, `javac`'s API, a CLI |

That analysis stands. What an earlier version of this section inferred from it —
that BSP is "none of the compiler's business" — does not, and the reason is that
the table has a column, not a row, for who occupies each side. **For a plain
`flix.toml` project, `flix` is the build tool**: it resolves dependencies, owns
`build/`, packages the jar, and runs the tests. "Leave it to the build tool" leaves
it to nobody. So `flix bsp` serves the upper boundary itself, and is documented in
[`docs/BSP.md`](BSP.md).

The two are peers in purpose rather than layers. `flix bsp` is for a project whose
build *is* `flix`; `--diagnostics-json` is for a project whose build is Gradle,
Mill or Bazel — a foreign build tool that owns the build and drives the compiler
through this contract. A project has one or the other, and neither is a stepping
stone to the other. In the Scala ecosystem the lower boundary is `zinc`, which is
exactly the in-process linkage this contract exists to avoid.

**Build requests do not belong in the language server.** That `LspServer` already
holds a warm `Flix` instance is a fact about implementation, not an argument
about where a boundary goes. If a warm compiler is ever wanted here it is a
compiler daemon on its own endpoint. `flix bsp` is that endpoint, and the
constraint is honoured rather than worked around: no build request was added to
`LspServer`, and the two share no state.

The "measure first" caution in the last section applies to a *daemon*, and deserves an answer
rather than a pass. `flix bsp` does not acquire a daemon's problems, because the
client owns the process: a session lasts as long as the editor keeps the pipe open
and ends when it closes, so there is no stale process to find, no ownership
question, and no discovery protocol beyond the `.bsp/flix.json` the client already
reads. What it does acquire is the cost of a session having a lifetime a CLI
invocation does not — one `Flix` instance, one project configuration — which is why
reload is transactional and compiles are serialised.

One detail about diagnostics, because the wire shape invites the wrong conclusion:
BSP carries a `Diagnostic` whose fields and zero-based ranges match LSP's exactly,
but `ch.epfl.scala.bsp4j.Diagnostic` is a different JVM type from
`org.eclipse.lsp4j.Diagnostic`. The conversion in `BspDiagnostics` is explicit, and
`code` is the stable `E####` rather than the category the language server puts
there.

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

**No daemon on this boundary**, so a foreign build tool gets no warm compiler: each
`flix check --diagnostics-json` is a fresh JVM. Flix carries the incremental
machinery already — `Flix` holds the cached ASTs and a `ChangeSet` driven by the
typed AST's dependency graph — so what is missing is a process to keep it in, not
the mechanism.

Before building one, measure: cold build wall-time on a real project, and JVM
starts per build. If a cold build costs seconds, a daemon is not worth stale
processes, cache keys that must carry compiler version and options, and ownership
on crash. The reference class is Kotlin's daemon, not the happy path.

`flix bsp` is not that daemon and does not answer this question. It keeps a warm
compiler for the length of one editor session, but the client starts and owns the
process, so none of the three costs above arises — and it is unreachable from a
build tool driving the CLI, which is the case this section is about.
