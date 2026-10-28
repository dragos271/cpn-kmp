package no.hvl.cpn.lang.eval

import no.hvl.cpn.lang.ast.AndAlsoExpr
import no.hvl.cpn.lang.ast.AppExpr
import no.hvl.cpn.lang.ast.AsPat
import no.hvl.cpn.lang.ast.CaseExpr
import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.ConBind
import no.hvl.cpn.lang.ast.ConPat
import no.hvl.cpn.lang.ast.ConstExpr
import no.hvl.cpn.lang.ast.ConstPat
import no.hvl.cpn.lang.ast.Constant
import no.hvl.cpn.lang.ast.DatatypeDecl
import no.hvl.cpn.lang.ast.Decl
import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.ExceptionDecl
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.FixityDecl
import no.hvl.cpn.lang.ast.FnExpr
import no.hvl.cpn.lang.ast.FunBind
import no.hvl.cpn.lang.ast.FunDecl
import no.hvl.cpn.lang.ast.GlobrefDecl
import no.hvl.cpn.lang.ast.HandleExpr
import no.hvl.cpn.lang.ast.IdentExpr
import no.hvl.cpn.lang.ast.IdentPat
import no.hvl.cpn.lang.ast.IfExpr
import no.hvl.cpn.lang.ast.InfixExpr
import no.hvl.cpn.lang.ast.InfixPat
import no.hvl.cpn.lang.ast.LetExpr
import no.hvl.cpn.lang.ast.ListExpr
import no.hvl.cpn.lang.ast.ListPat
import no.hvl.cpn.lang.ast.LocalDecl
import no.hvl.cpn.lang.ast.LongIdent
import no.hvl.cpn.lang.ast.MatchRule
import no.hvl.cpn.lang.ast.Node
import no.hvl.cpn.lang.ast.OpenDecl
import no.hvl.cpn.lang.ast.OrElseExpr
import no.hvl.cpn.lang.ast.Pattern
import no.hvl.cpn.lang.ast.RaiseExpr
import no.hvl.cpn.lang.ast.RecordExpr
import no.hvl.cpn.lang.ast.RecordPat
import no.hvl.cpn.lang.ast.SelectorExpr
import no.hvl.cpn.lang.ast.SeqExpr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.ast.TupleExpr
import no.hvl.cpn.lang.ast.TuplePat
import no.hvl.cpn.lang.ast.TypeDecl
import no.hvl.cpn.lang.ast.TypedExpr
import no.hvl.cpn.lang.ast.TypedPat
import no.hvl.cpn.lang.ast.UseDecl
import no.hvl.cpn.lang.ast.ValDecl
import no.hvl.cpn.lang.ast.VarDecl
import no.hvl.cpn.lang.ast.WhileExpr
import no.hvl.cpn.lang.ast.WildcardPat
import no.hvl.cpn.lang.colour.AliasColourSet
import no.hvl.cpn.lang.colour.BoolColourSet
import no.hvl.cpn.lang.colour.ColourSetEnvironment
import no.hvl.cpn.lang.colour.ColourSetException
import no.hvl.cpn.lang.colour.ColsetEvaluator
import no.hvl.cpn.lang.colour.CpnColourSet
import no.hvl.cpn.lang.colour.EnumColourSet
import no.hvl.cpn.lang.colour.IndexColourSet
import no.hvl.cpn.lang.colour.TimedColourSet
import no.hvl.cpn.lang.colour.UnionColourSet
import no.hvl.cpn.lang.colour.UnitColourSet
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.lang.value.MlRecord
import no.hvl.cpn.lang.value.MlTuple
import no.hvl.cpn.lang.value.formatValue

/** Names in scope at run time: a frame of values on top of an enclosing environment. */
internal class Env(
    private val names: Map<String, Any?>,
    val parent: Env?,
) {
    fun lookup(name: String): Any? {
        var env: Env? = this
        while (env != null) {
            val value = env.names[name]
            if (value != null || name in env.names) return value
            env = env.parent
        }
        return UNBOUND
    }

    fun with(names: Map<String, Any?>): Env = if (names.isEmpty()) this else Env(names, this)

    /** Everything in scope, outermost first, so later entries shadow earlier ones when collected into a map. */
    fun entries(): Sequence<Map.Entry<String, Any?>> = (parent?.entries() ?: emptySequence()) + names.entries.asSequence()

    /** The names this environment adds on top of [base], one of its ancestors. */
    fun addedSince(base: Env): Map<String, Any?> {
        val frames = ArrayDeque<Map<String, Any?>>()
        var env: Env? = this
        while (env != null && env !== base) {
            frames.addFirst(env.names)
            env = env.parent
        }
        return LinkedHashMap<String, Any?>().apply { frames.forEach(::putAll) }
    }

    /** One frame with everything in scope, for fast lookups in the global environment. */
    fun flattened(): Env = Env(LinkedHashMap<String, Any?>().apply { entries().forEach { (k, v) -> put(k, v) } }, null)

    companion object {
        val UNBOUND = Any()
    }
}

/**
 * The CPN ML evaluator (T2.5): a tree-walking interpreter for the SML'97 core and the CPN Tools
 * declarations, over the shared AST. It assumes the program type-checks (T2.4); run the type checker
 * first for good error messages, as [no.hvl.cpn.lang.eval.CpnMlInscriptions] users do.
 *
 * [declare] processes declaration blocks in order: values and functions, datatypes, exceptions,
 * `colset` (built in [colourSets], whose range bounds and `subset ... by` predicates this evaluator
 * computes), `var` (recorded in [variables]) and `globref`. [evaluate] computes an expression, with
 * CPN variables bound as given.
 *
 * Random functions (`discrete`, `CS.ran ()`, `random`) draw from [random].
 */
public class Interpreter(
    public var random: RandomSource = RandomSource.seeded(0),
) {
    private var global: Env = Env(Library.values(this), null)

    /** The environment colour set declarations are evaluated in (the global one while [declare] runs). */
    private var colourSetScope: Env = global

    private var source: SourceText? = null

    public val colourSets: ColourSetEnvironment =
        ColourSetEnvironment.standard(
            evaluator =
                object : ColsetEvaluator {
                    override fun value(expr: Expr): Any? = expression(expr, colourSetScope)

                    override fun predicate(
                        expr: Expr,
                        base: CpnColourSet,
                    ): (Any?) -> Boolean {
                        val function = expression(expr, colourSetScope)
                        return { colour -> call(function, colour) as Boolean }
                    }
                },
        )

    private val declaredVariables = LinkedHashMap<String, CpnColourSet>()

    /** CPN variables (`var n : NO`) by name, with their colour sets. */
    public val variables: Map<String, CpnColourSet> get() = declaredVariables

    /** What `print` wrote. */
    public val output: StringBuilder = StringBuilder()

    // ---------------------------------------------------------------------------------------------
    // Entry points

    /**
     * Runs a declaration block, adding what it declares to the global environment.
     * @throws EvaluationException if evaluating a declaration fails (e.g. raises an exception).
     * @throws no.hvl.cpn.lang.colour.ColsetDeclarationException if a colour set cannot be built.
     */
    public fun declare(
        declarations: Declarations,
        source: SourceText? = null,
    ) {
        this.source = source
        guarded(declarations) {
            for (decl in declarations.declarations) global = declaration(decl, global)
            global = global.flattened()
        }
    }

    /** The value of [expr] with the CPN variables in [bindings] bound. @throws EvaluationException */
    public fun evaluate(
        expr: Expr,
        bindings: Map<String, Any?> = emptyMap(),
        source: SourceText? = null,
    ): Any? {
        this.source = source
        return guarded(expr) { expression(expr, global.with(bindings)) }
    }

    /** True if [name] is a constructor in the global environment (`red`, `SOME`, `true`, a union alternative, ...). */
    public fun isConstructor(name: String): Boolean = global.lookup(name) is Constructor

    /** Whether [name] is bound to anything in the global environment. */
    public fun isBound(name: String): Boolean = global.lookup(name) !== Env.UNBOUND

    /** The declared CPN variables [expr] uses, i.e. those occurring free in it. */
    public fun variablesOf(expr: Expr): Set<String> = LinkedHashSet<String>().also { FreeVariables(this, it).expression(expr, emptySet()) }

    /** Turns failures inside the evaluator into [EvaluationException]s located at the innermost application. */
    private fun <T> guarded(
        root: Node,
        block: () -> T,
    ): T {
        at(root.span)
        try {
            return block()
        } catch (e: MlRaise) {
            throw EvaluationException(current, "uncaught exception ${formatValue(e.exception)}", currentSource)
        } catch (e: EvaluationException) {
            throw e
        } catch (e: ColourSetException) {
            throw EvaluationException(current, e.message ?: "colour set error", currentSource)
        } catch (e: no.hvl.cpn.lang.colour.ColsetDeclarationException) {
            throw e
        } catch (e: ClassCastException) {
            throw EvaluationException(current, "a value has an unexpected type (does the code type-check?)", currentSource)
        } catch (e: IllegalArgumentException) {
            throw EvaluationException(current, e.message ?: "evaluation failed", currentSource)
        } catch (e: IllegalStateException) {
            throw EvaluationException(current, e.message ?: "evaluation failed", currentSource)
        }
    }

    /** The application being evaluated and the text it is in, for error messages. */
    private var current: Span = Span.NONE
    private var currentSource: SourceText? = null

    private fun at(span: Span) {
        current = span
        currentSource = source
    }

    /** Runs [body] of a function defined in [origin], so errors inside it are located there. */
    private inline fun <T> inSource(
        origin: SourceText?,
        body: () -> T,
    ): T {
        val caller = source
        source = origin
        try {
            return body()
        } finally {
            source = caller
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Expressions

    internal fun call(
        function: Any?,
        argument: Any?,
    ): Any? = (function as MlFunction).apply(argument)

    private fun expression(
        e: Expr,
        env: Env,
    ): Any? =
        when (e) {
            is ConstExpr -> constant(e.constant)
            is IdentExpr -> identifier(e.name, env)
            is TupleExpr -> if (e.elements.isEmpty()) Unit else MlTuple(e.elements.map { expression(it, env) })
            is RecordExpr -> record(e, env)
            is SelectorExpr -> selector(e.label)
            is ListExpr -> e.elements.map { expression(it, env) }
            is AppExpr -> {
                val function = expression(e.function, env)
                val argument = expression(e.argument, env)
                at(e.span)
                call(function, argument)
            }
            is InfixExpr -> {
                val function = identifier(e.operator, env)
                val left = expression(e.left, env)
                val right = expression(e.right, env)
                at(e.span)
                if (function is MlFunction2) function.apply2(left, right) else call(function, MlTuple(listOf(left, right)))
            }
            is TypedExpr -> expression(e.expr, env)
            is AndAlsoExpr -> expression(e.left, env) as Boolean && expression(e.right, env) as Boolean
            is OrElseExpr -> expression(e.left, env) as Boolean || expression(e.right, env) as Boolean
            is IfExpr -> if (expression(e.condition, env) as Boolean) expression(e.then, env) else expression(e.otherwise, env)
            is CaseExpr -> {
                val value = expression(e.scrutinee, env)
                at(e.span)
                matchRules(e.rules, value, env) { raise("Match") }
            }
            is FnExpr -> Closure(e.rules, env)
            is LetExpr -> expression(e.body, e.declarations.fold(env) { acc, decl -> declaration(decl, acc) })
            is SeqExpr -> {
                var last: Any? = Unit
                for (expr in e.exprs) last = expression(expr, env)
                last
            }
            is RaiseExpr -> throw MlRaise(expression(e.exception, env) as MlCon)
            is HandleExpr ->
                try {
                    expression(e.expr, env)
                } catch (raised: MlRaise) {
                    matchRules(e.rules, raised.exception, env) { throw raised }
                }
            is WhileExpr -> {
                while (expression(e.condition, env) as Boolean) expression(e.body, env)
                Unit
            }
        }

    private fun constant(c: Constant): Any =
        when (c) {
            // D8: int literals are Ints; only those beyond Int stay Longs (intinf).
            is Constant.IntConst -> if (c.value in Int.MIN_VALUE..Int.MAX_VALUE) c.value.toInt() else c.value
            is Constant.RealConst -> MlReal(c.value)
            is Constant.StringConst -> c.value
            is Constant.CharConst -> c.value
        }

    private fun identifier(
        name: LongIdent,
        env: Env,
    ): Any? {
        val value = env.lookup(name.text)
        if (value === Env.UNBOUND) {
            return colourSetFunction(name) ?: throw EvaluationException(name.span, "unbound value ${name.text}", source)
        }
        return if (value is ConstantConstructor) value.value else value
    }

    /** `{1 = a, 2 = b}` is the tuple `(a, b)`, as in SML; `{}` is unit. */
    private fun record(
        e: RecordExpr,
        env: Env,
    ): Any? {
        if (e.fields.isEmpty()) return Unit
        val values = LinkedHashMap<String, Any?>()
        for (field in e.fields) values[field.label.name] = expression(field.value, env)
        val n = values.size
        return if (n >= 2 && (1..n).all { it.toString() in values }) MlTuple((1..n).map { values[it.toString()] }) else MlRecord(values)
    }

    private fun selector(label: String): MlFunction =
        object : MlFunction {
            override fun apply(argument: Any?): Any? =
                when (argument) {
                    is MlTuple -> argument.elements[label.toInt() - 1]
                    is MlRecord -> argument[label]
                    else -> throw IllegalArgumentException("#$label applied to ${formatValue(argument)}")
                }
        }

    /** `NO.all ()`, `CS.ord c`, ...; null if [name] is not a colour set function. */
    private fun colourSetFunction(name: LongIdent): Any? {
        if (name.qualifiers.size != 1) return null
        val cs = colourSets[name.qualifiers.single()] ?: return null

        fun fn(body: (Any?) -> Any?) =
            object : MlFunction {
                override fun apply(argument: Any?): Any? = body(argument)
            }
        return when (name.name) {
            "all" -> fn { cs.all() }
            "size" -> fn { (cs.size ?: throw ColourSetException("size is not defined for ${cs.name}: it is infinite")).toInt() }
            "ord" -> fn { cs.ord(it).toInt() }
            "col" -> fn { cs.col((it as Number).toLong()) }
            "ran" -> fn { cs.ran(random::nextLong) }
            "mkstr" -> fn { cs.mkstr(it) }
            "mkstr_ms" -> fn { cs.mkstrMs(toMultiset(it)) }
            "legal" -> fn { cs.legal(it) }
            else -> null
        }
    }

    /** Applies the first rule whose pattern matches [value]; [otherwise] when none does. */
    private inline fun matchRules(
        rules: List<MatchRule>,
        value: Any?,
        env: Env,
        otherwise: () -> Any?,
    ): Any? {
        for (rule in rules) {
            val bound = HashMap<String, Any?>()
            if (match(rule.pattern, value, env, bound)) return expression(rule.body, env.with(bound))
        }
        return otherwise()
    }

    /** `fn` and `case` rules over the environment they were created in. */
    private inner class Closure(
        val rules: List<MatchRule>,
        val env: Env,
    ) : MlFunction {
        private val origin = source

        override fun apply(argument: Any?): Any? = inSource(origin) { matchRules(rules, argument, env) { raise("Match") } }
    }

    /** A `fun` with curried arguments; [arguments] are those received so far. */
    private inner class FunClosure(
        val bind: FunBind,
        val env: Env,
        val arguments: List<Any?> = emptyList(),
        val origin: SourceText? = source,
    ) : MlFunction {
        private val arity =
            bind.clauses
                .first()
                .arguments.size

        override fun apply(argument: Any?): Any? {
            val all = arguments + listOf(argument)
            if (all.size < arity) return FunClosure(bind, env, all, origin)
            return inSource(origin) {
                val clause =
                    bind.clauses.firstNotNullOfOrNull { clause ->
                        val bound = HashMap<String, Any?>()
                        if (clause.arguments.indices.all { match(clause.arguments[it], all[it], env, bound) }) clause to bound else null
                    } ?: raise("Match")
                expression(clause.first.body, env.with(clause.second))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Patterns

    private fun constructorOf(
        name: LongIdent,
        env: Env,
    ): Constructor? = env.lookup(name.text) as? Constructor

    /** Matches [value] against [p], adding the variables it binds to [bound]. */
    private fun match(
        p: Pattern,
        value: Any?,
        env: Env,
        bound: MutableMap<String, Any?>,
    ): Boolean =
        when (p) {
            is WildcardPat -> true
            is IdentPat -> {
                val constructor = constructorOf(p.name, env)
                if (constructor != null) {
                    constructor.destruct(value) !== Constructor.NO_MATCH
                } else {
                    bound[p.name.name] = value
                    true
                }
            }
            is ConstPat -> valuesEqual(constant(p.constant), value)
            is TuplePat ->
                if (p.elements.isEmpty()) {
                    value == Unit
                } else {
                    val tuple = value as MlTuple
                    p.elements.indices.all { match(p.elements[it], tuple.elements[it], env, bound) }
                }
            is RecordPat ->
                p.fields.all { field ->
                    val fieldValue =
                        when (value) {
                            is MlTuple -> value.elements[field.label.name.toInt() - 1]
                            is MlRecord -> value[field.label.name]
                            else -> throw IllegalArgumentException("record pattern against ${formatValue(value)}")
                        }
                    match(field.pattern, fieldValue, env, bound)
                }
            is ListPat -> {
                val list = value as List<*>
                list.size == p.elements.size && p.elements.indices.all { match(p.elements[it], list[it], env, bound) }
            }
            is ConPat -> {
                val constructor =
                    constructorOf(p.constructor, env) ?: throw IllegalStateException("${p.constructor.text} is not a constructor")
                val argument = constructor.destruct(value)
                argument !== Constructor.NO_MATCH && match(p.argument, argument, env, bound)
            }
            is InfixPat -> {
                val constructor = constructorOf(p.operator, env) ?: throw IllegalStateException("${p.operator.text} is not a constructor")
                val argument = constructor.destruct(value)
                if (argument === Constructor.NO_MATCH) {
                    false
                } else {
                    val pair = argument as MlTuple
                    match(p.left, pair.elements[0], env, bound) && match(p.right, pair.elements[1], env, bound)
                }
            }
            is AsPat -> {
                bound[p.name.name] = value
                match(p.pattern, value, env, bound)
            }
            is TypedPat -> match(p.pattern, value, env, bound)
        }

    // ---------------------------------------------------------------------------------------------
    // Declarations

    private fun declaration(
        decl: Decl,
        env: Env,
    ): Env =
        when (decl) {
            is ValDecl -> if (decl.recursive) recursiveValues(decl, env) else values(decl, env)
            is FunDecl -> {
                val frame = HashMap<String, Any?>()
                val inner = Env(frame, env) // filled below, so the functions see themselves
                for (bind in decl.bindings) frame[bind.name.name] = FunClosure(bind, inner)
                inner
            }
            is TypeDecl, is FixityDecl -> env
            is DatatypeDecl -> env.with(decl.bindings.flatMap { it.constructors }.associate { it.name.name to constructor(it) })
            is ExceptionDecl -> env.with(decl.bindings.associate { it.name.name to constructor(it) })
            is LocalDecl -> {
                val locals = decl.locals.fold(env) { acc, d -> declaration(d, acc) }
                val body = decl.body.fold(locals) { acc, d -> declaration(d, acc) }
                env.with(body.addedSince(locals))
            }
            is OpenDecl ->
                decl.structures.fold(env) { acc, structure ->
                    val prefix = structure.text + "."
                    val members = LinkedHashMap<String, Any?>()
                    for ((name, value) in acc.entries()) if (name.startsWith(prefix)) members[name.removePrefix(prefix)] = value
                    acc.with(members)
                }
            is ColsetDecl -> {
                colourSetScope = env
                env.with(constructorsOf(colourSets.declare(decl)))
            }
            is VarDecl -> {
                val cs =
                    colourSets[decl.colset.text]
                        ?: throw EvaluationException(decl.colset.span, "unknown colour set ${decl.colset.text}", source)
                for (name in decl.names) declaredVariables[name.name] = cs
                env
            }
            is GlobrefDecl -> env.with(mapOf(decl.name.name to MlRef(expression(decl.value, env))))
            is UseDecl -> throw EvaluationException(
                decl.span,
                "use \"${decl.path}\" is not supported: put the declarations in the model",
                source,
            )
        }

    private fun values(
        decl: ValDecl,
        env: Env,
    ): Env {
        val bound = HashMap<String, Any?>()
        for (bind in decl.bindings) {
            val value = expression(bind.value, env)
            at(bind.span)
            if (!match(bind.pattern, value, env, bound)) raise("Bind")
        }
        return env.with(bound)
    }

    private fun recursiveValues(
        decl: ValDecl,
        env: Env,
    ): Env {
        val frame = HashMap<String, Any?>()
        val inner = Env(frame, env) // filled below, so the functions see themselves
        for (bind in decl.bindings) {
            val name = (bind.pattern as IdentPat).name.name
            frame[name] = Closure((bind.value as FnExpr).rules, inner)
        }
        return inner
    }

    private fun constructor(bind: ConBind): Constructor =
        if (bind.argument == null) ConstantConstructor(bind.name.name, MlCon(bind.name.name)) else DataConstructor(bind.name.name)

    /** The constructors a colour set declaration brings into scope, matching how its colours are represented. */
    private fun constructorsOf(cs: CpnColourSet): Map<String, Any?> =
        when (cs) {
            is TimedColourSet -> constructorsOf(cs.untimed)
            is EnumColourSet -> cs.constants.associateWith { ConstantConstructor(it, MlCon(it)) }
            is IndexColourSet -> mapOf(cs.constructor to DataConstructor(cs.constructor))
            is UnionColourSet ->
                cs.alternatives.associate { (name, argument) ->
                    name to if (argument == null) ConstantConstructor(name, MlCon(name)) else DataConstructor(name)
                }
            is BoolColourSet ->
                if (cs.falseName == "false" && cs.trueName == "true") {
                    emptyMap()
                } else {
                    mapOf(cs.falseName to ConstantConstructor(cs.falseName, false), cs.trueName to ConstantConstructor(cs.trueName, true))
                }
            is UnitColourSet -> if (cs.unitName == "()") emptyMap() else mapOf(cs.unitName to ConstantConstructor(cs.unitName, Unit))
            is AliasColourSet -> emptyMap()
            else -> emptyMap()
        }
}
