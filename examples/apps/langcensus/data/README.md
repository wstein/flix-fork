# Data

## `languages.json`

Maps a lower-case file extension to a language name, e.g. `"rs": "Rust"`.

Generated from [github-linguist][linguist]'s `lib/linguist/languages.yml`, which is
the database GitHub itself uses to label code. Inverting it gives one entry per
extension rather than per language.

An extension claimed by several languages is resolved by preferring the language
that lists it as its *primary* extension — Linguist documents that the primary is
always listed first. Roughly one extension in nine is still ambiguous after that
rule, because Linguist settles those by inspecting file contents, which a census
working from commit metadata cannot do. The generator pins the widely used ones
(`.md`, `.h`, `.m`, `.html`, `.yaml`, ...) so that, for example, `.md` is Markdown
rather than GCC Machine Description.

To refresh, re-download `languages.yml` and regenerate.

[linguist]: https://github.com/github-linguist/linguist
