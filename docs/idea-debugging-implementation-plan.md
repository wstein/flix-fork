# Flix JVM debugging and IntelliJ IDEA implementation plan

Status: implemented for the compiler and native IntelliJ debugger increment (M1–M4,
M7–M9). BSP milestones M5–M6 were explicitly excluded from this increment. M10's
automated gates are implemented; its click-through IDEA matrix remains a manual release
qualification activity.

Implementation note (2026-09-17): this document remains the architectural record and
acceptance checklist. The shipped contract is summarized in `docs/idea-debugging.md`.
Where an initial design below differs, the implemented choices are authoritative:

- format-1 source index and format-2 pre-erasure scope metadata are published atomically
  beside the format-4 launch manifest;
- evaluation supports JDI-visible parameters, captures, source lets, and pattern locals;
- the launch/evaluation build ID combines the manifest fingerprint and source digest;
- evaluation uses the LSP project's Bootstrap dependencies, keeps one incremental
  compiler, retains at most 32 LRU answers, and caps raw artifact classes at 16 MiB;
- the debuggee host has no session registry or retained loaders, so returning from an
  invocation is the reset/release boundary;
- IntelliJ uses its existing native JVM debugger and two-phase CLI launcher, without BSP.
Prepared: 2026-09-16.

Updated for the merge of stable JVM naming into `dev0.76.0` and a source review of
the sibling `flix-jetbrains-plugin` repository. This file lives in the original
research worktree; the implementation baseline is the merged compiler checkout.

This plan turns [the debugging research notes](idea-debugging.md) into an executable
implementation sequence for the v0.76.0 backend with stable JVM naming. It incorporates
the source/history review of those notes. Where the notes imply stronger guarantees,
the contracts and acceptance criteria below define the proposed scope.

## 1. Intended outcome

A developer can build a Flix project with debug information, launch the program JVM,
attach IntelliJ IDEA, set a breakpoint in a `.flix` source file, step through supported
source constructs, and inspect available variables. BSP provides project import,
compilation, diagnostics, execution, tests, and launch environment queries. A subsequent
milestone restores and validates Flix expression evaluation through the plugin's
existing debugger client bridge against the new compiler backend.

The same compiler must support `--Xdebug --Xsequential` without losing the sequential
mode's required code elimination. Stable JVM naming must continue to satisfy its
existing identity and cleanup contracts.

Deliver this in three usable increments:

1. **JVM debugging foundation:** debug compilation, verified breakpoint locations,
   local variables, source/class metadata, and a reproducible launch contract.
2. **IDEA workflow:** a tested BSP import and `.flix` breakpoint/navigation/stepping
   workflow, adapting the existing sibling Flix plugin.
3. **Evaluation:** compilation outside the debuggee, execution inside the paused JVM,
   and an IDEA client bridge with explicitly bounded frame and variable support.

Do not describe increment 1 alone as complete IntelliJ language support.

### Non-goals for the initial delivery

- DAP implementation; BSP `canDebug` remains false while no DAP server exists.
- HotSwap, edit-and-continue, or breakpoint persistence in an already running JVM
  after changing its class files. Rebuild/relaunch is the supported edit workflow.
- Arbitrary Flix evaluation in every lifted, handler, or continuation frame.
- A deterministic replay debugger or a guarantee of identical traces across runs.
- Joint Java/Scala compilation and namespace-layout changes. Those remain a separate
  integration track, with compatibility tests where they intersect this work.
- Debugging a WebAssembly target. Sequential JVM execution is in scope.

## 2. Pinned evidence and current state

These are local Git revisions inspected while preparing the plan. Branch names are
labels for discovery; implementation and test reports must record immutable revisions.

| Reference | Inspected revision | Role |
| --- | --- | --- |
| `v0.76.0` | `f2d4678c20bff1242f4cad5e23144db91027b762` | Shared backend baseline |
| `feat/stable-jvm-name-table` | `d58262f38b2e4ef8a0fdd7d36e4c512e84482b44` | Target naming implementation |
| `dev0.76.0` | `076a828bf19b6ee692d04e25953a2a3e17d69cff` | Implementation baseline: merged stable naming and sequential runtime |
| Previous `dev0.76.0` | `74fee506e4c8e531392faae0cbeb0f97eebe789e` | First parent of the merge; sequential runtime reference |
| `develop` | `0e8a5eb7ed8a27a46926f24de6551cb066730b9e` | Historical BSP, metadata, launch, evaluation work |
| `fix/debug-line-binding` | `7d465860c7c457de269647729d508115c9b49791` | Historical breakpoint fixes |
| `feat/joint-compilation` | `1b34c228a87901b5fc058bc7c0a027371cb9c56e` | Separate facade/layout reference |
| Sibling `flix-jetbrains-plugin` | `90a7e73366d39c73929e65a40b7df14bea95761e` | Existing IDEA integration and compiler-product consumers |

| Capability | Merged compiler / sibling plugin state | Implementation disposition |
| --- | --- | --- |
| Stable generated JVM names | Implemented | Preserve and regression-test |
| Dedicated `--Xdebug` pipeline | Not present | Implement for current backend |
| Historical `LineNumbers` / `DebugScopes` components | Not present | Reimplement contracts, reuse applicable fixtures |
| `debug-index.json` | Historical implementation | Port with build/source identity |
| BSP endpoint and install command | Historical implementation | Port after build/launch contracts |
| Debug evaluation provider/host | Historical implementation | Port after scope and loader contracts |
| Sequential runtime option | Merged into compiler baseline | Test composition with debug mode |
| IDEA source debugger and evaluation bridge | Implemented in sibling plugin for historical compiler contracts | Adapt consumers and requalify on merged compiler |
| IDEA end-to-end behavior | Historical gate records successes and remaining gaps; not rerun in this review | Reproduce relevant gates for the new compiler/plugin pair |

Historical source can be inspected without switching branches, for example:

```sh
git show 7d465860c --
git show 74fee506e:main/src/ca/uwaterloo/flix/language/phase/LibraryOptions.scala
git show 0e8a5eb7e:main/src/ca/uwaterloo/flix/api/lsp/provider/DebugEvalProvider.scala
git show 0e8a5eb7e:docs/BSP.md
```

The review established source-level risks and discrepancies. It did not execute a
combined debug/sequential build or an IntelliJ debugging session. The plugin's historical
Green gate is evidence to preserve, not evidence that the merged backend already works.
Some README/ADR comments predate implemented launch/evaluation changes; current source
and the dated verification rows take precedence when those descriptions disagree.

## 3. Corrections that drive the design

| Priority | Research-note assumption | Required correction |
| --- | --- | --- |
| 10/10 | Bypassing the whole optimizer composes with sequential mode | Sequential library switches require folding and reachability pruning; preserve those operations in debug builds |
| 10/10 | Frozen stable names supply debugger provenance | Freeze discards provenance; capture dedicated debug records before information is lost |
| 9/10 | JVM attributes imply native IDEA `.flix` support | Prove editor breakpoint creation, mapping, navigation, and stepping in a named IDE/plugin configuration |
| 9/10 | `--Xdatalog-execution=sequential` is an equivalent CLI recipe | The inspected integration CLI exposes `--Xsequential`; API sub-options have different guarantees |
| 9/10 | Historical implementations are available on the target branch | Track historical, ported, and validated status separately |
| 8/10 | Lifted frames and all locals already work in evaluation | Historical evaluator rejects lifted frames and constructs parameters from definition parameters |
| 7/10 | `--Xdebug` necessarily emits SMAP | The historical no-inlining fix explicitly removes SMAP for its debug fixtures |
| 7/10 | Stable naming fixes namespace/package layout | Naming stability and the joint-compilation layout change are distinct features |

Stable names reduce incidental naming churn. They do not imply unchanged class bytes,
unchanged source indexes, no IDEA reindexing, or persistent identities for identical
unnamed siblings. The existing [stable naming contract](STABLE-JVM-NAMES.md) remains
authoritative for those boundaries.

## 4. Integration and branch strategy

Start the implementation branch at merged `dev0.76.0`, commit `076a828bf`. Its parents
are `74fee506e` and `d58262f38`; stable naming and sequential execution are already
combined. Do not redo that merge or plan a final merge as the first compatibility check.
Keep v0.76.0 as the historical common baseline, not the implementation branch point.

Use an isolated worktree from that merged revision if needed for implementation.
Maintain a paired plugin branch from `90a7e733` for consumer changes. Every integration
report must name both revisions and the actual compiler-jar digest used by the plugin.
Run optimizer/provenance tests on the combined compiler from M1 onward.

Reimplement historical behavior against `Instructions`, `GenFunAndClosureClasses`, and
`classes/Gen*`; do not mechanically transplant the removed backend architecture. Use
old commits to recover invariants, fixtures, and failure cases. Each port commit should
identify its source revisions and any intentional change in behavior.

Do not add unrelated package-manager changes beyond those already in the baseline. A small textual
overlap does not demonstrate semantic compatibility. Merge conflicts involving either
monomorphizer, lowering, source locations, or optimizer traversal require targeted
provenance and runtime validation even when Git can merge them automatically.

Implementation proceeded on `feat/idea-debugging-foundation`, paired with the plugin's
`feat/debug-foundation-compat` branch. Publication and merge remain repository-owner
release actions; the implementation and automated qualification do not perform them.

## 5. Component contracts

### 5.1 Architecture and ownership

```text
Typed sources + compiler options
                |
        compiler transformations
          /           |              \
 stable name      debug metadata     runtime selection
 registry         collector          (parallel/sequential)
     |                 |
 freeze names     compact snapshot before information loss
          \           /
           JVM code generation
            /        |          \
       class files  debug index  build/launch manifest
            |          |                  |
       program JVM     |           CLI launcher / BSP
            |          |                  |
           JDWP <--- IDEA Flix integration ---> BSP client
            ^                  |
            |          custom evaluation request
       evaluation host <--- compiler-produced artifact
```

Runtime selection is an independent compilation option. BSP consumes build products;
it is not the debugger. The language server may service the custom evaluation request,
but ordinary build requests remain in the compiler/build endpoint.

### 5.2 Debug compilation policy

Introduce `--Xdebug` in `Main.scala` and `util/Options.scala`, then make its policy
explicit at the optimizer boundary. Preserve executable source-level function bodies
that survive program reachability analysis, including small helpers. Do not promise a
class for unused, tree-shaken definitions or every non-executable source line.

The preferred design is to retain the established optimizer pipeline and gate the
inliner: under `--Xdebug`, it may inline only explicitly identified compiler-owned
library-switch definitions. Constant folding, branch simplification, and reachability
then continue to use their established phase ordering, while source-written helpers
remain distinct bodies. Recognize exact compiler-owned symbols; never match user
function spellings or evaluate arbitrary user code at compile time.

Audit all optimizer rewrites that can erase a source function, not only `Inliner`, and
make their debug policy explicit. If selective inlining cannot express a required
library switch without weakening source attribution or phase invariants, a narrow
phase-local reduction is the fallback—not a duplicate mini-optimizer. Document the
chosen gate(s), exact allow-list, and why they preserve both sequential reachability
and source bodies in both monomorphization pipelines.

Required transformations such as closure conversion, lifting, erasure, and backend
lowering continue. Preserve their naming provenance transfers and prune dead origins.
Debug mode must not become an alternate pipeline that silently skips required work.

### 5.3 Dedicated debug provenance

Keep debug provenance separate from `JvmNameTable`. Do not extend the frozen naming
table to retain typed ASTs, complete provenance graphs, or mutable compiler state.

Proposed lifecycle:

1. When typed source is available, capture compact declaration identities, source
   identities/ranges, parameter identities, lexical scopes, and Flix type descriptions.
2. During both monomorphizers, record source-declaration links and specialization
   arguments before erasure. Record supported/unsupported frame classifications.
3. During lowering, closure conversion, and lifting, propagate source ownership,
   capture identities, synthetic roles, and continuation relationships explicitly.
4. Prune debug records with surviving declarations. Before each destructive phase,
   copy only information needed later; a snapshot immediately before final freeze
   cannot recover information already discarded by earlier phases.
5. Immediately before naming freeze in `CodeGen`, finalize the compact symbol-based
   snapshot for live emitted entities. Freeze names using the unchanged naming API.
6. Join snapshot symbols with actual emitted class names and method descriptors.
   Resolve bytecode ranges, slots, fields, and resume locations during emission.
7. Publish metadata only with a successful build. Release collector and snapshot
   state on success, failure, and compiler reuse.

The collector is compilation-local. Parallel phases must use safe collection/merge
boundaries and deterministic serialization. Missing metadata for a supposedly supported
frame is a build invariant failure, rather than a guessed source-name mapping.

### 5.4 Proposed debug metadata schema

Finalize the schema in M3 after examining historical consumers. The following are
required logical fields, not a claim about the existing JSON format:

| Record | Required information |
| --- | --- |
| Build | Schema version, build identity, compiler revision/version, relevant options, source/dependency fingerprints |
| Source | Stable source identity, canonical project-relative path/URI, content digest, source kind |
| Class | Emitted binary name, source associations, generated role |
| Method | Class, method name and JVM descriptor, source declaration identity, specialization, frame kind, evaluation capability |
| Scope | Lexical scope identity, parent, source extent, emitted code ranges |
| Variable | Binding identity, display name, Flix type, JVM descriptor, visibility; slot/range from LVT, field access from capture/continuation metadata |
| Continuation | Resume state and corresponding source/scope mapping when supported |

Method descriptors are required to distinguish overloaded/generated methods. Same-named
bindings in disjoint sibling scopes require binding identities and lifetimes rather
than name-only lookup; fixtures must obey Flix's rejection of nested shadowing. JVM
slots for `Long`/`Double` require two-slot handling. A local visible on one bytecode
range must not be advertised elsewhere merely because a slot was reused.

Use the plugin's existing `debug-index.json` and `debug-scopes.json` product separation,
with a consistent build identity. Preserve LocalVariableTable authority for live slots
and ranges; join richer source/type identities to it instead of maintaining a second
independent allocation table. Avoid duplicating complete records across files.
Sort unordered collections for reproducibility, but preserve semantically meaningful
classpath, parameter, and field order. Identical builds should yield identical logical
metadata; source edits are allowed to change locations and digests.

For `debug-index.json`, evolve from basename lookup to an exact source-identity lookup
(canonical project-relative path or URI) before falling back to a uniquely matching
basename for legacy artifacts. A duplicate basename must never choose an arbitrary
class. Version this as a coordinated compiler/plugin schema migration; retain format 1
reading only where its ambiguity can be detected and reported.

Do not invent absolute-file URLs for standard-library or package sources. Define their
source resolution contract, including extraction/read-only navigation where needed.
Reject stale evaluation/launch inputs with an actionable diagnostic. For optional
inspection/index metadata, preserve the plugin's documented fallback behavior while
making unsupported versions visible; absent metadata is not evidence of absent classes.

### 5.5 Launch and build identity

Port the build-manifest launch contract before BSP or evaluation depends on it. The
launcher needs the selected Java executable, main class, ordered runtime classpath,
working directory, JVM arguments, and program arguments. Represent arguments as arrays,
not a shell command string. Environment overrides belong to the launch request; do not
persist the ambient environment in build metadata.

The existing plugin strictly reads `build.json` format 4. Preserve that launch contract
for the first compatibility slice. Any later version bump requires an updated plugin
reader and compiler-produced compatibility fixtures in the same release sequence;
changing the number alone prevents all debug launches. Build manifest, classes, and
debug sidecars must identify the same successful compilation. A failed build must not
make old output appear current. Clean/release builds must retire stale debug sidecars
and evaluation-host products using the build system's output ownership mechanism.

Record enough identity for evaluation to reject a stale compiler snapshot or mismatched
debuggee. Use a small generated build-identity resource or marker reachable in the
debuggee if the client cannot establish this from launch ownership alone.

The launch command attaches JDWP to the program JVM, not the compiler or BSP server.
Use a loopback endpoint by default and the selected JDK's supported JDWP syntax. Tests
allocate ports dynamically and await protocol readiness instead of sleeping for a fixed
interval. Manual recipes may use port 5005 as an example.

### 5.6 Existing JetBrains plugin requirements

Repository: `../flix-jetbrains-plugin` relative to the active compiler repository,
not relative to the temporary research worktree. Reviewed at `90a7e733`.
Its current build pins IntelliJ IDEA 2026.1.3 and LSP4IJ 0.20.1. Treat those as the
first regression configuration, not an assertion that every later IDE build works.

The plugin already implements the IDE half of much of this plan. Preserve its accepted
architecture in `docs/adr/0001-single-language-owner.md`,
`docs/adr/0002-native-jvm-debugger.md`, and `docs/adr/0003-cross-module-contracts.md`:

- Exactly one `Language("Flix")`, parser/file-type owner, gutter registration, and LSP
  client. LSP4IJ remains the LSP client; BSP must not start a second language server or
  introduce another language owner.
- IntelliJ's native JVM debugger is the sole JDWP owner. Do not reintroduce the retired
  LSP4IJ DAP adapter or attach a standalone JDI probe to an IDE-owned session. Probe
  tests use their own debuggee sessions.
- Never call `VirtualMachine.setDefaultStratum("Flix")`. Resolve each Flix location
  using the Flix stratum when present, with default-stratum handling otherwise.
- Keep foreign Java/Kotlin/Scala/Groovy locations owned by their existing integrations.
  For a `.flix` source-position query, an empty answer is final; throwing
  `NoDataException` delegates to another manager and can bind unrelated Java lines.
- Filter class-prepare results by the requested executable line even when the source
  index identifies the class. One source produces many classes with different lines.
- Preserve index-independent source lookup, cache invalidation on resume/redefinition,
  and conservative ambiguity handling. No global package exclusions that hide user
  Java/Kotlin/Scala code may be added to fix Flix stepping.

Module boundaries are requirements, not a proposed redesign:

| Plugin module | Existing responsibility | Constraint for this work |
| --- | --- | --- |
| `shared` | Compiler/JDK resolution, command construction, build/index/scope readers | Remains platform-free; shared rules remain pure functions |
| `language` | Flix language/PSI, settings, `FlixDebugEval` service interface | Loads without Java or LSP4IJ; keep the common evaluation interface here |
| `backend` | LSP4IJ server and `FlixDebugEvalClient` implementation | No dependency on debugger; LSP-owned UI/services remain host-side in Split Mode |
| `debugger` | Position manager, native run configuration, stepping, frames, renderers, evaluation invocation | Optional Java-dependent content module; no dependency on backend |
| `frontend` | Thin frontend content module | Do not move backend file/process/LSP access here |

Use the existing `language` evaluation interface across the backend/debugger boundary;
do not add reflection-based cross-module access, a second LSP client, or platform
services to `shared`. Every extension registration change also updates
`flix-integration.yaml`, its owning module descriptor, and the generated integration
matrix. Run `checkIntegrationGlue`; do not edit the generated matrix by hand.

### 5.7 Concrete producer/consumer compatibility work

| Compiler product/behavior | Existing plugin consumer | Required action |
| --- | --- | --- |
| `build/development/build.json`, format 4; `launch.java`, `mainClass`, `runtimeClasspath` | `shared/FlixBuildSpec.java`, `run/FlixLaunch.kt` | Preserve first; coordinate schema extensions with reader tests |
| `build/development/debug-index.json`, format 1, source-to-class lists | `shared/FlixDebugIndex.java`, `FlixPositionManager` | Emit compatible data initially; evolve exact source identity with both producer and reader |
| `build/development/debug-scopes.json`, format 2, class/method/name-to-type mapping | `shared/FlixDebugScopes.java` | Preserve its projection or version both ends for binding identities/method descriptors |
| `flix/debugEval/compile` | Backend request/response beans and `language/FlixDebugEval` | Keep existing field/status semantics; negotiate any added build/frame identity requirements |
| Packaged `dev.flix.runtime.DebugEvalHost` and artifact entry contract | `FlixRemoteEval.kt` | Port host to new runtime; retain platform-managed invocation |
| Source attributes and optional Flix SMAP stratum | `FlixSourceLocations`, `FlixSourceFiles`, `FlixSourceCache` | Test no-SMAP and SMAP paths separately; preserve foreign-language ownership |
| Generated names and namespace layout | `FlixFrames`, `FlixValues`, renderer recognition | Replace old-backend assumptions with explicit metadata and versioned fallbacks |
| `pcLines`, `cloNames`, per-value struct/tag metadata | `FlixResumePoints`, `FlixValues`, `FlixStructs` | Specify representation and port producer/reader together |
| Runtime frame/resumption object layout | `FlixContinuations`, `FlixAsyncStackTraceProvider` | Audit against v0.76 generators and revalidate continuation reconstruction |

Important source-confirmed incompatibilities to address explicitly:

1. `FlixValues.withoutStableHash` recognizes eleven-character Base58 suffixes; the
   merged `JvmNameTable` uses twelve lowercase base36 characters. `FlixFrames` also
   inverts the historical namespace/class layout. Merely changing the suffix regex is
   insufficient and risks stripping legal user names. Emit source/display identity
   metadata and select any legacy fallback by a documented compiler format.
2. Plugin renderers assume historical `dev.flix.gen` structural classes, whereas
   current `Mangle` places structural classes in the root package. Runtime readers
   also hardcode names such as `FramesCons$`/`ResumptionCons$`; some names remain valid
   (`mkClassName` still appends `$`), which does not establish layout compatibility.
   Audit every hardcoded prefix, class name, field, descriptor, and method against the
   emitted artifacts rather than assuming either wholesale compatibility or renaming.
3. The index reader currently selects candidate classes by basename. Exact-source
   disambiguation is a coordinated enhancement, not an already satisfied requirement.
   Keep final path/line verification so candidate over-selection never binds the wrong
   file. Existing conservative duplicate-basename refusal is a safe baseline.
4. Scope/index readers use narrow parsers, not general schema-independent JSON readers.
   Preserve expected key structure and binding formatting for compatibility fixtures;
   a richer schema requires deliberate reader changes. Do not silently add nested
   objects that a legacy reader mistakes for classes or methods.
5. Source names in labels, stepping scopes, and evaluation have different purposes.
   Continue using PSI/source positions for stepping ownership where appropriate;
   never infer evaluation scope by removing a generated suffix.

Current evaluation request fields are `className`, `methodName`, `expression`, `policy`
(`pure` or `allowEffects`), and `withArtifact`. Responses distinguish `ok`, `failed`,
and `rejected`, with `tpe`, `eff`, diagnostics/reason and optional `artifact`,
`entryClass`, `entryMethod`, `valueField`, and ordered `parameters`. Preserve those
distinctions. New source/build identity and method-descriptor requirements need a
capability/version transition and tests for old/new client combinations.

Launch adaptation must preserve the existing two-phase runner:

1. Resolve compiler/JDK using the shared rules, then perform
   `build --Xdebug --yes [--entrypoint ...]` on the background task, with no JDWP agent.
2. Read the successful build's launch specification, start the program with JDWP, and
   hand the connection to IntelliJ. The program Java executable comes from the spec;
   it need not be identical to the executable selected to run the compiler.

Retain cancellation and failure handling, runner ordering, `ModuleRunProfile` and
`RemoteConnectionCreator` gates, and the refusal to compile late on the EDT. Ensure
`--Xdebug` is passed exactly once and after the subcommand. Reject duplicate inherited
JDWP configuration. A failed build must never launch prior output.

Use the established compiler/JDK resolver for LSP, tasks, and debugging. Parse `.envrc`
and flixw pins without executing project scripts. Preserve run-configuration argument
ownership instead of blindly appending global extra arguments. Test the new sequential
option through the actual debug build path as well as through the CLI.

The plugin already has `FlixCodeFragmentFactory`, `FlixDebugEvalClient`, and
`FlixRemoteEval`. Adapt them in M9. In IDEA, invocation must go through
`DebugProcessImpl.invokeMethod`, including primitive boxing, because raw JDI invocation
can deadlock on the platform's class-prepare suspension. Raw JDI remains appropriate
only in an independently owned test harness with its own event handling.

Preserve pure evaluation by default and the existing effectful-evaluation setting;
do not introduce a modal consent dialog while holding debugger-thread state. Keep
watches and Boolean breakpoint conditions on the existing evaluator path, with no
duplicate condition registration. Preserve separate server-start and response timeouts.

Continuation/value compatibility is part of the IDE increment, not an incidental nice
to-have. Validate logical stack selection, `pc` resume locations, capture names,
struct/enum display identities, and raw fallbacks. Do not concatenate historical
continuation chains or hide real Java frames under an invented Flix stack. Sequential
Datalog stepping remains stepping through solver code; relational rule-level debugging
requires a separate protocol and is not claimed here.

For every changed wire contract, commit compiler-produced fixture artifacts and run the
plugin readers against them. Test the current/current pair, supported legacy pairs,
and unsupported versions with explicit outcomes. Optional inspection can degrade;
launch/evaluation must not silently use incompatible artifacts.

## 6. Milestones and acceptance gates

Effort sizes are relative: S is a localized task; M spans several components; L requires
compiler/runtime or IDE integration. They are not elapsed-time commitments. The sections
below retain the original execution checklist; the status header and shipped-contract
document record the implemented result, while M10's IDEA click-through rows remain manual.

### M0 — Paired baseline, fixtures, and plugin contract audit (M)

Dependencies: none.

- Record the revisions above, JDK, build tool, supported OS, and IDEA/plugin versions.
- Confirm clean baseline compilation and existing stable-name tests using `build.mill`.
- Inventory historical tests and associate each with the invariant it checks.
- Define one small reusable fixture project with a small helper, first statement,
  closure capture, same-named sibling-scope locals, generic specialization, effect handler, and two
  source files with the same basename in different directories.
- Inventory the sibling plugin consumers listed in section 5.7 against the merged
  backend. Record required name/layout/schema adaptations before changing a producer.
- Reproduce selected historical debugger-gate rows with a known compatible compiler to
  establish a plugin baseline, then record failures against the merged compiler without
  conflating missing compiler debug support with a plugin regression.
- Use the existing plugin modules and fixtures, including `flix-lab` mixed-language
  fixtures where available. Keep a small hermetic compiler/plugin contract fixture for CI.

Acceptance: a paired revision report, fixture inventory, and compiler-to-plugin
compatibility table exist. Existing native-debugger ownership is preserved. A failed
IDEA experiment does not block independent compiler work, but blocks claims that the
new compiler/plugin pair is ready.

### M1 — Debug policy and sequential composition (L, priority 10/10)

Dependencies: M0 baseline. Runs directly on the merged sequential/stable-name compiler.

Likely files: `Main.scala`, `util/Options.scala`, `api/Flix.scala`,
  `phase/LibraryOptions.scala`, `optimizer/Optimizer.scala`,
`optimizer/Inliner.scala`, and both monomorphizer/lowering paths as required.

- Add explicit debug options and a compiler-owned switch reduction policy.
- Disable source-function inlining in debug mode while retaining required lowering.
- Preserve constant-switch elimination, dead branch removal, and tree shaking.
- Keep `--Xsequential` as the documented CLI option. Preserve compiler API distinctions
  without presenting Datalog-only configuration as fully sequential execution.
- Maintain provenance for retained/replaced expressions and prune eliminated symbols.

Acceptance:

- A reachable one-expression helper remains a distinct breakpointable body in debug
  mode; the release-mode positive control demonstrates existing optimization behavior.
- Sequential Datalog, collection, region, lazy, and fresh-identity fixtures run correctly
  both with and without debug mode, under both monomorphizers.
- Bytecode reachability checks confirm removal of the expected parallel/locking paths.
  Include positive controls where those constructs exist in default mode.
- Check that sequential library paths do not merely retain generated `spawn` rejection
  paths in place of the concurrency that should have been eliminated.
- Explicit user `spawn` retains the sequential implementation's documented rejection
  behavior; Java interop remains subject to its single-threaded-use precondition.
- Existing stable-name fixtures pass. Debug-off output does not acquire debug-only data.

### M2 — Source attribution and reliable breakpoints (L)

Dependencies: M1.

Likely files: new `phase/jvm/LineNumbers.scala`, `Instructions.scala`,
`GenFunAndClosureClasses.scala`, `GenExpression.scala`, class-building utilities, and
handler lowering in both pipelines.

- Reimplement provisional function-header attribution and pending-line resolution.
- Emit at most one line-table entry per bytecode offset, with a real body statement
  taking precedence over a provisional header at the same offset.
- Preserve the declaration location where it is the only meaningful executable site.
- Mark synthetic handler wrappers and plumbing accurately so they do not claim the
  user's body breakpoint. Keep legitimate handler body locations.
- Specify stepping behavior around compiler-generated transitions and non-executable
  lines. Do not promise a stop for every lexical line.
- Keep SMAP conditional on actual cross-file mapping needs. Do not require SMAP in a
  debug fixture that contains no inlined cross-file code. If future transformations
  create such code, add an explicit mapping policy and tests then.

Acceptance:

- Inspect emitted `SourceFile` and `LineNumberTable` attributes for first statement,
  single-expression helper, same-line expressions, multiline body, and handler cases.
- Launch a real JVM through a reusable JDI harness; query locations, install breakpoints
  before relevant classes load, handle class-prepare events, and observe actual stops.
- Assert that handler wrappers do not introduce duplicate user-line stops.
- Verify same-basename sources resolve distinctly through the debug source identity.
- Bound test waits and guarantee child-process cleanup on success and failure.

### M3 — Debug scope lifecycle and variable inspection (L, priority 10/10)

Dependencies: M1; integrates with M2 emission.

Likely files: new debug collector/snapshot components, `api/Flix.scala`,
`JvmCompilationOrigins.scala` integration points, `CodeGen.scala`, monomorphizers,
`ClosureConv.scala`, `LambdaLift.scala`, and function/frame generators.

- Implement the lifecycle and records in sections 5.3–5.4.
- Capture source declaration/specialization links before erasure, independently of hashes.
- Emit `LocalVariableTable` entries with correct ranges, descriptors, and slot widths.
- Exclude compiler temporaries from user-visible locals. Preserve user variables even
  when their names resemble compiler-generated names; use explicit visibility metadata.
- Record closure captures and continuation values with their actual field access paths.
- Port struct/enum display metadata only where it identifies values correctly. The
  current backend shares classes by layout, so a class-level source type/name alone
  may be ambiguous. Use scope/type context or value-specific metadata; otherwise expose
  a documented raw representation instead of assigning the wrong source type.
- Freeze names and finalize debug mappings without retaining raw naming provenance.
- Add an emitted-class verifier (ASM or equivalent) to assert LVT range/slot invariants:
  variables sharing a slot have non-overlapping live ranges, and `Long`/`Double`
  two-slot allocations cannot be overlapped at the second slot.

Acceptance:

- JDI reads expected parameters, in-scope locals, primitive wide values, and captures.
- Same-named sibling-scope variables resolve by active range; out-of-scope/unavailable variables are
  absent or explicitly unavailable, never read from a reused slot.
- Specialized definitions map to the correct source owner; unsupported lifted frames
  are classified explicitly even if their raw variables can be inspected.
- Shared-layout enum/struct fixtures are not mislabeled.
- Reusing the compiler after success and injected failure retains no prior debug
  records. Existing freeze/failure lifecycle tests remain valid with debug on and off.

### M4 — Source index, artifact publication, and program launch (M)

Dependencies: M2 and M3. Launch-schema design can proceed earlier.

Likely files: `api/Bootstrap.scala`, bytecode/build product abstractions, debug index
writer, and tooling documentation. Port applicable tests from historical tooling work.

- Emit source/class and scope sidecars as owned build products.
- Publish manifest and metadata consistently with generated classes.
- Implement source identity, freshness checks, and build identity from section 5.5.
- Produce the launch environment from the same resolved inputs used by build/run.
- Include the evaluation host only once M8 adds it, using the same product mechanism.

Acceptance:

- A fresh external JVM runs using only the reported executable/classpath/arguments;
  the compiler's in-process classloader is not needed.
- Dependency jars, paths containing spaces/non-ASCII text, working directory, argument
  boundaries, and environment overrides behave correctly.
- A rebuild removing a source/class or turning debug off cannot expose stale metadata.
- Failed compilation, no-main projects, missing dependencies, and stale manifests
  produce specific errors instead of launching an unrelated prior build.
- Index ordering is deterministic for unchanged inputs; edit tests distinguish expected
  location/digest changes from unintended class-name churn.

### M5 — BSP lifecycle, import, and compilation (L)

Dependencies: M4 build products and M0 client selection.

Likely files: new `api/bsp` package, CLI commands, build dependencies, and `docs/BSP.md`.

- Port `bsp` and `bsp-install`, initialization/shutdown/exit, build targets, sources,
  dependency/resource/output queries, compilation, diagnostics, and reload/clean behavior.
- Keep one target where the compiler builds source and test definitions as one program.
- Centralize supported capabilities; unsupported requests fail explicitly.
- Reserve stdout exclusively for framed protocol messages. Route logs appropriately.
- Preserve ownership checks when installing `.bsp/flix.json`; do not silently overwrite
  a connection definition owned by another tool.
- Serialize or otherwise safely isolate compiler-mutating requests. Define cancellation
  and shutdown behavior without publishing partially written build products.
- Verify the actual BSP library/client versions and compatible notification methods.
  Historical `2.2.0-M2` observations are evidence, not a timeless compatibility claim.
- Preserve the plugin's existing Tools > Flix tasks, code lenses, and run/debug actions.
  BSP adds project import/build integration; it does not register duplicate gutter
  actions, replace LSP4IJ, or silently change the compiler selected by the shared resolver.
  Define whether a debug launch uses the existing CLI build or an equivalent serialized
  BSP build; retain CLI build as the initial proven route and prevent concurrent writers
  from publishing different builds into the same development output directory.

Acceptance:

- A protocol harness completes lifecycle, discovers sources, compiles success/failure
  fixtures, and confirms diagnostic ranges and clearing after errors are fixed/deleted.
- No compiler logging corrupts stdout framing, including failure and startup paths.
- Capabilities match implemented requests in both directions.
- The selected IDEA configuration imports the fixture and displays compiler errors.
- `canDebug` remains false and a DAP request cannot return a JDWP endpoint.

### M6 — BSP execution, tests, and JVM environments (M)

Dependencies: M5 and M4.

- Port program/test execution, result reporting, and JVM run/test environment queries.
- Use the shared launch contract rather than rebuilding classpaths independently.
- Honor run working directory, arguments, and environment overrides.
- Select output notifications from verified protocol/client support, with a supported
  fallback. Keep test output routed according to the test reporting contract.
- Cancel/terminate owned child processes and release resources on server shutdown.

Acceptance: fixture output is visible in the selected IDEA console; exit status,
failing tests, no-main projects, cancellation, and environment queries are correct.
Test both the modern output channel and the older-client fallback with protocol fixtures.

### M7 — Adapt and requalify the existing IntelliJ source debugger (L)

Dependencies: M0 compatibility audit and M2–M4. This milestone deliberately has a
standalone CLI-build → `build.json` v4 → native-JDWP-launch path; it is not gated on
BSP. M5–M6 later qualify the imported-project workflow and must not replace that path
until equivalence and output ownership are demonstrated.

- Adapt `FlixPositionManager`, frame/value helpers, continuation readers, and the
  existing run configuration to the merged compiler's products and names. Reuse existing
  language/file recognition and registration; do not create a parallel debugger plugin.
- Consult the chosen IntelliJ SDK/version before selecting extension APIs. JetBrains'
  [custom-language debugger guidance](https://intellij-support.jetbrains.com/hc/en-us/community/posts/360001525719-Custom-language-debugger)
  illustrates that custom JVM languages may need breakpoint/handler integration; its
  older API examples are not a promise of current compatibility.
- Consume metadata and class-prepare events; handle several generated classes per file.
- Keep class-prepare registration bounded. Deduplicate/filter by emitted-name families
  established from the index and verify source/class/line identity in the callback;
  do not blindly register a request per generated class. Where root-package classes
  make a broad filter unsafe, measure the event cost and use the smallest precise
  request set rather than an unrestricted catch-all filter.
- Mark unresolved/non-executable breakpoints honestly, with a useful explanation.
- Use the manifest to launch or provide a precise manual Remote JVM Debug recipe.
- Resolve dependency source documents and distinguish identical source basenames.
- Document the required IDEA distribution/version, plugins, JDK, and source setup.
- Preserve Flix-aware Step Over inside one definition, last-line return to caller,
  recursion depth handling, and foreign-language stepping. Test new class names against
  `FlixFrames`/`FlixValues` and the continuation/value renderers rather than only testing
  whether a breakpoint can bind.

Acceptance in a recorded IDEA session, using the existing CLI build/manifest launcher
first (BSP import is a later additional row):

1. Import the fixture, build with debug information, and launch the program JVM.
2. Set a `.flix` gutter breakpoint before its class loads; observe a bound breakpoint
   and a stop at the intended statement, including the small helper.
3. Step into/over/out of supported calls, inspect locals/captures, and navigate frames.
4. Confirm handler plumbing does not claim the body line.
5. Stop, make an unrelated edit, rebuild, relaunch, and confirm rebinding. Test a moved
   source line separately from an unchanged line with stable generated names.
6. Repeat the supported workflow in sequential mode.
7. Repeat launch and source navigation in Split Mode with backend-owned paths/processes.
8. Verify Flix → Java → Flix and Flix → Kotlin/JVM → Java → Flix, including per-frame
   evaluator selection. Kotlin inline/lambda frames must not steal Flix positions.
   Scala/Groovy remain optional profiles; coroutine parity remains unclaimed.
9. Verify logical continuation stacks, resume locations, enum/struct/closure rendering,
   source lookup during indexing, and safe fallback when metadata is unavailable.
10. Check that language support still loads without the Java module and debugger
    inspection still works without LSP4IJ; only compiler-backed evaluation is unavailable.

Store a short reproducible transcript and version matrix; screenshots may supplement
but do not replace stated expected/observed results. If `.flix` integration remains
unavailable, label the release JVM-debuggable only and leave this milestone incomplete.

### M8 — Evaluation compiler and debuggee host (L)

Dependencies: M3 and M4; the client bridge follows in M9.

Port the historical provider, protocol, and host behavior onto the new metadata and
runtime layouts. The host defines and executes bytecode; it does not compile Flix.

Initial support contract:

| Frame/value | Initial evaluation behavior |
| --- | --- |
| Source definition with representable parameter types | Evaluate using referenced, JDI-visible parameters |
| Specialized definition | Evaluate when source types remain representable; otherwise return a structured rejection |
| Let-bound locals | Evaluate when the local is live in the JVM `LocalVariableTable` |
| Lifted lambda/local definition | Evaluate visible parameters, captures, and source locals in `applyFrame` |
| Handler/continuation frame | Evaluate restored values after their JVM slots become live; reject unavailable state |
| Stale build/source snapshot or unavailable argument | Reject with rebuild/relaunch or availability diagnostic |

This table bounds compiler-backed evaluation, not the plugin's existing read-only
expression evaluator. Preserve its supported direct local/field/value reads where
available, including in frames the compiler-backed provider rejects. Present the
difference clearly rather than regressing a working watch to an unconditional refusal.

- Specify the custom `flix/debugEval/compile` request/response, schema versions, build
  identity, method descriptor, selected frame location, expression, and evaluation policy.
- Reconstruct scope from recorded declaration and type substitution, not a parsed class
  name. Resolve referenced parameters through the language frontend rather than relying
  on a substring match that confuses shadowing or identifier prefixes.
- Compile a wrapper externally and return bytecode, entry point, return representation,
  and ordered argument bindings. Deliver the host and all required helper classes as
  debug build products.
- Use a fresh artifact loader with the program loader as parent. Give wrapper classes
  artifact-specific identities. Detect incompatible reuse of an existing class name;
  stable naming does not make an edited class compatible with the running program.
- Use existing program objects as arguments; do not serialize and recreate the heap.
- Port trampoline/result unwrapping to current runtime descriptors. Refuse unsupported
  suspensions rather than executing paused program handlers implicitly.
- Make error cases structured: type errors, unsupported frames, stale state, missing
  values/classes, linkage failures, thrown program exceptions, and evaluation policy.
- Reuse the compiler and cache compiled artifacts only after uncached correctness.
  Cache keys include source/dependency/build identity, scope, specialization, expression,
  relevant options, and policy. Never cache runtime answers or paused object handles as
  interchangeable evaluation results.
- Bound cache size and artifact payloads. Release session-owned state at detach.
- Define `DebugEvalHost.resetSession()` (or an equivalent session token) and invoke it
  on resume, detach, and relaunch. Discard per-evaluation loaders/artifact registries,
  avoid strong host-side references to paused values, and test repeated evaluation for
  bounded retained-loader/class growth. JVM class unloading remains GC-dependent, so
  this is a release-of-references contract, not a promise of immediate Metaspace reuse.

Acceptance: real paused-JVM tests cover primitive/object parameters, supported generic
specializations, newly needed specialization classes, repeated/edited expressions,
thrown exceptions, rejected suspensions, unsupported frames, stale builds, and loader
isolation. Verify host classes are present in debug artifacts and absent from release
products. Validate cache invalidation independently of successful expression execution.

### M9 — IDEA evaluation bridge (L)

Dependencies: M7 and M8.

- Adapt the existing `FlixCodeFragmentFactory` → `language/FlixDebugEval` →
  `backend/FlixDebugEvalClient` path to the compiler's ported request. Keep module
  isolation and the existing null/unavailable-service behavior when LSP4IJ is absent.
- Check frame/build identity, obtain available arguments from the paused frame, construct
  invocation values through the native debugger's managed invocation API, invoke the
  host on the correct suspended thread, and
  render the returned value or structured error.
- Invalidate frame mirrors after invocation/resume and reacquire them before further
  inspection. Handle detach/resume races and distinguish compiler cancellation from a
  debuggee invocation that is already running.
- Define breakpoint handling during evaluation and prevent recursive watch evaluation.
- Default to explicit evaluation while invocation behavior is being established. State
  supported effect policy; even a nominally pure expression may loop or allocate heavily.
  Do not claim that a timeout safely stops arbitrary code executing in the debuggee.
- Expose the existing effectful-evaluation setting as a clear, non-modal IDEA control
  (default off), send its policy with every request, and present a compiler rejection
  with the inferred effect and the opt-in remedy. Do not show a modal dialog while the
  debugger thread is suspended.
- Disable unsupported frame evaluation with a clear reason while retaining inspection.

Acceptance: evaluate a parameter expression and an object-using function call from
IDEA, receive an ordinary type error, reject an unsupported frame/stale build, and
continue the original program afterward. Exercise breakpoint reentry, detach/resume,
and nonterminating evaluation handling without promising rollback of side effects.
Also test watches, Boolean breakpoint conditions, pure/effectful policy, unavailable
LSP service, primitive boxing, class preparation during invocation, and Java/Kotlin
frame evaluator non-interference.

### M10 — Integration, documentation, and release qualification (M)

Dependencies: milestones required by the chosen release increment.

- Run the matrix below on pinned feature/integration revisions.
- Update user-facing recipes, schema documentation, support matrix, limitations, and
  troubleshooting. Preserve historical notes as background and link them to this plan.
- Update the plugin README and affected stale comments alongside consumer adaptations:
  its older text describing agent-on-`flix run`, eleven-character naming, or no evaluation
  host must not contradict the current two-phase launch and implemented evaluation path.
- Measure representative debug compile time, emitted class count/size, metadata size,
  and small Datalog runtime versus release mode. Report observed overhead; do not claim
  an unmeasured performance budget or let performance work silently reintroduce inlining.
- Check that release-mode ABI, exported names, stable-name behavior, build/run, and
  package resolution retain their existing tests.
- Mark each acceptance gate passed/failed/deferred with a revision and result. A deferred
  optional feature is documented; a failed prerequisite prevents advertising its increment.

## 7. Validation matrix and test organization

Run core compiler/runtime fixtures across all eight combinations below. Apply BSP/IDEA
checks to supported debug configurations and use debug-off cases as negative controls.

| Dimension | Values |
| --- | --- |
| Debug information | Off; on |
| Execution mode | Default; `--Xsequential` |
| Monomorphization | Existing default; constraint-based alternative |

Use the actual CLI/API option names in the target revision. Do not introduce a new
flag solely to match a historical recipe. Test at least the repository's configured
JDK 21 baseline, plus any additional JDK promised by the chosen IDEA support matrix.

| Test layer | Evidence it provides | What it cannot establish alone |
| --- | --- | --- |
| Compiler/unit | Policy, provenance lifecycle, scopes, schema validation | Live debugger behavior |
| Bytecode inspection | Lines, slots/ranges, class products, eliminated concurrency | Editor mapping or actual stops |
| JDI integration | Class prepare, binding, real stops, variables, host invocation | IDEA UI/plugin behavior |
| BSP protocol | Lifecycle, capabilities, diagnostics, output, launch data | `.flix` source debugger support |
| IDEA workflow | Import, source breakpoints, navigation, stepping, evaluation UI | Every compiler input/program |

Proposed new suites, named by behavior rather than old implementation structure:

- `TestDebugCompilationPolicy` and debug/sequential integration fixtures.
- `TestLineNumberTable` and `TestDebugBreakpoints` with the shared JDI harness.
- `TestDebugScopeLifecycle`, `TestDebugVariables`, and `TestDebugIndex`.
- `TestBuildLaunchContract` and artifact freshness/cleanup fixtures.
- BSP lifecycle/capability/diagnostic/run/test suites adapted from historical tests.
- `TestDebugEvalProvider`, `TestDebugEvalHost`, and paused-JVM evaluation integration.

Extend existing stable-name and sequential tests where they already express the needed
invariant; avoid duplicating entire suites. Tests should assert observable semantics or
contracts, not private implementation choices.

The repository uses Mill. During implementation, use `./mill flix.compile`, the
existing `flix.test` module's targeted suite runner, and `./mill flix.assembly` for
packaged-product validation. Confirm runner syntax and suite names in that checkout;
the proposed suites above do not exist yet. Do not report these commands as already run.

### Plugin regression and integration gates

Reuse the sibling repository's tests rather than constructing a second plugin harness:

| Contract | Existing suites to extend |
| --- | --- |
| Build/index/scopes/argument wire compatibility | `FlixBuildSpecTest`, `FlixDebugIndexTest`, `FlixDebugScopesTest`, `FlixLaunchCommandTest` |
| Correct debuggee and background build | `FlixDebuggeeIdentityTest`, `FlixDebugPhaseOrderTest`, `FlixDebuggerRunnerGatesTest`, `FlixLaunchTest` |
| Line/source ownership and caches | `FlixSourceLocationsTest`, `FlixForwardResolutionTest`, `FlixPositionManagerDelegationTest`, `FlixSourceCacheTest`, `FlixDebugSessionTest` |
| Names, values, logical stack, stepping | `FlixFrameNamesTest`, `FlixValuesTest`, `FlixValueTreeTest`, `FlixContinuationsTest`, `FlixSteppingFilterTest`, `FlixDefinitionScopeTest` |
| Evaluation and module bridge | `FlixEvaluatorTest`, `FlixEvaluatorAnswerTest`, `FlixDebugEvalSeamTest` |
| Packaged module/classloader ownership | `FlixPlatformDependenciesTest`, descriptor/assembled-plugin tests, `checkIntegrationGlue` |

From the plugin checkout, run focused `:shared:test` / `:debugger:test` tasks while
adapting consumers, then `./gradlew check buildPlugin`. Inspect descriptor/structure
verification output as well as exit status; the plugin records a malformed XML case
that packaging alone failed to catch. Verify an installed packaged plugin, because
sandbox classpaths have previously hidden missing Java content-module dependencies.

Use `./gradlew runIde` and `./gradlew runIdeSplitMode` for the relevant live gate rows.
`./gradlew testIdeUi` is a separate interactive test: it requires a compiler jar and
drives the desktop, so it is not part of ordinary headless `check`. Existing disabled
debugger UI cases remain unproven until enabled and passed; a passing gutter test does
not establish launch/evaluation behavior or detect duplicate registrations.

Use `scripts/FlixLineProbe.java` / `scripts/FlixDebugProbe.java` for independently owned
JDI sessions, and use the plugin's `docs/native-debugger-gate.md` and
`docs/phase-8-verification.md` to record the updated evidence. Do not replace their
measured/unmeasured distinctions with a single broad Green label.

Validate the adopted parser against representative v0.76 fixtures, especially the
definitions used by breakpoint eligibility and `FlixDefinitionScope`. The plugin's
existing corpus pin remains useful evidence but does not prove parser compatibility
with every new compiler construct; update a grammar/spec pin only as a separate,
reviewed compatibility change.

## 8. Suggested change sequence

Each row should be independently reviewable, with its gate included. Large milestones
may require several commits; avoid combining schema changes with unrelated refactoring.

| Order | Change | Prerequisite |
| --- | --- | --- |
| 1 | Paired baseline report, reusable fixtures, plugin compatibility audit | M0 |
| 2 | Compiler-owned switch reduction and sequential regression coverage | M1 design |
| 3 | Debug option and inlining policy with provenance preservation | 2 |
| 4 | Source attribution, line tables, JDI breakpoint harness | 3 |
| 5 | Debug provenance capture/propagation/lifecycle | 3 |
| 6 | Locals/captures and scope metadata finalization | 4–5 |
| 7 | Source index, build identity, launch manifest, owned products | 6 |
| 8 | Existing IDEA debugger naming/layout/stepping adaptations via CLI build/launch | 4, 7, M0 audit |
| 9 | BSP lifecycle/import/compile/diagnostics | 7 |
| 10 | BSP run/test/JVM environments and output compatibility | 9 |
| 11 | Evaluation protocol, provider, packaged runtime host | 6–7 |
| 12 | Existing IDEA evaluation bridge compatibility changes | 8, 11 |
| 13 | Integration report, recipes, supported-version matrix | Required preceding gates |

After the schema and fixtures are agreed, compiler emission, build/launch work, and
IDE extension work can proceed independently where their prerequisites permit. Changes
to shared compiler phase ownership must be coordinated rather than developed as
competing provenance implementations.

## 9. Decisions to close during implementation

These are bounded design tasks with defaults, not reasons to postpone the whole plan.

| Decision | Default / selection rule | Must close by |
| --- | --- | --- |
| Constant reduction placement | Existing optimizer with an explicit debug-aware compiler-switch inlining allow-list; narrow phase-local reduction only if required | M1 |
| Debug metadata packaging | Existing index v1/scope v2 projection first; coordinate richer schema and build identity with plugin readers | M3 |
| Shared-layout value rendering | Use explicit type/value context; raw representation when ambiguous | M3 |
| Manifest schema compatibility | Preserve format 4 launch contract initially; release a reader update before any incompatible producer version | M4 |
| IDEA plugin ownership/API | Adapt sibling plugin at the pinned revision within its existing ADR/module boundaries | Established; verify in M7 |
| Source package navigation | Read-only, identity-preserving source resolver with tested URI handling | M7 |
| Evaluation effects | Preserve an explicit conservative policy and reject unsupported suspension; no implicit handler resume | M8 |
| Initial evaluation scope | Definition parameters and representable specializations only | M8 |
| Later locals/lifted evaluation | Separate extension requiring lexical environment/type reconstruction tests | After M9 |

## 10. Risk controls and completion criteria

| Risk | Detection | Containment |
| --- | --- | --- |
| Debug bypass retains forbidden sequential code | Combined bytecode/reachability and execution tests | Mandatory switch reduction before optional optimization |
| Provenance lost before snapshot | Specialization/lifting tests and missing-record checks | Capture at the phase that owns the information |
| Freeze retains compiler graphs | Existing lifecycle tests plus collector cleanup assertions | Separate compact debug snapshot and scoped ownership |
| Breakpoints bind to wrappers/wrong source | JDI stops and duplicate-basename fixture | Explicit synthetic attribution and source identity |
| Build/metadata/debuggee mismatch | Build-identity and failed/repeated build tests | Reject stale sessions and publish consistent products |
| Stable names hide incompatible runtime classes | Edited-source and loader mismatch tests | Build compatibility check and artifact-specific wrappers |
| BSP support mistaken for IDEA debugging | Real editor workflow gate | Separate import, attach, and source-debug support statuses |
| Evaluation deadlocks/reenters breakpoints | Controlled paused-JVM/IDE tests | Explicit invocation policy; honest cancellation limitations |
| Joint-compilation layout assumptions leak in | Exported ABI and namespace fixtures | Keep layout changes in their own integration track |
| Plugin assumes old hash/package/runtime layout | Compiler-produced name/value/continuation fixtures consumed by plugin tests | Explicit metadata plus versioned legacy recognition |
| Schema silently defeats narrow plugin readers | Cross-repository fixture tests, including unexpected fields/versions | Coordinated schema migration and explicit fallback/refusal |
| Native debugger invocation deadlocks | Evaluation that prepares new classes in an IDEA session | Platform-managed method invocation and existing evaluator bridge |
| BSP and CLI builds overwrite each other's outputs | Concurrent-build and stale-launch fixtures | One build owner per output directory and validated build identity |

The foundation increment is complete only when M1–M4 gates pass on the integrated
stable-name/sequential build. The native-IDEA increment requires M7 and its recorded
CLI-build/manifest client workflow; BSP-backed import/run qualification is a separate
M5–M6 increment. Evaluation additionally requires M8–M9. M10 qualifies each increment
before it is advertised.

Completion reports must distinguish source inspection, automated test results, and
manual IDE observations. They must list any unsupported frame kinds or IDE versions.
No claim of universal breakpoint persistence, complete expression evaluation, or
deterministic replay is part of this plan.

## Appendix: Historical port references

| Concern | Commits from the research notes |
| --- | --- |
| Helper breakpoints / optimizer policy | `7d465860c` |
| Header/statement line collision | `b89a626ae`, `d580e8226` |
| Handler source attribution | `30fbfb7b5` |
| Conditional cross-file SMAP | `232078d14` |
| Local variable visibility | `fa0be2075`, `7f9053bb2` |
| Source index | `f11f18cd2` |
| Parameter/local/capture types | `2f23679e1`, `3d25e0662`, `aa294e5a0` |
| Struct/enum display metadata | `9dc0fdc82`, `ee0086444`, `620f6484e` |
| Continuation metadata | `b3268140d`, `f80c61f60` |
| Launch manifest | `c2f527bc8` |
| BSP lifecycle, diagnostics, run output/environment | `2db7b6f1a`, `2de82d3a4`, `478d55ca7`, `1e9ad47f3`, `aec912bd6` |
| Evaluation host and product packaging | `d638ee62b`, `47bdd8422` |
| Evaluation scope/protocol and failures | `cdacdb414`, `0e8a5eb7e`, `aec7d4fd3` |
| Evaluation compiler reuse and cache | `9a4102ffa`, `53e1c9edc` |
| Unified sequential CLI | `5dc3e5ea2` |

Read each patch and its applicable tests before porting it. Historical implementation
details are reference material; the current backend and the contracts above determine
the new implementation.
