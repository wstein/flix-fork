# flix-yaml-native (not started)

A placeholder for a YAML parser written in Flix, with **no JVM dependency**.

## Why this is a separate package, and GPL

`flix-yaml` binds snakeyaml-engine and is Apache-2.0, matching Flix itself. A
native parser is likely to be derived from an existing implementation, and the
most complete reference for a *pure functional* YAML parser is
[HsYAML][hsyaml] — which is **GPL-2.0**.

GPL-2.0 code cannot be relicensed into an Apache-2.0 project. A derivative work
stays GPL. Keeping it in its own package means:

- `flix-yaml` stays Apache-2.0 and safe for any downstream licence;
- a GPL-derived parser remains possible for those who can accept GPL;
- nobody acquires a GPL obligation by depending on YAML support generally.

If the parser is written from the specification instead, without consulting
GPL sources, it can be relicensed. That decision belongs to whoever writes it,
and it must be made *before* reading HsYAML, not after.

## Why it might be worth doing anyway

- Removes a JVM dependency, which matters if Flix ever targets another backend.
- Makes the parser debuggable and evolvable in Flix.
- Permissively licensed references exist: **libyaml** (MIT) and **go-yaml**.
  Studying those instead of HsYAML keeps the Apache-2.0 option open.

## Scope

The measured cost, for anyone estimating:

| | |
| :-- | :-- |
| YAML 1.2.2 spec | 211 numbered productions |
| yaml-test-suite | 351 cases |
| HsYAML | 262 KB across 18 files; the tokenizer alone is 79 KB |
| Flix's whole JSON library | 1,625 lines |

The tokenizer is the hard part, not the parser: YAML's indentation is semantic
and block versus flow context changes the lexer's rules, so it cannot be built
the way `Util.Json`'s index-based recursive descent was.

**Gate before writing any parser:** run the 351 cases and report pass, fail and
*silently wrong* separately. Silently wrong must be zero. A subset that rejects
what it cannot handle is honest; one that mis-parses it quietly is worse than
nothing.

[hsyaml]: https://github.com/haskell-hvr/HsYAML
