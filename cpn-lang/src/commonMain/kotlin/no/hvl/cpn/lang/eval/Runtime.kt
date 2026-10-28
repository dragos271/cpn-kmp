package no.hvl.cpn.lang.eval

import no.hvl.cpn.core.Multiset
import no.hvl.cpn.lang.ast.SourceText
import no.hvl.cpn.lang.ast.Span
import no.hvl.cpn.lang.value.MlCon
import no.hvl.cpn.lang.value.MlReal
import no.hvl.cpn.lang.value.MlRecord
import no.hvl.cpn.lang.value.MlTuple
import no.hvl.cpn.lang.value.formatValue

/*
 * Runtime values the evaluator adds to those of no.hvl.cpn.lang.value (see the table there):
 * functions ([MlFunction]), references ([MlRef]) and constructors ([Constructor]). Exceptions are
 * [MlCon] values, raised by throwing [MlRaise].
 */

/** A function value: a closure, a constructor or a library function. */
public interface MlFunction {
    public fun apply(argument: Any?): Any?
}

/** A function of a pair, called directly by infix applications such as `a + b` without building the tuple. */
internal interface MlFunction2 : MlFunction {
    fun apply2(
        left: Any?,
        right: Any?,
    ): Any?

    override fun apply(argument: Any?): Any? {
        val pair = argument as MlTuple
        return apply2(pair.elements[0], pair.elements[1])
    }
}

/** `ref v`: compared by identity, as SML references are. */
public class MlRef(
    public var value: Any?,
) {
    override fun toString(): String = "ref(${formatValue(value)})"
}

/** An ML exception in flight: `raise Div`, `raise Fail "x"`. [exception] is an [MlCon]. */
public class MlRaise(
    public val exception: MlCon,
) : RuntimeException("uncaught exception ${formatValue(exception)}")

/** Evaluation failed outside ML's own exceptions (an uncaught exception, or something the type checker should have rejected). */
public class EvaluationException(
    public val span: Span,
    public val reason: String,
    source: SourceText? = null,
) : IllegalArgumentException(if (source == null || span.isNone) reason else "${source.describe(span)}: $reason")

/** Where random choices come from: `discrete`, `ran()`, `random`. Deterministic for a given seed on every platform. */
public interface RandomSource {
    /** Uniform in `0 until bound`. */
    public fun nextLong(bound: Long): Long

    /** Uniform in `[0, 1)`. */
    public fun nextDouble(): Double

    public companion object {
        public fun seeded(seed: Long): RandomSource {
            val random = kotlin.random.Random(seed)
            return object : RandomSource {
                override fun nextLong(bound: Long): Long = random.nextLong(bound)

                override fun nextDouble(): Double = random.nextDouble()
            }
        }
    }
}

internal fun raise(
    name: String,
    argument: Any? = null,
): Nothing = throw MlRaise(if (argument == null) MlCon(name) else MlCon(name, argument, hasArgument = true))

// -------------------------------------------------------------------------------------------------
// Constructors

/**
 * A value constructor in scope: datatype and exception constructors, colour set constants (`red`,
 * `ph`, `Data`), and the built-in `true`, `nil`, `::`, `SOME`, `ref`. Patterns use [destruct] to
 * take values apart; expressions use [apply] (or the constant itself for nullary ones).
 */
internal sealed class Constructor(
    val name: String,
) : MlFunction {
    /** The argument [value] was built from, [NO_MATCH] if it was built by another constructor. Nullary ones return Unit. */
    abstract fun destruct(value: Any?): Any?

    companion object {
        val NO_MATCH = Any()
    }
}

/** A nullary constructor; [value] is what it denotes (`MlCon("red")`, `true`, `emptyList()`, `Unit`). */
internal class ConstantConstructor(
    name: String,
    val value: Any?,
) : Constructor(name) {
    override fun destruct(value: Any?): Any? = if (valuesEqual(value, this.value)) Unit else NO_MATCH

    override fun apply(argument: Any?): Any? = throw IllegalStateException("$name takes no argument")
}

/** A constructor with an argument, building [MlCon]s: `SOME x`, `Data (1, "a")`, `ph 3`, `Fail "x"`. */
internal class DataConstructor(
    name: String,
) : Constructor(name) {
    override fun destruct(value: Any?): Any? = if (value is MlCon && value.name == name && value.hasArgument) value.argument else NO_MATCH

    override fun apply(argument: Any?): Any? = MlCon(name, argument, hasArgument = true)
}

/** `::` on Kotlin lists. */
internal object ConsConstructor : Constructor("::"), MlFunction2 {
    override fun destruct(value: Any?): Any? =
        if (value is List<*> && value.isNotEmpty()) MlTuple(listOf(value.first(), value.subList(1, value.size))) else NO_MATCH

    override fun apply2(
        left: Any?,
        right: Any?,
    ): Any? {
        val tail = right as List<*>
        return ArrayList<Any?>(tail.size + 1).apply {
            add(left)
            addAll(tail)
        }
    }

    override fun apply(argument: Any?): Any? = super<MlFunction2>.apply(argument)
}

/** `ref`: builds and opens [MlRef]s. */
internal object RefConstructor : Constructor("ref") {
    override fun destruct(value: Any?): Any? = if (value is MlRef) value.value else NO_MATCH

    override fun apply(argument: Any?): Any? = MlRef(argument)
}

// -------------------------------------------------------------------------------------------------
// Equality, order and multisets

/**
 * SML `=`. Structural, except that Int and Long (D8: `int` also stands for `intinf` and `time`,
 * which are Longs) compare by value, and references by identity.
 */
internal fun valuesEqual(
    a: Any?,
    b: Any?,
): Boolean =
    when {
        a is Int && b is Long -> a.toLong() == b
        a is Long && b is Int -> a == b.toLong()
        a is MlTuple && b is MlTuple ->
            a.elements.size == b.elements.size &&
                a.elements.indices.all { valuesEqual(a.elements[it], b.elements[it]) }
        a is MlRecord && b is MlRecord -> a.fields.keys == b.fields.keys && a.fields.all { (k, v) -> valuesEqual(v, b.fields[k]) }
        a is MlCon && b is MlCon -> a.name == b.name && a.hasArgument == b.hasArgument && valuesEqual(a.argument, b.argument)
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { valuesEqual(a[it], b[it]) }
        else -> a == b
    }

/** SML's `<` on int, real, string and char. */
internal fun compareValues(
    a: Any?,
    b: Any?,
): Int =
    when {
        a is Int && b is Int -> a.compareTo(b)
        (a is Int || a is Long) && (b is Int || b is Long) -> (a as Number).toLong().compareTo((b as Number).toLong())
        a is MlReal && b is MlReal -> a.compareTo(b)
        a is String && b is String -> a.compareTo(b)
        a is Char && b is Char -> a.compareTo(b)
        else -> throw IllegalArgumentException("cannot compare ${formatValue(a)} and ${formatValue(b)}")
    }

/** A multiset, or a single colour as shorthand for one token of it (CPN ML arc inscriptions). */
@Suppress("UNCHECKED_CAST")
internal fun toMultiset(value: Any?): Multiset<Any?> = value as? Multiset<Any?> ?: Multiset.repeated(value, 1)
