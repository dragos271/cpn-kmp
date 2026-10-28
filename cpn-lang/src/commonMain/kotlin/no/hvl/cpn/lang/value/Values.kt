package no.hvl.cpn.lang.value

/*
 * Runtime representation of CPN ML values, shared by colour sets (T2.3) and the evaluator (T2.5):
 *
 * | CPN ML                 | Kotlin                         |
 * |------------------------|--------------------------------|
 * | int                    | Int                            |
 * | intinf, time           | Long (D8: no bignums yet)      |
 * | real                   | [MlReal]                       |
 * | string / char          | String / Char                  |
 * | bool / unit            | Boolean / Unit                 |
 * | tuple, product         | [MlTuple]                      |
 * | record                 | [MlRecord]                     |
 * | constructor value      | [MlCon] (enum, index, union, datatype) |
 * | list                   | List                           |
 * | multiset               | no.hvl.cpn.core.Multiset       |
 */

/**
 * A real. Not a bare Double: on Kotlin/JS `2.0` and `2` are the same number, so `is Int` could not
 * tell an int from a real.
 */
public data class MlReal(
    val value: Double,
) : Comparable<MlReal> {
    override fun compareTo(other: MlReal): Int = value.compareTo(other.value)

    override fun toString(): String = formatReal(value)
}

/** A tuple; colours of product colour sets. Never has one element. */
public data class MlTuple(
    val elements: List<Any?>,
) {
    override fun toString(): String = elements.joinToString(",", "(", ")") { formatValue(it) }
}

/** A record. Equality ignores field order; [fields] keeps the declared order for printing. */
public data class MlRecord(
    val fields: Map<String, Any?>,
) {
    public operator fun get(label: String): Any? = fields[label]

    override fun toString(): String = fields.entries.joinToString(",", "{", "}") { (k, v) -> "$k=${formatValue(v)}" }
}

/**
 * A constructor value: enumeration constants (`red`), index colours (`ph(3)`), union colours
 * (`Data((1,"COL"))`, `Stop`) and values of ML datatypes. [argument] is null for nullary constructors.
 */
public data class MlCon(
    val name: String,
    val argument: Any? = null,
    val hasArgument: Boolean = argument != null,
) {
    override fun toString(): String = formatValue(this)
}

/** CPN Tools / SML notation for a value whose colour set is not known; colour sets refine this (e.g. `bool with (no, yes)`). */
public fun formatValue(value: Any?): String =
    when (value) {
        null -> "null"
        is MlReal -> formatReal(value.value)
        is Int -> formatInt(value.toLong())
        is Long -> formatInt(value)
        is Double -> formatReal(value)
        is String -> formatString(value)
        is Char -> "#" + formatString(value.toString())
        is Boolean -> value.toString()
        is Unit -> "()"
        is MlTuple, is MlRecord -> value.toString()
        is MlCon -> if (!value.hasArgument) value.name else formatConstructed(value.name, formatValue(value.argument))
        is List<*> -> value.joinToString(",", "[", "]") { formatValue(it) }
        else -> value.toString()
    }

/** `Data` applied to an argument: parenthesized once, e.g. `Data(1,"COL")`, `Ack(3)`, `ph(2)`. */
internal fun formatConstructed(
    name: String,
    argument: String,
): String = if (argument.startsWith("(") && argument != "()") "$name$argument" else "$name($argument)"

/** SML writes negative numbers with `~`. */
public fun formatInt(value: Long): String = if (value < 0) "~" + value.toString().removePrefix("-") else value.toString()

/**
 * SML-style reals (`1.5`, `~2.0`, `1.5E10`, `3.0E~7`), identical on every platform. Kotlin's own
 * `Double.toString` differs between JVM and JS (`2.0` vs `2`, `1.0E-7` vs `1e-7`).
 */
public fun formatReal(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "~inf"
    if (value == 0.0) return if (1.0 / value < 0) "~0.0" else "0.0"
    // Whatever the platform prints (`1.5`, `1.0E-7`, `1e-7`, `1.5e+21`), read it as digits and an exponent.
    val raw =
        kotlin.math
            .abs(value)
            .toString()
            .lowercase()
    val mantissa = raw.substringBefore('e')
    val printedExponent = if ('e' in raw) raw.substringAfter('e').removePrefix("+").toInt() else 0
    var digits = mantissa.replace(".", "")
    var integerDigits = if ('.' in mantissa) mantissa.indexOf('.') else mantissa.length
    val leadingZeros = digits.length - digits.trimStart('0').length
    digits = digits.drop(leadingZeros)
    integerDigits -= leadingZeros
    // value = d1.d2d3... x 10^exponent
    val exponent = integerDigits + printedExponent - 1
    val significant = digits.trimEnd('0').ifEmpty { "0" }
    val body =
        when {
            exponent in 0..14 ->
                significant.padEnd(exponent + 1, '0').take(exponent + 1) + "." +
                    significant.drop(exponent + 1).ifEmpty { "0" }
            exponent in -5..-1 -> "0." + "0".repeat(-exponent - 1) + significant
            else -> significant.first() + "." + significant.drop(1).ifEmpty { "0" } + "E" + formatInt(exponent.toLong())
        }
    val negative = value < 0
    return if (negative) "~$body" else body
}

/** A string literal with SML escapes. */
public fun formatString(value: String): String =
    buildString {
        append('"')
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c.code < 32 -> append("\\^").append((c.code + 64).toChar())
                c.code == 127 -> append("\\127")
                else -> append(c)
            }
        }
        append('"')
    }
