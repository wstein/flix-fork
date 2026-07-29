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

## Mappings keep their order

`Yaml.YMapping` is a list of pairs, not a `Map`. A YAML file is written by a
person and read back by one, so the order they chose is preserved. `Util.Json`
sorts keys, which is right for canonical output and wrong for round-tripping
config.

## Converting to JSON

`Yaml.toJson` exists and is deliberately explicit, because it loses information:

- non-string mapping keys are rendered as strings (JSON has no others)
- key order is lost, since `Json` objects are a sorted `Map`
- duplicate keys collapse

Parsing never produces `Json` implicitly.

## Not implemented

- **Writing.** Reading only, for now.
- **Multiple documents.** `---`-separated streams parse only the first document.
- **Anchors are expanded, not preserved.** The `Yaml` type has no anchor or tag
  case, so a round trip loses them.

## Tests

```bash
flix test
```

[engine]: https://bitbucket.org/snakeyaml/snakeyaml-engine
