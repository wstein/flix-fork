package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.Symbol

/**
  * The readable spelling of a lexically nested class's origin, kept beside its hashed provenance.
  *
  * A provenance key hashes every level of its path when it is captured, so it cannot be read back.
  * This keeps what a person would call the class instead: `owner` is the qualified name of the
  * source definition it was written in, and `path` the `let` binders, local definitions, methods,
  * and source-order ordinals between that definition and it -- `List("discount", "0")` for the
  * first lambda bound by `let discount`. `specialization` is the specialized owner once the
  * definition has been specialized, whose own name tells the copies apart.
  *
  * It never identifies anything: the key still does. `JvmNameTable` spells a class with it only
  * where the spelling is unique, and falls back to the key's hash elsewhere.
  */
final case class JvmReadableOrigin(owner: List[String], path: List[String], specialization: Option[Symbol])
