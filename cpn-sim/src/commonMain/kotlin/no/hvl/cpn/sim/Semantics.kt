package no.hvl.cpn.sim

import no.hvl.cpn.core.Arc
import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.Marking
import no.hvl.cpn.core.Multiset
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.TimedMultiset
import no.hvl.cpn.core.Transition
import no.hvl.cpn.core.Variable

/** A transition with a binding of all its variables: the unit of occurrence. */
public data class BindingElement(
    val transition: Transition,
    val binding: Binding,
) {
    /** CPN Tools style, e.g. `(SendPacket, <d="COL",n=1>)`. */
    override fun toString(): String = "(${transition.name}, $binding)"
}

public class NotEnabledException(
    message: String,
) : IllegalStateException(message)

/**
 * Enabling and occurrence rule for flat (non-hierarchical) CPNs, following Jensen & Kristensen (2009),
 * ch. 4: a step of binding elements is enabled in a marking if every guard holds and the tokens the
 * step consumes, summed over all its binding elements, are present.
 *
 * Timed nets (ch. 10, T3.2): tokens on timed places carry integer timestamps, and a step is enabled
 * at the marking's model time only if the timed tokens it consumes are *available*, timestamp ≤
 * time. Firing gives produced tokens the timestamp time + the transition's delay + the arc's delay.
 *
 * Hierarchical nets are flattened first (T1.5).
 */
public class Semantics(
    public val net: Net,
) {
    private val arcsByTransition: Map<Transition, List<Arc<*>>>
    private val variablesByTransition: Map<Transition, Set<Variable<*>>>

    init {
        require(!net.isHierarchical && net.fusionSets.isEmpty()) {
            "Net '${net.name}' has substitution transitions or fusion sets; flatten it before simulating"
        }
        val arcs = net.pages.flatMap { it.arcs }.groupBy { it.transition }
        val transitions = net.pages.flatMap { it.transitions }
        arcsByTransition = transitions.associateWith { arcs[it].orEmpty() }
        variablesByTransition =
            transitions.associateWith { t ->
                t.guard.variables + t.delay.variables + arcsByTransition.getValue(t).flatMap { it.inscription!!.variables }
            }
    }

    public val transitions: Set<Transition> get() = arcsByTransition.keys

    public val initialMarking: Marking = Marking.initial(net)

    /** Var(t): the variables of the guard and of the arcs around [transition]. */
    public fun variablesOf(transition: Transition): Set<Variable<*>> = variablesByTransition.getValue(transition)

    /** Arcs that consume tokens for [transition], including double arcs. */
    public fun inputArcs(transition: Transition): List<Arc<*>> = arcsByTransition.getValue(transition).filter { it.direction.isInput }

    /** True if [element] is enabled in [marking]. */
    public fun isEnabled(
        element: BindingElement,
        marking: Marking,
    ): Boolean = isEnabled(listOf(element), marking)

    /** True if [step] (a multiset of binding elements, given as a list) is enabled concurrently in [marking]. */
    public fun isEnabled(
        step: List<BindingElement>,
        marking: Marking,
    ): Boolean {
        val time = enablingTime(step, marking) ?: return false
        return time <= marking.time
    }

    /**
     * The earliest model time, at least the marking's, at which [step] is enabled: the guards hold and
     * all consumed timed tokens are available. Null if it is not enabled at any time (not enough
     * tokens, or a guard fails). For untimed nets this is the marking's time or null.
     */
    public fun enablingTime(
        step: List<BindingElement>,
        marking: Marking,
    ): Long? {
        require(step.isNotEmpty()) { "A step must contain at least one binding element" }
        step.forEach(::checkBinding)
        tellTime(marking)
        if (!step.all { it.transition.guard.evaluate(it.binding) }) return null
        var time = marking.time
        for ((place, tokens) in consumed(step)) {
            if (!(tokens subsetOf marking[place])) return null
            if (place.timed) time = maxOf(time, marking.timed(place).earliest(tokens) ?: return null)
        }
        return time
    }

    /** [enablingTime] of one binding element. */
    public fun enablingTime(
        element: BindingElement,
        marking: Marking,
    ): Long? = enablingTime(listOf(element), marking)

    /** Fires [element], returning the successor marking. */
    public fun fire(
        element: BindingElement,
        marking: Marking,
    ): Marking = fire(listOf(element), marking)

    /** Fires [step] concurrently, returning the successor marking. */
    public fun fire(
        step: List<BindingElement>,
        marking: Marking,
    ): Marking {
        if (!isEnabled(step, marking)) throw NotEnabledException("Step $step is not enabled in marking\n$marking")
        val now = marking.time
        val consumed = consumed(step)
        val produced = produced(step)
        val timedProduced = if (net.isTimed) producedTimed(step, now) else emptyMap()
        val changes = LinkedHashMap<Place<*>, Any>()
        for (place in consumed.keys + produced.keys) {
            changes[place] =
                if (place.timed) {
                    marking.timed(place).minus(consumed[place].orEmpty(), now) + (timedProduced[place] ?: TimedMultiset.empty())
                } else {
                    (marking[place] - consumed[place].orEmpty()) + produced[place].orEmpty()
                }
        }
        return marking.update(changes)
    }

    /** What [step] puts on timed places, with timestamps: [now] + the transition's delay + each arc's delay. */
    private fun producedTimed(
        step: List<BindingElement>,
        now: Long,
    ): Map<Place<Any?>, TimedMultiset<Any?>> {
        val sum = LinkedHashMap<Place<Any?>, TimedMultiset<Any?>>()
        for ((transition, binding) in step) {
            val delay = transition.delay.evaluate(binding)
            for (arc in arcsByTransition.getValue(transition)) {
                if (!arc.direction.isOutput || !arc.place.timed) continue
                @Suppress("UNCHECKED_CAST")
                val typed = arc as Arc<Any?>
                val tokens = typed.inscription!!.evaluateTimed(binding).shifted(now + delay)
                sum[typed.place] = (sum[typed.place] ?: TimedMultiset.empty()) + tokens
            }
        }
        return sum
    }

    /** Lets inscriptions read the model time ([Net.clock]). */
    private fun tellTime(marking: Marking) {
        net.clock?.invoke(marking.time)
    }

    /** Tokens removed by [step], per input place. */
    public fun consumed(step: List<BindingElement>): Map<Place<Any?>, Multiset<Any?>> = sumArcs(step) { it.direction.isInput }

    /**
     * Tokens added by [step], per output place.
     *
     * @throws IllegalStateException if an output arc evaluates to a colour outside its place's colour set.
     */
    public fun produced(step: List<BindingElement>): Map<Place<Any?>, Multiset<Any?>> {
        // Inscriptions may read the time; isEnabled told it already when called through fire.
        val produced = sumArcs(step) { it.direction.isOutput }
        for ((place, tokens) in produced) {
            val bad = tokens.distinct.filterNot { it in place.colourSet }
            check(bad.isEmpty()) { "Step $step puts colours $bad on place '${place.name}', which are not in ${place.colourSet}" }
        }
        return produced
    }

    private fun sumArcs(
        step: List<BindingElement>,
        include: (Arc<*>) -> Boolean,
    ): Map<Place<Any?>, Multiset<Any?>> {
        val sum = LinkedHashMap<Place<Any?>, Multiset<Any?>>()
        for ((transition, binding) in step) {
            for (arc in arcsByTransition.getValue(transition)) {
                if (!include(arc)) continue
                @Suppress("UNCHECKED_CAST")
                val typed = arc as Arc<Any?>
                val tokens = typed.inscription!!.evaluate(binding)
                sum[typed.place] = sum[typed.place].orEmpty() + tokens
            }
        }
        return sum
    }

    /** A binding element must bind exactly Var(t); anything else is a caller error, not "not enabled". */
    private fun checkBinding(element: BindingElement) {
        val transition = element.transition
        require(transition in arcsByTransition) { "Transition '${transition.name}' is not part of net '${net.name}'" }
        val expected = variablesOf(transition)
        val actual = element.binding.variables
        require(actual == expected) {
            "Binding $actual of '${transition.name}' must bind exactly ${expected.map { it.name }}: " +
                "missing ${(expected - actual).map { it.name }}, extra ${(actual - expected).map { it.name }}"
        }
    }
}

private fun <T> Multiset<T>?.orEmpty(): Multiset<T> = this ?: Multiset.empty()
