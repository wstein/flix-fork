///
/// Reading YAML.
///
/// Uses snakeyaml-engine's composer rather than its loader. The loader returns plain
/// Java values and throws away the position and tag of every node; the composer keeps
/// both, which is what makes an HsYAML-shaped tree possible.
///
pub mod Yaml.Parse {

    import java.lang.{String => JString}
    import org.snakeyaml.engine.v2.api.LoadSettings
    import org.snakeyaml.engine.v2.composer.Composer
    import org.snakeyaml.engine.v2.exceptions.YamlEngineException
    import org.snakeyaml.engine.v2.nodes.MappingNode
    import org.snakeyaml.engine.v2.nodes.Node
    import org.snakeyaml.engine.v2.nodes.ScalarNode
    import org.snakeyaml.engine.v2.nodes.SequenceNode
    import org.snakeyaml.engine.v2.parser.ParserImpl
    import org.snakeyaml.engine.v2.scanner.StreamReader

    use Yaml.Pos
    use Yaml.Scalar
    use Yaml.Tag
    use Yaml.Yaml
    use Yaml.YamlError.YamlError

    ///
    /// The largest document accepted, in code points.
    ///
    pub def maxCodePoints(): Int32 = 8 * 1024 * 1024

    ///
    /// The largest number of aliases expanded per document.
    ///
    /// With ten references per level, nine levels reach a billion nodes using only
    /// ninety aliases, so the budget has to bound the product rather than the count.
    /// This is snakeyaml-engine's own default. Documents that use anchors normally,
    /// such as docker-compose files, stay far below it.
    ///
    pub def maxAliases(): Int32 = 50

    ///
    /// Parses `s` as a single YAML document.
    ///
    /// Resolves the YAML 1.2 core schema. Under YAML 1.1, which most JVM YAML
    /// libraries still implement, `country: no` reads as boolean false rather than
    /// the string "no"; silently turning a country code into `false` is the kind of
    /// corruption a configuration parser must not do.
    ///
    /// A tag never causes a type to be constructed. A scalar carrying a tag outside
    /// the core schema is kept as `SUnknown` with its text, not interpreted.
    ///
    pub def parse(s: String): Result[YamlError, Yaml] \ IO =
        try {
            let noDuplicates = LoadSettings.builder().setAllowDuplicateKeys(false);
            let boundedAliases = noDuplicates.setMaxAliasesForCollections(maxAliases());
            let boundedSize = boundedAliases.setCodePointLimit(maxCodePoints());
            let settings = boundedSize.build();
            let composer = new Composer(settings, new ParserImpl(settings, new StreamReader(settings, s)));
            let root = composer.getSingleNode();
            if (root.isPresent())
                fromNode(root.get())
            else
                // An empty document is a null node, as the specification says.
                Ok(Yaml.YScalar(Yaml.noPos(), "tag:yaml.org,2002:null", Scalar.SNull))
        } catch {
            case e: YamlEngineException => Err(YamlError.Malformed(e.getMessage()))
        }

    ///
    /// Parses `s` and decodes it into a value of the caller's own type.
    ///
    pub def decode(s: String): Result[YamlError, a] \ IO with Yaml.FromYaml.FromYaml[a] =
        forM (node <- parse(s);
              value <- Yaml.FromYaml.fromYaml(node))
        yield value

    ///
    /// Converts a composed node into a `Yaml` value.
    ///
    /// Returns a `Result` because building a mapping can fail: the composer does not
    /// reject duplicate keys, so that check lives here.
    ///
    def fromNode(n: Node): Result[YamlError, Yaml] \ IO =
        let pos = posOf(n);
        let tag = n.getTag().getValue();
        if (n instanceof ScalarNode)
            Ok(Yaml.YScalar(pos, tag, scalarOf(tag, unchecked_cast(n as ScalarNode).getValue())))
        else if (n instanceof SequenceNode)
            sequenceOf(unchecked_cast(n as SequenceNode)) |> Result.map(xs -> Yaml.YSequence(pos, tag, xs))
        else if (n instanceof MappingNode)
            mappingOf(unchecked_cast(n as MappingNode)) |> Result.map(m -> Yaml.YMapping(pos, tag, m))
        else
            // No other node kind exists once aliases are resolved.
            Ok(Yaml.YScalar(pos, tag, Scalar.SUnknown(tag, n.toString())))

    ///
    /// Reads a node's start position.
    ///
    def posOf(n: Node): Pos \ IO =
        let mark = n.getStartMark();
        if (mark.isPresent())
            Pos.Pos(mark.get().getLine(), mark.get().getColumn())
        else
            Yaml.noPos()

    ///
    /// Interprets a scalar according to its resolved tag.
    ///
    /// The composer has already applied the core schema, so the tag says what the
    /// text means. Anything outside the core schema stays as text.
    ///
    def scalarOf(tag: Tag, text: String): Scalar =
        if (tag == "tag:yaml.org,2002:null")
            Scalar.SNull
        else if (tag == "tag:yaml.org,2002:bool")
            Scalar.SBool(String.toLowerCase(text) == "true")
        else if (tag == "tag:yaml.org,2002:int")
            match BigInt.fromString(text) {
                case Some(i) => Scalar.SInt(i)
                case None    => Scalar.SUnknown(tag, text)
            }
        else if (tag == "tag:yaml.org,2002:float")
            match Float64.fromString(text) {
                case Some(f) => Scalar.SFloat(f)
                case None    => Scalar.SUnknown(tag, text)
            }
        else if (tag == "tag:yaml.org,2002:str")
            Scalar.SStr(text)
        else
            Scalar.SUnknown(tag, text)

    ///
    /// Converts a sequence node, preserving order.
    ///
    def sequenceOf(n: SequenceNode): Result[YamlError, List[Yaml]] \ IO =
        Adaptor.fromList(n.getValue()) |> Result.traverse(fromNode)

    ///
    /// Converts a mapping node.
    ///
    /// Note the shape this forces: a `Map` sorts its keys, so the order the author
    /// wrote is not recoverable from the result. That is HsYAML's trade-off too, and
    /// it buys logarithmic lookup and node-typed keys.
    ///
    /// Duplicate keys are rejected rather than collapsed. `Map` would quietly keep the
    /// last one, which is the behaviour that makes a mis-edited configuration file look
    /// like a working one.
    ///
    def mappingOf(n: MappingNode): Result[YamlError, Map[Yaml, Yaml]] \ IO =
        forM (pairs <- Adaptor.fromList(n.getValue()) |> Result.traverse(t ->
                  forM (k <- fromNode(t.getKeyNode());
                        v <- fromNode(t.getValueNode()))
                  yield (k, v));
              checked <- rejectDuplicates(pairs))
        yield checked

    ///
    /// Builds a mapping, failing if a key appears twice.
    ///
    def rejectDuplicates(pairs: List[(Yaml, Yaml)]): Result[YamlError, Map[Yaml, Yaml]] =
        let m = List.toMap(pairs);
        if (Map.size(m) == List.length(pairs))
            Ok(m)
        else
            let dup = pairs
                |> List.map(fst)
                |> List.sort
                |> List.findMap(k -> if (List.count(x -> x == k, List.map(fst, pairs)) > 1) Some(k) else None);
            match dup {
                case Some(k) => Err(YamlError.Malformed("duplicate key '${k}'"))
                case None    => Err(YamlError.Malformed("duplicate key in mapping"))
            }

}
