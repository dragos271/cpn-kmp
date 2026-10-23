package no.hvl.cpn.core

/**
 * An immutable marking: the multiset of tokens on each place.
 *
 * Places without tokens are not stored, so two markings are equal exactly when every place has the
 * same tokens. The hash is computed once, for use as a state-space key.
 */
public class Marking private constructor(
    // Invariant: no empty multisets.
    private val tokens: Map<Place<*>, Multiset<*>>,
) {
    /** Places that currently hold at least one token. */
    public val markedPlaces: Set<Place<*>> get() = tokens.keys

    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(place: Place<T>): Multiset<T> = (tokens[place] ?: Multiset.empty<T>()) as Multiset<T>

    public fun <T> with(
        place: Place<T>,
        multiset: Multiset<T>,
    ): Marking {
        val result = LinkedHashMap(tokens)
        if (multiset.isEmpty()) result.remove(place) else result[place] = multiset
        return Marking(result)
    }

    /** Applies [changes] in one copy, e.g. all consumed or produced tokens of a step. */
    public fun update(changes: Map<Place<*>, Multiset<*>>): Marking {
        if (changes.isEmpty()) return this
        val result = LinkedHashMap(tokens)
        for ((place, multiset) in changes) {
            if (multiset.isEmpty()) result.remove(place) else result[place] = multiset
        }
        return Marking(result)
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Marking && hash == other.hash && tokens == other.tokens)

    private val hash: Int = tokens.hashCode()

    override fun hashCode(): Int = hash

    /**
     * One `Place: tokens` line per place of [places], in CPN Tools notation and colour-set order.
     * Defaults to the marked places only.
     */
    public fun format(places: List<Place<*>> = tokens.keys.toList()): String =
        places.joinToString("\n") { place -> "${place.name}: ${formatTokens(place)}" }

    private fun <T> formatTokens(place: Place<T>): String = this[place].format(place.colourSet.order, place.colourSet::format)

    override fun toString(): String = format()

    public companion object {
        public val EMPTY: Marking = Marking(emptyMap())

        /** The initial marking M0 of [net]. */
        public fun initial(net: Net): Marking = of(net.pages.flatMap { it.places }.associateWith { it.initialMarking })

        public fun of(tokens: Map<Place<*>, Multiset<*>>): Marking = EMPTY.update(tokens)
    }
}
