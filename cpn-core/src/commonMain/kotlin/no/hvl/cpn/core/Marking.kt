package no.hvl.cpn.core

/**
 * An immutable marking: the tokens on each place, and for timed nets the model time (T3.2).
 *
 * Untimed places hold a [Multiset]; timed places a [TimedMultiset], whose untimed view [get]
 * returns, so code that ignores time keeps working. Places without tokens are not stored, so two
 * markings are equal exactly when every place has the same tokens (with the same timestamps) and the
 * times are equal. The hash is computed once, for use as a state-space key.
 */
public class Marking private constructor(
    // Invariant: no empty token collections. Values are Multiset or TimedMultiset.
    private val tokens: Map<Place<*>, Any>,
    /** The model time; 0 for untimed nets. */
    public val time: Long = 0,
) {
    /** Places that currently hold at least one token. */
    public val markedPlaces: Set<Place<*>> get() = tokens.keys

    /** The tokens on [place]; for a timed place, without their timestamps. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(place: Place<T>): Multiset<T> =
        when (val t = tokens[place]) {
            null -> Multiset.empty()
            is TimedMultiset<*> -> (t as TimedMultiset<T>).untimed
            else -> t as Multiset<T>
        }

    /** The tokens on a timed [place] with their timestamps (untimed tokens count as time 0). */
    @Suppress("UNCHECKED_CAST")
    public fun <T> timed(place: Place<T>): TimedMultiset<T> =
        when (val t = tokens[place]) {
            null -> TimedMultiset.empty()
            is TimedMultiset<*> -> t as TimedMultiset<T>
            else -> TimedMultiset.at(t as Multiset<T>, 0)
        }

    public fun <T> with(
        place: Place<T>,
        multiset: Multiset<T>,
    ): Marking = update(mapOf(place to multiset))

    /** Applies [changes] in one copy, e.g. all consumed or produced tokens of a step. Values are Multisets or TimedMultisets. */
    public fun update(changes: Map<Place<*>, Any>): Marking {
        if (changes.isEmpty()) return this
        val result = LinkedHashMap(tokens)
        for ((place, value) in changes) {
            val stored: Any =
                when {
                    value is Multiset<*> && place.timed -> TimedMultiset.at(value, 0)
                    else -> value
                }
            val empty = (stored as? Multiset<*>)?.isEmpty() ?: (stored as TimedMultiset<*>).isEmpty()
            if (empty) result.remove(place) else result[place] = stored
        }
        return Marking(result, time)
    }

    /** The same tokens at model time [time]. */
    public fun at(time: Long): Marking = if (time == this.time) this else Marking(tokens, time)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Marking && hash == other.hash && time == other.time && tokens == other.tokens)

    private val hash: Int = tokens.hashCode() * 31 + time.hashCode()

    override fun hashCode(): Int = hash

    /**
     * One `Place: tokens` line per place of [places], in CPN Tools notation and colour-set order.
     * Defaults to the marked places only.
     */
    public fun format(places: List<Place<*>> = tokens.keys.toList()): String =
        places.joinToString("\n") { place -> "${place.name}: ${tokensOf(place)}" }

    /**
     * The tokens on [place] in CPN Tools notation and colour-set order, e.g. ``1`1++2`3`` or `empty`;
     * timed places with timestamps, ``1`1@0+++2`3@5``.
     */
    public fun <T> tokensOf(place: Place<T>): String =
        if (place.timed) {
            timed(place).format(place.colourSet.order, place.colourSet::format)
        } else {
            this[place].format(place.colourSet.order, place.colourSet::format)
        }

    override fun toString(): String = format()

    public companion object {
        public val EMPTY: Marking = Marking(emptyMap())

        /** The initial marking M0 of [net], at time 0. */
        public fun initial(net: Net): Marking =
            of(net.pages.flatMap { it.places }.associateWith { if (it.timed) it.initialTimedMarking else it.initialMarking })

        public fun of(tokens: Map<Place<*>, Any>): Marking = EMPTY.update(tokens)
    }
}
