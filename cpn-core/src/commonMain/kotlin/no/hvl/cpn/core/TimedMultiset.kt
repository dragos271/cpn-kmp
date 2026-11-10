package no.hvl.cpn.core

/**
 * The tokens of a timed place (T3.2): every token has an integer timestamp, and is *available* at
 * model time `now` when its timestamp is at most `now` (Jensen & Kristensen 2009, ch. 10). Written
 * in CPN Tools notation, ``1`x@5+++2`y@0``.
 *
 * Also used for what an arc produces, with timestamps relative to the firing (its delays).
 * Immutable; timestamps are kept sorted per colour.
 */
public class TimedMultiset<T> private constructor(
    // Invariant: no empty lists, each list sorted ascending.
    private val stamps: Map<T, List<Long>>,
) {
    /** The tokens without their timestamps. */
    public val untimed: Multiset<T> by lazy { Multiset.fromCounts(stamps.mapValues { it.value.size }) }

    public val size: Int get() = stamps.values.sumOf { it.size }

    public fun isEmpty(): Boolean = stamps.isEmpty()

    /** Timestamps of the tokens of [colour], ascending. */
    public fun timestamps(colour: T): List<Long> = stamps[colour].orEmpty()

    /** Each distinct (colour, timestamp) with its count. */
    public fun entries(): List<Triple<T, Long, Int>> =
        stamps.flatMap { (colour, times) -> times.groupingBy { it }.eachCount().map { (t, n) -> Triple(colour, t, n) } }

    /** The tokens available at [now]. */
    public fun available(now: Long): Multiset<T> = Multiset.fromCounts(stamps.mapValues { (_, times) -> times.count { it <= now } })

    /**
     * The earliest time at which [demand] is available, or null if there are not enough tokens at all.
     * For each colour needing `n` tokens that is the `n`-th smallest timestamp.
     */
    public fun earliest(demand: Multiset<T>): Long? {
        var time = Long.MIN_VALUE
        for ((colour, n) in demand.entries()) {
            val times = stamps[colour] ?: return null
            if (times.size < n) return null
            time = maxOf(time, times[n - 1])
        }
        return time
    }

    /**
     * Removes [demand] at time [now]: for each colour, the tokens with the largest timestamps that are
     * at most [now]. (Jensen & Kristensen's timed subtraction; provisional until checked against CPN
     * Tools in T4.2. It only matters when several available tokens of one colour have different times.)
     *
     * @throws IllegalArgumentException if [demand] is not available at [now].
     */
    public fun minus(
        demand: Multiset<T>,
        now: Long,
    ): TimedMultiset<T> {
        if (demand.isEmpty()) return this
        val result = LinkedHashMap(stamps)
        for ((colour, n) in demand.entries()) {
            val times = stamps[colour].orEmpty()
            val available = times.indexOfLast { it <= now } + 1
            require(available >= n) { "Cannot remove $n`$colour at time $now from $this: only $available available" }
            val rest = times.subList(0, available - n) + times.subList(available, times.size)
            if (rest.isEmpty()) result.remove(colour) else result[colour] = rest
        }
        return TimedMultiset(result)
    }

    public operator fun plus(other: TimedMultiset<T>): TimedMultiset<T> {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        val result = LinkedHashMap(stamps)
        for ((colour, times) in other.stamps) {
            val mine = result[colour]
            result[colour] = if (mine == null) times else (mine + times).sorted()
        }
        return TimedMultiset(result)
    }

    /** Each token's colour replaced by [f] of it, keeping the timestamps. */
    public fun <R> map(f: (T) -> R): TimedMultiset<R> {
        var result = empty<R>()
        for ((colour, times) in stamps) {
            val mapped = f(colour)
            for (t in times) result += of(mapped, 1, t)
        }
        return result
    }

    /** Every timestamp moved by [delta]: relative delays become times when a transition fires. */
    public fun shifted(delta: Long): TimedMultiset<T> =
        if (delta ==
            0L
        ) {
            this
        } else {
            TimedMultiset(stamps.mapValues { (_, t) -> t.map { it + delta } })
        }

    override fun equals(other: Any?): Boolean = other is TimedMultiset<*> && stamps == other.stamps

    override fun hashCode(): Int = stamps.hashCode()

    /** CPN Tools notation, ``1`x@5+++2`y@0``, colours in [order] and then by time; `empty` if there are no tokens. */
    public fun format(
        order: Comparator<in T>? = null,
        element: (T) -> String = { it.toString() },
    ): String {
        if (stamps.isEmpty()) return "empty"
        val colours = if (order == null) stamps.keys.toList() else stamps.keys.sortedWith(order)
        return colours.joinToString("+++") { colour ->
            stamps
                .getValue(colour)
                .groupingBy {
                    it
                }.eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString("+++") { (t, n) -> "$n`${element(colour)}@$t" }
        }
    }

    override fun toString(): String = format()

    public companion object {
        private val EMPTY = TimedMultiset<Any?>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        public fun <T> empty(): TimedMultiset<T> = EMPTY as TimedMultiset<T>

        /** Every token of [multiset] with timestamp [time]. */
        public fun <T> at(
            multiset: Multiset<T>,
            time: Long,
        ): TimedMultiset<T> =
            if (multiset.isEmpty()) empty() else TimedMultiset(multiset.entries().associate { (colour, n) -> colour to List(n) { time } })

        /** [count] tokens of [colour] at [time]. */
        public fun <T> of(
            colour: T,
            count: Int,
            time: Long,
        ): TimedMultiset<T> = at(Multiset.repeated(colour, count), time)
    }
}
