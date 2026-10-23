package no.hvl.cpn.core

/**
 * One use of a page: prime pages have one instance each, and every substitution transition adds an
 * instance of its subpage. Instances of a page are numbered from 1 in depth-first order, as in CPN Tools.
 */
public class PageInstance(
    public val page: Page,
    public val number: Int,
    public val parent: PageInstance?,
    /** The substitution transition (on [parent]'s page) that created this instance; null for prime pages. */
    public val substitution: Transition?,
) {
    /** CPN Tools style, e.g. `Sender (1)`. */
    override fun toString(): String = "${page.name} ($number)"
}

/**
 * The result of [flatten]: an equivalent net with a single page, no substitution transitions and no
 * fusion sets, plus the mapping from (instance, original element) to the flat element.
 */
public class FlatNet internal constructor(
    public val net: Net,
    public val original: Net,
    public val instances: List<PageInstance>,
    private val places: Map<Pair<PageInstance, Place<*>>, Place<*>>,
    private val transitions: Map<Pair<PageInstance, Transition>, Transition>,
) {
    public fun instancesOf(page: Page): List<PageInstance> = instances.filter { it.page === page }

    /** The flat place that [place] of [instance] became; glued ports and fused places share one. */
    @Suppress("UNCHECKED_CAST")
    public fun <T> place(
        instance: PageInstance,
        place: Place<T>,
    ): Place<T> = places[instance to place] as Place<T>? ?: throw NoSuchElementException("No place '${place.name}' in $instance")

    public fun transition(
        instance: PageInstance,
        transition: Transition,
    ): Transition = transitions[instance to transition] ?: throw NoSuchElementException("No transition '${transition.name}' in $instance")

    /** Every (instance, original place) that maps to [flatPlace]. */
    public fun originsOf(flatPlace: Place<*>): List<Pair<PageInstance, Place<*>>> = places.filterValues { it === flatPlace }.keys.toList()
}

/**
 * Flattens a hierarchical net (Jensen & Kristensen 2009, ch. 5):
 *
 * - each page instance contributes its places and ordinary transitions, named `Page'Name N`
 *   (CPN Tools notation, N = instance number);
 * - a port place of a subpage instance is the same place as its socket in the parent instance;
 * - all places of a fusion set are one place;
 * - arcs of substitution transitions are dropped.
 *
 * A glued or fused place is named after, and takes the initial marking of, its first member in
 * depth-first order, i.e. the outermost socket.
 */
public fun Net.flatten(): FlatNet = Flattener(this).flatten()

private class Flattener(
    private val net: Net,
) {
    private val instances = mutableListOf<PageInstance>()
    private val instanceCount = mutableMapOf<Page, Int>()
    private val placeMap = LinkedHashMap<Pair<PageInstance, Place<*>>, Place<*>>()
    private val transitionMap = LinkedHashMap<Pair<PageInstance, Transition>, Transition>()
    private val fusionPlaces = mutableMapOf<String, Place<*>>()
    private val flatPlaces = mutableListOf<Place<*>>()
    private val flatTransitions = mutableListOf<Transition>()
    private val flatArcs = mutableListOf<Arc<*>>()

    fun flatten(): FlatNet {
        val problems = net.validate()
        if (problems.isNotEmpty()) throw InvalidNetException(problems)
        for (page in net.primePages) instantiate(page, parent = null, substitution = null)
        val page = Page("flat", net.name, flatPlaces.toList(), flatTransitions.toList(), flatArcs.toList())
        val flat = Net(net.name, listOf(page), net.variables)
        return FlatNet(flat, net, instances.toList(), placeMap, transitionMap)
    }

    private fun instantiate(
        page: Page,
        parent: PageInstance?,
        substitution: Transition?,
    ) {
        val number = (instanceCount[page] ?: 0) + 1
        instanceCount[page] = number
        val instance = PageInstance(page, number, parent, substitution)
        instances += instance

        val socketOf =
            substitution
                ?.substitution
                ?.assignments
                ?.entries
                ?.associate { (socket, port) -> port to socket }
                .orEmpty()
        for (place in page.places) {
            val socket = socketOf[place]
            placeMap[instance to place] =
                when {
                    socket != null -> gluedToSocket(instance, place, placeMap.getValue(parent!! to socket))
                    place.fusionSet != null -> fusionPlaces.getOrPut(place.fusionSet) { newPlace(instance, place) }
                    else -> newPlace(instance, place)
                }
        }

        for (transition in page.transitions) {
            if (transition.isSubstitution) continue
            val flat = Transition("${instance.qualifiedId}/${transition.id}", qualifiedName(instance, transition.name), transition.guard)
            transitionMap[instance to transition] = flat
            flatTransitions += flat
            for (arc in page.arcsOf(transition)) {
                @Suppress("UNCHECKED_CAST")
                val typed = arc as Arc<Any?>

                @Suppress("UNCHECKED_CAST")
                val place = placeMap.getValue(instance to arc.place) as Place<Any?>
                flatArcs += Arc("${instance.qualifiedId}/${arc.id}", place, flat, arc.direction, typed.inscription)
            }
        }

        for (transition in page.transitions) {
            val subpage = transition.substitution?.subpage ?: continue
            instantiate(subpage, instance, transition)
        }
    }

    /** A port glued to a socket; if the port is also fused, the fusion set must not already be elsewhere. */
    private fun gluedToSocket(
        instance: PageInstance,
        port: Place<*>,
        socketPlace: Place<*>,
    ): Place<*> {
        val fusionSet = port.fusionSet ?: return socketPlace
        val existing = fusionPlaces.getOrPut(fusionSet) { socketPlace }
        require(existing === socketPlace) {
            "Port '${port.name}' of $instance is in fusion set '$fusionSet' and glued to a different socket; this cannot be flattened"
        }
        return socketPlace
    }

    private fun newPlace(
        instance: PageInstance,
        place: Place<*>,
    ): Place<*> {
        @Suppress("UNCHECKED_CAST")
        val typed = place as Place<Any?>
        val flat = Place("${instance.qualifiedId}/${place.id}", qualifiedName(instance, place.name), typed.colourSet, typed.initialMarking)
        flatPlaces += flat
        return flat
    }

    private fun qualifiedName(
        instance: PageInstance,
        name: String,
    ): String = "${instance.page.name}'$name ${instance.number}"

    private val PageInstance.qualifiedId: String get() = "${page.id}#$number"
}
