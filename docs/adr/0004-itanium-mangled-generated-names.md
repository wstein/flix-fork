# 4. Spell generated names by Itanium-style mangling, and compact only overlong ones

## Status

Proposed. Scoped to the *spelling* of generated JVM class names: what a specialization, a lambda,
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
  jars one file per class, and file systems and archive tools cap a component at 255 bytes. A
  name that is too long fails at `build-jar` or on unpacking, far from the compile that made it.
- **Keys cannot be read back.** `JvmTypeKey` encodes a type into a Base64 string inside the key,
  and `JvmOriginKey.compose` stores its parents' digests. As with readable paths, a spelling must
  be recorded beside the key when the specialization is registered, from the `Type` or
  `SimpleType` arguments `specializedSymbol` and `erasedSymbol` receive.

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

### 1. A specialization is spelled by its mangled type arguments

After the definition's own name comes the mangling of the arguments it is specialized at, in an
Itanium-style grammar adapted to JVM class names:

| Construct | Itanium | Here |
|---|---|---|
| builtin type | one letter (`i`, `b`) | its Flix name: `Int32`, `Bool`, `String`, `Unit`, `Obj` for an erased reference |
| source name | `<length><identifier>`: `3map` | the same: `5Color` |
| qualified name | `N <names> E`: `N4Acme5ColorE` | the same |
| type arguments | `I <args> E` | the same |
| substitution | `S_`, `S0_`, ... for a repeated component | the same; which components are candidates, and their numbering order, is an open question |

For example, with the definition's name kept plain in front:

```
Def$map$I5Int326StringE                     map at (Int32, String)
Def$index$I3MapI6String4ListI5Int32EEEE     index at Map[String, List[Int32]]
Def$swap$I5ColorS_E                         swap at (Color, Color): S_ repeats 5Color
Case$Option$IObjE$None                      None of Option erased at a reference type
```

Length-prefixed names and explicit `I ... E` / `N ... E` brackets make the grammar injective:
every spelling parses back to exactly one argument list, which a `flix demangle` command can print.
Builtins are spelled as words rather than Itanium's letters, because readability is the point;
they cannot be confused with a source name, which always starts with its length. A record's fields
are spelled in label order, a function type as its parameter and result types, and an effect
argument as its effects in the canonical order `JvmTypeKey` already imposes -- whatever the key
distinguishes, the spelling distinguishes.

Mangling runs through `Mangle.mangle`, so an operator or other special character in a source name
is escaped the way it is today.

### 2. Nested classes keep their readable origin

A lambda, local definition, or anonymous class keeps its readable path from `bfdb35bf2`. Under a
specialized owner it now leads with the owner's *mangled* suffix instead of its hash:
`Clo$map$I5Int326StringE$0`.

### 3. Overlong names are compacted as Scala does, with a better hash

A name whose class file name component would exceed 240 characters -- counting its namespace
prefix, `Def$`/`Clo$`/`Anon$`, and `.class` -- is compacted to

```
<first quarter> $$$$ <hash> $$$$ <last quarter>
```

where `<hash>` is SHA-256 of the full uncompacted name, reduced to a fixed number of lowercase
base-36 digits: `--Xsymbol-hash-length`, default 12, zero-padded. Fixed width keeps the result's
length predictable; Scala's unpadded hexadecimal varies from 16 to 32 characters and is not strictly
injective as a formatting (`0x01 0x23` and `0x12 0x03` both print as `123`). The table's existing
collision check applies to compacted names, with its existing advice.

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
readable spelling is not unique; 49 digits is the most a SHA-256 digest fills, since
36^49 < 2^256 < 36^50. `--Xsymbol-names counter` names each class by its internal counter as
upstream Flix does, for comparing against upstream and for ruling the naming out when chasing a
defect; it ignores `--Xsymbol-hash-length`. Provenance is still required in either mode.

`--Xstable-name-length` has been neither pushed nor released, so it is renamed without a
deprecation period: `N > 0` becomes `--Xsymbol-hash-length N`, and `0` becomes
`--Xsymbol-names counter`. `JvmNameTable`'s width parameter keeps meaning a width; the mode becomes
its own parameter instead of the value zero.

## Consequences

- **Readable everywhere.** Stack traces, profilers, and the debugger show which specialization a
  frame is in; `flix demangle` recovers the types.
- **Still stable.** A spelling is a pure function of the specialization -- no counter, schedule,
  or unrelated edit changes it. Renaming a type renames the specializations at it, which is what a
  reader expects.
- **Fewer hashes, and fewer collisions.** Only overlong names and non-unique nested spellings
  are hashed, so the collision check guards a small population instead of every generated class.
- **Longer names.** Constant pools and jar entries grow with the spelling. The 240-character cap
  bounds each name, and compaction bounds the worst case.
- **A spelling is recorded beside each specialization key**, as readable paths are beside lexical
  keys: `specializedSymbol` and `erasedSymbol` have the types in hand when they register.

## Alternatives considered

Rated for readability, stability, and cost (★ low to ★★★★★ high).

| Alternative | Readable | Stable | Cost | Verdict |
|---|---|---|---|---|
| Keep hashing every specialization (status quo) | ★ | ★★★★★ | none | Rejected: unreadable where it matters most |
| Plain `$`-separated type names (`Def$map$Map$String$List$Int32`) | ★★★★★ | ★★★★★ | low | Rejected: not injective, arity and nesting are lost |
| Faithful Itanium, builtins as letters (`Def$map$IiiE`) | ★★ | ★★★★★ | low | Rejected: injective but cryptic for no gain |
| **Itanium-style, builtins as words, Scala-style compaction with SHA-256** | ★★★★ | ★★★★★ | medium | **Proposed** |
| Compaction hashed with MD5 as Scala does | -- | ★★★★ | low | Rejected: no reason to prefer it over the SHA-256 already in use, and variable-width formatting |
| Hash encoded as base64url | ★★★ | ★★ | low | Rejected, see below |
| One flag, `--Xstable-name-length`, with `0` as the counter mode | -- | -- | none | Rejected: a mode is not a length, see §4 |

**Why not base64url.** Denser, at six bits a character, but it mixes cases, which a case-insensitive
file system folds together (see §3), and it contains `-`, which the JVM accepts in a class name but
Java source cannot name.

## Open questions

- **Which types to spell.** Monomorph specializations are keyed by full types, `Eraser`'s by
  erased ones. Spelling the erased key is short and matches Flix's existing `Tuple2$Obj$Int32`
  convention; spelling full types is more informative. Where the two coexist for one definition,
  the spelling must follow the key that actually distinguishes the classes.
- **Effect and region arguments.** Whether every effect the key distinguishes needs spelling, or
  some are erased before any class depends on them.
- **The limit's unit.** 240 *characters* as Scala counts, or 240 *bytes* of the file name's
  encoding, which a non-ASCII identifier makes longer.
- **Substitution numbering.** Itanium numbers substitutions in a fixed traversal order; the
  grammar needs the same, pinned by golden vectors as the key encoding already is.
- **`flix demangle`.** A small command, but it makes the grammar a public contract.
