package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SourceLocation, Symbol, TypedAst}
import ca.uwaterloo.flix.util.InternalCompilerException

final class JvmSourceOrigins private (val provenance: JvmProvenance,
                                      private var bodies: Map[Symbol.DefnSym, JvmLexicalOrigins]) {

  def body(sym: Symbol.DefnSym): JvmLexicalOrigins = bodies.getOrElse(sym,
    throw InternalCompilerException(s"Missing source body provenance for '$sym'.", sym.loc))

  def releaseBodies(): Unit = {
    bodies = Map.empty
  }

  /** Passes each expression's origin and optional readable class path. */
  def foreachExpression(consume: (TypedAst.Expr, GeneratedJvmKey, Option[JvmReadableOrigin]) => Unit): Unit =
    bodies.foreach { case (owner, lexical) =>
      lexical.allEntries.foreach { case (exp, key) => consume(exp, key, JvmSourceOrigins.readable(owner, lexical, exp)) }
    }

  /** Returns immutable source bindings before their typed bodies are released. */
  def foreachBinding(consume: (Symbol.DefnSym, JvmLexicalOrigins.Binding) => Unit): Unit =
    bodies.foreach { case (sym, lexical) => lexical.bindings.foreach(binding => consume(sym, binding)) }
}

object JvmSourceOrigins {

  /** Returns how `exp`, captured in the body of `owner`, reads, if it is a class of its own. */
  private def readable(owner: Symbol.DefnSym, lexical: JvmLexicalOrigins, exp: TypedAst.Expr): Option[JvmReadableOrigin] =
    lexical.readablePath(exp).map(JvmReadableOrigin(owner.namespace :+ owner.text, _, None))

  def capture(root: TypedAst.Root, captureDebugBindings: Boolean = true): JvmSourceOrigins = {
    val provenance = JvmDeclarationOrigins.capture(root)
    val definitions = root.defs.values.map(defn => (defn, defn.spec.declaredScheme.quantifiers))
    val members = root.instances.values.flatMap { instance =>
      instance.defs.map(defn => (defn, (instance.tparams.map(_.sym) ::: defn.spec.declaredScheme.quantifiers).distinct))
    }
    val defaults = root.sigs.values.flatMap { sig =>
      sig.exp.map { exp =>
        val sym = new Symbol.DefnSym(None, sig.sym.trt.namespace :+ sig.sym.trt.name, sig.sym.name, sig.loc)
        val parameters = (root.traits(sig.sym.trt).tparam.sym :: sig.spec.declaredScheme.quantifiers).distinct
        (TypedAst.Def(sym, sig.spec, exp, sig.loc), parameters)
      }
    }
    val bodies = (definitions ++ members ++ defaults).map { case (defn, parameters) =>
      val lexical = JvmLexicalOrigins.capture(defn.exp, provenance.origin(defn.sym), defn.spec.fparams.toList,
        (tpe, localOrigins) => JvmTypeKey.encodeLexical(tpe, parameters,
          sym => localOrigins.getOrElse(sym, provenance.origin(sym))), provenance.origin,
        captureDebugBindings)
      lexical.entries.foreach {
        case (exp@TypedAst.Expr.NewObject(sym, _, _, _, _, _, _), key) =>
          provenance.register(sym, key)
          readable(defn.sym, lexical, exp).foreach(provenance.registerReadable(sym, _))
        case _ => ()
      }
      defn.sym -> lexical
    }.toList
    if (bodies.map(_._1).distinct.size != bodies.size) {
      throw InternalCompilerException("Duplicate declaration in JVM source provenance.", SourceLocation.Unknown)
    }
    new JvmSourceOrigins(provenance, bodies.toMap)
  }
}
