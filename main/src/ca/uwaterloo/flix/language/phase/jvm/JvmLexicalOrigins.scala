package ca.uwaterloo.flix.language.phase.jvm

import ca.uwaterloo.flix.language.ast.{SemanticOp, SourceLocation, Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.ast.TypedAst.{Expr, FormalParam}
import ca.uwaterloo.flix.language.fmt.{FormatOptions, FormatType}
import ca.uwaterloo.flix.language.ast.shared.*
import ca.uwaterloo.flix.util.InternalCompilerException

import java.util.{Base64, IdentityHashMap}
import java.security.MessageDigest
import scala.collection.mutable

/** Temporary, declaration-local identity lookup. Release after copying origins to generated symbols. */
final class JvmLexicalOrigins private (private val origins: IdentityHashMap[Expr, GeneratedJvmKey],
                                       val entries: List[(Expr, GeneratedJvmKey)],
                                       val allEntries: List[(Expr, GeneratedJvmKey)],
                                       val bindings: List[JvmLexicalOrigins.Binding],
                                       private[jvm] val fingerprintEvaluations: Long) {
  def get(exp: Expr): Option[GeneratedJvmKey] = Option(origins.get(exp))

  def originOf(exp: Expr): GeneratedJvmKey = get(exp).getOrElse {
    throw InternalCompilerException("Missing lexical JVM origin.", exp.loc)
  }
}

/**
  * Captures source structure before optimization; unsupported semantic forms fail closed.
  * Named holes retain their source namespace and name; anonymous holes omit their
  * generated labels. Expression holes retain their enclosed expression. Neither hole
  * form includes the compiler's local-scope cache.
  */
object JvmLexicalOrigins {
  private def debugType(tpe: Type): String =
    FormatType.formatTypeWithOptions(tpe, FormatOptions(FormatOptions.VarName.NameBased))

  type TypeEncoder = (Type, Map[Symbol, GeneratedJvmKey]) => String

  /** A source binding captured before ANF/lowering replaces its user-facing name. */
  /** A source binding retained for debug metadata before lowering erases its Flix type. */
  case class Binding(identity: String, name: String, loc: SourceLocation, kind: String, tpe: String)

  def capture(exp: Expr, owner: GeneratedJvmKey, fparams: List[FormalParam],
              encodeType: TypeEncoder, sourceOrigin: Symbol => GeneratedJvmKey): JvmLexicalOrigins = {
    new Capture(encodeType, sourceOrigin).run(exp, owner, fparams)
  }

  private case class Env(values: Map[Symbol.VarSym, String], localOrigins: Map[Symbol, GeneratedJvmKey]) {
    def getOrElse(sym: Symbol.VarSym, missing: => String): String = values.getOrElse(sym, missing)
    def +(binding: (Symbol.VarSym, String)): Env = copy(values = values + binding)
    def ++(bindings: Iterable[(Symbol.VarSym, String)]): Env = copy(values = values ++ bindings)
  }

  private sealed trait BindingContext {
    def scope: String
  }

  private case class AlphaBinding(depth: Int) extends BindingContext {
    def scope: String = depth.toString
  }

  private case class SiteBinding(scope: String) extends BindingContext

  private def frame(tag: String, parts: List[String]): String =
    Base64.getEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(GeneratedJvmKey(tag, parts).bytes))

  private def fail(message: String, exp: Expr): Nothing =
    throw InternalCompilerException(message, exp.loc)

  private final class Capture(encodeType: TypeEncoder, sourceOrigin: Symbol => GeneratedJvmKey) {
    private val origins = new IdentityHashMap[Expr, GeneratedJvmKey]()
    private val ordered = mutable.ListBuffer.empty[(Expr, GeneratedJvmKey)]
    private val all = mutable.ListBuffer.empty[(Expr, GeneratedJvmKey)]
    private val bindings = mutable.ListBuffer.empty[Binding]
    private val groups = mutable.Map.empty[(String, String, String), Int]
    private val fingerprints = new IdentityHashMap[Env, IdentityHashMap[Expr, mutable.Map[Int, String]]]()
    private var fingerprintEvaluations = 0L

    def run(exp: Expr, owner: GeneratedJvmKey, fparams: List[FormalParam]): JvmLexicalOrigins = {
      val env = Env(fparams.zipWithIndex.map { case (param, index) =>
        param.bnd.sym -> frame("parameter", List(index.toString))
      }.toMap, Map.empty)
      fparams.zipWithIndex.foreach { case (param, index) =>
        if (!param.bnd.sym.isWild) bindings += Binding(frame("parameter", List(index.toString)), param.bnd.sym.text, param.bnd.sym.loc, "parameter", debugType(param.tpe))
      }
      visit(exp, env, frame(owner.family, owner.fields), "body", isRoot = true)
      new JvmLexicalOrigins(origins, ordered.toList, all.toList, bindings.toList, fingerprintEvaluations)
    }

    private def symbol(sym: Symbol): String = {
      val key = sourceOrigin(sym)
      frame(key.family, key.fields)
    }

    private def reference(sym: Symbol.VarSym, env: Env, exp: Expr): String =
      env.getOrElse(sym, fail("Unbound lexical JVM origin variable.", exp))

    private def bind(params: List[FormalParam], env: Env, scope: String): Env =
      env ++ params.zipWithIndex.map { case (param, index) =>
        param.bnd.sym -> frame("bound", List(scope, index.toString))
      }

    private def encode(tpe: Type, env: Env): String = encodeType(tpe, env.localOrigins)

    private def parameters(params: List[FormalParam], env: Env): String =
      frame("parameters", params.map(param => param.src match {
        case TypeSource.Ascribed => frame("ascribed", List(encode(param.tpe, env)))
        case TypeSource.Inferred => frame("inferred", Nil)
      }))

    private def mapLambdaBody[A](lambda: Expr.Lambda, env: Env, context: BindingContext)
                                (consume: (FormalParam, Expr, Env) => A): A =
      consume(lambda.fparam, lambda.exp, bind(List(lambda.fparam), env, context.scope))

    private def mapLocalDefBodies[A, B](local: Expr.LocalDef, env: Env, context: BindingContext)
                                      (body: (List[FormalParam], Expr, Env) => A,
                                       rest: (Expr, () => Env) => B): (A, B) = {
      val binding = context match {
        case AlphaBinding(_) => frame("recursive", List(context.scope))
        case SiteBinding(_) => frame("local-def", List(context.scope))
      }
      val recursive = env + (local.bnd.sym -> binding)
      val params = local.fparams.toList
      val result = body(params, local.exp1, bind(params, recursive, context.scope))
      val continuation = () => context match {
        case AlphaBinding(_) => env + (local.bnd.sym -> binding)
        case SiteBinding(_) => recursive
      }
      (result, rest(local.exp2, continuation))
    }

    private def mapObjectBodies[A, B](obj: Expr.NewObject, env: Env, context: BindingContext)
                                    (constructor: (TypedAst.JvmConstructor, Env) => A,
                                     method: (TypedAst.JvmMethod, Env, String) => B): (List[A], List[B]) = {
      val constructors = obj.constructors.map(constructor(_, env))
      val methods = obj.methods.map { entry =>
        val methodScope = context match {
          case AlphaBinding(_) => context.scope
          case SiteBinding(site) => frame("method", List(site, entry.ident.name, parameters(entry.fparams.toList, env)))
        }
        method(entry, bind(entry.fparams.toList, env, methodScope), methodScope)
      }
      (constructors, methods)
    }

    private def identity(scope: String, role: String, fingerprint: String): String = {
      val group = (scope, role, fingerprint)
      val ordinal = groups.getOrElse(group, 0)
      groups(group) = ordinal + 1
      frame("site", List(scope, role, fingerprint, ordinal.toString))
    }

    private def record(exp: Expr, scope: String, role: String, kind: String, fingerprint: String): String = {
      val site = identity(scope, role, fingerprint)
      val key = GeneratedJvmKey("lexical-" + kind, List(site))
      if (origins.containsKey(exp)) fail("Repeated AST identity in lexical capture.", exp)
      origins.put(exp, key)
      ordered += ((exp, key))
      all += ((exp, key))
      frame(key.family, key.fields)
    }

    private def visit(exp: Expr, env: Env, scope: String, role: String, isRoot: Boolean = false): Unit = {
      exp match {
        case _: Expr.Lambda | _: Expr.LocalDef | _: Expr.NewObject | _: Expr.Let => ()
        case _ =>
          val site = if (isRoot) frame("root", List(scope, role)) else identity(scope, "expression:" + role, fingerprint(exp, env, 0))
          val key = GeneratedJvmKey("lexical-expression", List(site))
          if (origins.containsKey(exp)) fail("Repeated AST identity in lexical capture.", exp)
          origins.put(exp, key)
          all += ((exp, key))
      }
      exp match {
        case lambda: Expr.Lambda =>
          val site = record(exp, scope, role, "lambda", fingerprint(exp, env, 0))
          mapLambdaBody(lambda, env, SiteBinding(site)) { (param, body, inner) =>
            if (!param.bnd.sym.isWild) {
              bindings += Binding(frame("lambda-parameter", List(site)), param.bnd.sym.text,
                param.bnd.sym.loc, "lambda-parameter", debugType(param.tpe))
            }
            visit(body, inner, site, "body")
          }
        case local: Expr.LocalDef =>
          val site = record(exp, scope, role, "local-def", localFingerprint(local, env, 0))
          mapLocalDefBodies(local, env, SiteBinding(site))(
            (_, body, inner) => visit(body, inner, site, "body"),
            (rest, recursive) => visit(rest, recursive(), scope, role))
          ()
        case Expr.Let(binder, value, rest, _, _, _) =>
          val binding = identity(scope, "let:" + role, fingerprint(value, env, 0))
          if (!binder.sym.isWild) bindings += Binding(binding, binder.sym.text, binder.sym.loc, "let", debugType(binder.tpe))
          val site = if (isRoot) frame("root", List(scope, role)) else frame("let-expression", List(binding))
          val key = GeneratedJvmKey("lexical-expression", List(site))
          if (origins.containsKey(exp)) fail("Repeated AST identity in lexical capture.", exp)
          origins.put(exp, key)
          all += ((exp, key))
          visit(value, env, binding, "value")
          visit(rest, env + (binder.sym -> binding), scope, role)
        case obj: Expr.NewObject =>
          val site = record(exp, scope, role, "anonymous-class", fingerprint(exp, env, 0))
          mapObjectBodies(obj, env, SiteBinding(site))(
            (constructor, inner) => visit(constructor.exp, inner, site, "constructor"),
            (method, inner, methodScope) => visit(method.exp, inner, methodScope, "body"))
          ()
        case Expr.Var(sym, _, _) => reference(sym, env, exp); ()
        case Expr.Use(_, _, body, _) => visit(body, env, scope, role)
        case _ =>
          val key = origins.get(exp)
          val (_, _, children) = scopedShape(exp, env, 0, Some(frame(key.family, key.fields)))
          children.foreach { case (childRole, child, childEnv) => visit(child, childEnv, scope, frame("role-path", List(role, childRole))) }
      }
    }

    private def localFingerprint(local: Expr.LocalDef, env: Env, depth: Int): String =
      mapLocalDefBodies(local, env, AlphaBinding(depth))(fingerprintLocalBody(env, depth), (_, _) => ())._1

    private def fingerprintLocalBody(env: Env, depth: Int)(params: List[FormalParam], body: Expr, inner: Env): String =
      frame("local-def", List(parameters(params, env), fingerprint(body, inner, depth + 1)))

    private def fingerprint(exp: Expr, env: Env, depth: Int): String = {
      val expressions = fingerprints.computeIfAbsent(env, _ => new IdentityHashMap[Expr, mutable.Map[Int, String]]())
      val depths = expressions.computeIfAbsent(exp, _ => mutable.Map.empty[Int, String])
      depths.getOrElseUpdate(depth, {
        fingerprintEvaluations += 1
        fingerprintUncached(exp, env, depth)
      })
    }

    private def fingerprintUncached(exp: Expr, env: Env, depth: Int): String = exp match {
      case Expr.Var(sym, _, _) => frame("var", List(reference(sym, env, exp)))
      case lambda: Expr.Lambda =>
        mapLambdaBody(lambda, env, AlphaBinding(depth)) { (param, body, inner) =>
          frame("lambda", List(parameters(List(param), env), fingerprint(body, inner, depth + 1)))
        }
      case Expr.Let(binder, value, rest, _, _, _) =>
        frame("let", List(fingerprint(value, env, depth),
          fingerprint(rest, env + (binder.sym -> frame("let-bound", List(depth.toString))), depth + 1)))
      case local: Expr.LocalDef =>
        val (bodyKey, restKey) = mapLocalDefBodies(local, env, AlphaBinding(depth))(
          fingerprintLocalBody(env, depth),
          (rest, recursive) => fingerprint(rest, recursive(), depth + 1))
        frame("local", List(bodyKey, restKey))
      case obj: Expr.NewObject =>
        val (constructorKeys, methodKeys) = mapObjectBodies(obj, env, AlphaBinding(depth))(
          (constructor, inner) => frame("constructor", List(
            encode(constructor.retTpe, env), encode(constructor.eff, env), fingerprint(constructor.exp, inner, depth))),
          (method, inner, _) => frame("method", List(method.ident.name, parameters(method.fparams.toList, env), encode(method.retTpe, env),
            encode(method.eff, env), frame("annotations", method.ann.map(ann => frame("annotation", List(ann.clazz.descriptorString(), ann.isRuntimeVisible.toString))).sorted),
            fingerprint(method.exp, inner, depth + 1))))
        frame("anonymous-class", List(obj.clazz.desc.descriptorString(), obj.clazz.isInterface.toString,
          frame("constructors", constructorKeys), frame("methods", methodKeys.sorted)))
      case Expr.Use(_, _, body, _) => fingerprint(body, env, depth)
      case _ =>
        val (tag, fields, children) = scopedShape(exp, env, depth)
        frame(tag, fields ::: children.map { case (role, child, childEnv) =>
          val childDepth = if (childEnv eq env) depth else depth + 1
          frame(role, List(fingerprint(child, childEnv, childDepth)))
        })
    }

    private def pattern(pat: TypedAst.Pattern): (String, List[Symbol.VarSym]) = pat match {
      case TypedAst.Pattern.Wild(_, _) => (frame("wild", Nil), Nil)
      case TypedAst.Pattern.Var(binder, _, _) => (frame("binder", Nil), List(binder.sym))
      case TypedAst.Pattern.Cst(cst, _, _) => (constant(cst), Nil)
      case TypedAst.Pattern.Tag(sym, pats, _, _) =>
        val children = pats.map(pattern)
        (frame("tag-pattern", symbol(sym.sym) :: children.map(_._1)), children.flatMap(_._2))
      case TypedAst.Pattern.Tuple(pats, _, _) =>
        val children = pats.toList.map(pattern)
        (frame("tuple-pattern", children.map(_._1)), children.flatMap(_._2))
      case TypedAst.Pattern.Record(pats, rest, _, _) =>
        val children = pats.map(label => (label.label.name, pattern(label.pat)))
        val tail = pattern(rest)
        (frame("record-pattern", children.map { case (label, child) => frame(label, List(child._1)) } :+ tail._1),
          children.flatMap(_._2._2) ::: tail._2)
      case TypedAst.Pattern.Error(_, loc) => throw InternalCompilerException("Erroneous lexical pattern.", loc)
    }

    private def bindSymbols(symbols: List[Symbol.VarSym], env: Env, scope: String): Env =
      env ++ symbols.distinct.zipWithIndex.map { case (sym, index) => sym -> frame("pattern-bound", List(scope, index.toString)) }

    /** Records debugger-visible pattern binders during the single source-capture traversal. */
    private def recordPatternBindings(pat: TypedAst.Pattern, scope: String): Unit = pat match {
      case TypedAst.Pattern.Var(binder, tpe, _) =>
        if (!binder.sym.isWild) bindings += Binding(scope, binder.sym.text, binder.sym.loc, "pattern", debugType(tpe))
      case TypedAst.Pattern.Tag(_, pats, _, _) =>
        pats.zipWithIndex.foreach { case (p, i) => recordPatternBindings(p, frame("tag-binding", List(scope, i.toString))) }
      case TypedAst.Pattern.Tuple(pats, _, _) =>
        pats.toList.zipWithIndex.foreach { case (p, i) => recordPatternBindings(p, frame("tuple-binding", List(scope, i.toString))) }
      case TypedAst.Pattern.Record(pats, rest, _, _) =>
        pats.foreach(p => recordPatternBindings(p.pat, frame("record-binding", List(scope, p.label.name))))
        recordPatternBindings(rest, frame("record-rest", List(scope)))
      case _: TypedAst.Pattern.Wild | _: TypedAst.Pattern.Cst => ()
      case TypedAst.Pattern.Error(_, loc) => throw InternalCompilerException("Erroneous debug pattern.", loc)
    }

    private def scopedShape(exp: Expr, env: Env, depth: Int,
                            bindingScope: Option[String] = None): (String, List[String], List[(String, Expr, Env)]) = {
      val context = bindingScope.getOrElse(frame("alpha-scope", List(depth.toString)))
      val ruleGroups = mutable.Map.empty[(String, String), Int]
      def child(role: String, body: Expr): (String, Expr, Env) = (role, body, env)
      def rule(role: String, metadata: String, symbols: List[Symbol.VarSym], bodies: List[(String, Expr)]): List[(String, Expr, Env)] = {
        val alpha = bindSymbols(symbols, env, frame("rule", List(depth.toString, role, metadata)))
        val ruleScope = bindingScope match {
          case None => frame("rule", List(context, role, metadata))
          case Some(_) =>
            val bodyKey = frame("rule-body", metadata :: bodies.map { case (bodyRole, body) => frame(bodyRole, List(fingerprint(body, alpha, depth + 1))) })
            val group = (role, bodyKey)
            val ordinal = ruleGroups.getOrElse(group, 0)
            ruleGroups(group) = ordinal + 1
            frame("rule", List(context, role, bodyKey, ordinal.toString))
        }
        val inner = bindSymbols(symbols, env, ruleScope)
        bodies.map { case (bodyRole, body) => (frame("rule-role", List(role, metadata, bodyRole)), body, inner) }
      }
      exp match {
        case Expr.Region(binder, regionSym, body, _, _, _) =>
          val origin = GeneratedJvmKey("lexical-region", List(context))
          val inner = bindSymbols(List(binder.sym), env, context).copy(localOrigins = env.localOrigins + (regionSym -> origin))
          ("region", Nil, List(("body", body, inner)))
        case Expr.Match(scrutinee, rules, _, _, _) =>
          val children = rules.zipWithIndex.flatMap { case (entry, index) =>
            bindingScope.foreach(s => recordPatternBindings(entry.pat, frame("match-binding", List(s, index.toString))))
            val (pat, binders) = pattern(entry.pat)
            rule("match", pat, binders, entry.guard.toList.map(guard => "guard" -> guard) ::: List("body" -> entry.exp))
          }
          ("match", Nil, child("scrutinee", scrutinee) :: children)
        case Expr.RestrictableChoose(star, scrutinee, rules, _, _, _) =>
          val children = rules.flatMap { entry => entry.pat match {
            case TypedAst.RestrictableChoosePattern.Tag(sym, pats, _, _) =>
              val binders = pats.flatMap {
                case TypedAst.RestrictableChoosePattern.Var(binder, _, _) => List(binder.sym)
                case TypedAst.RestrictableChoosePattern.Wild(_, _) => Nil
                case _ => fail("Erroneous restrictable pattern.", exp)
              }
              val pat = frame("restrictable-pattern", symbol(sym.sym) :: pats.map {
                case _: TypedAst.RestrictableChoosePattern.Var => "var"
                case _: TypedAst.RestrictableChoosePattern.Wild => "wild"
                case _ => fail("Erroneous restrictable pattern.", exp)
              })
              rule("choose", pat, binders, List("body" -> entry.exp))
            case _ => fail("Erroneous restrictable pattern.", exp)
          }}
          ("choose", List(star.toString), child("scrutinee", scrutinee) :: children)
        case Expr.ExtMatch(scrutinee, rules, _, _, _) =>
          val children = rules.flatMap { entry => entry.pat match {
            case TypedAst.ExtPattern.Default(_) => rule("ext", "default", Nil, List("body" -> entry.exp))
            case TypedAst.ExtPattern.Tag(label, pats, _) =>
              val binders = pats.flatMap {
                case TypedAst.ExtTagPattern.Var(binder, _, _) => List(binder.sym)
                case TypedAst.ExtTagPattern.Wild(_, _) | TypedAst.ExtTagPattern.Unit(_, _) => Nil
                case _ => fail("Erroneous extensible pattern.", exp)
              }
              val pat = frame("ext-pattern", label.name :: pats.map {
                case _: TypedAst.ExtTagPattern.Var => "var"
                case _: TypedAst.ExtTagPattern.Wild => "wild"
                case _: TypedAst.ExtTagPattern.Unit => "unit"
                case _ => fail("Erroneous extensible pattern.", exp)
              })
              rule("ext", pat, binders, List("body" -> entry.exp))
            case _ => fail("Erroneous extensible pattern.", exp)
          }}
          ("ext-match", Nil, child("scrutinee", scrutinee) :: children)
        case Expr.TryCatch(body, rules, _, _, _) =>
          ("try", Nil, child("body", body) :: rules.flatMap(entry =>
            rule("catch", entry.clazz.descriptorString(), List(entry.bnd.sym), List("body" -> entry.exp))))
        case Expr.Handler(sym, rules, _, _, _, _, _) =>
          ("handler", List(symbol(sym.sym)), rules.flatMap(entry =>
            rule("operation", frame("op", List(symbol(entry.op.sym), parameters(entry.fparams.toList, env))),
              entry.fparams.toList.map(_.bnd.sym), List("body" -> entry.exp))))
        case Expr.SelectChannel(rules, default, _, _, _) =>
          val children = rules.flatMap { entry =>
            child("channel", entry.chan) :: rule("select", fingerprint(entry.chan, env, depth), List(entry.bnd.sym), List("body" -> entry.exp))
          }
          ("select", Nil, children ::: default.toList.map(body => child("default", body)))
        case Expr.ParYield(fragments, body, _, _, _) =>
          val patterns = fragments.map(fragment => pattern(fragment.pat))
          val inner = bindSymbols(patterns.flatMap(_._2), env, frame("par-yield", List(context)))
          ("par-yield", patterns.map(_._1), fragments.map(fragment => child("fragment", fragment.exp)) ::: List(("body", body, inner)))
        case Expr.FixpointConstraintSet(constraints, _, _) =>
          val parts = constraints.map { constraint =>
            val inner = bindSymbols(constraint.cparams.map(_.bnd.sym), env, frame("constraint", List(context)))
            val head = predicateHead(constraint.head, inner)
            val bodies = constraint.body.map(predicateBody(_, inner))
            (frame("constraint", head._1 :: bodies.map(_._1)), head._2 ::: bodies.flatMap(_._2))
          }
          ("constraints", parts.map(_._1), parts.flatMap(_._2))
        case Expr.FixpointLambda(params, body, _, _, _) =>
          ("fixpoint-lambda", params.map(param => frame("predicate", List(param.pred.name, encode(param.tpe, env)))), List(child("body", body)))
        case Expr.FixpointMerge(left, right, _, _, _) => ("fixpoint-merge", Nil, List(child("left", left), child("right", right)))
        case Expr.FixpointQueryWithProvenance(exps, select, withPreds, _, _, _) =>
          val head = predicateHead(select, env)
          ("query-provenance", head._1 :: withPreds.map(_.name), exps.map(body => child("database", body)) ::: head._2)
        case Expr.FixpointQueryWithSelect(exps, query, selects, from, where, pred, _, _, _) =>
          val symbols = from.flatMap {
            case TypedAst.Predicate.Body.Atom(_, _, _, _, terms, _, _) => terms.flatMap(term => pattern(term)._2)
            case TypedAst.Predicate.Body.Functional(binders, _, _) => binders.map(_.sym)
            case TypedAst.Predicate.Body.Guard(_, _) => Nil
          }
          val inner = bindSymbols(symbols, env, frame("query", List(context)))
          val bodies = from.map(predicateBody(_, inner))
          ("query-select", pred.name :: bodies.map(_._1), exps.map(body => child("database", body)) :::
            List(("query", query, inner)) ::: selects.map(body => ("select", body, inner)) :::
            bodies.flatMap(_._2) ::: where.map(body => ("where", body, inner)))
        case Expr.FixpointSolveWithProject(exps, predicates, mode, _, _, _) =>
          val modeKey = mode match {
            case SolveMode.Default => "default"
            case SolveMode.WithProvenance => "provenance"
          }
          ("solve", List(modeKey, frame("predicates", predicates.toList.map(preds => frame("some", preds.map(_.name))))), exps.map(body => child("database", body)))
        case Expr.FixpointInjectInto(exps, predicates, _, _, _) =>
          ("inject", predicates.map(pred => frame("predicate", List(pred.pred.name, pred.arity.toString))), exps.map(body => child("value", body)))
        case _ =>
          val (tag, fields, children) = shape(exp, env)
          (tag, fields, children.map { case (role, body) => child(role, body) })
      }
    }

    private def predicateHead(head: TypedAst.Predicate.Head, env: Env): (String, List[(String, Expr, Env)]) = head match {
      case TypedAst.Predicate.Head.Atom(pred, den, terms, _, _) =>
        (frame("head", List(pred.name, denotation(den))), terms.map(term => ("head-term", term, env)))
    }

    private def predicateBody(body: TypedAst.Predicate.Body, env: Env): (String, List[(String, Expr, Env)]) = body match {
      case TypedAst.Predicate.Body.Atom(pred, den, polarity, fixity, terms, _, _) =>
        val sign = polarity match {
          case Polarity.Positive => "positive"
          case Polarity.Negative => "negative"
        }
        val fixed = fixity match {
          case Fixity.Loose => "loose"
          case Fixity.Fixed => "fixed"
        }
        val patterns = terms.map { term =>
          val (shape, symbols) = pattern(term)
          frame("term", shape :: symbols.map(sym => env.getOrElse(sym, throw InternalCompilerException("Unbound Datalog variable.", term.loc))))
        }
        (frame("body-atom", List(pred.name, denotation(den), sign, fixed) ::: patterns), Nil)
      case TypedAst.Predicate.Body.Functional(binders, exp, _) =>
        (frame("functional", binders.map(binder => reference(binder.sym, env, exp))), List(("functional", exp, env)))
      case TypedAst.Predicate.Body.Guard(exp, _) => (frame("guard", Nil), List(("guard", exp, env)))
    }

    private def denotation(den: Denotation): String = den match {
      case Denotation.Relational => "relational"
      case Denotation.Latticenal => "latticenal"
    }

    private def shape(exp: Expr, env: Env): (String, List[String], List[(String, Expr)]) = {
      def node(tag: String, fields: List[String], children: (String, Expr)*): (String, List[String], List[(String, Expr)]) =
        (tag, fields, children.toList)
      def sequence(tag: String, exps: List[Expr], fields: List[String] = Nil): (String, List[String], List[(String, Expr)]) =
        (tag, fields, exps.map(child => ("element", child)))
      exp match {
        case Expr.Cst(cst, _, _) => node("constant", List(constant(cst)))
        case Expr.Hole(sym, _, _, _, _) =>
          if (sym.isAnonymous) node("anonymous-hole", Nil)
          else node("named-hole", List(frame("namespace", sym.namespace), sym.name))
        case Expr.HoleWithExp(body, _, _, _, _) => node("hole-with-expression", Nil, "body" -> body)
        case Expr.ApplyClo(function, argument, _, _, _, _) => node("apply", Nil, "function" -> function, "argument" -> argument)
        case Expr.ApplyLocalDef(sym, args, _, _, _, _, _) => sequence("apply-local", args, List(reference(sym.sym, env, exp)))
        case Expr.ApplyDef(sym, args, _, _, _, _, _, _) =>
          sequence("apply-def", args, List(symbol(sym.sym)))
        case Expr.ApplySig(sym, args, _, _, _, _, _, _, _) =>
          sequence("apply-sig", args, List(symbol(sym.sym)))
        case Expr.ApplyOp(sym, args, _, _, _, _) => sequence("apply-op", args, List(symbol(sym.sym)))
        case Expr.OpenAs(sym, body, _, _) => node("open-as", List(symbol(sym.sym)), "body" -> body)
        case Expr.Unary(op, body, _, _, _) => node("unary", List(operator(op)), "operand" -> body)
        case Expr.Binary(op, left, right, _, _, _) => node("binary", List(operator(op)), "left" -> left, "right" -> right)
        case Expr.IfThenElse(condition, consequent, alternative, _, _, _) =>
          node("if", Nil, "condition" -> condition, "then" -> consequent, "else" -> alternative)
        case Expr.Stm(exps, result, _, _, _) => sequence("statements", exps ::: List(result))
        case Expr.Discard(body, _, _) => node("discard", Nil, "body" -> body)
        case Expr.Tuple(exps, _, _, _) => sequence("tuple", exps)
        case Expr.Tag(sym, exps, _, _, _) =>
          sequence("tag", exps, List(symbol(sym.sym)))
        case Expr.RestrictableTag(sym, exps, _, _, _) => sequence("restrictable-tag", exps, List(symbol(sym.sym)))
        case Expr.ExtTag(label, exps, _, _, _) => sequence("ext-tag", exps, List(label.name))
        case Expr.RecordSelect(body, label, _, _, _) => node("record-select", List(label.name), "record" -> body)
        case Expr.RecordExtend(label, value, rest, _, _, _) => node("record-extend", List(label.name), "value" -> value, "rest" -> rest)
        case Expr.RecordRestrict(label, body, _, _, _) => node("record-restrict", List(label.name), "record" -> body)
        case Expr.VectorLit(exps, _, _, _) => sequence("vector", exps)
        case Expr.VectorLoad(base, index, _, _, _) => node("vector-load", Nil, "base" -> base, "index" -> index)
        case Expr.VectorLength(body, _) => node("vector-length", Nil, "body" -> body)
        case Expr.ArrayLit(exps, region, _, _, _) => ("array", Nil, ("region" -> region) :: exps.map(child => ("element", child)))
        case Expr.ArrayNew(value, length, region, _, _, _) => node("array-new", Nil, "value" -> value, "length" -> length, "region" -> region)
        case Expr.ArrayLoad(base, index, _, _, _) => node("array-load", Nil, "base" -> base, "index" -> index)
        case Expr.ArrayLength(body, _, _) => node("array-length", Nil, "body" -> body)
        case Expr.ArrayStore(base, index, value, _, _) => node("array-store", Nil, "base" -> base, "index" -> index, "value" -> value)
        case Expr.StructNew(sym, fields, region, _, _, _) =>
          ("struct-new", List(symbol(sym)), fields.map { case (field, value) => (symbol(field.sym), value) } ::: region.toList.map(value => ("region", value)))
        case Expr.StructGet(body, field, _, _, _) => node("struct-get", List(symbol(field.sym)), "body" -> body)
        case Expr.StructPut(body, field, value, _, _, _) => node("struct-put", List(symbol(field.sym)), "body" -> body, "value" -> value)
        case Expr.Ascribe(body, expectedType, expectedEff, _, _, _) =>
          node("ascribe", List(frame("type", expectedType.toList.map(encode(_, env))), frame("effect", expectedEff.toList.map(encode(_, env)))), "body" -> body)
        case Expr.InstanceOf(body, clazz, _) => node("instance-of", List(clazz.descriptorString()), "body" -> body)
        case Expr.CheckedCast(cast, body, tpe, eff, _) =>
          val kind = cast match {
            case CheckedCastType.TypeCast => "type"
            case CheckedCastType.EffectCast => "effect"
          }
          node("checked-cast", List(kind, encode(tpe, env), encode(eff, env)), "body" -> body)
        case Expr.UncheckedCast(body, declaredType, declaredEff, _, _, _) =>
          node("unchecked-cast", List(frame("type", declaredType.toList.map(encode(_, env))), frame("effect", declaredEff.toList.map(encode(_, env)))), "body" -> body)
        case Expr.Throw(body, _, _, _) => node("throw", Nil, "body" -> body)
        case Expr.Unsafe(body, runEff, asEff, _, _, _) => node("unsafe", List(encode(runEff, env), frame("as", asEff.toList.map(encode(_, env)))), "body" -> body)
        case Expr.RunWith(body, handler, _, _, _) => node("run-with", Nil, "body" -> body, "handler" -> handler)
        case Expr.NewChannel(body, _, _, _) => node("new-channel", Nil, "body" -> body)
        case Expr.GetChannel(body, _, _, _) => node("get-channel", Nil, "body" -> body)
        case Expr.PutChannel(channel, value, _, _, _) => node("put-channel", Nil, "channel" -> channel, "value" -> value)
        case Expr.Spawn(body, region, _, _, _) => node("spawn", Nil, "body" -> body, "region" -> region)
        case Expr.Lazy(body, _, _) => node("lazy", Nil, "body" -> body)
        case Expr.Force(body, _, _, _) => node("force", Nil, "body" -> body)
        case Expr.InvokeSuperConstructor(method, args, _, _, _) => sequence("super-constructor", args, List(javaMethod(method)))
        case Expr.InvokeConstructor(method, args, _, _, _) => sequence("constructor", args, List(javaMethod(method)))
        case Expr.InvokeStaticMethod(method, args, _, _, _) => sequence("static-method", args, List(javaMethod(method)))
        case Expr.InvokeSuperMethod(method, args, _, _, _) => sequence("super-method", args, List(javaMethod(method)))
        case Expr.InvokeMethod(method, receiver, args, _, _, _) =>
          ("method", List(javaMethod(method)), ("receiver" -> receiver) :: args.map(arg => ("argument", arg)))
        case Expr.GetField(field, receiver, _, _, _) => node("get-field", List(javaField(field)), "receiver" -> receiver)
        case Expr.PutField(field, receiver, value, _, _, _) => node("put-field", List(javaField(field)), "receiver" -> receiver, "value" -> value)
        case Expr.GetStaticField(field, _, _, _) => node("get-static-field", List(javaField(field)))
        case Expr.PutStaticField(field, value, _, _, _) => node("put-static-field", List(javaField(field)), "value" -> value)
        case _: Expr.Error => fail("JVM lexical origins require an error-free typed AST.", exp)
        case _ => fail("Unsupported expression in lexical JVM origin: " + exp.productPrefix, exp)
      }
    }
  }

  private def javaMethod(method: ca.uwaterloo.flix.language.ast.jvm.JavaMethod): String =
    frame("java-method", List(method.ref.owner.descriptorString(), method.ref.name, method.ref.descriptor.descriptorString(), method.ref.isInterface.toString))

  private def javaField(field: ca.uwaterloo.flix.language.ast.jvm.JavaField): String =
    frame("java-field", List(field.ref.owner.descriptorString(), field.ref.name, field.ref.descriptor.descriptorString()))

  private def operator(op: SemanticOp): String = op match {
    case SemanticOp.BoolOp.Not => "BoolOp.Not"
    case SemanticOp.BoolOp.And => "BoolOp.And"
    case SemanticOp.BoolOp.Or => "BoolOp.Or"
    case SemanticOp.BoolOp.Eq => "BoolOp.Eq"
    case SemanticOp.BoolOp.Neq => "BoolOp.Neq"
    case SemanticOp.CharOp.Eq => "CharOp.Eq"
    case SemanticOp.CharOp.Neq => "CharOp.Neq"
    case SemanticOp.CharOp.Lt => "CharOp.Lt"
    case SemanticOp.CharOp.Le => "CharOp.Le"
    case SemanticOp.CharOp.Gt => "CharOp.Gt"
    case SemanticOp.CharOp.Ge => "CharOp.Ge"
    case SemanticOp.Float32Op.Neg => "Float32Op.Neg"
    case SemanticOp.Float32Op.Add => "Float32Op.Add"
    case SemanticOp.Float32Op.Sub => "Float32Op.Sub"
    case SemanticOp.Float32Op.Mul => "Float32Op.Mul"
    case SemanticOp.Float32Op.Div => "Float32Op.Div"
    case SemanticOp.Float32Op.Exp => "Float32Op.Exp"
    case SemanticOp.Float32Op.Eq => "Float32Op.Eq"
    case SemanticOp.Float32Op.Neq => "Float32Op.Neq"
    case SemanticOp.Float32Op.Lt => "Float32Op.Lt"
    case SemanticOp.Float32Op.Le => "Float32Op.Le"
    case SemanticOp.Float32Op.Gt => "Float32Op.Gt"
    case SemanticOp.Float32Op.Ge => "Float32Op.Ge"
    case SemanticOp.Float64Op.Neg => "Float64Op.Neg"
    case SemanticOp.Float64Op.Add => "Float64Op.Add"
    case SemanticOp.Float64Op.Sub => "Float64Op.Sub"
    case SemanticOp.Float64Op.Mul => "Float64Op.Mul"
    case SemanticOp.Float64Op.Div => "Float64Op.Div"
    case SemanticOp.Float64Op.Exp => "Float64Op.Exp"
    case SemanticOp.Float64Op.Eq => "Float64Op.Eq"
    case SemanticOp.Float64Op.Neq => "Float64Op.Neq"
    case SemanticOp.Float64Op.Lt => "Float64Op.Lt"
    case SemanticOp.Float64Op.Le => "Float64Op.Le"
    case SemanticOp.Float64Op.Gt => "Float64Op.Gt"
    case SemanticOp.Float64Op.Ge => "Float64Op.Ge"
    case SemanticOp.Int8Op.Neg => "Int8Op.Neg"
    case SemanticOp.Int8Op.Not => "Int8Op.Not"
    case SemanticOp.Int8Op.Add => "Int8Op.Add"
    case SemanticOp.Int8Op.Sub => "Int8Op.Sub"
    case SemanticOp.Int8Op.Mul => "Int8Op.Mul"
    case SemanticOp.Int8Op.Div => "Int8Op.Div"
    case SemanticOp.Int8Op.Rem => "Int8Op.Rem"
    case SemanticOp.Int8Op.Exp => "Int8Op.Exp"
    case SemanticOp.Int8Op.And => "Int8Op.And"
    case SemanticOp.Int8Op.Or => "Int8Op.Or"
    case SemanticOp.Int8Op.Xor => "Int8Op.Xor"
    case SemanticOp.Int8Op.Shl => "Int8Op.Shl"
    case SemanticOp.Int8Op.Shr => "Int8Op.Shr"
    case SemanticOp.Int8Op.Eq => "Int8Op.Eq"
    case SemanticOp.Int8Op.Neq => "Int8Op.Neq"
    case SemanticOp.Int8Op.Lt => "Int8Op.Lt"
    case SemanticOp.Int8Op.Le => "Int8Op.Le"
    case SemanticOp.Int8Op.Gt => "Int8Op.Gt"
    case SemanticOp.Int8Op.Ge => "Int8Op.Ge"
    case SemanticOp.Int16Op.Neg => "Int16Op.Neg"
    case SemanticOp.Int16Op.Not => "Int16Op.Not"
    case SemanticOp.Int16Op.Add => "Int16Op.Add"
    case SemanticOp.Int16Op.Sub => "Int16Op.Sub"
    case SemanticOp.Int16Op.Mul => "Int16Op.Mul"
    case SemanticOp.Int16Op.Div => "Int16Op.Div"
    case SemanticOp.Int16Op.Rem => "Int16Op.Rem"
    case SemanticOp.Int16Op.Exp => "Int16Op.Exp"
    case SemanticOp.Int16Op.And => "Int16Op.And"
    case SemanticOp.Int16Op.Or => "Int16Op.Or"
    case SemanticOp.Int16Op.Xor => "Int16Op.Xor"
    case SemanticOp.Int16Op.Shl => "Int16Op.Shl"
    case SemanticOp.Int16Op.Shr => "Int16Op.Shr"
    case SemanticOp.Int16Op.Eq => "Int16Op.Eq"
    case SemanticOp.Int16Op.Neq => "Int16Op.Neq"
    case SemanticOp.Int16Op.Lt => "Int16Op.Lt"
    case SemanticOp.Int16Op.Le => "Int16Op.Le"
    case SemanticOp.Int16Op.Gt => "Int16Op.Gt"
    case SemanticOp.Int16Op.Ge => "Int16Op.Ge"
    case SemanticOp.Int32Op.Neg => "Int32Op.Neg"
    case SemanticOp.Int32Op.Not => "Int32Op.Not"
    case SemanticOp.Int32Op.Add => "Int32Op.Add"
    case SemanticOp.Int32Op.Sub => "Int32Op.Sub"
    case SemanticOp.Int32Op.Mul => "Int32Op.Mul"
    case SemanticOp.Int32Op.Div => "Int32Op.Div"
    case SemanticOp.Int32Op.Rem => "Int32Op.Rem"
    case SemanticOp.Int32Op.Exp => "Int32Op.Exp"
    case SemanticOp.Int32Op.And => "Int32Op.And"
    case SemanticOp.Int32Op.Or => "Int32Op.Or"
    case SemanticOp.Int32Op.Xor => "Int32Op.Xor"
    case SemanticOp.Int32Op.Shl => "Int32Op.Shl"
    case SemanticOp.Int32Op.Shr => "Int32Op.Shr"
    case SemanticOp.Int32Op.Eq => "Int32Op.Eq"
    case SemanticOp.Int32Op.Neq => "Int32Op.Neq"
    case SemanticOp.Int32Op.Lt => "Int32Op.Lt"
    case SemanticOp.Int32Op.Le => "Int32Op.Le"
    case SemanticOp.Int32Op.Gt => "Int32Op.Gt"
    case SemanticOp.Int32Op.Ge => "Int32Op.Ge"
    case SemanticOp.Int64Op.Neg => "Int64Op.Neg"
    case SemanticOp.Int64Op.Not => "Int64Op.Not"
    case SemanticOp.Int64Op.Add => "Int64Op.Add"
    case SemanticOp.Int64Op.Sub => "Int64Op.Sub"
    case SemanticOp.Int64Op.Mul => "Int64Op.Mul"
    case SemanticOp.Int64Op.Div => "Int64Op.Div"
    case SemanticOp.Int64Op.Rem => "Int64Op.Rem"
    case SemanticOp.Int64Op.Exp => "Int64Op.Exp"
    case SemanticOp.Int64Op.And => "Int64Op.And"
    case SemanticOp.Int64Op.Or => "Int64Op.Or"
    case SemanticOp.Int64Op.Xor => "Int64Op.Xor"
    case SemanticOp.Int64Op.Shl => "Int64Op.Shl"
    case SemanticOp.Int64Op.Shr => "Int64Op.Shr"
    case SemanticOp.Int64Op.Eq => "Int64Op.Eq"
    case SemanticOp.Int64Op.Neq => "Int64Op.Neq"
    case SemanticOp.Int64Op.Lt => "Int64Op.Lt"
    case SemanticOp.Int64Op.Le => "Int64Op.Le"
    case SemanticOp.Int64Op.Gt => "Int64Op.Gt"
    case SemanticOp.Int64Op.Ge => "Int64Op.Ge"
    case SemanticOp.StringOp.Concat => "StringOp.Concat"
    case SemanticOp.ReflectOp.ReflectEff => "ReflectOp.ReflectEff"
    case SemanticOp.ReflectOp.ReflectType => "ReflectOp.ReflectType"
    case SemanticOp.ReflectOp.ReflectValue => "ReflectOp.ReflectValue"
    case SemanticOp.ObjectOp.RefEq => "ObjectOp.RefEq"
    case SemanticOp.ObjectOp.Ordinal => "ObjectOp.Ordinal"
  }

  private def constant(cst: Constant): String = cst match {
    case Constant.Unit => frame("unit", Nil)
    case Constant.Null => frame("null", Nil)
    case Constant.Bool(value) => frame("bool", List(value.toString))
    case Constant.Char(value) => frame("char", List(value.toInt.toString))
    case Constant.Float32(value) => frame("float32", List(java.lang.Float.floatToRawIntBits(value).toString))
    case Constant.Float64(value) => frame("float64", List(java.lang.Double.doubleToRawLongBits(value).toString))
    case Constant.BigDecimal(value) => frame("decimal", List(value.unscaledValue().toString, value.scale().toString))
    case Constant.Int8(value) => frame("int8", List(value.toString))
    case Constant.Int16(value) => frame("int16", List(value.toString))
    case Constant.Int32(value) => frame("int32", List(value.toString))
    case Constant.Int64(value) => frame("int64", List(value.toString))
    case Constant.BigInt(value) => frame("bigint", List(value.toString))
    case Constant.Str(value) => frame("string", List(value))
    case Constant.Regex(value) => frame("regex", List(value.pattern(), value.flags().toString))
    case Constant.RecordEmpty => frame("record-empty", Nil)
    case Constant.Static => frame("static", Nil)
  }
}
