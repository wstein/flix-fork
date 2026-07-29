# flix-yaml

Reads YAML into Flix values. Apache-2.0, backed by
[snakeyaml-engine][engine] through Java interop.

```flix
pub enum Server({host = String, port = Int32, tags = List[String]})

instance FromYaml[Server] {
    pub def fromYaml(y: Yaml): Result[YamlError, Server] =
        forM (host <- Yaml.FromYaml.decodeAtKey("host", y);
              port <- Yaml.FromYaml.decodeAtKey("port", y);
              tags <- Yaml.FromYaml.decodeAtKey("tags", y))
        yield Server.Server({host = host, port = port, tags = tags})
}

let config: Result[YamlError, Server] = Yaml.Parse.decode(doc);
```

Nothing Java-shaped is exposed. Errors are a Flix ADT, not exceptions; results are
`Result`; decoding goes through a trait. The API deliberately mirrors
`Util.Json` — `parse`, `decode`, `decodeAtKey`, `decodeAtKeyOpt` — so it should
already be familiar.

## YAML 1.2, not 1.1

The schema version is not a detail. Under YAML **1.1**, which most JVM YAML
libraries still implement, this is a boolean:

```yaml
country: no        # YAML 1.1 -> false      YAML 1.2 -> "no"
```

Norway becomes `false`. This package resolves the **1.2 core schema**, so `no`,
`yes`, `on` and `off` are strings. Only `true` and `false` are booleans. There is
a test pinning exactly this.

## Safety

YAML's features are more dangerous than JSON's, so the defaults are strict and
there is no unsafe variant to reach for:

| Guard | Value | Why |
| :-- | :-- | :-- |
| Custom tags | never honoured | A tag never constructs a type. This is the `load` vs `safe_load` distinction behind a long line of RCEs. |
| Alias budget | 50 | "Billion laughs": ten references across nine levels reach 10⁹ nodes using only ninety aliases. The budget must bound the *product*, not the count. |
| Document size | 8 MiB of code points | Bounds the input. |
| Duplicate keys | rejected | Returned as an error rather than silently resolved. |

## The node type

Modelled on [HsYAML][hsyaml]'s `Node`:

```flix
pub enum Yaml {
    case YScalar(Pos, Tag, Scalar)
    case YMapping(Pos, Tag, Map[Yaml, Yaml])
    case YSequence(Pos, Tag, List[Yaml])
}
```

Every node carries its **source position** and its **resolved tag**, and scalars
are a separate typed sum (`SNull`, `SBool`, `SInt`, `SFloat`, `SStr`, `SUnknown`).

Mappings are a `Map` **keyed by node**, because YAML permits any node as a key,
not only a string. Equality and ordering deliberately ignore position — otherwise
a key could never be looked up, since the caller has no position to build one
with. HsYAML hand-writes its `Eq`/`Ord` for exactly this reason; so does this.

Two consequences follow from the `Map`, both inherited from HsYAML's design:

- **Key order is not preserved.** A `Map` sorts. If you need to round-trip a
  config file with its original ordering, this is the wrong shape.
- **Lookup is logarithmic** rather than linear, and keys may be sequences or
  mappings.

Duplicate keys are rejected rather than collapsed. The composer does not check
this itself, so the package does.

## Converting to JSON

`Yaml.toJson` exists and is deliberately explicit, because it loses information:

- non-string mapping keys are rendered as strings (JSON has no others)
- key order is lost, since `Json` objects are a sorted `Map`
- duplicate keys collapse

Parsing never produces `Json` implicitly.

## Not implemented

- **Writing.** Reading only, for now.
- **Multiple documents.** `---`-separated streams parse only the first document.
- **Anchors are expanded, not preserved.** HsYAML has an `Anchor` constructor and
  keeps them; snakeyaml-engine's composer resolves aliases into shared nodes
  before the tree is seen, so there is nothing left to record. Round-tripping
  anchors would need a different backend.

## Tests

```bash
flix test
```

[engine]: https://bitbucket.org/snakeyaml/snakeyaml-engine
[hsyaml]: https://github.com/haskell-hvr/HsYAML
