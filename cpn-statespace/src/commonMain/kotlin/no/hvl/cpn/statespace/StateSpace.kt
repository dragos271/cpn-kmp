package no.hvl.cpn.statespace

import no.hvl.cpn.core.FlatNet
import no.hvl.cpn.core.Marking
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.flatten
import no.hvl.cpn.sim.BindingElement
import no.hvl.cpn.sim.BindingSearch
import no.hvl.cpn.sim.Semantics
import kotlin.time.Duration
import kotlin.time.TimeSource

/** When to stop exploring; a state space that hits one is [StateSpaceStatus.PARTIAL]. */
public data class StateSpaceLimits(
    val maxNodes: Int = 1_000_000,
    val maxArcs: Int = 10_000_000,
    /** Wall-clock time for the exploration; null for none. */
    val maxTime: Duration? = null,
)

/** CPN Tools' wording: "Full" if every reachable marking was explored, "Partial" if a limit stopped it. */
public enum class StateSpaceStatus { FULL, PARTIAL }

/** An arc of the state space: [element] occurs in node [from] and leads to node [to] (node numbers start at 1). */
public data class StateSpaceArc(
    val from: Int,
    val to: Int,
    val element: BindingElement,
)

/**
 * The state space (reachability graph) of a CPN (T3.3): one node per reachable marking, one arc per
 * enabled binding element. Built breadth-first, so node 1 is the initial marking and nodes are
 * numbered as CPN Tools numbers them; markings are found again through their hash.
 *
 * For timed nets (T3.2) a marking includes the model time. In a marking where nothing is enabled now,
 * the successors are those of the earliest time something is enabled, as in CPN Tools' timed state
 * spaces; such a state space is finite only if the model time stops growing.
 *
 * Use [build]. Exploration stops at the first of [StateSpaceLimits]; [status] then says PARTIAL and
 * only [explored] nodes have all their successors.
 */
public class StateSpace private constructor(
    /** The flattened net whose markings the nodes are (names like `Page'Place 1`). */
    public val flatNet: FlatNet,
    private val markings: List<Marking>,
    public val arcs: List<StateSpaceArc>,
    private val exploredCount: Int,
    public val status: StateSpaceStatus,
    /** Which limit stopped the exploration, for a partial state space. */
    public val stoppedBy: String?,
    /** How long the exploration took. */
    public val duration: Duration,
) {
    public val nodeCount: Int get() = markings.size

    public val arcCount: Int get() = arcs.size

    /** Node numbers, 1 to [nodeCount]. */
    public val nodes: IntRange get() = 1..nodeCount

    /** True if [node]'s successors were all computed (always, for a full state space). */
    public fun explored(node: Int): Boolean = node in 1..exploredCount

    public fun marking(node: Int): Marking = markings[node - 1]

    /** The node of [marking], or null if it is not in the state space. */
    public fun node(marking: Marking): Int? = index[marking]

    public fun outArcs(node: Int): List<StateSpaceArc> = outgoing[node - 1].map { arcs[it] }

    public fun inArcs(node: Int): List<StateSpaceArc> = incoming[node - 1].map { arcs[it] }

    public fun successors(node: Int): List<Int> = outgoing[node - 1].map { arcs[it].to }.distinct()

    /** Explored nodes without successors: the dead markings. */
    public val deadMarkings: List<Int> by lazy { (1..exploredCount).filter { outgoing[it - 1].isEmpty() } }

    private val index: Map<Marking, Int> by lazy { markings.withIndex().associate { (i, m) -> m to i + 1 } }

    private val outgoing: List<IntArray> by lazy { adjacency { it.from } }

    private val incoming: List<IntArray> by lazy { adjacency { it.to } }

    private fun adjacency(end: (StateSpaceArc) -> Int): List<IntArray> {
        val counts = IntArray(nodeCount)
        for (arc in arcs) counts[end(arc) - 1]++
        val lists = List(nodeCount) { IntArray(counts[it]) }
        val filled = IntArray(nodeCount)
        arcs.forEachIndexed { i, arc ->
            val n = end(arc) - 1
            lists[n][filled[n]++] = i
        }
        return lists
    }

    public companion object {
        /**
         * Explores the state space of [net], flattened first.
         *
         * @throws no.hvl.cpn.sim.UnboundVariableException if a transition has a variable that cannot be bound.
         */
        public fun build(
            net: Net,
            limits: StateSpaceLimits = StateSpaceLimits(),
        ): StateSpace {
            val start = TimeSource.Monotonic.markNow()
            val flatNet = net.flatten()
            val semantics = Semantics(flatNet.net)
            val search = BindingSearch(semantics)
            val timed = flatNet.net.isTimed

            val markings = ArrayList<Marking>()
            val index = HashMap<Marking, Int>()
            val arcs = ArrayList<StateSpaceArc>()

            fun nodeOf(marking: Marking): Int =
                index.getOrPut(marking) {
                    markings += marking
                    markings.size
                }

            nodeOf(semantics.initialMarking)
            var explored = 0
            var stoppedBy: String? = null
            while (explored < markings.size) {
                if (markings.size > limits.maxNodes) {
                    stoppedBy = "more than ${limits.maxNodes} nodes"
                    break
                }
                if (arcs.size > limits.maxArcs) {
                    stoppedBy = "more than ${limits.maxArcs} arcs"
                    break
                }
                val maxTime = limits.maxTime
                if (maxTime != null && explored % 64 == 0 && start.elapsedNow() > maxTime) {
                    stoppedBy = "the time limit of $maxTime"
                    break
                }
                val node = explored + 1
                var marking = markings[explored]
                var enabled = search.enabledBindingElements(marking)
                if (enabled.isEmpty() && timed) {
                    // Nothing enabled now: the successors are those at the next time something is.
                    search.nextEnablingTime(marking)?.let { next ->
                        marking = marking.at(next)
                        enabled = search.enabledBindingElements(marking)
                    }
                }
                for (element in enabled) {
                    arcs += StateSpaceArc(node, nodeOf(semantics.fire(element, marking)), element)
                }
                explored++
            }
            val status = if (stoppedBy == null) StateSpaceStatus.FULL else StateSpaceStatus.PARTIAL
            return StateSpace(flatNet, markings, arcs, explored, status, stoppedBy, start.elapsedNow())
        }
    }
}
