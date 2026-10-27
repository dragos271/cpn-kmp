package no.hvl.cpn.lang.types

import no.hvl.cpn.lang.ast.AliasColset
import no.hvl.cpn.lang.ast.AndAlsoExpr
import no.hvl.cpn.lang.ast.AppExpr
import no.hvl.cpn.lang.ast.AsPat
import no.hvl.cpn.lang.ast.BoolColset
import no.hvl.cpn.lang.ast.CaseExpr
import no.hvl.cpn.lang.ast.ColsetDecl
import no.hvl.cpn.lang.ast.ColsetSpec
import no.hvl.cpn.lang.ast.ConPat
import no.hvl.cpn.lang.ast.ConstExpr
import no.hvl.cpn.lang.ast.ConstPat
import no.hvl.cpn.lang.ast.Constant
import no.hvl.cpn.lang.ast.DatatypeDecl
import no.hvl.cpn.lang.ast.Decl
import no.hvl.cpn.lang.ast.Declarations
import no.hvl.cpn.lang.ast.EnumColset
import no.hvl.cpn.lang.ast.ExceptionDecl
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.FixityDecl
import no.hvl.cpn.lang.ast.FnExpr
import no.hvl.cpn.lang.ast.FunDecl
import no.hvl.cpn.lang.ast.GlobrefDecl
import no.hvl.cpn.lang.ast.HandleExpr
import no.hvl.cpn.lang.ast.IdentExpr
import no.hvl.cpn.lang.ast.IdentPat
import no.hvl.cpn.lang.ast.IfExpr
import no.hvl.cpn.lang.ast.IndexColset
import no.hvl.cpn.lang.ast.InfixExpr
import no.hvl.cpn.lang.ast.InfixPat
import no.hvl.cpn.lang.ast.IntColset
import no.hvl.cpn.lang.ast.IntInfColset
import no.hvl.cpn.lang.ast.LetExpr
import no.hvl.cpn.lang.ast.ListColset
import no.hvl.cpn.lang.ast.ListExpr
import no.hvl.cpn.lang.ast.ListPat
import no.hvl.cpn.lang.ast.LocalDecl
import no.hvl.cpn.lang.ast.LongIdent
import no.hvl.cpn.lang.ast.MatchRule
import no.hvl.cpn.lang.ast.Node
import no.hvl.cpn.lang.ast.OpenDecl
import no.hvl.cpn.lang.ast.OrElseExpr
import no.hvl.cpn.lang.ast.Pattern
import no.hvl.cpn.lang.ast.ProductColset
import no.hvl.cpn.lang.ast.RaiseExpr
import no.hvl.cpn.lang.ast.RangeSpec
import no.hvl.cpn.lang.ast.RealColset
import no.hvl.cpn.lang.ast.RecordColset
import no.hvl.cpn.lang.ast.RecordExpr
import no.hvl.cpn.lang.ast.RecordPat
import no.hvl.cpn.lang.ast.SelectorExpr
import no.hvl.cpn.lang.ast.SeqExpr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.ast.StringColset
import no.hvl.cpn.lang.ast.SubsetColset
import no.hvl.cpn.lang.ast.TimeColset
import no.hvl.cpn.lang.ast.TupleExpr
import no.hvl.cpn.lang.ast.TuplePat
import no.hvl.cpn.lang.ast.TypeDecl
import no.hvl.cpn.lang.ast.TypeExpr
import no.hvl.cpn.lang.ast.TypedExpr
import no.hvl.cpn.lang.ast.TypedPat
import no.hvl.cpn.lang.ast.UnionColset
import no.hvl.cpn.lang.ast.UnitColset
import no.hvl.cpn.lang.ast.UseDecl
import no.hvl.cpn.lang.ast.ValDecl
import no.hvl.cpn.lang.ast.VarDecl
import no.hvl.cpn.lang.ast.WhileExpr
import no.hvl.cpn.lang.ast.WildcardPat
import no.hvl.cpn.lang.ast.at

/** A type error, located in its source. */
public class TypeError(
    public val span: Span,
    public val reason: String,
    public val source: SourceText? = null,
) : Exception(if (source == null) reason else "${source.describe(span)}: $reason")

/**
 * Hindley-Milner type inference for CPN ML: let-polymorphism with SML's value restriction,
 * equality types (`=` needs `''a`; reals and functions cannot be compared), default overloading
 * (`+` works on int or real, int if nothing decides), flexible records (`#label`), datatypes and
 * exceptions, plus the CPN Tools declarations (`colset`, `var`, `globref`) and inscriptions.
 *
 * Check the declarations first ([checkDeclarations]); they extend [environment], in which the
 * inscriptions are then checked. Errors are collected rather than thrown, so one bad declaration
 * does not hide the problems in the next ones.
 */
public class TypeChecker(
    public var environment: TypeEnvironment = TypeEnvironment.basis(),
) {
    private val unifier = Unifier { name -> environment.equality[name] ?: Equality.IF_ARGUMENTS }
    private var level = 0
    private var source: SourceText? = null

    // ---------------------------------------------------------------------------------------------
    // Entry points

    /** Checks a declaration block, adding what it declares to [environment]. */
    public fun checkDeclarations(
        declarations: Declarations,
        source: SourceText? = null,
    ): List<TypeError> {
        this.source = source
        val errors = mutableListOf<TypeError>()
        for (decl in declarations.declarations) {
            try {
                environment = declaration(decl, environment)
                defaultOverloadsIn(environment)
            } catch (e: TypeError) {
                errors += e.located()
            }
        }
        return errors
    }

    /** The type of [expr] in [environment]. @throws TypeError */
    public fun typeOf(
        expr: Expr,
        source: SourceText? = null,
    ): Type {
        this.source = source
        try {
            val type = expression(expr, environment)
            unifier.defaultOverloads(type)
            return type
        } catch (e: TypeError) {
            throw e.located()
        }
    }

    /** An arc inscription must denote tokens of [colourSet]: a colour, or a multiset of colours. */
    public fun checkArcInscription(
        expr: Expr,
        colourSet: String,
        source: SourceText? = null,
    ): List<TypeError> = checkTokens(expr, colourSet, source, "arc inscription")

    /** An initial marking must denote tokens of [colourSet], like an arc inscription. */
    public fun checkInitialMarking(
        expr: Expr,
        colourSet: String,
        source: SourceText? = null,
    ): List<TypeError> = checkTokens(expr, colourSet, source, "initial marking")

    /** A guard is a boolean, or a list of booleans that must all hold (CPN Tools). */
    public fun checkGuard(
        expr: Expr,
        source: SourceText? = null,
    ): List<TypeError> =
        collect(source) {
            val type = prune(expression(expr, environment))
            val condition = if (type is TypeCon && type.name == "list") type.args.single() else type
            expect(
                condition,
                BOOL,
                expr,
            ) { actual, _ -> "a guard must be bool or a list of bools, not ${if (condition === type) actual else "$actual list"}" }
        }

    private fun checkTokens(
        expr: Expr,
        colourSet: String,
        source: SourceText?,
        what: String,
    ): List<TypeError> =
        collect(source) {
            val colour = environment.colourSet(colourSet) ?: throw TypeError(expr.span, "unknown colour set $colourSet")
            val type = prune(expression(expr, environment))
            // A single colour is shorthand for 1`colour; an undetermined type (e.g. `empty`) is taken as a multiset.
            val element =
                when {
                    type is TypeCon && type.name == "ms" -> type.args.single()
                    type is TypeVar -> fresh().also { unifier.unify(type, multisetType(it)) }
                    else -> type
                }
            expect(element, colour, expr) { actual, expected ->
                val asMultiset = TypePrinter().print(multisetType(colour))
                val found = if (element === type) actual else TypePrinter().print(multisetType(element))
                "$what has type $found, but the place holds $colourSet: expected $expected or $asMultiset"
            }
            unifier.defaultOverloads(type)
        }

    private fun collect(
        source: SourceText?,
        check: () -> Unit,
    ): List<TypeError> {
        this.source = source
        return try {
            check()
            emptyList()
        } catch (e: TypeError) {
            listOf(e.located())
        }
    }

    /** The error with the checker's current source attached, if it has none yet. */
    private fun TypeError.located(): TypeError {
        val current = this@TypeChecker.source
        return if (source != null || current == null) this else TypeError(span, reason, current)
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private fun fail(
        node: Node,
        message: String,
    ): Nothing = throw TypeError(node.span, message)

    /** Unifies, or fails at [node] with a message built from both types (printed with one printer). */
    private fun expect(
        actual: Type,
        expected: Type,
        node: Node,
        message: (actual: String, expected: String) -> String,
    ) {
        try {
            unifier.unify(actual, expected)
        } catch (e: UnifyException) {
            val printer = TypePrinter()
            val text = message(printer.print(actual), printer.print(expected))
            fail(node, if (e.detail == null) text else "$text: ${e.detail}")
        }
    }

    private fun fresh(kind: Kind = Kind.Free): TypeVar = unifier.fresh(level, kind)

    private inline fun <T> deeper(block: () -> T): T {
        level++
        try {
            return block()
        } finally {
            level--
        }
    }

    private fun lookup(
        name: LongIdent,
        env: TypeEnvironment,
    ): ValueBinding? = env.values[name.text]

    private fun defaultOverloadsIn(env: TypeEnvironment) {
        for (binding in env.values.values) if (binding.scheme.vars.isEmpty()) unifier.defaultOverloads(binding.scheme.body)
    }

    private fun typeFrom(
        expr: TypeExpr,
        env: TypeEnvironment,
        variables: MutableMap<String, TypeVar> = HashMap(),
    ): Type =
        TypeConverter(
            env,
            variables,
            { v -> fresh(if (v.isEquality) Kind.Equality else Kind.Free) },
            { node, message -> fail(node, message) },
        ).convert(expr)

    // ---------------------------------------------------------------------------------------------
    // Expressions

    private fun expression(
        expr: Expr,
        env: TypeEnvironment,
    ): Type =
        when (expr) {
            is ConstExpr -> constant(expr.constant)
            is IdentExpr -> identifier(expr, env)
            is TupleExpr -> if (expr.elements.isEmpty()) UNIT else tupleOf(expr.elements.map { expression(it, env) })
            is RecordExpr -> {
                val labels = expr.fields.map { it.label.name }
                labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
                    fail(
                        expr,
                        "label $it appears twice",
                    )
                }
                RecordType(expr.fields.associate { it.label.name to expression(it.value, env) })
            }
            is SelectorExpr -> {
                val field = fresh()
                FunType(fresh(Kind.FlexRecord(mapOf(expr.label to field))), field)
            }
            is ListExpr -> {
                val element = fresh()
                for (e in expr.elements) {
                    expect(
                        expression(e, env),
                        element,
                        e,
                    ) { actual, expected -> "list elements must have one type: this one is $actual, the others $expected" }
                }
                listType(element)
            }
            is AppExpr -> application(expr.function, expression(expr.function, env), expression(expr.argument, env), expr.argument)
            is InfixExpr -> {
                val operator = IdentExpr(expr.operator).at(expr.span)
                val operatorType = identifier(operator, env)
                val argumentType = tuple(expression(expr.left, env), expression(expr.right, env))
                val result = fresh()
                try {
                    unifier.unify(operatorType, FunType(argumentType, result))
                } catch (e: UnifyException) {
                    val printer = TypePrinter()
                    val (left, right) = tupleElements(prune(argumentType) as RecordType)!!.map { printer.print(it) }
                    val text = "operator ${expr.operator.text} cannot be applied to $left and $right (it takes ${printer.print(
                        operatorType,
                    )})"
                    fail(expr, if (e.detail == null) text else "$text: ${e.detail}")
                }
                result
            }
            is TypedExpr ->
                expression(expr.expr, env).also { t ->
                    expect(t, typeFrom(expr.type, env), expr) { a, b -> "expression has type $a, not the annotated $b" }
                }
            is AndAlsoExpr -> logical(expr.left, expr.right, "andalso", env)
            is OrElseExpr -> logical(expr.left, expr.right, "orelse", env)
            is IfExpr -> {
                expect(expression(expr.condition, env), BOOL, expr.condition) { a, _ -> "the condition of if must be bool, not $a" }
                val then = expression(expr.then, env)
                expect(
                    expression(expr.otherwise, env),
                    then,
                    expr.otherwise,
                ) { a, b -> "the branches of if have different types: then gives $b, else gives $a" }
                then
            }
            is CaseExpr -> match(expr.rules, expression(expr.scrutinee, env), env, "case")
            is FnExpr -> {
                val argument = fresh()
                val result = match(expr.rules, argument, env, "fn")
                FunType(argument, result)
            }
            is LetExpr -> {
                var inner = env
                for (decl in expr.declarations) inner = declaration(decl, inner)
                expression(expr.body, inner)
            }
            is SeqExpr -> expr.exprs.map { expression(it, env) }.last()
            is RaiseExpr -> {
                expect(expression(expr.exception, env), EXN, expr.exception) { a, _ -> "raise needs an exception, not $a" }
                fresh()
            }
            is HandleExpr -> {
                val type = expression(expr.expr, env)
                val handlerResult = match(expr.rules, EXN, env, "handle")
                expect(handlerResult, type, expr) { a, b -> "the handler gives $a but the expression gives $b" }
                type
            }
            is WhileExpr -> {
                expect(expression(expr.condition, env), BOOL, expr.condition) { a, _ -> "the condition of while must be bool, not $a" }
                expression(expr.body, env)
                UNIT
            }
        }

    private fun constant(c: Constant): Type =
        when (c) {
            is Constant.IntConst -> INT
            is Constant.RealConst -> REAL
            is Constant.StringConst -> STRING
            is Constant.CharConst -> CHAR
        }

    private fun identifier(
        expr: IdentExpr,
        env: TypeEnvironment,
    ): Type {
        val binding = lookup(expr.name, env)
        if (binding != null) return unifier.instantiate(binding.scheme, level)
        colourSetFunction(expr.name, env)?.let { return it }
        fail(expr, "unbound value or constructor: ${expr.name.text}")
    }

    /** `NO.all()`, `CS.ord c`, ...: the CPN Tools colour set functions. */
    private fun colourSetFunction(
        name: LongIdent,
        env: TypeEnvironment,
    ): Type? {
        if (name.qualifiers.size != 1) return null
        val colour = env.colourSet(name.qualifiers.single()) ?: return null
        return when (name.name) {
            "all" -> FunType(UNIT, multisetType(colour))
            "size" -> FunType(UNIT, INT)
            "ord" -> FunType(colour, INT)
            "col" -> FunType(INT, colour)
            "ran" -> FunType(UNIT, colour)
            "mkstr" -> FunType(colour, STRING)
            "mkstr_ms" -> FunType(multisetType(colour), STRING)
            "legal" -> FunType(fresh(), BOOL)
            else -> null
        }
    }

    private fun application(
        function: Expr,
        functionType: Type,
        argumentType: Type,
        argument: Expr,
    ): Type {
        val f = prune(functionType)
        if (f !is FunType && f !is TypeVar) {
            fail(function, "${describe(function)} has type ${TypePrinter().print(f)} and cannot be applied to an argument")
        }
        val result = fresh()
        val parameter = if (f is FunType) f.param else fresh()
        if (f is TypeVar) unifier.unify(f, FunType(parameter, result)) else unifier.unify((f as FunType).result, result)
        expect(argumentType, parameter, argument) { a, b -> "${describe(function)} expects an argument of type $b, but this has type $a" }
        return result
    }

    private fun describe(e: Expr): String = if (e is IdentExpr) e.name.text else "this function"

    private fun logical(
        left: Expr,
        right: Expr,
        operator: String,
        env: TypeEnvironment,
    ): Type {
        expect(expression(left, env), BOOL, left) { a, _ -> "the operands of $operator must be bool, not $a" }
        expect(expression(right, env), BOOL, right) { a, _ -> "the operands of $operator must be bool, not $a" }
        return BOOL
    }

    /** Rules of `case`, `fn` or `handle`: every pattern matches [argument], every body has one result type. */
    private fun match(
        rules: List<MatchRule>,
        argument: Type,
        env: TypeEnvironment,
        construct: String,
    ): Type {
        val result = fresh()
        for (rule in rules) {
            val bound = LinkedHashMap<String, Type>()
            expect(
                pattern(rule.pattern, env, bound),
                argument,
                rule.pattern,
            ) { a, b -> "this $construct pattern has type $a, but it must match $b" }
            var inner = env
            for ((name, type) in bound) inner = inner.withValue(name, ValueBinding(Scheme.mono(type), ValueKind.VALUE))
            expect(
                expression(rule.body, inner),
                result,
                rule.body,
            ) { a, b -> "the rules of this $construct give different types: $b and $a" }
        }
        return result
    }

    // ---------------------------------------------------------------------------------------------
    // Patterns

    /** The type a pattern matches; the variables it binds are added to [bound] (monomorphic). */
    private fun pattern(
        p: Pattern,
        env: TypeEnvironment,
        bound: MutableMap<String, Type>,
    ): Type =
        when (p) {
            is WildcardPat -> fresh()
            is ConstPat -> constant(p.constant).also { if (it == REAL) fail(p, "real constants cannot be used in patterns") }
            is IdentPat -> {
                val binding = lookup(p.name, env)?.takeIf { it.kind != ValueKind.VALUE }
                when {
                    binding != null -> {
                        if (binding.hasArgument) fail(p, "constructor ${p.name.text} needs an argument in a pattern")
                        unifier.instantiate(binding.scheme, level)
                    }
                    p.name.qualifiers.isNotEmpty() -> fail(p, "unbound constructor ${p.name.text}")
                    else -> bind(p.name.name, p, bound)
                }
            }
            is TuplePat -> if (p.elements.isEmpty()) UNIT else tupleOf(p.elements.map { pattern(it, env, bound) })
            is RecordPat -> {
                val fields = p.fields.associate { it.label.name to pattern(it.pattern, env, bound) }
                if (p.flexible) fresh(Kind.FlexRecord(fields)) else RecordType(fields)
            }
            is ListPat -> {
                val element = fresh()
                for (e in p.elements) {
                    expect(
                        pattern(e, env, bound),
                        element,
                        e,
                    ) { a, b -> "list pattern elements must have one type: $a and $b" }
                }
                listType(element)
            }
            is ConPat -> constructed(p.constructor, p.argument, p, env, bound) { pattern(p.argument, env, bound) }
            is InfixPat -> constructed(p.operator, null, p, env, bound) { tuple(pattern(p.left, env, bound), pattern(p.right, env, bound)) }
            is AsPat -> {
                val inner = pattern(p.pattern, env, bound)
                p.type?.let { t -> expect(inner, typeFrom(t, env), p) { a, b -> "pattern has type $a, not the annotated $b" } }
                bind(p.name.name, p, bound).also { unifier.unify(it, inner) }
            }
            is TypedPat ->
                pattern(p.pattern, env, bound).also { t ->
                    expect(t, typeFrom(p.type, env), p) { a, b -> "pattern has type $a, not the annotated $b" }
                }
        }

    private fun bind(
        name: String,
        at: Node,
        bound: MutableMap<String, Type>,
    ): Type {
        if (name in bound) fail(at, "$name is bound twice in this pattern")
        return fresh().also { bound[name] = it }
    }

    private fun constructed(
        name: LongIdent,
        argumentNode: Pattern?,
        at: Pattern,
        env: TypeEnvironment,
        bound: MutableMap<String, Type>,
        argument: () -> Type,
    ): Type {
        val binding = lookup(name, env)?.takeIf { it.kind != ValueKind.VALUE } ?: fail(at, "${name.text} is not a constructor")
        if (!binding.hasArgument) fail(at, "constructor ${name.text} takes no argument")
        val type = prune(unifier.instantiate(binding.scheme, level)) as FunType
        expect(argument(), type.param, argumentNode ?: at) { a, b -> "constructor ${name.text} takes $b, not $a" }
        return type.result
    }

    // ---------------------------------------------------------------------------------------------
    // Declarations

    private fun declaration(
        decl: Decl,
        env: TypeEnvironment,
    ): TypeEnvironment =
        when (decl) {
            is ValDecl -> if (decl.recursive) recursiveValues(decl, env) else values(decl, env)
            is FunDecl -> functions(decl, env)
            is TypeDecl ->
                decl.bindings.fold(env) { acc, bind ->
                    val variables = LinkedHashMap<String, TypeVar>()
                    val params = bind.parameters.map { p -> fresh().also { variables[p.name] = it } }
                    acc.withType(bind.name.name, TypeBinding.Abbreviation(params, typeFrom(bind.type, acc, variables)))
                }
            is DatatypeDecl -> datatypes(decl, env)
            is ExceptionDecl ->
                decl.bindings.fold(env) { acc, bind ->
                    val type = bind.argument?.let { FunType(typeFrom(it, acc), EXN) } ?: EXN
                    acc.withValue(bind.name.name, ValueBinding(Scheme.mono(type), ValueKind.EXCEPTION, bind.argument != null))
                }
            is LocalDecl -> {
                val withLocals = decl.locals.fold(env) { acc, d -> declaration(d, acc) }
                val withBody = decl.body.fold(withLocals) { acc, d -> declaration(d, acc) }
                // Only what the body declares is visible afterwards.
                var result = env
                for ((name, binding) in withBody.values) if (withLocals.values[name] !== binding) result = result.withValue(name, binding)
                for ((name, binding) in withBody.types) if (withLocals.types[name] !== binding) result = result.withType(name, binding)
                for ((name, type) in withBody.colourSets) {
                    if (withLocals.colourSets[name] !==
                        type
                    ) {
                        result = result.withColourSet(name, type)
                    }
                }
                result
            }
            is FixityDecl -> env
            is OpenDecl ->
                decl.structures.fold(env) { acc, structure ->
                    val prefix = structure.text + "."
                    val members = acc.values.filterKeys { it.startsWith(prefix) }
                    if (members.isEmpty()) fail(structure, "unknown structure ${structure.text}")
                    members.entries.fold(acc) { a, (name, binding) -> a.withValue(name.removePrefix(prefix), binding) }
                }
            is ColsetDecl -> colourSet(decl, env)
            is VarDecl -> {
                val type = env.colourSet(decl.colset.text) ?: fail(decl.colset, "unknown colour set ${decl.colset.text}")
                decl.names.fold(env) { acc, name -> acc.withValue(name.name, ValueBinding(Scheme.mono(type), ValueKind.VALUE)) }
            }
            is GlobrefDecl -> {
                val type = expression(decl.value, env)
                unifier.defaultOverloads(type)
                env.withValue(decl.name.name, ValueBinding(Scheme.mono(TypeCon("ref", listOf(type))), ValueKind.VALUE))
            }
            is UseDecl -> fail(decl, "use \"${decl.path}\" is not supported: put the declarations in the model")
        }

    private fun values(
        decl: ValDecl,
        env: TypeEnvironment,
    ): TypeEnvironment {
        var result = env
        for (bind in decl.bindings) {
            val bound = LinkedHashMap<String, Type>()
            val generalizable = isValue(bind.value, env)
            deeper {
                val valueType = expression(bind.value, env)
                expect(
                    pattern(bind.pattern, env, bound),
                    valueType,
                    bind.pattern,
                ) { a, b -> "the pattern has type $a but the value has type $b" }
            }
            for ((name, type) in bound) {
                val scheme = if (generalizable) unifier.generalize(type, level) else Scheme.mono(type)
                result = result.withValue(name, ValueBinding(scheme, ValueKind.VALUE))
            }
        }
        return result
    }

    private fun recursiveValues(
        decl: ValDecl,
        env: TypeEnvironment,
    ): TypeEnvironment {
        val names = LinkedHashMap<String, TypeVar>()
        deeper {
            for (bind in decl.bindings) {
                val name = (bind.pattern as? IdentPat)?.name?.name ?: fail(bind.pattern, "val rec can only bind a name")
                if (bind.value !is FnExpr) fail(bind.value, "val rec needs a fn expression")
                names[name] = fresh()
            }
            val inner =
                names.entries.fold(
                    env,
                ) { acc, (name, type) -> acc.withValue(name, ValueBinding(Scheme.mono(type), ValueKind.VALUE)) }
            for (bind in decl.bindings) {
                val name = (bind.pattern as IdentPat).name.name
                expect(expression(bind.value, inner), names.getValue(name), bind.value) { a, b -> "$name is used as $b but defined as $a" }
            }
        }
        return names.entries.fold(
            env,
        ) { acc, (name, type) -> acc.withValue(name, ValueBinding(unifier.generalize(type, level), ValueKind.VALUE)) }
    }

    private fun functions(
        decl: FunDecl,
        env: TypeEnvironment,
    ): TypeEnvironment {
        val names = decl.bindings.associate { it.name.name to fresh() }
        deeper {
            for (bind in decl.bindings) names.getValue(bind.name.name).level = level
            val inner =
                names.entries.fold(
                    env,
                ) { acc, (name, type) -> acc.withValue(name, ValueBinding(Scheme.mono(type), ValueKind.VALUE)) }
            for (bind in decl.bindings) {
                val arity =
                    bind.clauses
                        .first()
                        .arguments.size
                for (clause in bind.clauses) {
                    if (clause.arguments.size !=
                        arity
                    ) {
                        fail(clause, "all clauses of ${bind.name.name} must take the same number of arguments ($arity)")
                    }
                    val bound = LinkedHashMap<String, Type>()
                    val arguments = clause.arguments.map { pattern(it, env, bound) }
                    val body =
                        bound.entries.fold(
                            inner,
                        ) { acc, (name, type) -> acc.withValue(name, ValueBinding(Scheme.mono(type), ValueKind.VALUE)) }
                    val result = expression(clause.body, body)
                    clause.resultType?.let { t ->
                        expect(result, typeFrom(t, env), clause.body) { a, b -> "${bind.name.name} returns $a, not the annotated $b" }
                    }
                    val type = arguments.foldRight(result) { argument, acc -> FunType(argument, acc) }
                    expect(
                        type,
                        names.getValue(bind.name.name),
                        clause,
                    ) { a, b -> "this clause of ${bind.name.name} has type $a, but the function has type $b" }
                }
            }
        }
        return names.entries.fold(
            env,
        ) { acc, (name, type) -> acc.withValue(name, ValueBinding(unifier.generalize(type, level), ValueKind.VALUE)) }
    }

    private fun datatypes(
        decl: DatatypeDecl,
        env: TypeEnvironment,
    ): TypeEnvironment {
        // All names first, so the datatypes can refer to each other.
        var result =
            decl.bindings.fold(
                env,
            ) { acc, bind -> acc.withType(bind.name.name, TypeBinding.Constructor(bind.name.name, bind.parameters.size)) }
        val constructorArguments = HashMap<String, MutableList<Type>>()
        for (bind in decl.bindings) {
            environment.equality[bind.name.name] = Equality.IF_ARGUMENTS
            val variables = LinkedHashMap<String, TypeVar>()
            val params = bind.parameters.map { p -> unifier.fresh(level + 1).also { variables[p.name] = it } }
            val type = TypeCon(bind.name.name, params)
            for (con in bind.constructors) {
                val argument = con.argument?.let { typeFrom(it, result, variables) }
                argument?.let { constructorArguments.getOrPut(bind.name.name) { mutableListOf() } += it }
                val scheme = Scheme(params, argument?.let { FunType(it, type) } ?: type)
                result = result.withValue(con.name.name, ValueBinding(scheme, ValueKind.CONSTRUCTOR, argument != null))
            }
        }
        // A datatype admits equality if all its constructor arguments do; decide by fixpoint.
        var changed = true
        while (changed) {
            changed = false
            for (bind in decl.bindings) {
                val name = bind.name.name
                if (environment.equality[name] == Equality.NEVER) continue
                if (constructorArguments[name].orEmpty().any { !admitsEquality(it) }) {
                    environment.equality[name] = Equality.NEVER
                    changed = true
                }
            }
        }
        return result
    }

    /** Whether [type] admits equality, treating type variables as equality variables. */
    private fun admitsEquality(type: Type): Boolean =
        when (val t = prune(type)) {
            is TypeVar -> true
            is FunType -> false
            is RecordType -> t.fields.values.all(::admitsEquality)
            is TypeCon ->
                when (environment.equality[t.name] ?: Equality.IF_ARGUMENTS) {
                    Equality.NEVER -> false
                    Equality.ALWAYS -> true
                    Equality.IF_ARGUMENTS -> t.args.all(::admitsEquality)
                }
        }

    /** SML's non-expansive expressions: only these get polymorphic types (the value restriction). */
    private fun isValue(
        expr: Expr,
        env: TypeEnvironment,
    ): Boolean =
        when (expr) {
            is ConstExpr, is IdentExpr, is FnExpr, is SelectorExpr -> true
            is TupleExpr -> expr.elements.all { isValue(it, env) }
            is RecordExpr -> expr.fields.all { isValue(it.value, env) }
            is ListExpr -> expr.elements.all { isValue(it, env) }
            is TypedExpr -> isValue(expr.expr, env)
            is AppExpr -> {
                val f = expr.function
                f is IdentExpr &&
                    f.name.text != "ref" &&
                    lookup(f.name, env)?.kind?.let { it != ValueKind.VALUE } == true &&
                    isValue(expr.argument, env)
            }
            else -> false
        }

    // ---------------------------------------------------------------------------------------------
    // CPN colour sets

    private fun colourSet(
        decl: ColsetDecl,
        env: TypeEnvironment,
    ): TypeEnvironment {
        val name = decl.name.name
        // The standard colour sets (UNIT, BOOL, INT, ...) may be declared again, as in CPN Tools' standard declarations.
        val standard = TypeEnvironment.basis().colourSets[name]
        if (name in env.colourSets && env.colourSets[name] !== standard) fail(decl.name, "colour set $name is already declared")

        fun abbreviation(type: Type) = env.withType(name, TypeBinding.Abbreviation(emptyList(), type)).withColourSet(name, type)

        fun datatype(constructors: List<Pair<String, Type?>>): TypeEnvironment {
            val type = TypeCon(name)
            environment.equality[name] =
                if (constructors.all { (_, arg) -> arg == null || admitsEquality(arg) }) Equality.IF_ARGUMENTS else Equality.NEVER
            return constructors.fold(env.withType(name, TypeBinding.Constructor(name, 0)).withColourSet(name, type)) { acc, (con, arg) ->
                acc.withValue(con, ValueBinding(Scheme.mono(arg?.let { FunType(it, type) } ?: type), ValueKind.CONSTRUCTOR, arg != null))
            }
        }

        fun component(id: LongIdent): Type = env.colourSet(id.text) ?: fail(id, "unknown colour set ${id.text}")

        fun range(
            spec: RangeSpec?,
            bound: Type,
        ) {
            if (spec == null) return
            for (e in listOf(
                spec.low,
                spec.high,
            )) {
                expect(expression(e, env), bound, e) { a, b -> "range bounds of $name must be $b, not $a" }
            }
        }

        return when (val spec: ColsetSpec = decl.spec) {
            is UnitColset -> spec.newName?.let { datatype(listOf(it.name to null)) } ?: abbreviation(UNIT)
            is BoolColset ->
                if (spec.falseName !=
                    null
                ) {
                    datatype(listOf(spec.falseName.name to null, spec.trueName!!.name to null))
                } else {
                    abbreviation(BOOL)
                }
            is IntColset -> abbreviation(INT).also { range(spec.range, INT) }
            is IntInfColset -> abbreviation(INT).also { range(spec.range, INT) }
            is RealColset -> abbreviation(REAL).also { range(spec.range, REAL) }
            is TimeColset -> abbreviation(INT)
            is StringColset ->
                abbreviation(STRING).also {
                    range(spec.characters, STRING)
                    range(spec.length, INT)
                }
            is EnumColset -> datatype(spec.constants.map { it.name to null })
            is IndexColset -> datatype(listOf(spec.constructor.name to INT)).also { range(spec.range, INT) }
            is ProductColset -> abbreviation(tupleOf(spec.components.map(::component)))
            is RecordColset -> abbreviation(RecordType(spec.fields.associate { it.label.name to component(it.colset) }))
            is UnionColset -> datatype(spec.alternatives.map { it.constructor.name to it.colset?.let(::component) })
            is ListColset -> abbreviation(listType(component(spec.element))).also { range(spec.length, INT) }
            is SubsetColset -> {
                val base = component(spec.base)
                spec.predicate?.let { p ->
                    expect(expression(p, env), FunType(base, BOOL), p) { a, b -> "the subset predicate must have type $b, not $a" }
                }
                spec.members?.let { m ->
                    expect(expression(m, env), listType(base), m) { a, b -> "the subset members must have type $b, not $a" }
                }
                abbreviation(base)
            }
            is AliasColset -> abbreviation(component(spec.colset))
        }
    }
}
