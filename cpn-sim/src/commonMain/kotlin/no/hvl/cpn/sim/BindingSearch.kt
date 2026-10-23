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
        matchPatterns(patterns, 0, Binding.EMPTY, marking) { partial ->
            enumerate(plan.enumerated, 0, partial) { binding ->
                val element = BindingElement(transition, binding)
                if (semantics.isEnabled(element, marking)) found += element
            }
        }
        return found.toList()
    }

    /** All enabled binding elements of all transitions in [marking]. */
    public fun enabledBindingElements(marking: Marking): List<BindingElement> =
        semantics.transitions.flatMap { enabledBindings(it, marking) }

    /** True if no transition is enabled in [marking]. */
    public fun isDead(marking: Marking): Boolean = semantics.transitions.none { enabledBindings(it, marking).isNotEmpty() }

    private fun matchPatterns(
        patterns: List<Pair<Place<Any?>, PatternInscription<Any?>>>,
        index: Int,
        binding: Binding,
        marking: Marking,
        onMatch: (Binding) -> Unit,
    ) {
        if (index == patterns.size) return onMatch(binding)
        val (place, pattern) = patterns[index]
        for ((token, count) in marking[place].entries()) {
            if (count < pattern.count) continue
            val extended = pattern.match(token, binding) ?: continue
            matchPatterns(patterns, index + 1, extended, marking, onMatch)
        }
    }

    private fun enumerate(
        variables: List<Variable<Any?>>,
        index: Int,
        binding: Binding,
        onBinding: (Binding) -> Unit,
    ) {
        if (index == variables.size) return onBinding(binding)
        val variable = variables[index]
        for (value in variable.colourSet.values!!) {
            enumerate(variables, index + 1, binding.with(variable, value), onBinding)
        }
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
