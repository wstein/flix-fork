# Upstream discussion draft: typed JVM boundaries

Status: local draft; not posted upstream.

After `@Export`'s removal, propose a deliberately smaller opt-in replacement:

- Separate deep, directional `Java.Boundary` conversion traits; leave `ToJava`/`ToFlix` intact.
- A named API contract selecting public monomorphic functions. Phase 1 uses a `.flix-api`
  sidecar with explicit Java signatures, rather than changing normal Flix grammar.
- Concrete associated-type elaboration after successful instance validation, followed by
  ordinary checked source wrappers. Do not relax surface associated-type rules.
- A thin forwarding facade that records generic JVM signatures before erasure.
- Syntax-only bootstrap stubs from the explicit contract. Checked instance reductions must
  match it before code generation; class, descriptor, and generic-signature diffs fail the build.
- Existing entry-point effect policy, explicit type-tagged opaque handles, and conservative
  region rejection. No implicit Object/opaque fallback or bytecode conversion backend.

The prototype demonstrates recursive `List[Int32]` element conversion and `List<Integer>`
signatures, Java-first cyclic builds without stubs at runtime, and isolated Java/Kotlin/Scala
callers. It includes contract-located CLI and LSP diagnostics and instance-driven ABI rebuilds.
See [the implementation and reproducible checks](JAVA-BOUNDARY-PHASE1.md).

Review questions for maintainers:

1. Is an explicit sidecar API contract acceptable as an experimental bootstrap boundary?
2. Should two-pass checked wrapper synthesis remain an opt-in API, or gain a dedicated frontend
   entry point before a production rollout?
3. Which packaged container shapes and opaque-handle API should be committed as public ABI?

Tuple/record/enum Java classes, Java interfaces implemented by Flix, and Java-supplied effect
handlers remain separate later phases. This proposal does not reintroduce the removed export
backend, promise legacy-shape compatibility, or include a publication request.
