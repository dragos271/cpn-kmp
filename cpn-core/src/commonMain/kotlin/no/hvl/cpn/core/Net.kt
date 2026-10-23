package no.hvl.cpn.core

/** Kind of a port place on a subpage (CPN Tools `In`, `Out`, `I/O`). */
public enum class PortType { IN, OUT, IN_OUT }

public enum class ArcDirection {
    PLACE_TO_TRANSITION,
    TRANSITION_TO_PLACE,

    /** CPN Tools double arc: consumes and produces the same inscription. */
    BIDIRECTIONAL,
    ;

    public val isInput: Boolean get() = this != TRANSITION_TO_PLACE
    public val isOutput: Boolean get() = this != PLACE_TO_TRANSITION
}

/**
 * A place. [portType] is set for port places of a subpage; places sharing a [fusionSet] name
 * behave as one place across the net (T1.5 merges them).
 *
 * Model elements compare by identity; [id] is unique within a net and used for lookups and I/O.
 */
public class Place<T>(
    public val id: String,
    public val name: String,
    public val colourSet: ColourSet<T>,
    public val initialMarking: Multiset<T> = Multiset.empty(),
    public val portType: PortType? = null,
    public val fusionSet: String? = null,
) {
    public val isPort: Boolean get() = portType != null

    override fun toString(): String = name
}

/**
 * Replaces a transition with an instance of [subpage]. [assignments] maps each socket place
 * (on the page of the substitution transition) to the port place of [subpage] it is glued to.
 */
public class Substitution(
    public val subpage: Page,
    public val assignments: Map<Place<*>, Place<*>>,
)

public class Transition(
    public val id: String,
    public val name: String,
    public val guard: Guard = Guard.TRUE,
    public val substitution: Substitution? = null,
) {
    public val isSubstitution: Boolean get() = substitution != null

    override fun toString(): String = name
}

/**
 * An arc between [place] and [transition]. Arcs of substitution transitions only record the
 * socket connection and have no [inscription].
 */
public class Arc<T>(
    public val id: String,
    public val place: Place<T>,
    public val transition: Transition,
    public val direction: ArcDirection,
    public val inscription: ArcInscription<T>?,
) {
    override fun toString(): String =
        when (direction) {
            ArcDirection.PLACE_TO_TRANSITION -> "$place -> $transition"
            ArcDirection.TRANSITION_TO_PLACE -> "$transition -> $place"
            ArcDirection.BIDIRECTIONAL -> "$place <-> $transition"
        } + (inscription?.let { " : $it" } ?: "")
}

public class Page(
    public val id: String,
    public val name: String,
    public val places: List<Place<*>>,
    public val transitions: List<Transition>,
    public val arcs: List<Arc<*>>,
) {
    public val ports: List<Place<*>> get() = places.filter { it.isPort }

    public fun place(name: String): Place<*> =
        places.singleOrNull { it.name == name } ?: throw NoSuchElementException("No place '$name' on page '${this.name}'")

    /** Typed lookup, e.g. to assign a subpage port: `subpage.place("Out", ColourSets.INT)`. */
    public fun <T> place(
        name: String,
        colourSet: ColourSet<T>,
    ): Place<T> {
        val place = place(name)
        require(place.colourSet == colourSet) { "Place '$name' has colour set ${place.colourSet}, not $colourSet" }
        @Suppress("UNCHECKED_CAST")
        return place as Place<T>
    }

    public fun transition(name: String): Transition =
        transitions.singleOrNull { it.name == name } ?: throw NoSuchElementException("No transition '$name' on page '${this.name}'")

    public fun arcsOf(transition: Transition): List<Arc<*>> = arcs.filter { it.transition === transition }

    public fun inputArcs(transition: Transition): List<Arc<*>> = arcsOf(transition).filter { it.direction.isInput }

    public fun outputArcs(transition: Transition): List<Arc<*>> = arcsOf(transition).filter { it.direction.isOutput }

    override fun toString(): String = name
}

/**
 * A (possibly hierarchical) Coloured Petri Net. Pages that are no substitution transition's
 * subpage are the prime pages, instantiated once each.
 */
public class Net(
    public val name: String,
    public val pages: List<Page>,
    public val variables: List<Variable<*>>,
) {
    public val primePages: List<Page>
        get() {
            val subpages = pages.flatMap { page -> page.transitions.mapNotNull { it.substitution?.subpage } }.toSet()
            return pages.filter { it !in subpages }
        }

    public val isHierarchical: Boolean get() = pages.any { page -> page.transitions.any { it.isSubstitution } }

    /** Places grouped by fusion set name. */
    public val fusionSets: Map<String, List<Place<*>>>
        get() = pages.flatMap { it.places }.filter { it.fusionSet != null }.groupBy { it.fusionSet!! }

    public fun page(name: String): Page =
        pages.singleOrNull { it.name == name } ?: throw NoSuchElementException("No page '$name' in net '${this.name}'")

    public fun variable(name: String): Variable<*> =
        variables.singleOrNull { it.name == name } ?: throw NoSuchElementException("No variable '$name' in net '${this.name}'")

    /** Structural problems of this net; empty if it is well-formed. */
    public fun validate(): List<String> = NetValidator(this).validate()

    override fun toString(): String = name
}

public class InvalidNetException(
    public val problems: List<String>,
) : IllegalArgumentException("Invalid net:\n" + problems.joinToString("\n") { "  - $it" })
