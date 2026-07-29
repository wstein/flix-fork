mod TestYaml {

    use Assert.{assertEq, assertTrue, assertTrueWithMsg}
    use Yaml.Yaml
    use Yaml.YamlError.YamlError
    use Yaml.FromYaml.FromYaml

    /// A caller's own type, decoded straight from YAML.
    pub enum Server({host = String, port = Int32, tags = List[String]})

    instance FromYaml[Server] {
        pub def fromYaml(y: Yaml): Result[YamlError, Server] =
            forM (host <- Yaml.FromYaml.decodeAtKey("host", y);
                  port <- Yaml.FromYaml.decodeAtKey("port", y);
                  tags <- Yaml.FromYaml.decodeAtKey("tags", y))
            yield Server.Server({host = host, port = port, tags = tags})
    }

    ///
    /// The point of the package: YAML in, your own type out, no Java in sight.
    ///
    @Test
    def decodesIntoOwnType(): Unit \ Assert + IO =
        let doc = "host: example.com\nport: 8080\ntags:\n  - web\n  - prod\n";
        let decoded: Result[YamlError, Server] = Yaml.Parse.decode(doc);
        match decoded {
            case Ok(Server.Server(s)) =>
                assertEq(expected = "example.com", s#host);
                assertEq(expected = 8080, s#port);
                assertEq(expected = "web" :: "prod" :: Nil, s#tags)
            case Err(e) => assertTrueWithMsg(false, "decode failed: ${e}")
        }

    ///
    /// Mappings are a `Map` keyed by node, as in HsYAML, so lookup is by content and
    /// the author's key order is not recoverable. This pins that trade-off.
    ///
    @Test
    def mappingIsKeyedByNode(): Unit \ Assert + IO =
        match Yaml.Parse.parse("zebra: 1\nalpha: 2\nmiddle: 3\n") {
            case Ok(Yaml.YMapping(_, _, m)) =>
                let keys = Map.keysOf(m) |> Set.toList |> List.map(k -> "${k}");
                assertEq(expected = "alpha" :: "middle" :: "zebra" :: Nil, keys)
            case other => assertTrueWithMsg(false, "unexpected: ${other}")
        }

    ///
    /// Every node knows where it came from, which is what makes a useful error
    /// message possible.
    ///
    @Test
    def nodesCarryTheirPosition(): Unit \ Assert + IO =
        match Yaml.Parse.parse("first: 1\nsecond: 2\n") {
            case Ok(y) =>
                let found: Result[YamlError, Yaml] = Yaml.FromYaml.decodeAtKey("second", y);
                match found {
                case Ok(node) =>
                    let Yaml.Pos.Pos(line, _) = Yaml.posOf(node);
                    assertEq(expected = 1, line)
                case Err(e) => assertTrueWithMsg(false, "lookup failed: ${e}")
                }
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    ///
    /// Resolved tags are kept rather than discarded once a scalar is typed.
    ///
    @Test
    def nodesCarryTheirTag(): Unit \ Assert + IO =
        match Yaml.Parse.parse("n: 7\n") {
            case Ok(y) =>
                assertEq(expected = "tag:yaml.org,2002:map", Yaml.tagOf(y));
                let found: Result[YamlError, Yaml] = Yaml.FromYaml.decodeAtKey("n", y);
                match found {
                    case Ok(node) => assertEq(expected = "tag:yaml.org,2002:int", Yaml.tagOf(node))
                    case Err(e)   => assertTrueWithMsg(false, "lookup failed: ${e}")
                }
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    ///
    /// A tag outside the core schema is kept as text, never used to build a value.
    ///
    @Test
    def unknownTagIsNotConstructed(): Unit \ Assert + IO =
        match Yaml.Parse.parse("x: !Custom hello\n") {
            case Ok(y) =>
                let found: Result[YamlError, Yaml] = Yaml.FromYaml.decodeAtKey("x", y);
                match found {
                case Ok(Yaml.YScalar(_, tag, Yaml.Scalar.SUnknown(_, text))) =>
                    assertEq(expected = "!Custom", tag);
                    assertEq(expected = "hello", text)
                case other => assertTrueWithMsg(false, "expected an unconstructed scalar, got ${other}")
                }
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    @Test
    def coreSchemaTypesScalars(): Unit \ Assert + IO =
        match Yaml.Parse.parse("i: 42\nf: 1.5\nb: true\ns: hello\nn: null\n") {
            case Ok(y) =>
                let i: Result[YamlError, Int32] = Yaml.FromYaml.decodeAtKey("i", y);
                let f: Result[YamlError, Float64] = Yaml.FromYaml.decodeAtKey("f", y);
                let b: Result[YamlError, Bool] = Yaml.FromYaml.decodeAtKey("b", y);
                let s: Result[YamlError, String] = Yaml.FromYaml.decodeAtKey("s", y);
                let n: Result[YamlError, Option[String]] = Yaml.FromYaml.decodeAtKeyOpt("n", y);
                assertEq(expected = Ok(42), i);
                assertEq(expected = Ok(1.5f64), f);
                assertEq(expected = Ok(true), b);
                assertEq(expected = Ok("hello"), s);
                assertEq(expected = Ok(None), n)
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    ///
    /// The Norway problem. Under YAML 1.1 `no` is boolean false; under 1.2's core
    /// schema it is the string "no". Getting this wrong silently corrupts data, so
    /// it is pinned.
    ///
    @Test
    def norwayIsAString(): Unit \ Assert + IO =
        match Yaml.Parse.parse("country: no\n") {
            case Ok(y) =>
                let c: Result[YamlError, String] = Yaml.FromYaml.decodeAtKey("country", y);
                assertEq(expected = Ok("no"), c)
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    @Test
    def missingKeyIsReported(): Unit \ Assert + IO =
        match Yaml.Parse.parse("a: 1\n") {
            case Ok(y) =>
                let got: Result[YamlError, Int32] = Yaml.FromYaml.decodeAtKey("b", y);
                assertEq(expected = Err(YamlError.MissingKey("b")), got)
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    @Test
    def wrongShapeIsReported(): Unit \ Assert + IO =
        match Yaml.Parse.parse("a: hello\n") {
            case Ok(y) =>
                let got: Result[YamlError, Int32] = Yaml.FromYaml.decodeAtKey("a", y);
                assertEq(expected = Err(YamlError.Mismatch("integer", "string")), got)
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

    ///
    /// A malformed document is a value, not a thrown Java exception.
    ///
    @Test
    def malformedIsAnErrorNotAnException(): Unit \ Assert + IO =
        match Yaml.Parse.parse("a: [1, 2\n") {
            case Err(YamlError.Malformed(_)) => assertTrue(true)
            case other => assertTrueWithMsg(false, "expected Malformed, got ${other}")
        }

    ///
    /// Duplicate keys are rejected rather than silently resolved, which is the gap
    /// left open in `Util.Json`.
    ///
    @Test
    def duplicateKeysRejected(): Unit \ Assert + IO =
        match Yaml.Parse.parse("a: 1\na: 2\n") {
            case Err(YamlError.Malformed(_)) => assertTrue(true)
            case other => assertTrueWithMsg(false, "expected duplicate key to be rejected, got ${other}")
        }

    ///
    /// The billion laughs attack: a few nested aliases expanding geometrically.
    /// The alias budget must stop it.
    ///
    @Test
    def billionLaughsIsBounded(): Unit \ Assert + IO =
        // Nine levels of ten references each: ~10^9 nodes from a few hundred bytes.
        let bomb = String.unlines(
            "a: &a [x, x, x, x, x, x, x, x, x, x]" ::
            "b: &b [*a, *a, *a, *a, *a, *a, *a, *a, *a, *a]" ::
            "c: &c [*b, *b, *b, *b, *b, *b, *b, *b, *b, *b]" ::
            "d: &d [*c, *c, *c, *c, *c, *c, *c, *c, *c, *c]" ::
            "e: &e [*d, *d, *d, *d, *d, *d, *d, *d, *d, *d]" ::
            "f: &f [*e, *e, *e, *e, *e, *e, *e, *e, *e, *e]" ::
            "g: &g [*f, *f, *f, *f, *f, *f, *f, *f, *f, *f]" ::
            "h: &h [*g, *g, *g, *g, *g, *g, *g, *g, *g, *g]" ::
            "i: [*h, *h, *h, *h, *h, *h, *h, *h, *h, *h]" :: Nil);
        match Yaml.Parse.parse(bomb) {
            case Err(YamlError.Malformed(_)) => assertTrue(true)
            case other => assertTrueWithMsg(false, "alias expansion was not bounded: ${other}")
        }

    ///
    /// The alias budget must not be so tight that ordinary anchor use breaks. This
    /// is the shape a docker-compose file uses.
    ///
    @Test
    def ordinaryAnchorsStillParse(): Unit \ Assert + IO =
        let doc = String.unlines(
            "defaults: &defaults" ::
            "  restart: always" ::
            "  network: bridge" ::
            "web:" ::
            "  <<: *defaults" ::
            "  image: nginx" ::
            "api:" ::
            "  <<: *defaults" ::
            "  image: app" :: Nil);
        match Yaml.Parse.parse(doc) {
            case Ok(_)  => assertTrue(true)
            case Err(e) => assertTrueWithMsg(false, "ordinary anchors were rejected: ${e}")
        }

    ///
    /// The bridge to JSON is explicit and lossy, never implicit.
    ///
    @Test
    def toJsonIsAvailable(): Unit \ Assert + IO =
        match Yaml.Parse.parse("b: 2\na: 1\n") {
            case Ok(y) => assertEq(expected = "{\"a\":1,\"b\":2}", Util.Json.toCompactString(Yaml.toJson(y)))
            case Err(e) => assertTrueWithMsg(false, "parse failed: ${e}")
        }

}
