package no.hvl.cpn.statespace

import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.Transition

/** Fairness of a transition instance in the infinite occurrence sequences, strongest first (CPN Tools' terms). */
public enum class Fairness(
    public val text: String,
) {
    /** It occurs infinitely often in every infinite occurrence sequence. */
    IMPARTIAL("Impartial"),

    /** It occurs infinitely often in every infinite sequence in which it is enabled infinitely often. */
    FAIR("Fair"),

    /** It occurs infinitely often in every infinite sequence in which it is continuously enabled from some point. */
    JUST("Just"),

    /** None of these. */
    NO_FAIRNESS("No Fairness"),
}

public data class IntegerBound(
    val place: String,
    val upper: Int,
    val lower: Int,
)

public data class MultisetBound(
    val place: String,
    val upper: String,
    val lower: String,
)

/**
 * The properties of CPN Tools' standard state-space report (T3.4; Jensen & Kristensen 2009, ch. 7),
 * computed from a [StateSpace] and its SCC graph. Place and transition names are those of the
 * flattened net, `Page'Name instance`, as CPN Tools prints them.
 *
 * For a [StateSpaceStatus.PARTIAL] state space the properties describe only the explored part, as in
 * CPN Tools; [format] says so.
 */
public class StandardReport internal constructor(
    public val net: String,
    public val status: StateSpaceStatus,
    public val nodes: Int,
    public val arcs: Int,
    public val seconds: Double,
    public val sccNodes: Int,
    /** Arcs of the state space between different SCCs. */
    public val sccArcs: Int,
    public val integerBounds: List<IntegerBound>,
    public val multisetBounds: List<MultisetBound>,
    /** Markings reachable from every reachable marking: the nodes of the only terminal SCC, or empty if there are several. */
    public val homeMarkings: List<Int>,
    public val deadMarkings: List<Int>,
    public val deadTransitions: List<String>,
    public val liveTransitions: List<String>,
    /** Null if there are no infinite occurrence sequences (the SCC graph has no cycle). */
    public val fairness: Map<String, Fairness>?,
) {
    /** The report in the layout of CPN Tools' standard report. */
    public fun format(): String =
        buildString {
            fun section(title: String) {
                appendLine()
                appendLine(" $title")
                appendLine("-".repeat(72))
            }

            fun nodes(list: List<Int>): String =
                when {
                    list.isEmpty() -> "None"
                    list.size <= 20 -> list.joinToString(",", "[", "]")
                    else -> list.take(20).joinToString(",", "[", ",...]") + " (${list.size} in total)"
                }

            fun names(list: List<String>): String = if (list.isEmpty()) "     None" else list.joinToString("\n") { "     $it" }

            appendLine("State space report for: $net")
            if (status == StateSpaceStatus.PARTIAL) {
                appendLine("The state space is partial: these properties hold for the explored part only.")
            }

            section("Statistics")
            appendLine("  State Space")
            appendLine("     Nodes:  $nodes")
            appendLine("     Arcs:   $arcs")
            appendLine("     Secs:   $seconds")
            appendLine("     Status: ${if (status == StateSpaceStatus.FULL) "Full" else "Partial"}")
            appendLine()
            appendLine("  Scc Graph")
            appendLine("     Nodes:  $sccNodes")
            appendLine("     Arcs:   $sccArcs")

            section("Boundedness Properties")
            appendLine("  Best Integer Bounds")
            val width = (integerBounds.maxOfOrNull { it.place.length } ?: 0).coerceAtLeast(20) + 4
            appendLine("     ${"".padEnd(width)}${"Upper".padEnd(10)}Lower")
            for (b in integerBounds) appendLine("     ${b.place.padEnd(width)}${b.upper.toString().padEnd(10)}${b.lower}")
            appendLine()
            appendLine("  Best Upper Multi-set Bounds")
            for (b in multisetBounds) appendLine("     ${b.place}\n         ${b.upper.replace("++", "++\n         ")}")
            appendLine()
            appendLine("  Best Lower Multi-set Bounds")
            for (b in multisetBounds) appendLine("     ${b.place}\n         ${b.lower.replace("++", "++\n         ")}")

            section("Home Properties")
            appendLine("  Home Markings")
            appendLine("     ${nodes(homeMarkings)}")

            section("Liveness Properties")
            appendLine("  Dead Markings")
            appendLine("     ${nodes(deadMarkings)}")
            appendLine()
            appendLine("  Dead Transition Instances")
            appendLine(names(deadTransitions))
            appendLine()
            appendLine("  Live Transition Instances")
            appendLine(names(liveTransitions))

            section("Fairness Properties")
            val fair = fairness
            if (fair == null) {
                appendLine("     No infinite occurrence sequences.")
            } else {
                val nameWidth = (fair.keys.maxOfOrNull { it.length } ?: 0) + 4
                for ((transition, f) in fair) appendLine("     ${transition.padEnd(nameWidth)}${f.text}")
            }
        }

    override fun toString(): String = format()
}

/** The standard report of this state space (T3.4). */
public fun StateSpace.standardReport(): StandardReport {
    val n = nodeCount
    val succ = Array(n) { node -> outArcs(node + 1).map { it.to - 1 }.toIntArray() }
    val scc = components(n, { succ[it] })

    // SCC graph: arcs between different components; terminal components have none leaving them.
    var sccArcs = 0
    val leaves = BooleanArray(scc.count)
    for (arc in arcs) {
        val from = scc.component[arc.from - 1]
        val to = scc.component[arc.to - 1]
        if (from != to) {
            sccArcs++
            leaves[from] = true
        }
    }
    val terminal = (0 until scc.count).filter { !leaves[it] }

    val places = flatNet.net.pages.flatMap { it.places }
    val transitions = flatNet.net.pages.flatMap { it.transitions }

    // Home markings: reachable from everywhere, which needs a single terminal component.
    val home = if (terminal.size == 1) nodes.filter { scc.component[it - 1] == terminal.single() } else emptyList()

    // Liveness: a transition is live if it occurs inside every terminal component.
    val occurring = arcs.map { it.element.transition }.toSet()
    val inside = HashMap<Int, MutableSet<Transition>>()
    for (arc in arcs) {
        val c = scc.component[arc.from - 1]
        if (c == scc.component[arc.to - 1]) inside.getOrPut(c) { HashSet() } += arc.element.transition
    }
    val insideTerminal = terminal.map { inside[it].orEmpty() }
    val live = transitions.filter { t -> terminal.isNotEmpty() && insideTerminal.all { t in it } }

    return StandardReport(
        net = flatNet.original.name,
        status = status,
        nodes = n,
        arcs = arcCount,
        seconds = duration.inWholeMilliseconds / 1000.0,
        sccNodes = scc.count,
        sccArcs = sccArcs,
        integerBounds = places.map { integerBound(it) },
        multisetBounds = places.map { multisetBound(it) },
        homeMarkings = home,
        deadMarkings = deadMarkings,
        deadTransitions = transitions.filter { it !in occurring }.map { it.name },
        liveTransitions = live.map { it.name },
        fairness = if (scc.hasCycle) transitions.associate { it.name to fairness(it) } else null,
    )
}

private fun StateSpace.integerBound(place: Place<*>): IntegerBound {
    var upper = Int.MIN_VALUE
    var lower = Int.MAX_VALUE
    for (node in nodes) {
        val size = marking(node)[place].size
        if (size > upper) upper = size
        if (size < lower) lower = size
    }
    return IntegerBound(place.name, upper, lower)
}

/** Per colour, the most and the fewest tokens of it the place ever holds (CPN Tools' multiset bounds). */
@Suppress("UNCHECKED_CAST")
private fun StateSpace.multisetBound(place: Place<*>): MultisetBound {
    val typed = place as Place<Any?>
    val upper = HashMap<Any?, Int>()
    var lower: MutableMap<Any?, Int>? = null
    for (node in nodes) {
        val tokens = marking(node)[typed]
        for ((colour, count) in tokens.entries()) upper[colour] = maxOf(upper[colour] ?: 0, count)
        lower =
            if (lower == null) {
                tokens.entries().associate { it.key to it.value }.toMutableMap()
            } else {
                lower.keys.retainAll(tokens.distinct)
                for ((colour, count) in tokens.entries()) lower[colour]?.let { lower[colour] = minOf(it, count) }
                lower
            }
    }

    fun show(counts: Map<Any?, Int>) = Multiset.fromCounts(counts).format(typed.colourSet.order, typed.colourSet::format)
    return MultisetBound(place.name, show(upper), show(lower.orEmpty()))
}

/**
 * Fairness of [transition]. Infinite occurrence sequences are the cycles of the state space; one that
 * avoids [transition] is a cycle in the graph without its arcs. [transition] is
 * - impartial if no such cycle exists;
 * - fair if no such cycle passes a marking where it is enabled;
 * - just if no such cycle has it enabled in every marking.
 */
private fun StateSpace.fairness(transition: Transition): Fairness {
    val n = nodeCount
    val without = Array(n) { node -> outArcs(node + 1).filter { it.element.transition != transition }.map { it.to - 1 }.toIntArray() }
    val enabled = BooleanArray(n) { node -> outArcs(node + 1).any { it.element.transition == transition } }
    val cycles = components(n, { without[it] })
    if (!cycles.hasCycle) return Fairness.IMPARTIAL
    val reachesEnabled = (0 until n).any { enabled[it] && cycles.nontrivial[cycles.component[it]] }
    if (!reachesEnabled) return Fairness.FAIR
    // Just fails if a cycle avoiding the transition stays where it is enabled.
    val enabledOnly = components(n, { without[it] }, include = { enabled[it] })
    return if (enabledOnly.hasCycle) Fairness.NO_FAIRNESS else Fairness.JUST
}
