# 4. Spell generated names by Itanium-style mangling, and compact only overlong ones

## Status

Proposed; prototyped on `feat/itanium-generated-names`, with an edit-resistance finding still open.
Scoped to the *spelling* of generated JVM class names: what a specialization, a lambda,
a local definition, and an anonymous class are called. Their *identity* -- the provenance keys
`JvmProvenance` records and `JvmNameTable` checks -- is unchanged. Numbered 4 in the fork's series:
ADRs 1 and 2 are on `feat/stable-specialization-names-rewrite`, and ADR 3, JVM interop as a typed
boundary, is on `dev0.77.0`.

## Context

`JvmNameTable` names every generated symbol after its provenance. The name is stable -- no
counter, no thread schedule, no unrelated edit changes it -- but a specialization's name is a hash:

```
dev/flix/gen/Def$map$k3j9x0q2m1ab        List.map at Int32 -> String
dev/flix/gen/Def$println$qsmbcoowzoho    println at some type
```

Stack traces, profiler output, `javap`, and the debugger all show those twelve digits, and nothing
in them says which specialization a frame is in.

Two recent changes on this branch point the way:

- `--Xstable-name-length` (`13cc632f5`) made the width of that hash a setting, with `0` opting
  back into upstream's counter ids.
- Readable names for nested classes (`bfdb35bf2`) spell a lambda, local definition, or anonymous
  class by where it is written -- `Clo$price$discount$0` -- keeping the hash as the fallback where
  that spelling is not unique. A lambda of a specialized owner still leads with the owner's hash:
  `Clo$map$k3j9x0q2m1ab$0`.

What remains unreadable is the specialization itself. It is also the one name that *can* be
spelled without loss: a specialization is a definition plus the types it is instantiated at, and
`JvmOriginKey.compose("specialization", ...)` and `compose("erasure", ...)` already key it by
exactly those types, through the canonical encodings of `JvmTypeKey`.

Two facts bound any spelling:

- **A class file name must fit one path component.** Class files are written to disk and into
  jars one file per class, and file systems and archive tools cap a component at 255 bytes. The
  namespace is a sequence of directories, not part of that component. A
  name that is too long fails at `build-jar` or on unpacking, far from the compile that made it.
- **Keys cannot be read back.** `JvmTypeKey` encodes a type into a Base64 string inside the key,
  and `JvmOriginKey.compose` stores its parents' digests. As with readable paths, a spelling must
  be recorded beside the key when the specialization is registered, from the `Type` or
  `SimpleType` arguments `specializedSymbol` and `erasedSymbol` receive.

### How Flix spells its shape classes

Flix already spells types into one family of names: its *shape* classes, which every value of the
same erased shape shares, in the root package -- `Tuple$Obj$Int32`, `Tag$Obj`, `Struct$Obj`,
`Lazy$Int32`, `Fn2$Obj$Obj$Obj`. The spelling happens in three places:

1. **`Eraser` decides the shape.** It erases element types in `SimpleType`,
   `Tuple(elms) => SimpleType.mkTuple(elms.map(erase))`, so `(String, Int32)` becomes
   `Tuple(Object, Int32)`. It makes no name for it.
2. **`TypeDescs.toErasedClassDesc` maps each erased type to a JVM type**: a primitive stays
   primitive, and every reference type becomes `Object`.
3. **Each generator's `desc` spells the name, in code generation, wherever the class is used**:
   `GenTuple.desc(elms)` is `mkClassName("Tuple", elms.map(Mangle.erasedName))`, and `GenTag`,
   `GenStruct`, `GenLazy`, and `GenArrow` (`Fn<arity>`) are the same. `Mangle.erasedName` turns each
   JVM type into its atom -- `Bool`, `Char`, `Int8` to `Float64`, and `Obj` for every reference --
   and `mkClassName` joins them with `$`. `CodeGen` collects the distinct shapes from the program's
   types (`getTupleTypesOf(allTypes)` and the rest) and generates one class for each.

These names never had the problem this ADR addresses. A shape class is not a symbol: it has no
`GenSym` id and no entry in `JvmNameTable`, and its name is a pure function of its erased shape,
recomputed at every use, so it is the same in every build. And it is injective by construction:
every argument is one atom, and the family, or the argument count it spells (`Fn2`), fixes how
many there are.

Erasure is what makes an Itanium-style grammar unnecessary there. Itanium's brackets and length
prefixes delimit nested components of varying length; erasure has already collapsed every nested
type to `Obj`, and an atom cannot contain `$`, so a plain `$` join is unambiguous. The same holds
for an erased enum case, whose enum's name identifies its declaration and so its arity. Only a
monomorph specialization keeps full types, and only it needs a grammar -- which upstream avoids by
naming it with a counter, `Def$map$1234`. Were shape classes ever specialized by full types
instead -- a tuple class per `(List[Int32], String)` rather than per `(Obj, Obj)` -- nesting would
return to their names, and they would need brackets too.

Upstream already has the other half of Itanium. Itanium reserves the prefix `_Z`, so a mangled name
cannot be mistaken for a source identifier; `Mangle.scala`'s *prefix invariant* does the same job:
a class named after a programmer's identifier must carry a reserved prefix -- `Def$`, `Clo$`,
`Eff$`, or `Case$` -- because the segments of a shape name, `Obj`, `Int32`, and the rest, are legal
Flix identifiers. Without it `enum Tag { case Obj }` would be named `Tag$Obj`, which is a shape
class. This ADR keeps that invariant: every mangled or readable suffix it introduces follows such a
prefix and the symbol's own name, and none of its segments can be a Flix identifier ending in `$`,
since a Flix identifier cannot contain `$` at all.

### Special characters in identifiers

A Flix identifier may contain characters a JVM class name should not. `Mangle.mangle` replaces each
operator character with a word -- `+` with `plus`, `<` with `less`, `.` with `dot`, `|` with
`bar`, and so on -- which is how the class of `|>` becomes `Def$bargreater`. `$` itself is never
escaped, since no Flix identifier can contain it; that is what lets `$` delimit a name's segments.

### Upstream, the fork today, and this ADR

| | Upstream | Fork today | ADR 4 |
|---|---|---|---|
| Shape classes | flat erased atoms | same | same |
| Specialized definition | `Def$map$1234` (counter) | `Def$map$k3j9x0q2m1ab` (SHA-256, base 36) | `Def$map$I5Int326StringE` (Itanium-style) |
| Erased enum case | `Case$Option$1234$None` | `Case$Option$<hash>$None` | `Case$Option$Obj$None` (flat) |
| Lambda | `Clo$map$1234` | `Clo$price$discount$0` (readable path) | the same, led by the owner's mangled suffix |
| Anonymous class | `Anon$42` | `Anon$Shop$price$0` | the same |
| Package layout | namespace = package | beside the facade (`f4dac093c`) | unchanged |

Upstream mangles types only into shape classes, and there only as flat erased atoms; it never
spells a specialization's type arguments into its name. This ADR extends upstream's own flat
convention to erased enum cases, and adds a bracketed Itanium-style grammar only for definitions,
whose arguments nest.

### How Scala does it

Scala spells names in full and compacts only overlong ones. Scala 2.13 (`StdNames.scala`) and
Scala 3 (`NameOps.scala`) both replace a name longer than about 233 characters (`240` minus the
room for `$.class`) with

```
prefix + "$$$$" + md5 + "$$$$" + suffix
```

keeping the first and last quarter of the limit, so the name stays recognizable at both ends. The
hash is MD5 as unpadded hexadecimal, `md5.digest().map(b => (b & 0xFF).toHexString).mkString`:
16 to 32 characters, 32 in the ordinary case. `-Xmax-classfile-name` was removed in 2.13
(scala/scala#7497); the limit is fixed at 240. Only overlong names pay for the hash.

## Decision

### 1. A specialization is spelled by its type arguments, in the grammar its key needs

After the specialized symbol's own name come the arguments it is specialized at. Flix has two
kinds of specialization, and each is spelled the way its arguments require:

- **An erasure specialization** -- an enum or struct specialized by `Eraser`, `erasedSymbol` -- is
  keyed by erased types, and every argument is an *atom*: a primitive or `Obj`. The declaration
  fixes how many there are. It is spelled as a flat list, `Case$List$Obj$Nil` for the `Nil`
  singleton of `List` erased at a reference type, today `Case$List$zl5deigc9az4$Nil`: with only
  atoms and a known arity, a flat list is already injective, so brackets would add nothing but
  length. It is the spelling Flix already uses for its shape classes, `Tuple$Obj$Int32` and
  `Tag$Obj$Obj`.

  Unlike a shape class, though, an erased enum case is a declaration symbol, named by
  `JvmNameTable` when it freezes, not recomputed where it is used. Its flat spelling is therefore
  recorded when `Eraser` registers the specialization: `erasedSymbol(fresh, sym, targs)` already
  receives the erased `targs`, and spells each through the same `Mangle.erasedName` atoms, so
  `Case$Option$Obj$None` and `Tuple$Obj$Int32` use one vocabulary.
- **A monomorph specialization** -- a definition specialized by `specializedSymbol` -- is keyed by
  full types, which nest and are nominal: `Map[String, List[Int32]]`, records, functions, effects.
  A flat list loses where one argument ends and the next begins, so it is spelled in an
  Itanium-style grammar adapted to JVM class names:

| Construct | Itanium | Here |
|---|---|---|
| builtin type | one letter (`i`, `b`) | its Flix name: `Int32`, `Bool`, `String`, `Unit`, `Obj` for an erased reference |
| source name | `<length><identifier>`: `3map` | UTF-8 byte escaping before length-prefixing: `5Color` |
| qualified name | `N <names> E`: `N4Acme5ColorE` | the same |
| type arguments | `I <args> E` | the same |
| substitution | `S_`, `S0_`, ... for a repeated component | complete type components in depth-first postorder |
| other canonical type forms | ABI-specific productions | `F<arity>_` for arrows, `O` for canonical effect sets, `B`/`H` for ordered record/schema rows, and `X` followed by the full versioned type key for forms not yet given a readable production |

For example, with the symbol's own name kept plain in front:

```
Def$map$I5Int326StringE                     map at (Int32, String)
Def$index$I3MapI6String4ListI5Int32EEE      index at Map[String, List[Int32]]
Def$swap$I5ColorS_E                         swap at (Color, Color): S_ repeats 5Color
Case$Option$Obj$None                        None of Option erased at a reference type (flat)
Case$Option$Int32$None                      None of Option erased at Int32 (flat)
```

The two spellings cannot collide: they name different kinds of symbol, a definition against an
enum case, under different prefixes (`Def$`, `Clo$` against `Case$`).

Length-prefixed names and explicit `I ... E` / `N ... E` brackets make the grammar injective.
Source-name bytes outside ASCII letters, digits, and `_` are written as `$hh` before counting;
this prevents the subsequent JVM-name escape pass from invalidating a length frame. The `X`
production keeps the entire canonical type key, not a truncated digest, so it remains injective.
`flix demangle` decodes that versioned key to a readable structural type, prints the other
readable productions, and reports when compaction has discarded an unrecoverable middle.
Builtins are spelled as words rather than Itanium's letters, because readability is the point;
they cannot be confused with a source name, which always starts with its length. A record's fields
are spelled in label order, a function type as its parameter and result types, and an effect
argument as its effects in the canonical order `JvmTypeKey` already imposes -- whatever the key
distinguishes, the spelling distinguishes.

The final class name still runs through `Mangle.mangle`; the type grammar's `$hh` escaping has
already made its framed payloads safe before that pass.

### 2. Nested classes keep their readable origin

A lambda, local definition, or anonymous class keeps its readable path from `bfdb35bf2`. Under a
specialized owner it now leads with the owner's *mangled* suffix instead of its hash:
`Clo$map$I5Int326StringE$0`.

### 3. Overlong names are compacted as Scala does, with a better hash

A name whose class file name component would exceed 240 UTF-8 bytes -- counting its
`Def$`/`Clo$`/`Anon$` prefix and `.class`, but not its namespace directories -- is compacted to

```
<first quarter> $$$$ <hash> $$$$ <last quarter>
```

where `<hash>` is SHA-256 of the full uncompacted name, reduced to a fixed number of lowercase
base-36 digits: `--Xsymbol-hash-length`, default 12, zero-padded. Fixed width keeps the result's
length predictable; Scala's unpadded hexadecimal varies from 16 to 32 characters and is not strictly
injective as a formatting (`0x01 0x23` and `0x12 0x03` both print as `123`). The final CodeGen
class-name collision check catches compacted-name collisions and names the width flag in its advice.

The alphabet stays lowercase base 36. Class files are files, and the default file systems of macOS
(APFS) and Windows (NTFS) are case-insensitive: two names differing only in case would land on one
file when classes are written out or a jar is unpacked, and one class would silently replace the
other. Lowercase base 36 is also denser than lowercase base 32, 5.17 bits a character against 5.

### 4. Two flags: the hash's width, and whether names are stable at all

`--Xstable-name-length` (`13cc632f5`) set both the hash's width and, at `0`, a different naming
scheme. Under this decision its width only measures a hash, and a mode is not a length, so it
splits in two:

| Flag | Meaning | Default |
|---|---|---|
| `--Xsymbol-hash-length N` | the width of the SHA-256 base-36 hash, from 1 to 49 | 12 |
| `--Xsymbol-names stable\|counter` | provenance names, or upstream's counter ids | `stable` |

`--Xsymbol-hash-length` sets the compaction hash and the fallback hash of a nested class whose
readable spelling is not unique; 49 digits is a policy cap, not the full digest width (which can
be 50 digits because 36^49 < 2^256 < 36^50). `--Xsymbol-names counter` names each class by its internal counter as
upstream Flix does, for comparing against upstream and for ruling the naming out when chasing a
defect; it ignores `--Xsymbol-hash-length`. Provenance is still required in either mode.

`--Xstable-name-length` has been neither pushed nor released, so it is renamed without a
deprecation period: `N > 0` becomes `--Xsymbol-hash-length N`, and `0` becomes
`--Xsymbol-names counter`. `JvmNameTable`'s width parameter keeps meaning a width; the mode becomes
its own parameter instead of the value zero.

### 5. Names that differ only in case are a collision

Every collision check compares exact strings: the provenance checks, the table's hash claims and
spelling duplicates, and `CodeGen`'s final check for duplicate class names. The hash is safe, being
lowercase base 36. But a mangled or readable name carries identifiers the programmer wrote, and the
default file systems of macOS (APFS) and Windows (NTFS) are case-insensitive, so two names that
differ only in case are one file:

```
Case$Color$Red       Case$COLOR$Red          two enums in one namespace
Clo$price$Total$0    Clo$price$total$0       two let binders
```

The compiler would pass both as distinct, and one class would silently replace the other when the
classes are written to disk or a jar is unpacked -- the failure the base-36 alphabet exists to
prevent. Upstream has the same latent gap for its own declaration names (`Def$foo` against
`Def$Foo`), but a hash on every generated suffix used to hide it for generated names; readable and
mangled names bring it back.

`CodeGen` therefore runs its duplicate check a second time on case-folded names: two class names
equal when compared ignoring case are an error naming both, as two identical names are. There is no
fallback to a hash: which of the two would keep its readable name is not a stable property, and a
programmer can rename one of two identifiers that differ only in case.

## Consequences

- **Readable for common specializations.** Stack traces, profilers, and the debugger show the
  common type arguments. An `X` fallback retains identity and can be rendered from its
  versioned structural key; only compaction prevents recovery.
- **Still stable.** A spelling is a pure function of the specialization -- no counter, schedule,
  or unrelated edit changes it. Renaming a type renames the specializations at it, which is what a
  reader expects.
- **Fewer hashes, and fewer collisions.** Only overlong names and non-unique nested spellings
  are hashed, so the collision check guards a small population instead of every generated class.
- **Longer names.** Constant pools and jar entries grow with the spelling. The 240-byte cap
  bounds each name, and compaction bounds the worst case.
- **A spelling is recorded beside each specialization key**, as readable paths are beside lexical
  keys: `specializedSymbol` and `erasedSymbol` have the types in hand when they register.

## Prototype validation (2026-09-29)

- The fork's JVM naming tests pass (172 tests). The uncommon `X` forms, including keys embedded
  in `K` constructors, now demangle from their versioned structural type keys without changing
  emitted class names; malformed keys fail closed.
- `flix-specialization-names-lab` passes all 14 fixture checks after its classifier and golden
  snapshots were updated for mangled defs, flat erasure names, readable nested ordinals, and
  actual fallback/compaction hashes. Its short-name stress probe found 37 distinct,
  demangleable specializations. The class census found 1,126 readable generated classes and
  no apparent counter IDs in the lab build. At a one-digit fallback width, the compiler's
  collision guard reports a collision instead of silently overwriting a generated enum name.
- The separate `flix-stable-naming-lab/Corpus.flix` compiles with the prototype and emits two
  distinct, demangleable `ToString` specializations for `Shape[Int32]` and `Shape[String]`.
- **Acceptance remains open:** the lab's `edit-resistance --assert-stable-names` run found one
  nested `Clo$lowerStmt…$newTests$arg$<number>$0` name that changes between sequential and
  parallel builds and after unrelated edits. The readable path appears to include an internal
  generated argument number; its exact cause and fix still need confirmation. Neither the
  fixture snapshots nor the demangler tests establish edit stability for that class.

## Alternatives considered

Rated for readability, stability, and cost (★ low to ★★★★★ high).

| Alternative | Readable | Stable | Cost | Verdict |
|---|---|---|---|---|
| Keep hashing every specialization (status quo) | ★ | ★★★★★ | none | Rejected: unreadable where it matters most |
| Plain `$`-separated type names (`Def$map$Map$String$List$Int32`) | ★★★★★ | ★★★★★ | low | Rejected: not injective, arity and nesting are lost |
| Faithful Itanium, builtins as letters (`Def$map$IiiE`) | ★★ | ★★★★★ | low | Rejected: injective but cryptic for no gain |
| Itanium-style for erasure specializations too (`Case$Option$IObjE$None`) | ★★★ | ★★★★★ | low | Rejected: one grammar, but for an atom-only list with a known arity its brackets add length and no injectivity |
| Scala's `$mc … $sp` letters (`Def$map$mcIL$sp`) | ★★ | ★ | low | Rejected: every reference type is `L`, so `map` at `String` and at `Color` spell alike -- Scala can afford that only because it erases generics instead of specializing them |
| **Itanium-style, builtins as words, Scala-style compaction with SHA-256** | ★★★★ | ★★★★★ | medium | **Proposed** |
| Compaction hashed with MD5 as Scala does | -- | ★★★★ | low | Rejected: no reason to prefer it over the SHA-256 already in use, and variable-width formatting |
| Hash encoded as base64url | ★★★ | ★★ | low | Rejected, see below |
| One flag, `--Xstable-name-length`, with `0` as the counter mode | -- | -- | none | Rejected: a mode is not a length, see §4 |

**Why not base64url.** Denser, at six bits a character, but it mixes cases, which a case-insensitive
file system folds together (see §3), and it contains `-`, which the JVM accepts in a class name but
Java source cannot name.

## Open questions

- **Effect and region arguments: settled.** Spell everything the specialization key retains,
  including effects and regions; otherwise two distinct keys could acquire one class name.
- **The limit's unit: settled.** Count the UTF-8 bytes of the simple class filename plus `.class`.
- **Substitution numbering: settled.** Number complete type components in depth-first postorder,
  from zero; `S_` refers to zero and `S0_` to one. Golden vectors pin the traversal.
- **`flix demangle`: implemented for uncompacted names.** Compaction deliberately
  discards a middle span, so a demangler must report that it cannot recover it.
