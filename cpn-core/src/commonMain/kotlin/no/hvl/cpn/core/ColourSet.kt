package no.hvl.cpn.core

/**
 * The type of the tokens on a place (CPN ML `colset`).
 *
 * This is the engine-facing contract. `cpn-lang` implements it for colour sets declared in
 * inscriptions (T2.3); [ColourSets] has the basic ones needed to build nets directly in Kotlin.
 */
public interface ColourSet<T> {
    public val name: String

    /** True if [value] is a colour of this set. */
    public operator fun contains(value: Any?): Boolean

    /** All colours in colour-set order, or `null` if the set is infinite (e.g. `int`, `string`). */
    public val values: List<T>?

    /** Colour-set order, used for canonical marking output. `null` if the set has no natural order. */
    public val order: Comparator<in T>?

    /** CPN Tools text form of a colour, e.g. `"a"` for strings and `()` for unit. */
    public fun format(value: T): String = value.toString()

    public val isFinite: Boolean get() = values != null
}

/** Built-in colour sets, mirroring the CPN ML standard ones. */
public object ColourSets {
    public val UNIT: ColourSet<Unit> = FiniteColourSet("UNIT", listOf(Unit)) { "()" }
    public val BOOL: ColourSet<Boolean> = FiniteColourSet("BOOL", listOf(false, true))
    public val INT: ColourSet<Int> = IntColourSet("INT", null)
    public val STRING: ColourSet<String> =
        object : ColourSet<String> {
            override val name = "STRING"

            override fun contains(value: Any?) = value is String

            override val values: List<String>? = null
            override val order: Comparator<in String> = naturalOrder()

            override fun format(value: String) = "\"$value\""

            override fun toString() = name
        }

    /** CPN ML `colset NAME = int with low..high`. */
    public fun intRange(
        name: String,
        range: IntRange,
    ): ColourSet<Int> = IntColourSet(name, range)

    /**
     * A finite colour set with explicit colours, ordered as given. Covers CPN ML `with a | b | c`
     * enumerations; [format] controls how colours are printed.
     */
    public fun <T> enumeration(
        name: String,
        values: List<T>,
        format: (T) -> String = { it.toString() },
    ): ColourSet<T> = FiniteColourSet(name, values, format)
}

private class FiniteColourSet<T>(
    override val name: String,
    override val values: List<T>,
    private val formatter: (T) -> String = { it.toString() },
) : ColourSet<T> {
    private val index: Map<T, Int> = values.withIndex().associate { (i, v) -> v to i }

    init {
        require(index.size == values.size) { "Colour set $name has duplicate colours" }
    }

    override fun contains(value: Any?): Boolean = value in index

    override val order: Comparator<in T> = compareBy { index.getValue(it) }

    override fun format(value: T): String = formatter(value)

    override fun toString(): String = name
}

private class IntColourSet(
    override val name: String,
    private val range: IntRange?,
) : ColourSet<Int> {
    override fun contains(value: Any?): Boolean = value is Int && (range == null || value in range)

    override val values: List<Int>? = range?.toList()

    override val order: Comparator<in Int> = naturalOrder()

    override fun toString(): String = name
}
