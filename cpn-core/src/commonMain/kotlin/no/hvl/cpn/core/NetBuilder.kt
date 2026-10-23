package no.hvl.cpn.core

@DslMarker
internal annotation class NetDsl

/**
 * Builds and validates a net from Kotlin:
 *
 * ```
 * val net = net("Buffer") {
 *     val n = variable("n", ColourSets.INT)
 *     page("Main") {
 *         val count = place("Count", ColourSets.INT, Multiset.of(0))
 *         val buffer = place("Buffer", ColourSets.INT)
 *         transition("Produce", Guard.of(n) { it[n] < 3 }) {
 *             input(count, n)
 *             output(count, n) { b -> Multiset.of(b[n] + 1) }
 *             output(buffer, n)
 *         }
 *     }
 * }
 * ```
 *
 * Ids are derived from names (`Page`, `Page.Place`, `Page.arc3`), so names must be unique per page.
 *
 * @throws InvalidNetException if the net is not well-formed.
 */
public fun net(
    name: String,
    block: NetBuilder.() -> Unit,
): Net {
    val net = NetBuilder(name).apply(block).build()
    val problems = net.validate()
    if (problems.isNotEmpty()) throw InvalidNetException(problems)
    return net
}

@NetDsl
public class NetBuilder internal constructor(
    private val name: String,
) {
    private val pages = mutableListOf<Page>()
    private val variables = mutableListOf<Variable<*>>()

    public fun <T> variable(
        name: String,
        colourSet: ColourSet<T>,
    ): Variable<T> = Variable(name, colourSet).also { variables += it }

    /** Adds a page. Define subpages before the pages whose substitution transitions use them. */
    public fun page(
        name: String,
        block: PageBuilder.() -> Unit,
    ): Page = PageBuilder(name).apply(block).build().also { pages += it }

    internal fun build(): Net = Net(name, pages.toList(), variables.toList())
}

@NetDsl
public class PageBuilder internal constructor(
    private val name: String,
) {
    private val places = mutableListOf<Place<*>>()
    private val transitions = mutableListOf<Transition>()
    private val arcs = mutableListOf<Arc<*>>()

    public fun <T> place(
        name: String,
        colourSet: ColourSet<T>,
        initialMarking: Multiset<T> = Multiset.empty(),
        fusionSet: String? = null,
    ): Place<T> = Place("${this.name}.$name", name, colourSet, initialMarking, fusionSet = fusionSet).also { places += it }

    /** A port place, glued to a socket of each substitution transition that instantiates this page. */
    public fun <T> port(
        name: String,
        colourSet: ColourSet<T>,
        type: PortType,
        initialMarking: Multiset<T> = Multiset.empty(),
    ): Place<T> = Place("${this.name}.$name", name, colourSet, initialMarking, portType = type).also { places += it }

    public fun transition(
        name: String,
        guard: Guard = Guard.TRUE,
        block: TransitionBuilder.() -> Unit = {},
    ): Transition {
        val transition = Transition("${this.name}.$name", name, guard)
        transitions += transition
        TransitionBuilder(transition).apply(block)
        return transition
    }

    /**
     * A substitution transition instantiating [subpage]. Each `assign(socket, port)` also draws the
     * socket arc, in the direction the port type requires.
     */
    public fun substitution(
        name: String,
        subpage: Page,
        block: SubstitutionBuilder.() -> Unit,
    ): Transition {
        val assignments = SubstitutionBuilder().apply(block).assignments
        val transition = Transition("${this.name}.$name", name, substitution = Substitution(subpage, assignments.toMap()))
        transitions += transition
        for ((socket, port) in assignments) {
            val direction =
                when (port.portType) {
                    PortType.IN -> ArcDirection.PLACE_TO_TRANSITION
                    PortType.OUT -> ArcDirection.TRANSITION_TO_PLACE
                    PortType.IN_OUT, null -> ArcDirection.BIDIRECTIONAL
                }
            @Suppress("UNCHECKED_CAST")
            addArc(socket as Place<Any?>, transition, direction, null)
        }
        return transition
    }

    private fun <T> addArc(
        place: Place<T>,
        transition: Transition,
        direction: ArcDirection,
        inscription: ArcInscription<T>?,
    ) {
        arcs += Arc("$name.arc${arcs.size + 1}", place, transition, direction, inscription)
    }

    internal fun build(): Page = Page(name, name, places.toList(), transitions.toList(), arcs.toList())

    @NetDsl
    public inner class TransitionBuilder internal constructor(
        private val transition: Transition,
    ) {
        public fun <T> input(
            place: Place<T>,
            inscription: ArcInscription<T>,
        ): Unit = this@PageBuilder.addArc(place, transition, ArcDirection.PLACE_TO_TRANSITION, inscription)

        public fun <T> output(
            place: Place<T>,
            inscription: ArcInscription<T>,
        ): Unit = this@PageBuilder.addArc(place, transition, ArcDirection.TRANSITION_TO_PLACE, inscription)

        /** Double arc: consumes and puts back the same tokens. */
        public fun <T> both(
            place: Place<T>,
            inscription: ArcInscription<T>,
        ): Unit = this@PageBuilder.addArc(place, transition, ArcDirection.BIDIRECTIONAL, inscription)

        public fun <T> input(
            place: Place<T>,
            variable: Variable<T>,
            count: Int = 1,
        ): Unit = input(place, ArcInscription.VariableTerm(variable, count))

        public fun <T> output(
            place: Place<T>,
            variable: Variable<T>,
            count: Int = 1,
        ): Unit = output(place, ArcInscription.VariableTerm(variable, count))

        public fun <T> both(
            place: Place<T>,
            variable: Variable<T>,
            count: Int = 1,
        ): Unit = both(place, ArcInscription.VariableTerm(variable, count))

        public fun <T> input(
            place: Place<T>,
            tokens: Multiset<T>,
        ): Unit = input(place, ArcInscription.Constant(tokens))

        public fun <T> output(
            place: Place<T>,
            tokens: Multiset<T>,
        ): Unit = output(place, ArcInscription.Constant(tokens))

        /** Arc inscription written as Kotlin code; list every variable [body] reads. */
        public fun <T> input(
            place: Place<T>,
            vararg variables: Variable<*>,
            body: (Binding) -> Multiset<T>,
        ): Unit = input(place, ArcInscription.Function(variables.toSet(), body = body))

        public fun <T> output(
            place: Place<T>,
            vararg variables: Variable<*>,
            body: (Binding) -> Multiset<T>,
        ): Unit = output(place, ArcInscription.Function(variables.toSet(), body = body))

        public fun <T> both(
            place: Place<T>,
            vararg variables: Variable<*>,
            body: (Binding) -> Multiset<T>,
        ): Unit = both(place, ArcInscription.Function(variables.toSet(), body = body))
    }
}

@NetDsl
public class SubstitutionBuilder internal constructor() {
    internal val assignments = LinkedHashMap<Place<*>, Place<*>>()

    /** Glues [socket] (on the current page) to [port] (on the subpage). */
    public fun <T> assign(
        socket: Place<T>,
        port: Place<T>,
    ) {
        require(socket !in assignments) { "Socket '${socket.name}' is already assigned" }
        assignments[socket] = port
    }
}
