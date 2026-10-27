package no.hvl.cpn.sim

import no.hvl.cpn.core.FlatNet
import no.hvl.cpn.core.Marking
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Transition
import no.hvl.cpn.core.flatten

/** One fired binding element; [step] counts from 1. */
public data class Occurrence(
    val step: Int,
    val element: BindingElement,
) {
    /** CPN Tools simulation-report style, e.g. `3 SendPacket <d="COL",n=1>`. */
    override fun toString(): String = "$step ${element.transition.name} ${element.binding}"
}

public enum class StopReason {
    /** No binding element is enabled. */
    DEAD,

    /** The step limit of [Simulator.run] was reached. */
    MAX_STEPS,

    /** The `until` condition of [Simulator.run] held. */
    CONDITION,
}

public data class RunResult(
    val stepsTaken: Int,
    val reason: StopReason,
    val marking: Marking,
)

/**
 * Automatic and manual simulation of a CPN, one binding element at a time.
 *
 * The net is flattened first, so element names follow CPN Tools (`Page'Name 1`); [flatNet] maps
 * them back to the original pages. Random choices come from [SplitMix64] seeded with [seed], and
 * candidates are always produced in the same order, so a seed determines the whole run on every
 * platform.
 *
 * Like CPN Tools, each automatic step first picks an enabled transition uniformly at random, then
 * one of its enabled bindings uniformly at random.
 */
public class Simulator(
    net: Net,
    public val seed: Long = 0,
    maxEnumeration: Int = 100,
) {
    public val flatNet: FlatNet = net.flatten()
    public val semantics: Semantics = Semantics(flatNet.net)
    public val bindingSearch: BindingSearch = BindingSearch(semantics, maxEnumeration)

    private var random = SplitMix64(seed)
    private val history = mutableListOf<Occurrence>()

    public var marking: Marking = semantics.initialMarking
        private set

    /** Number of steps fired since creation or the last [reset]. */
    public val stepCount: Int get() = history.size

    /** All occurrences since creation or the last [reset]. */
    public val trace: List<Occurrence> get() = history.toList()

    /** Flat transition by its CPN Tools name, e.g. `"Protocol'SendPacket 1"`. */
    public fun transition(name: String): Transition =
        flatNet.net.pages
            .single()
            .transition(name)

    /** Enabled binding elements in the current marking. */
    public fun enabled(): List<BindingElement> = bindingSearch.enabledBindingElements(marking)

    public fun isDead(): Boolean = bindingSearch.isDead(marking)

    /** Fires one randomly chosen enabled binding element; returns null (and changes nothing) if the marking is dead. */
    public fun step(): Occurrence? {
        val candidates = semantics.transitions.map { it to bindingSearch.enabledBindings(it, marking) }.filter { it.second.isNotEmpty() }
        if (candidates.isEmpty()) return null
        val (_, bindings) = candidates[random.nextInt(candidates.size)]
        return record(bindings[random.nextInt(bindings.size)])
    }

    /**
     * Fires [element] chosen by the caller (CPN Tools "bind manually").
     *
     * @throws NotEnabledException if it is not enabled in the current marking.
     */
    public fun fire(element: BindingElement): Occurrence = record(element)

    /**
     * Fires [transition] with one of its enabled bindings chosen at random (clicking a transition in
     * CPN Tools); null if it is not enabled. Uses the same seeded random source as [step].
     */
    public fun fireRandom(transition: Transition): Occurrence? {
        val bindings = bindingSearch.enabledBindings(transition, marking)
        if (bindings.isEmpty()) return null
        return record(bindings[random.nextInt(bindings.size)])
    }

    /** Runs automatic steps until the net is dead, [maxSteps] steps were taken, or [until] holds after a step. */
    public fun run(
        maxSteps: Int,
        until: (Marking) -> Boolean = { false },
    ): RunResult {
        require(maxSteps >= 0) { "maxSteps must be non-negative, was $maxSteps" }
        var taken = 0
        while (taken < maxSteps) {
            step() ?: return RunResult(taken, StopReason.DEAD, marking)
            taken++
            if (until(marking)) return RunResult(taken, StopReason.CONDITION, marking)
        }
        val reason = if (isDead()) StopReason.DEAD else StopReason.MAX_STEPS
        return RunResult(taken, reason, marking)
    }

    /** Back to the initial marking and the start of the random sequence. */
    public fun reset() {
        marking = semantics.initialMarking
        random = SplitMix64(seed)
        history.clear()
    }

    private fun record(element: BindingElement): Occurrence {
        marking = semantics.fire(element, marking)
        return Occurrence(history.size + 1, element).also { history += it }
    }
}
