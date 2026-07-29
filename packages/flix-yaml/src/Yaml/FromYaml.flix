///
/// Decoding YAML into a caller's own types.
///
/// Mirrors `Util.Json.FromJson`, so anyone who has decoded JSON in Flix already
/// knows this API.
///
pub mod Yaml.FromYaml {

    use Yaml.Scalar
    use Yaml.Yaml
    use Yaml.YamlError.YamlError

    ///
    /// A type that can be read from a YAML node.
    ///
    pub trait FromYaml[a] {
        pub def fromYaml(y: Yaml): Result[YamlError, a]
    }

    ///
    /// Decodes the value at `key` of a mapping.
    ///
    pub def decodeAtKey(key: String, y: Yaml): Result[YamlError, a] with FromYaml[a] =
        forM (node <- lookup(key, y);
              value <- FromYaml.fromYaml(node))
        yield value

    ///
    /// Decodes the value at `key`, or `None` if the key is absent or null.
    ///
    /// A missing key and a key explicitly set to null are both `None`, which is what
    /// a configuration file usually means by an omitted setting.
    ///
    pub def decodeAtKeyOpt(key: String, y: Yaml): Result[YamlError, Option[a]] with FromYaml[a] =
        match lookup(key, y) {
            case Err(YamlError.MissingKey(_))         => Ok(None)
            case Err(e)                               => Err(e)
            case Ok(Yaml.YScalar(_, _, Scalar.SNull)) => Ok(None)
            case Ok(node)                             => FromYaml.fromYaml(node) |> Result.map(Some)
        }

    ///
    /// Returns the node at `key` of a mapping.
    ///
    /// The lookup is logarithmic, because mappings are a `Map` keyed by node. The key
    /// is built by `Yaml.key`, whose position the ordering discards.
    ///
    def lookup(key: String, y: Yaml): Result[YamlError, Yaml] = match y {
        case Yaml.YMapping(_, _, m) => match Map.get(Yaml.key(key), m) {
            case Some(v) => Ok(v)
            case None    => Err(YamlError.MissingKey(key))
        }
        case other => Err(YamlError.Mismatch("mapping", describe(other)))
    }

    ///
    /// Names the shape of a node, for error messages.
    ///
    pub def describe(y: Yaml): String = match y {
        case Yaml.YMapping(_, _, _)  => "mapping"
        case Yaml.YSequence(_, _, _) => "sequence"
        case Yaml.YScalar(_, _, s)   => match s {
            case Scalar.SNull          => "null"
            case Scalar.SBool(_)       => "boolean"
            case Scalar.SInt(_)        => "integer"
            case Scalar.SFloat(_)      => "float"
            case Scalar.SStr(_)        => "string"
            case Scalar.SUnknown(t, _) => "scalar tagged ${t}"
        }
    }

    ///
    /// A mismatch between the shape wanted and the shape found.
    ///
    def mismatch(want: String, got: Yaml): Result[YamlError, a] =
        Err(YamlError.Mismatch(want, describe(got)))

    instance FromYaml[Yaml] {
        pub def fromYaml(y: Yaml): Result[YamlError, Yaml] = Ok(y)
    }

    instance FromYaml[String] {
        pub def fromYaml(y: Yaml): Result[YamlError, String] = match y {
            case Yaml.YScalar(_, _, Scalar.SStr(s)) => Ok(s)
            case other => mismatch("string", other)
        }
    }

    instance FromYaml[Bool] {
        pub def fromYaml(y: Yaml): Result[YamlError, Bool] = match y {
            case Yaml.YScalar(_, _, Scalar.SBool(b)) => Ok(b)
            case other => mismatch("boolean", other)
        }
    }

    instance FromYaml[BigInt] {
        pub def fromYaml(y: Yaml): Result[YamlError, BigInt] = match y {
            case Yaml.YScalar(_, _, Scalar.SInt(i)) => Ok(i)
            case other => mismatch("integer", other)
        }
    }

    instance FromYaml[Int32] {
        pub def fromYaml(y: Yaml): Result[YamlError, Int32] = match y {
            case Yaml.YScalar(_, _, Scalar.SInt(i)) => match BigInt.tryToInt32(i) {
                case Some(n) => Ok(n)
                case None    => Err(YamlError.Mismatch("integer within Int32", "${i}"))
            }
            case other => mismatch("integer", other)
        }
    }

    instance FromYaml[Int64] {
        pub def fromYaml(y: Yaml): Result[YamlError, Int64] = match y {
            case Yaml.YScalar(_, _, Scalar.SInt(i)) => match BigInt.tryToInt64(i) {
                case Some(n) => Ok(n)
                case None    => Err(YamlError.Mismatch("integer within Int64", "${i}"))
            }
            case other => mismatch("integer", other)
        }
    }

    ///
    /// An integer is accepted where a float is wanted, as the core schema allows.
    ///
    instance FromYaml[Float64] {
        pub def fromYaml(y: Yaml): Result[YamlError, Float64] = match y {
            case Yaml.YScalar(_, _, Scalar.SFloat(f)) => Ok(f)
            case Yaml.YScalar(_, _, Scalar.SInt(i))   => match BigInt.tryToFloat64(i) {
                case Some(f) => Ok(f)
                case None    => Err(YamlError.Mismatch("float within Float64", "${i}"))
            }
            case other => mismatch("float", other)
        }
    }

    instance FromYaml[List[a]] with FromYaml[a] {
        pub def fromYaml(y: Yaml): Result[YamlError, List[a]] = match y {
            case Yaml.YSequence(_, _, xs) => Result.traverse(FromYaml.fromYaml, xs)
            case other => mismatch("sequence", other)
        }
    }

    ///
    /// A null decodes to `None`; anything else decodes as the inner type.
    ///
    instance FromYaml[Option[a]] with FromYaml[a] {
        pub def fromYaml(y: Yaml): Result[YamlError, Option[a]] = match y {
            case Yaml.YScalar(_, _, Scalar.SNull) => Ok(None)
            case other => FromYaml.fromYaml(other) |> Result.map(Some)
        }
    }

    instance FromYaml[Map[k, v]] with Order[k], FromYaml[k], FromYaml[v] {
        pub def fromYaml(y: Yaml): Result[YamlError, Map[k, v]] = match y {
            case Yaml.YMapping(_, _, m) =>
                Result.traverse(match (k, v) ->
                    forM (key <- FromYaml.fromYaml(k);
                          value <- FromYaml.fromYaml(v))
                    yield (key, value), Map.toList(m))
                |> Result.map(List.toMap)
            case other => mismatch("mapping", other)
        }
    }

}
