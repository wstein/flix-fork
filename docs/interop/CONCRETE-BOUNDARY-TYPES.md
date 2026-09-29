# Concrete boundary-type proof

This is the narrow compiler proof for ADR 3, not a replacement export implementation.
It runs on a feature branch based on the cleaned `dev0.77.0` compiler, without `@Export`.

## Proven contract

`BoundaryTypeElaborator.elaborate` accepts an associated-type symbol, a concrete Flix type,
and a successfully checked `TypedAst.Root` (including instance validation). It selects instance
heads through the compiler's trait environment, substitutes their arguments, checks their trait
and equality constraints, and recursively reduces associated definitions from the equality
environment. It does not contain a List/Integer conversion table or modify `Resolver`.

The fixture's checked instances derive:

| Input | `JavaResult.Out` | `JavaResult.Aef` |
|---|---|---|
| `List[Int32]` | `java.util.List[java.lang.Integer]` | `IO` |
| `List[List[Int32]]` | `java.util.List[java.util.List[java.lang.Integer]]` | equivalent to `IO` |
| user-defined `Token` | `java.lang.Integer` | `IO` |

Native type arguments remain in the returned `Type`; they are not erased to `Object`.
Aliases are expanded. Missing instances are rejected even when a selected associated result
does not mention its element constraints. Missing definitions, unresolved variables, equality
mismatches, and wrong result kinds are errors. Separate path guards detect repeated instance
and projection queries; a depth bound also rejects growing recursion deterministically.
Cycle and malformed-environment tests deliberately mutate a valid root to exercise these guards;
they do not suggest that such instances are accepted by the source language.

## Phase ordering and checked conversion

1. Check the original program through `Instances` successfully.
2. Query the checked trait/equality environments for concrete output types and effects.
3. Render the derived native type into an ordinary wrapper and recheck the augmented program.
4. Compile and execute the wrapper's test: its Java list contains boxed integers `1`, `2`, `3`.

The wrapper test builds native imports generically from the derived type, not from a conversion
table. The second frontend pass checks the actual conversion call and retains the derived
`List<Integer>` type. This proves a workable two-pass prototype, not a production wrapper phase.
Source `JavaResult.Out[List[Int32]]` and self-referential definitions remain rejected.

## Validation

All 18 focused tests pass, including the compiled runtime wrapper. The full compiler suite is
not part of this milestone's validation.

```console
./mill --no-server flix.test.testOnly ca.uwaterloo.flix.language.phase.interop.TestBoundaryTypeElaborator
```

From `flix-lab`:

```console
FLIX_FORK_ROOT=/path/to/compiler-worktree ./scripts/check-concrete-boundary-types
```

## Still unproven

No new export syntax, forwarding facade, JVM generic `Signature` emission, or joint-compilation
stubs are implemented here. In particular, syntax-only stubs cannot simply query a typed root
when the Java side of a cyclic build does not exist. That bootstrapping contract needs its own
proof. Primitive positional boxing, Java argument conversion, allowed entry-point effects,
region restrictions, and opaque handles also belong to subsequent integration work.

The old `flix-lab` staged Java caller is explicitly archived-export evidence, not evidence for
this replacement path. ADR 3 remains Proposed until the replacement ABI and callers pass.
