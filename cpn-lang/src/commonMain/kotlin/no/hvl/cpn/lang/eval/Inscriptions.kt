package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.ArcInscription
import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.Delay
import no.hvl.cpn.core.Guard
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.PatternInscription
import no.hvl.cpn.core.TimedMultiset
import no.hvl.cpn.core.Variable
import no.hvl.cpn.core.matching
import no.hvl.cpn.lang.ast.AppExpr
import no.hvl.cpn.lang.ast.ConstExpr
import no.hvl.cpn.lang.ast.Constant
import no.hvl.cpn.lang.ast.Expr
import no.hvl.cpn.lang.ast.IdentExpr
import no.hvl.cpn.lang.ast.InfixExpr
import no.hvl.cpn.lang.ast.ListExpr
import no.hvl.cpn.lang.ast.RecordExpr
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.TupleExpr
import no.hvl.cpn.lang.ast.TypedExpr
import no.hvl.cpn.lang.colour.AliasColourSet
import no.hvl.cpn.lang.colour.CpnColourSet
import no.hvl.cpn.lang.colour.ListColourSet
import no.hvl.cpn.lang.colour.LongColourSet
import no.hvl.cpn.lang.colour.ProductColourSet
import no.hvl.cpn.lang.colour.RecordColourSet
import no.hvl.cpn.lang.colour.SubsetColourSet
import no.hvl.cpn.lang.colour.TimedColourSet
import no.hvl.cpn.lang.colour.UnionColourSet
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlRecord
import no.hvl.cpn.lang.value.MlTuple

/**
 * Turns parsed CPN ML inscriptions into the engine's [ArcInscription]s and [Guard]s, evaluated by
 * [interpreter] after its declarations have run. One [Variable] per declared `var`, shared by every
 * inscription, so the engine binds each variable once per transition.
 *
 * Arc inscriptions that are patterns (``2`(n,d)``, `Data(n, p)`, `x`) become [PatternInscription]s,
 * so the binding search binds their variables from the tokens on input places, as CPN Tools does.
 */
public class CpnMlInscriptions(
    public val interpreter: Interpreter,
) {
    public val variables: Map<String, Variable<Any?>> =
        interpreter.variables.mapValues { (name, colourSet) -> Variable(name, colourSet) }

    /** An arc inscription for a place holding [colourSet]. */
    public fun arc(
        expr: Expr,
        colourSet: CpnColourSet,
        source: SourceText? = null,
    ): ArcInscription<Any?> {
        val used = interpreter.variablesOf(expr).map { variables.getValue(it) }.toSet()
        val pattern = pattern(expr)
        return if (pattern != null) {
            CpnMlPattern(expr, pattern.second, pattern.first, used, colourSet, source)
        } else {
            CpnMlArc(expr, used, colourSet, source)
        }
    }

    /**
     * The summands of an arc inscription that is a sum of patterns, ``1`(n1,1) ++ 1`(n2,2)``, so each
     * can become an arc of its own and bind its variables from a token; `[expr]` otherwise. Arcs
     * between the same place and transition add up, so this changes nothing but the binding search.
     */
    public fun summands(expr: Expr): List<Expr> {
        fun flatten(e: Expr): List<Expr> =
            if (e is InfixExpr &&
                e.operator.text == "++" &&
                e.operator.qualifiers.isEmpty()
            ) {
                flatten(e.left) + flatten(e.right)
            } else {
                listOf(e)
            }
        val parts = flatten(expr)
        return if (parts.size > 1 && parts.all { pattern(it) != null }) parts else listOf(expr)
    }

    /** A guard: a boolean, or a list of booleans that must all hold. */
    public fun guard(
        expr: Expr,
        source: SourceText? = null,
    ): Guard =
        object : Guard {
            override val variables: Set<Variable<*>> =
                interpreter
                    .variablesOf(
                        expr,
                    ).map { this@CpnMlInscriptions.variables.getValue(it) }
                    .toSet()

            override fun evaluate(binding: Binding): Boolean =
                when (val value = interpreter.evaluate(expr, values(variables, binding), source)) {
                    is Boolean -> value
                    else -> (value as List<*>).all { it as Boolean }
                }

            override fun toString(): String = source?.text ?: expr.toString()
        }

    /** A closed multiset, e.g. an initial marking, conformed to [colourSet]. */
    public fun multiset(
        expr: Expr,
        colourSet: CpnColourSet,
        source: SourceText? = null,
    ): Multiset<Any?> = tokens(interpreter.evaluate(expr, emptyMap(), source), colourSet)

    /** The initial marking of a timed place: tokens at time 0, or later with `@+` (``1`x@+5``). */
    public fun timedMultiset(
        expr: Expr,
        colourSet: CpnColourSet,
        source: SourceText? = null,
    ): TimedMultiset<Any?> = timedTokens(interpreter.evaluate(expr, emptyMap(), source), colourSet)

    /** A transition's time region `@+expr` given as [expr] (the expression after `@+`). */
    public fun delay(
        expr: Expr,
        source: SourceText? = null,
    ): Delay =
        object : Delay {
            override val variables: Set<Variable<*>> =
                interpreter.variablesOf(expr).map { this@CpnMlInscriptions.variables.getValue(it) }.toSet()

            override fun evaluate(binding: Binding): Long =
                (interpreter.evaluate(expr, values(variables, binding), source) as Number).toLong().also {
                    if (it < 0) throw EvaluationException(expr.span, "a delay must not be negative, was $it", source)
                }

            override fun toString(): String = "@+" + (source?.text ?: expr.toString())
        }

    /** For [no.hvl.cpn.core.Net.clock]: the model time `time()` returns. */
    public fun setTime(time: Long) {
        interpreter.now = time
    }

    private open inner class CpnMlArc(
        val expr: Expr,
        override val variables: Set<Variable<*>>,
        val colourSet: CpnColourSet,
        val source: SourceText?,
    ) : ArcInscription<Any?> {
        override fun evaluate(binding: Binding): Multiset<Any?> =
            tokens(interpreter.evaluate(expr, values(variables, binding), source), colourSet)

        override fun evaluateTimed(binding: Binding): TimedMultiset<Any?> =
            timedTokens(interpreter.evaluate(expr, values(variables, binding), source), colourSet)

        override fun toString(): String = source?.text ?: expr.toString()
    }

    private inner class CpnMlPattern(
        expr: Expr,
        val shape: Expr,
        override val count: Int,
        variables: Set<Variable<*>>,
        colourSet: CpnColourSet,
        source: SourceText?,
    ) : CpnMlArc(expr, variables, colourSet, source),
        PatternInscription<Any?> {
        override fun match(
            token: Any?,
            binding: Binding,
        ): Binding? = matchShape(shape, token, binding)
    }

    // ---------------------------------------------------------------------------------------------
    // Patterns

    /** `(count, shape)` if [expr] is ``count`shape`` or a bare shape; null if it is not a pattern. */
    private fun pattern(expr: Expr): Pair<Int, Expr>? {
        val (count, shape) =
            if (expr is InfixExpr && expr.operator.text == "`" && expr.operator.qualifiers.isEmpty()) {
                val n = ((expr.left as? ConstExpr)?.constant as? Constant.IntConst)?.value ?: return null
                if (n !in 1..Int.MAX_VALUE) return null
                n.toInt() to expr.right
            } else {
                1 to expr
            }
        return if (isShape(shape)) count to shape else null
    }

    /** Variables, constants, constructors, tuples, records and lists of those, and `x::xs`. */
    private fun isShape(e: Expr): Boolean =
        when (e) {
            is InfixExpr -> isCons(e) && isShape(e.left) && isShape(e.right)
            is ConstExpr -> e.constant !is Constant.RealConst
            is IdentExpr -> e.name.qualifiers.isEmpty() && (e.name.name in variables || interpreter.isConstructor(e.name.name))
            is TupleExpr -> e.elements.all(::isShape)
            is RecordExpr -> e.fields.all { isShape(it.value) }
            is ListExpr -> e.elements.all(::isShape)
            is AppExpr ->
                e.function.let { it is IdentExpr && it.name.qualifiers.isEmpty() && interpreter.isConstructor(it.name.name) } &&
                    isShape(e.argument)
            is TypedExpr -> isShape(e.expr)
            else -> false
        }

    /** Extends [binding] so that [shape] denotes [token], or null. Constants and constructors are compared by value. */
    private fun matchShape(
        shape: Expr,
        token: Any?,
        binding: Binding,
    ): Binding? =
        when (shape) {
            is IdentExpr -> {
                val variable = variables[shape.name.name]
                if (variable != null) {
                    binding.matching(variable, conform(token, variable.colourSet as CpnColourSet))
                } else if (valuesEqual(interpreter.evaluate(shape), token)) {
                    binding
                } else {
                    null
                }
            }
            is ConstExpr -> if (valuesEqual(interpreter.evaluate(shape), token)) binding else null
            is TupleExpr ->
                if (shape.elements.isEmpty()) {
                    if (token == Unit) binding else null
                } else {
                    val tuple = token as? MlTuple
                    if (tuple == null || tuple.elements.size != shape.elements.size) {
                        null
                    } else {
                        shape.elements.indices.fold(
                            binding as Binding?,
                        ) { acc, i -> acc?.let { matchShape(shape.elements[i], tuple.elements[i], it) } }
                    }
                }
            is RecordExpr ->
                shape.fields.fold(binding as Binding?) { acc, field ->
                    val value =
                        when (token) {
                            is MlRecord -> if (field.label.name in token.fields) token[field.label.name] else return null
                            is MlTuple -> token.elements.getOrNull((field.label.name.toIntOrNull() ?: return null) - 1) ?: return null
                            else -> return null
                        }
                    acc?.let { matchShape(field.value, value, it) }
                }
            is ListExpr -> {
                val list = token as? List<*>
                if (list == null || list.size != shape.elements.size) {
                    null
                } else {
                    shape.elements.indices.fold(binding as Binding?) { acc, i -> acc?.let { matchShape(shape.elements[i], list[i], it) } }
                }
            }
            is AppExpr -> {
                val name = (shape.function as IdentExpr).name.name
                if (token is MlCon && token.name == name && token.hasArgument) matchShape(shape.argument, token.argument, binding) else null
            }
            is InfixExpr -> {
                // x::xs on a list token: the head and the tail.
                val list = token as? List<*>
                if (!isCons(shape) || list.isNullOrEmpty()) {
                    null
                } else {
                    matchShape(shape.left, list.first(), binding)?.let { matchShape(shape.right, list.subList(1, list.size), it) }
                }
            }
            is TypedExpr -> matchShape(shape.expr, token, binding)
            else -> null
        }

    private fun isCons(e: InfixExpr) = e.operator.text == "::" && e.operator.qualifiers.isEmpty()

    /**
     * The tokens an inscription's value puts on a place of [colourSet]. A list is one token if the place
     * holds such lists (a list colour set), and otherwise a multiset of its elements, as the type
     * checker decided (in CPN Tools `'a ms` is `'a list`).
     */
    private fun tokens(
        value: Any?,
        colourSet: CpnColourSet,
    ): Multiset<Any?> =
        if (value is List<*> && conform(value, colourSet) in colourSet) {
            Multiset.repeated(conform(value, colourSet), 1)
        } else {
            conform(toMultiset(value), colourSet)
        }

    private fun timedTokens(
        value: Any?,
        colourSet: CpnColourSet,
    ): TimedMultiset<Any?> =
        if (value is List<*> && conform(value, colourSet) in colourSet) {
            TimedMultiset.of(conform(value, colourSet), 1, 0)
        } else {
            toTimed(value).map { conform(it, colourSet) }
        }

    private fun values(
        variables: Set<Variable<*>>,
        binding: Binding,
    ): Map<String, Any?> {
        val values = HashMap<String, Any?>(variables.size * 2)
        for (variable in variables) {
            if (binding.isBound(variable)) values[variable.name] = binding[variable]
        }
        return values
    }
}

/**
 * [multiset] with its colours in the representation [colourSet] uses. Evaluation gives Ints for
 * `int` (D8); places of `intinf` and `time` colour sets hold Longs.
 */
public fun conform(
    multiset: Multiset<Any?>,
    colourSet: CpnColourSet,
): Multiset<Any?> = if (multiset.distinct.all { it in colourSet }) multiset else multiset.map { conform(it, colourSet) }

/** [value] in the representation [colourSet] uses (see the [Multiset] overload); unchanged if it already fits or cannot. */
public fun conform(
    value: Any?,
    colourSet: CpnColourSet,
): Any? {
    if (value in colourSet) return value
    return when (colourSet) {
        is LongColourSet -> if (value is Int) value.toLong() else value
        is TimedColourSet -> conform(value, colourSet.untimed)
        is AliasColourSet -> conform(value, colourSet.target)
        is SubsetColourSet -> conform(value, colourSet.base)
        is ProductColourSet ->
            if (value is MlTuple && value.elements.size == colourSet.components.size) {
                MlTuple(value.elements.zip(colourSet.components) { v, cs -> conform(v, cs) })
            } else {
                value
            }
        is RecordColourSet ->
            if (value is MlRecord) {
                MlRecord(
                    LinkedHashMap<String, Any?>().apply { for ((label, cs) in colourSet.fields) put(label, conform(value[label], cs)) },
                )
            } else {
                value
            }
        is ListColourSet -> if (value is List<*>) value.map { conform(it, colourSet.element) } else value
        is UnionColourSet ->
            if (value is MlCon && value.hasArgument) {
                val argument = colourSet.alternatives.firstOrNull { it.first == value.name }?.second
                if (argument == null) value else MlCon(value.name, conform(value.argument, argument), hasArgument = true)
            } else {
                value
            }
        else -> value
    }
}
