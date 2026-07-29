///
/// Reading YAML.
///
/// SnakeYAML does the parsing; this module converts its result into a `Yaml` value
/// and does not let anything Java-shaped escape. Callers see `Result`, an ADT, and
/// a trait — the same shape as `Util.Json` — not `Object`, casts or exceptions.
///
pub mod Yaml.Parse {

    import java.lang.{Boolean => JBoolean}
    import java.lang.{Double => JDouble}
    import java.lang.{Integer => JInteger}
    import java.lang.{Long => JLong}
    import java.lang.Object
    import java.lang.{String => JString}
    import java.math.{BigInteger => JBigInteger}
    import java.util.{List => JList}
    import java.util.{Map => JMap}
    import java.util.ArrayList
    import java.util.Objects
    import org.snakeyaml.engine.v2.api.Load
    import org.snakeyaml.engine.v2.api.LoadSettings
    import org.snakeyaml.engine.v2.exceptions.YamlEngineException

    use Yaml.Yaml
    use Yaml.YamlError.YamlError

    ///
    /// The largest document accepted, in code points.
    ///
    /// YAML aliases can expand a small document into an enormous one, so a size the
    /// parser will not exceed is the defence. `Util.Json` caps nesting depth for the
    /// same reason; YAML needs the cap on the expanded result as well.
    ///
    pub def maxCodePoints(): Int32 = 8 * 1024 * 1024

    ///
    /// The largest number of aliases expanded per document.
    ///
    /// The "billion laughs" attack is a handful of nested aliases that expand
    /// geometrically: with ten references per level, nine levels reach a billion
    /// nodes while using only ninety aliases. A budget has to be low enough that the
    /// *product* stays bounded, not merely the count, which is why this is 50 -- the
    /// value SnakeYAML itself defaults to -- rather than a round hundred.
    ///
    /// Real documents that use anchors, such as docker-compose files, stay far below
    /// this.
    ///
    pub def maxAliases(): Int32 = 50

    ///
    ///
    /// Parses `s` as a single YAML document.
    ///
    /// Resolves the YAML 1.2 core schema. That choice is load-bearing: under YAML
    /// 1.1, which most JVM YAML libraries still implement, `country: no` reads as
    /// boolean false rather than the string "no". Silently turning a country code
    /// into `false` is the kind of corruption a config parser must not do.
    ///
    /// No custom tags are honoured, so a tag never causes a type to be constructed.
    /// That is the difference between `load` and `safe_load` in other YAML libraries
    /// and the source of a long line of remote code execution bugs. There is no
    /// unsafe variant here to reach for.
    ///
    pub def parse(s: String): Result[YamlError, Yaml] \ IO =
        try {
            let noDuplicates = LoadSettings.builder().setAllowDuplicateKeys(false);
            let boundedAliases = noDuplicates.setMaxAliasesForCollections(maxAliases());
            let boundedSize = boundedAliases.setCodePointLimit(maxCodePoints());
            let settings = boundedSize.build();
            let loaded: Object = new Load(settings).loadFromString(s);
            Ok(fromJava(loaded))
        } catch {
            case e: YamlEngineException => Err(YamlError.Malformed(e.getMessage()))
        }

    ///
    /// Parses `s` and decodes it into a value of the caller's own type.
    ///
    pub def decode(s: String): Result[YamlError, a] \ IO with Yaml.FromYaml[a] =
        forM (node <- parse(s);
              value <- Yaml.FromYaml.fromYaml(node))
        yield value

    ///
    /// Converts what SnakeYAML produced into a `Yaml` value.
    ///
    /// SnakeYAML's safe constructor resolves the core schema for us, so a scalar
    /// arrives already typed as `Integer`, `Double`, `Boolean` or `String`. That
    /// resolution is the part worth reusing; the Java shape is not.
    ///
    def fromJava(o: Object): Yaml \ IO =
        if (Objects.isNull(o))
            Yaml.YNull
        else if (o instanceof JString)
            Yaml.YString(unchecked_cast(o as String))
        else if (o instanceof JBoolean)
            Yaml.YBool(unchecked_cast(o as JBoolean).booleanValue())
        else if (o instanceof JInteger)
            Yaml.YInt(Int32.toBigInt(unchecked_cast(o as JInteger).intValue()))
        else if (o instanceof JLong)
            Yaml.YInt(Int64.toBigInt(unchecked_cast(o as JLong).longValue()))
        else if (o instanceof JBigInteger)
            Yaml.YInt(unchecked_cast(o as BigInt))
        else if (o instanceof JDouble)
            Yaml.YFloat(unchecked_cast(o as JDouble).doubleValue())
        else if (o instanceof JList)
            Yaml.YSequence(listFromJava(unchecked_cast(o as JList[Object])))
        else if (o instanceof JMap)
            Yaml.YMapping(mapFromJava(unchecked_cast(o as JMap[Object, Object])))
        else
            // Unreachable with the safe constructor, which only ever builds the types
            // above. Rendering rather than failing keeps an upstream change from
            // turning into a crash.
            Yaml.YString(o.toString())

    ///
    /// Converts a Java list, preserving order.
    ///
    def listFromJava(xs: JList[Object]): List[Yaml] \ IO =
        Adaptor.fromList(xs) |> List.map(fromJava)

    ///
    /// Converts a Java map, preserving insertion order.
    ///
    /// SnakeYAML returns a `LinkedHashMap`, so iterating it gives the order the
    /// author wrote. A `Map` would sort the keys and lose that.
    ///
    def mapFromJava(m: JMap[Object, Object]): List[(Yaml, Yaml)] \ IO =
        let keys: JList[Object] = unchecked_cast(new ArrayList(m.keySet()) as JList[Object]);
        Adaptor.fromList(keys) |> List.map(k -> (fromJava(k), fromJava(m.get(k))))

}
