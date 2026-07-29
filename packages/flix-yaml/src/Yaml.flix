///
/// The YAML document tree.
///
/// Modelled on HsYAML's `Node`: every node carries its source position and its
/// resolved tag, mappings are a `Map` keyed by node, and scalars are a separate
/// typed sum rather than being folded into the node type.
///
/// Two consequences of that shape are worth knowing before using it.
///
/// A mapping key may be any node, not only a string, because YAML allows it. That
/// is why mappings are `Map[Yaml, Yaml]` and why `Yaml` needs an ordering.
///
/// Equality and ordering deliberately **ignore position**, exactly as HsYAML does.
/// Two nodes with the same content are the same key regardless of where they were
/// written; without that, a key could never be looked up, since the caller has no
/// position to construct one with.
///
pub mod Yaml {

    ///
    /// A node in a YAML document.
    ///
    pub enum Yaml {
        case YScalar(Pos, Tag, Scalar)
        case YMapping(Pos, Tag, Map[Yaml, Yaml])
        case YSequence(Pos, Tag, List[Yaml])
    }

    ///
    /// Where a node begins in the source.
    ///
    /// Both are zero-based, as the underlying parser reports them.
    ///
    pub enum Pos(Int32, Int32) with Eq, Order, ToString

    ///
    /// The position used for a node built in code rather than parsed.
    ///
    /// Looking a key up means constructing one, and a constructed key has no source
    /// position. Since equality ignores position this value never affects a result;
    /// it exists so a key can be built at all.
    ///
    pub def noPos(): Pos = Pos.Pos(-1, -1)

    ///
    /// A resolved YAML tag, such as `tag:yaml.org,2002:int`.
    ///
    pub type alias Tag = String

    ///
    /// A scalar, resolved against the YAML 1.2 core schema.
    ///
    /// `SUnknown` carries a scalar whose tag is not one of the core types, keeping
    /// the tag and the raw text rather than guessing. A custom tag never causes a
    /// value to be constructed.
    ///
    pub enum Scalar with Eq, Order {
        case SNull
        case SBool(Bool)
        case SInt(BigInt)
        case SFloat(Float64)
        case SStr(String)
        case SUnknown(Tag, String)
    }

    instance ToString[Scalar] {
        pub def toString(s: Scalar): String = match s {
            case Scalar.SNull          => "null"
            case Scalar.SBool(b)       => "${b}"
            case Scalar.SInt(i)        => "${i}"
            case Scalar.SFloat(f)      => "${f}"
            case Scalar.SStr(s1)       => s1
            case Scalar.SUnknown(t, v) => "${t} ${v}"
        }
    }

    ///
    /// Equality ignores position, so a node's identity is its content.
    ///
    instance Eq[Yaml] {
        pub def eq(x: Yaml, y: Yaml): Bool = match (x, y) {
            case (Yaml.YScalar(_, t1, s1), Yaml.YScalar(_, t2, s2))     => t1 == t2 and s1 == s2
            case (Yaml.YMapping(_, t1, m1), Yaml.YMapping(_, t2, m2))   => t1 == t2 and m1 == m2
            case (Yaml.YSequence(_, t1, l1), Yaml.YSequence(_, t2, l2)) => t1 == t2 and l1 == l2
            case _ => false
        }
    }

    ///
    /// Ordering ignores position, for the same reason equality does.
    ///
    /// Scalars sort before sequences, which sort before mappings. The order between
    /// kinds is arbitrary but must be total, since it is what makes `Map[Yaml, Yaml]`
    /// possible.
    ///
    instance Order[Yaml] {
        pub def compare(x: Yaml, y: Yaml): Comparison = match (x, y) {
            case (Yaml.YScalar(_, t1, s1), Yaml.YScalar(_, t2, s2))     => Order.compare((t1, s1), (t2, s2))
            case (Yaml.YSequence(_, t1, l1), Yaml.YSequence(_, t2, l2)) => Order.compare((t1, l1), (t2, l2))
            case (Yaml.YMapping(_, t1, m1), Yaml.YMapping(_, t2, m2))   => Order.compare((t1, Map.toList(m1)), (t2, Map.toList(m2)))
            case (Yaml.YScalar(_, _, _), _)                             => Comparison.LessThan
            case (_, Yaml.YScalar(_, _, _))                             => Comparison.GreaterThan
            case (Yaml.YSequence(_, _, _), _)                           => Comparison.LessThan
            case (_, Yaml.YSequence(_, _, _))                           => Comparison.GreaterThan
        }
    }

    instance ToString[Yaml] {
        pub def toString(y: Yaml): String = match y {
            case Yaml.YScalar(_, _, s)   => "${s}"
            case Yaml.YSequence(_, _, xs) => "[" + (xs |> List.map(ToString.toString) |> List.join(", ")) + "]"
            case Yaml.YMapping(_, _, m)  =>
                "{" + (Map.toList(m) |> List.map(match (k, v) -> "${k}: ${v}") |> List.join(", ")) + "}"
        }
    }

    ///
    /// Returns the source position of `y`.
    ///
    pub def posOf(y: Yaml): Pos = match y {
        case Yaml.YScalar(p, _, _)   => p
        case Yaml.YMapping(p, _, _)  => p
        case Yaml.YSequence(p, _, _) => p
    }

    ///
    /// Returns the resolved tag of `y`.
    ///
    pub def tagOf(y: Yaml): Tag = match y {
        case Yaml.YScalar(_, t, _)   => t
        case Yaml.YMapping(_, t, _)  => t
        case Yaml.YSequence(_, t, _) => t
    }

    ///
    /// Builds a string node suitable for looking a mapping key up.
    ///
    pub def key(k: String): Yaml =
        Yaml.YScalar(noPos(), "tag:yaml.org,2002:str", Scalar.SStr(k))

    ///
    /// Converts a YAML node to JSON.
    ///
    /// Lossy, and named so the caller sees it:
    ///
    ///   - positions, tags and anchors are dropped;
    ///   - a mapping key that is not a string is rendered as one, since JSON has no
    ///     others;
    ///   - a scalar with a non-core tag becomes its raw text.
    ///
    pub def toJson(y: Yaml): Util.Json.Json =
        use Util.Json.Json.{JObject, JArray, JString, JNumber, JBool, JNull};
        match y {
            case Yaml.YScalar(_, _, s) => match s {
                case Scalar.SNull          => JNull
                case Scalar.SBool(b)       => JBool(b)
                case Scalar.SInt(i)        => JNumber(toBigDecimal("${i}"))
                case Scalar.SFloat(f)      => JNumber(toBigDecimal("${f}"))
                case Scalar.SStr(str)      => JString(str)
                case Scalar.SUnknown(_, v) => JString(v)
            }
            case Yaml.YSequence(_, _, xs) => JArray(xs |> List.map(toJson) |> List.toVector)
            case Yaml.YMapping(_, _, m)   =>
                JObject(Map.toList(m) |> List.map(match (k, v) -> ("${k}", toJson(v))) |> List.toMap)
        }

    ///
    /// Converts a rendered number to `BigDecimal`.
    ///
    /// The input is produced by rendering a number this module already holds, so the
    /// parse cannot fail.
    ///
    def toBigDecimal(s: String): BigDecimal =
        BigDecimal.fromString(s) |> Option.getWithDefault(0.0ff)

}
