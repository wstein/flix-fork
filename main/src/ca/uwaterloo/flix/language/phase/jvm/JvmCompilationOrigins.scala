package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{MonoAst, SimpleType, SimplifiedAst, SourceLocation, Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.ast.shared.Source
import ca.uwaterloo.flix.util.InternalCompilerException

import java.util.IdentityHashMap
import scala.collection.mutable

final class JvmCompilationOrigins(val symbols: JvmProvenance) {
  private var expressions = new IdentityHashMap[AnyRef, GeneratedJvmKey]()
  private var closed = false

  def expression(exp: AnyRef): GeneratedJvmKey = synchronized {
    Option(expressions.get(exp)).getOrElse(fail(s"Missing expression provenance for ${exp.getClass.getSimpleName}."))
  }

  def record(exp: AnyRef, key: GeneratedJvmKey): Unit = synchronized {
    requireOpen()
    val previous = expressions.get(exp)
    if (previous != null && previous != key) fail("Conflicting expression provenance.")
    expressions.put(exp, key)
  }

  def transfer[A <: AnyRef](from: AnyRef, to: A, phase: String): A = synchronized {
    attribute(to, expression(from), phase)
    to
  }

  def synthetic[A <: AnyRef](from: AnyRef, to: A, role: String): A = synchronized {
    attribute(to, JvmOriginKey.compose("synthetic-expression", List(expression(from)), List(role)), role)
    to
  }

  def specialize[A <: AnyRef](from: AnyRef, to: A, owner: Symbol.DefnSym, phase: String): A =
    cloneTree(from, to, symbols.origin(owner), phase)

  def cloneTree[A <: AnyRef](from: AnyRef, to: A, context: GeneratedJvmKey, phase: String): A = synchronized {
    attribute(to, JvmOriginKey.compose("cloned-expression", List(context, expression(from))), phase)
    to
  }

  def specializedSymbol(fresh: Symbol, original: Symbol, args: List[Type]): Unit = {
    val arguments = args.map(JvmTypeKey.encode(_, Nil, symbols.origin))
    symbols.register(fresh, JvmOriginKey.compose("specialization", List(symbols.origin(original)), arguments))
  }

  def erasedSymbol(fresh: Symbol, original: Symbol, args: List[SimpleType]): Unit = {
    val arguments = args.map(JvmTypeKey.encodeSimple(_, symbols.origin))
    symbols.register(fresh, JvmOriginKey.compose("erasure", List(symbols.origin(original)), arguments))
  }

  def derivedSymbol(fresh: Symbol, source: AnyRef, role: String): Unit =
    symbols.register(fresh, JvmOriginKey.compose("generated-symbol", List(expression(source)), List(role)))

  def retainExpressions(live: Iterable[AnyRef]): Unit = synchronized {
    requireOpen()
    val retained = new IdentityHashMap[AnyRef, GeneratedJvmKey]()
    live.foreach(exp => retained.put(exp, expression(exp)))
    expressions = retained
  }

  def retainMono(root: MonoAst.Root): Unit = retainRoot(root, keepExpressions = true)

  def retainSource(root: TypedAst.Root): Unit = {
    val defaults = root.sigs.values.filter(_.exp.nonEmpty).map { sig =>
      new Symbol.DefnSym(None, sig.sym.namespace, sig.sym.name, sig.loc): Symbol
    }.toSet
    // Review this live-declaration subset whenever TypedAst.Root gains fields that
    // carry declarations or bodies needed by later phases. Do not traverse the
    // whole root: module membership and other metadata can retain discarded symbols.
    val live = (root.defs, root.instances, root.sigs, root.enums, root.structs,
      root.restrictableEnums, root.effects, root.typeAliases, root.traits.keySet,
      root.defaultHandlers)
    retainRoot(live, keepExpressions = true, defaults)
  }

  def retainRoot(root: AnyRef, keepExpressions: Boolean): Unit = retainRoot(root, keepExpressions, Set.empty)

  private def retainRoot(root: AnyRef, keepExpressions: Boolean, additionalSymbols: Set[Symbol]): Unit = synchronized {
    requireOpen()
    val liveSymbols = mutable.Set.empty[Symbol]
    val liveExpressions = mutable.ListBuffer.empty[AnyRef]
    val seen = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    def visit(value: Any): Unit = value match {
      case sym: Symbol => liveSymbols += sym
      case _: Source | _: SourceLocation => ()
      case ref: AnyRef if seen.put(ref, true) != null => ()
      case values: Iterable[_] => values.foreach(visit)
      case product: Product =>
        if (keepExpressions && isExpression(product)) liveExpressions += product.asInstanceOf[AnyRef]
        product.productIterator.foreach(visit)
      case _ => ()
    }
    visit(root)
    retainExpressions(liveExpressions)
    symbols.retainLive(liveSymbols.toSet ++ additionalSymbols)
  }

  def close(): Unit = synchronized {
    expressions = new IdentityHashMap[AnyRef, GeneratedJvmKey]()
    symbols.close()
    closed = true
  }

  private def attribute(root: AnyRef, key: GeneratedJvmKey, phase: String): Unit = {
    requireOpen()
    if (!expressions.containsKey(root)) {
      record(root, key)
      def visit(value: Any, path: List[String]): Unit = value match {
        case _: Type | _: SimpleType | _: Symbol | _: Source | _: SourceLocation => ()
        case ref: AnyRef if isExpression(ref) =>
          attribute(ref, JvmOriginKey.compose("expansion", List(key), phase :: path.reverse), phase)
        case values: collection.Map[_, _] =>
          values.valuesIterator.foreach {
            case ref: AnyRef if isExpression(ref) && !expressions.containsKey(ref) =>
              fail("A generated map branch requires an explicit semantic origin.")
            case value => visit(value, "mapped" :: path)
          }
        case values: Iterable[_] => values.iterator.zipWithIndex.foreach { case (value, index) => visit(value, index.toString :: path) }
        case product: Product => product.productIterator.zip(product.productElementNames).foreach {
          case (value, name) => visit(value, name :: product.productPrefix :: path)
        }
        case _ => ()
      }
      root match {
        case product: Product => product.productIterator.zip(product.productElementNames).foreach {
          case (value, name) => visit(value, List(name, product.productPrefix))
        }
        case _ => ()
      }
    }
  }

  private def isExpression(value: Any): Boolean = value match {
    case _: TypedAst.Expr | _: MonoAst.Expr | _: SimplifiedAst.Expr => true
    case _ => false
  }

  private def requireOpen(): Unit = if (closed) fail("Compilation provenance is closed.")

  private def fail(message: String): Nothing = throw InternalCompilerException(message, SourceLocation.Unknown)
}

object JvmCompilationOrigins {
  def capture(root: TypedAst.Root): JvmCompilationOrigins = {
    val source = JvmSourceOrigins.capture(root)
    val origins = new JvmCompilationOrigins(source.provenance)
    source.foreachExpression(origins.record)
    source.releaseBodies()
    origins
  }
}
