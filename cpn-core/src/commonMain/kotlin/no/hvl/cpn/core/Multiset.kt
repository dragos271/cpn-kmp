package no.hvl.cpn.core

/**
 * An immutable finite multiset (bag), the token container of a CPN place.
 *
 * Mapping to CPN ML:
 *
 * | CPN ML          | Kotlin                     |
 * |-----------------|----------------------------|
 * | `empty`         | [Multiset.empty]           |
 * | `` n`x ``       | [Multiset.repeated] `(x, n)` |
 * | `a ++ b`        | `a + b`                    |
 * | `a -- b`        | `a - b`                    |
 * | `n ** a`        | `n * a`                    |
 * | `a <<= b`       | `a subsetOf b`             |
 * | `size a`        | [size]                     |
 * | `cf (x, a)`     | [count]                    |
 *
 * Equality and [hashCode] are structural and independent of insertion order. The hash is computed
 * once, so multisets can be used as keys when hash-consing markings in the state space.
 */
public class Multiset<T> private constructor(
    // Invariant: every count is > 0, so equal multisets have equal maps.
    private val counts: Map<T, Int>,
) : Iterable<T> {
    /** Total number of elements, counting multiplicities. */
    public val size: Int = counts.values.fold(0) { acc, n -> addCounts(acc, n) }

    /** The distinct elements, in insertion order. */
    public val distinct: Set<T> get() = counts.keys

    public fun isEmpty(): Boolean = counts.isEmpty()

    /** Multiplicity of [element]; 0 if absent. CPN ML `cf`. */
    public fun count(element: T): Int = counts[element] ?: 0

    public operator fun contains(element: T): Boolean = element in counts

    /** Each distinct element with its multiplicity, in insertion order. */
    public fun entries(): Set<Map.Entry<T, Int>> = counts.entries

    /** CPN ML `++`. */
    public operator fun plus(other: Multiset<T>): Multiset<T> {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        val result = LinkedHashMap(counts)
        for ((element, n) in other.counts) {
            result[element] = addCounts(result[element] ?: 0, n)
        }
        return Multiset(result)
    }

    public operator fun plus(element: T): Multiset<T> = this + repeated(element, 1)

    /**
     * CPN ML `--`. Like CPN Tools, this fails unless [other] is a sub-multiset of this one.
     *
     * @throws IllegalArgumentException if `other subsetOf this` does not hold.
     */
    public operator fun minus(other: Multiset<T>): Multiset<T> {
        require(other subsetOf this) { "Cannot subtract $other from $this: not a sub-multiset" }
        if (other.isEmpty()) return this
        val result = LinkedHashMap(counts)
        for ((element, n) in other.counts) {
            val remaining = result.getValue(element) - n
            if (remaining == 0) result.remove(element) else result[element] = remaining
        }
        return Multiset(result)
    }

    /** CPN ML `n ** a`: every multiplicity multiplied by [factor]. */
    public operator fun times(factor: Int): Multiset<T> {
        require(factor >= 0) { "Scalar must be non-negative, was $factor" }
        return when (factor) {
            0 -> empty()
            1 -> this
            else -> Multiset(counts.mapValuesTo(LinkedHashMap()) { (_, n) -> multiplyCount(n, factor) })
        }
    }

    /** CPN ML `<<=`: every element occurs in [other] at least as often as here. */
    public infix fun subsetOf(other: Multiset<T>): Boolean = size <= other.size && counts.all { (element, n) -> other.count(element) >= n }

    public fun filter(predicate: (T) -> Boolean): Multiset<T> = Multiset(counts.filterKeys(predicate))

    /** Applies [transform] to every element; elements that map to the same value have their counts summed. */
    public fun <R> map(transform: (T) -> R): Multiset<R> {
        val result = LinkedHashMap<R, Int>()
        for ((element, n) in counts) {
            val key = transform(element)
            result[key] = addCounts(result[key] ?: 0, n)
        }
        return Multiset(result)
    }

    /** Yields each element as many times as its multiplicity, grouped, in insertion order. */
    override fun iterator(): Iterator<T> =
        iterator {
            for ((element, n) in counts) repeat(n) { yield(element) }
        }

    /**
     * Formats this multiset in CPN Tools notation, e.g. ``1`"a"++2`"b"``, or `empty`.
     *
     * Pass [order] to get a canonical form (CPN Tools sorts by colour-set order); otherwise
     * insertion order is used.
     */
    public fun format(
        order: Comparator<in T>? = null,
        element: (T) -> String = { it.toString() },
    ): String {
        if (isEmpty()) return "empty"
        val entries = if (order == null) counts.entries.toList() else counts.entries.sortedWith(compareBy(order) { it.key })
        return entries.joinToString("++") { (e, n) -> "$n`${element(e)}" }
    }

    override fun toString(): String = format()

    override fun equals(other: Any?): Boolean = this === other || (other is Multiset<*> && hash == other.hash && counts == other.counts)

    private val hash: Int = counts.hashCode()

    override fun hashCode(): Int = hash

    public companion object {
        private val EMPTY = Multiset<Any?>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        public fun <T> empty(): Multiset<T> = EMPTY as Multiset<T>

        /** CPN ML `` count`element ``. */
        public fun <T> repeated(
            element: T,
            count: Int,
        ): Multiset<T> {
            require(count >= 0) { "Count must be non-negative, was $count" }
            return if (count == 0) empty() else Multiset(linkedMapOf(element to count))
        }

        /** One occurrence per argument; repeated arguments add up. */
        public fun <T> of(vararg elements: T): Multiset<T> = elements.asIterable().toMultiset()

        /** Builds a multiset from explicit multiplicities; zero counts are dropped. */
        public fun <T> fromCounts(counts: Map<T, Int>): Multiset<T> {
            val result = LinkedHashMap<T, Int>()
            for ((element, n) in counts) {
                require(n >= 0) { "Count of $element must be non-negative, was $n" }
                if (n > 0) result[element] = n
            }
            return Multiset(result)
        }

        internal fun <T> fromIterable(elements: Iterable<T>): Multiset<T> {
            val result = LinkedHashMap<T, Int>()
            for (element in elements) result[element] = addCounts(result[element] ?: 0, 1)
            return Multiset(result)
        }
    }
}

/** CPN ML `n ** a`. */
public operator fun <T> Int.times(multiset: Multiset<T>): Multiset<T> = multiset * this

public fun <T> Iterable<T>.toMultiset(): Multiset<T> = Multiset.fromIterable(this)

private fun addCounts(
    a: Int,
    b: Int,
): Int {
    val sum = a.toLong() + b
    check(sum <= Int.MAX_VALUE) { "Multiset count overflow: $a + $b" }
    return sum.toInt()
}

private fun multiplyCount(
    n: Int,
    factor: Int,
): Int {
    val product = n.toLong() * factor
    check(product <= Int.MAX_VALUE) { "Multiset count overflow: $n * $factor" }
    return product.toInt()
}
