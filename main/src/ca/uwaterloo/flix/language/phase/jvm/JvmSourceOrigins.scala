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

  def foreachExpression(consume: (TypedAst.Expr, GeneratedJvmKey) => Unit): Unit =
    bodies.values.foreach(_.allEntries.foreach { case (exp, key) => consume(exp, key) })

  /** Returns immutable source bindings before their typed bodies are released. */
  def foreachBinding(consume: (Symbol.DefnSym, JvmLexicalOrigins.Binding) => Unit): Unit =
    bodies.foreach { case (sym, lexical) => lexical.bindings.foreach(binding => consume(sym, binding)) }
}

object JvmSourceOrigins {

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
        case (TypedAst.Expr.NewObject(sym, _, _, _, _, _, _), key) => provenance.register(sym, key)
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
