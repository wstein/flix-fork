///
/// Decoding YAML into a caller's own types.
///
/// Mirrors `Util.Json.FromJson` so that anyone who has decoded JSON in Flix already
/// knows this API.
///
pub mod Yaml.FromYaml {

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
    /// Decodes the value at `key`, or `None` if the key is absent.
    ///
    /// A missing key and a key explicitly set to null are both `None`, which is what
    /// configuration files usually mean by an omitted setting.
    ///
    pub def decodeAtKeyOpt(key: String, y: Yaml): Result[YamlError, Option[a]] with FromYaml[a] =
        match lookup(key, y) {
            case Err(YamlError.MissingKey(_)) => Ok(None)
            case Err(e)                       => Err(e)
            case Ok(Yaml.YNull)               => Ok(None)
            case Ok(node)                     => FromYaml.fromYaml(node) |> Result.map(Some)
        }

    ///
    /// Returns the node at `key` of a mapping.
    ///
    def lookup(key: String, y: Yaml): Result[YamlError, Yaml] = match y {
        case Yaml.YMapping(kvs) =>
            match List.find(match (k, _) -> k == Yaml.YString(key), kvs) {
                case Some((_, v)) => Ok(v)
                case None         => Err(YamlError.MissingKey(key))
            }
        case other => Err(YamlError.Mismatch("mapping", describe(other)))
    }

    ///
    /// Names the shape of a node, for error messages.
    ///
    pub def describe(y: Yaml): String = match y {
        case Yaml.YMapping(_)  => "mapping"
        case Yaml.YSequence(_) => "sequence"
        case Yaml.YString(_)   => "string"
        case Yaml.YInt(_)      => "integer"
        case Yaml.YFloat(_)    => "float"
        case Yaml.YBool(_)     => "boolean"
        case Yaml.YNull        => "null"
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
            case Yaml.YString(s) => Ok(s)
            case other           => mismatch("string", other)
        }
    }

    instance FromYaml[Bool] {
        pub def fromYaml(y: Yaml): Result[YamlError, Bool] = match y {
            case Yaml.YBool(b) => Ok(b)
            case other         => mismatch("boolean", other)
        }
    }

    instance FromYaml[BigInt] {
        pub def fromYaml(y: Yaml): Result[YamlError, BigInt] = match y {
            case Yaml.YInt(i) => Ok(i)
            case other        => mismatch("integer", other)
        }
    }

    instance FromYaml[Int32] {
        pub def fromYaml(y: Yaml): Result[YamlError, Int32] = match y {
            case Yaml.YInt(i) => match BigInt.tryToInt32(i) {
                case Some(n) => Ok(n)
                case None    => Err(YamlError.Mismatch("integer within Int32", "${i}"))
            }
            case other => mismatch("integer", other)
        }
    }

    instance FromYaml[Int64] {
        pub def fromYaml(y: Yaml): Result[YamlError, Int64] = match y {
            case Yaml.YInt(i) => match BigInt.tryToInt64(i) {
                case Some(n) => Ok(n)
                case None    => Err(YamlError.Mismatch("integer within Int64", "${i}"))
            }
            case other => mismatch("integer", other)
        }
    }

    ///
    /// An integer is accepted where a float is wanted, as YAML's core schema does.
    ///
    instance FromYaml[Float64] {
        pub def fromYaml(y: Yaml): Result[YamlError, Float64] = match y {
            case Yaml.YFloat(f) => Ok(f)
            case Yaml.YInt(i)   => match BigInt.tryToFloat64(i) {
                case Some(f) => Ok(f)
                case None    => Err(YamlError.Mismatch("float within Float64", "${i}"))
            }
            case other          => mismatch("float", other)
        }
    }

    instance FromYaml[List[a]] with FromYaml[a] {
        pub def fromYaml(y: Yaml): Result[YamlError, List[a]] = match y {
            case Yaml.YSequence(xs) => Result.traverse(FromYaml.fromYaml, xs)
            case other              => mismatch("sequence", other)
        }
    }

    ///
    /// A null decodes to `None`; anything else decodes as the inner type.
    ///
    instance FromYaml[Option[a]] with FromYaml[a] {
        pub def fromYaml(y: Yaml): Result[YamlError, Option[a]] = match y {
            case Yaml.YNull => Ok(None)
            case other      => FromYaml.fromYaml(other) |> Result.map(Some)
        }
    }

    ///
    /// A mapping with string keys decodes to a `Map`. Key order is lost here, as it
    /// must be, since `Map` is sorted.
    ///
    instance FromYaml[Map[k, v]] with Order[k], FromYaml[k], FromYaml[v] {
        pub def fromYaml(y: Yaml): Result[YamlError, Map[k, v]] = match y {
            case Yaml.YMapping(kvs) =>
                Result.traverse(match (k, v) ->
                    forM (key <- FromYaml.fromYaml(k);
                          value <- FromYaml.fromYaml(v))
                    yield (key, value), kvs)
                |> Result.map(List.toMap)
            case other => mismatch("mapping", other)
        }
    }

}
