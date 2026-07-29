///
/// A YAML node.
///
/// Deliberately distinct from `Util.Json.Json` rather than reusing it. A YAML node
/// carries a resolved tag, and documents that differ only by tag are different YAML
/// but identical JSON. Parsing straight into `Json` would silently discard that, so
/// the lossy direction is an explicit conversion instead: see `Yaml.toJson`.
///
pub mod Yaml {

    ///
    /// A node in a YAML document.
    ///
    /// Mappings preserve insertion order, unlike `Json`, whose `Map` sorts keys. A
    /// YAML file is usually written by a person and read back by one, so the order
    /// they chose is information worth keeping.
    ///
    pub enum Yaml with Eq {
        case YMapping(List[(Yaml, Yaml)])
        case YSequence(List[Yaml])
        case YString(String)
        case YInt(BigInt)
        case YFloat(Float64)
        case YBool(Bool)
        case YNull
    }

    instance ToString[Yaml] {
        pub def toString(y: Yaml): String = match y {
            case Yaml.YNull        => "null"
            case Yaml.YBool(b)     => "${b}"
            case Yaml.YInt(i)      => "${i}"
            case Yaml.YFloat(f)    => "${f}"
            case Yaml.YString(s)   => s
            case Yaml.YSequence(xs) => "[" + (xs |> List.map(ToString.toString) |> List.join(", ")) + "]"
            case Yaml.YMapping(kvs) =>
                "{" + (kvs |> List.map(match (k, v) -> "${k}: ${v}") |> List.join(", ")) + "}"
        }
    }

    ///
    /// Converts a YAML node to JSON.
    ///
    /// Lossy on purpose, and named so the caller sees it:
    ///
    ///   - A mapping key that is not a string is rendered as one. YAML permits any
    ///     node as a key; JSON permits only strings.
    ///   - Key order is lost, because `Json` objects are a sorted `Map`.
    ///   - Duplicate keys collapse, last one winning.
    ///
    pub def toJson(y: Yaml): Util.Json.Json =
        use Util.Json.Json.{JObject, JArray, JString, JNumber, JBool, JNull};
        match y {
            case Yaml.YNull         => JNull
            case Yaml.YBool(b)      => JBool(b)
            case Yaml.YInt(i)       => JNumber(toBigDecimal("${i}"))
            case Yaml.YFloat(f)     => JNumber(toBigDecimal("${f}"))
            case Yaml.YString(s)    => JString(s)
            case Yaml.YSequence(xs) => JArray(xs |> List.map(toJson) |> List.toVector)
            case Yaml.YMapping(kvs) =>
                JObject(kvs |> List.map(match (k, v) -> (keyToString(k), toJson(v))) |> List.toMap)
        }

    ///
    /// Converts a rendered number to `BigDecimal`.
    ///
    /// Both inputs are produced by rendering a number this module already holds, so
    /// the parse cannot fail; zero is unreachable rather than a fallback.
    ///
    def toBigDecimal(s: String): BigDecimal =
        BigDecimal.fromString(s) |> Option.getWithDefault(0.0ff)

    ///
    /// Renders a mapping key as a JSON object key.
    ///
    def keyToString(k: Yaml): String = match k {
        case Yaml.YString(s) => s
        case other           => "${other}"
    }

}
