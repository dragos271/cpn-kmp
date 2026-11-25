package no.hvl.cpn.sim

import no.hvl.cpn.core.Binding
import no.hvl.cpn.core.Marking
import no.hvl.cpn.core.PatternInscription
import no.hvl.cpn.core.Place
import no.hvl.cpn.core.Transition
import no.hvl.cpn.core.Variable

/** A transition variable that neither an input-arc pattern nor a small colour set can bind. */
public class UnboundVariableException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Finds the enabled bindings of transitions, in the style of the CPN Tools simulator:
 *
 * 1. variables on input-arc patterns ([PatternInscription]) are bound by matching the tokens on
 *    those places, backtracking over the choices;
 * 2. the remaining variables are enumerated over their colour sets, which must be finite and have at
 *    most [maxEnumeration] colours;
 * 3. each candidate is kept if the guard holds and the full enabling rule is satisfied.
 *
 * Step 2 is decided once per transition, so a variable that can never be bound is reported when the
 * search is created, not in the middle of a simulation.
 *
 * @throws UnboundVariableException if some transition has such a variable.
 */
public class BindingSearch(
    public val semantics: Semantics,
    public val maxEnumeration: Int = 100,
) {
    private class Plan(
        val patterns: List<Pair<Place<Any?>, PatternInscription<Any?>>>,
        val enumerated: List<Variable<Any?>>,
    )

    private val plans: Map<Transition, Plan> = semantics.transitions.associateWith(::plan)

    /** All enabled bindings of [transition] in [marking], without duplicates. */
    public fun enabledBindings(
        transition: Transition,
        marking: Marking,
    ): List<BindingElement> {
        val plan =
            plans[transition]
                ?: throw IllegalArgumentException("Transition '${transition.name}' is not part of net '${semantics.net.name}'")
        val found = LinkedHashSet<BindingElement>()
        // Places with fewer distinct tokens first: fewer branches near the root of the search.
        val patterns = plan.patterns.sortedBy { (place, _) -> marking[place].distinct.size }
        matchPatterns(patterns, 0, Binding.EMPTY, marking, now = true) { partial ->
            enumerate(plan.enumerated, 0, partial) { binding ->
                val element = BindingElement(transition, binding)
                if (semantics.isEnabled(element, marking)) found += element
                false
            }
        }
        return found.toList()
    }

    /**
     * True if [transition] has some enabled binding in [marking]. Stops at the first one, so it is
     * much cheaper than [enabledBindings] when a transition has many: a station with 300 machine
     * slots has 300 bindings per waiting specimen.
     */
    public fun isEnabled(
        transition: Transition,
        marking: Marking,
    ): Boolean {
        val plan =
            plans[transition]
                ?: throw IllegalArgumentException("Transition '${transition.name}' is not part of net '${semantics.net.name}'")
        val patterns = plan.patterns.sortedBy { (place, _) -> marking[place].distinct.size }
        return matchPatterns(patterns, 0, Binding.EMPTY, marking, now = true) { partial ->
            enumerate(plan.enumerated, 0, partial) { binding -> semantics.isEnabled(BindingElement(transition, binding), marking) }
        }
    }

    /**
     * The earliest model time after the marking's at which some binding element becomes enabled, or
     * null if none ever will without other transitions firing (timed nets, T3.2). The clock moves
     * there when nothing is enabled now.
     */
    public fun nextEnablingTime(marking: Marking): Long? {
        var earliest: Long? = null
        for (transition in semantics.transitions) {
            val plan = plans.getValue(transition)
            val patterns = plan.patterns.sortedBy { (place, _) -> marking[place].distinct.size }
            // A token whose earliest time stamp is not before the best time found so far cannot give an earlier one.
            matchPatterns(patterns, 0, Binding.EMPTY, marking, before = { earliest }) { partial ->
                enumerate(plan.enumerated, 0, partial) { binding ->
                    val time = semantics.enablingTime(BindingElement(transition, binding), marking)
                    if (time != null && time > marking.time && (earliest == null || time < earliest!!)) earliest = time
                    false
                }
            }
        }
        return earliest
    }

    /** All enabled binding elements of all transitions in [marking]. */
    public fun enabledBindingElements(marking: Marking): List<BindingElement> =
        semantics.transitions.flatMap { enabledBindings(it, marking) }

    /** True if no transition is enabled in [marking], now or (for timed nets) at a later time. */
    public fun isDead(marking: Marking): Boolean =
        semantics.transitions.none { isEnabled(it, marking) } &&
            (!semantics.net.isTimed || nextEnablingTime(marking) == null)

    /**
     * Calls [onMatch] for each way to match the patterns; stops, returning true, as soon as it returns
     * true. With [now], only tokens available at the marking's time are matched: a binding that uses a
     * token with a later time stamp cannot be enabled now. This matters for timed places that hold many
     * tokens still in progress, such as specimens being processed overnight.
     */
    private fun matchPatterns(
        patterns: List<Pair<Place<Any?>, PatternInscription<Any?>>>,
        index: Int,
        binding: Binding,
        marking: Marking,
        now: Boolean = false,
        before: (() -> Long?)? = null,
        onMatch: (Binding) -> Boolean,
    ): Boolean {
        if (index == patterns.size) return onMatch(binding)
        val (place, pattern) = patterns[index]
        val timed = if (place.timed) marking.timed(place) else null
        val tokens = if (now && timed != null) timed.available(marking.time) else marking[place]
        for ((token, count) in tokens.entries()) {
            if (count < pattern.count) continue
            if (timed != null && before != null) {
                val bound = before()
                // The pattern needs `count` tokens of this colour; the count-th earliest is when they all are there.
                if (bound != null && timed.timestamps(token).sorted()[pattern.count - 1] >= bound) continue
            }
            val extended = pattern.match(token, binding) ?: continue
            if (matchPatterns(patterns, index + 1, extended, marking, now, before, onMatch)) return true
        }
        return false
    }

    /** Calls [onBinding] for each value of the enumerated variables; stops, returning true, as soon as it returns true. */
    private fun enumerate(
        variables: List<Variable<Any?>>,
        index: Int,
        binding: Binding,
        onBinding: (Binding) -> Boolean,
    ): Boolean {
        if (index == variables.size) return onBinding(binding)
        val variable = variables[index]
        for (value in variable.colourSet.values!!) {
            if (enumerate(variables, index + 1, binding.with(variable, value), onBinding)) return true
        }
        return false
    }

    private fun plan(transition: Transition): Plan {
        @Suppress("UNCHECKED_CAST")
        val patterns =
            semantics
                .inputArcs(transition)
                .mapNotNull { arc -> (arc.inscription as? PatternInscription<*>)?.let { arc.place to it } }
                as List<Pair<Place<Any?>, PatternInscription<Any?>>>
        val bound = patterns.flatMap { (_, pattern) -> pattern.variables }.toSet()

        @Suppress("UNCHECKED_CAST")
        val enumerated = (semantics.variablesOf(transition) - bound).toList() as List<Variable<Any?>>
        for (variable in enumerated) {
            val size = variable.colourSet.values?.size
            if (size == null || size > maxEnumeration) {
                val reason = if (size == null) "is infinite" else "has $size colours (limit $maxEnumeration)"
                throw UnboundVariableException(
                    "Variable '${variable.name}' of transition '${transition.name}' is not bound by an input-arc pattern, " +
                        "and its colour set ${variable.colourSet} $reason",
                )
            }
        }
        return Plan(patterns, enumerated)
    }
}
