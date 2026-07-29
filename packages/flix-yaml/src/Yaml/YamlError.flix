///
/// What can go wrong reading YAML.
///
pub mod Yaml.YamlError {

    ///
    /// A failure while reading or decoding a document.
    ///
    /// Parsing and decoding are separate cases on purpose, as in `Util.Json`: a
    /// malformed document and a well-formed one that does not fit the caller's type
    /// are different problems with different fixes.
    ///
    pub enum YamlError with Eq {
        ///
        /// The document is not valid YAML, or exceeded a safety limit.
        ///
        case Malformed(String)

        ///
        /// The document is valid YAML but not the shape the caller asked for.
        ///
        case Mismatch(String, String)

        ///
        /// A mapping did not contain the requested key.
        ///
        case MissingKey(String)
    }

    instance ToString[YamlError] {
        pub def toString(e: YamlError): String = match e {
            case YamlError.Malformed(msg) => "malformed YAML: ${msg}"
            case YamlError.Mismatch(want, got) => "expected ${want}, found ${got}"
            case YamlError.MissingKey(k)  => "missing key '${k}'"
        }
    }

}
