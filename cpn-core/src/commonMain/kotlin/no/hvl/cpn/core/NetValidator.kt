package no.hvl.cpn.core

/** Structural checks that do not need inscription evaluation (that is the type checker's job, T2.4). */
internal class NetValidator(
    private val net: Net,
) {
    private val problems = mutableListOf<String>()

    fun validate(): List<String> {
        checkUniqueIds()
        checkVariables()
        net.pages.forEach(::checkPage)
        checkFusionSets()
        return problems
    }

    private fun report(message: String) {
        problems += message
    }

    private fun checkUniqueIds() {
        val ids =
            net.pages.map { it.id } +
                net.pages.flatMap { page -> page.places.map { it.id } + page.transitions.map { it.id } + page.arcs.map { it.id } }
        ids
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { report("Duplicate id '$it'") }
        net.variables
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .forEach { report("Duplicate variable '$it'") }
    }

    private fun checkVariables() {
        val declared = net.variables.toSet()
        for (page in net.pages) {
            for (transition in page.transitions) {
                val used = transition.guard.variables + page.arcsOf(transition).flatMap { it.inscription?.variables.orEmpty() }
                (used - declared).forEach {
                    report(
                        "Transition '${transition.name}' on page '${page.name}' uses undeclared variable '${it.name}'",
                    )
                }
            }
        }
    }

    private fun checkPage(page: Page) {
        val where = "on page '${page.name}'"
        for (place in page.places) {
            val bad = place.initialMarking.distinct.filterNot { it in place.colourSet }
            if (bad.isNotEmpty()) report("Initial marking of place '${place.name}' $where has colours not in ${place.colourSet}: $bad")
        }
        for (arc in page.arcs) {
            if (arc.place !in page.places) report("Arc '${arc.id}' $where refers to place '${arc.place.name}' of another page")
            if (arc.transition !in
                page.transitions
            ) {
                report("Arc '${arc.id}' $where refers to transition '${arc.transition.name}' of another page")
            }
        }
        for (transition in page.transitions) {
            val substitution = transition.substitution
            if (substitution == null) {
                page.arcsOf(transition).filter { it.inscription == null }.forEach { report("Arc '$it' $where has no inscription") }
            } else {
                checkSubstitution(page, transition, substitution)
            }
        }
    }

    private fun checkSubstitution(
        page: Page,
        transition: Transition,
        substitution: Substitution,
    ) {
        val where = "Substitution transition '${transition.name}' on page '${page.name}'"
        val subpage = substitution.subpage
        if (subpage !in net.pages) report("$where refers to page '${subpage.name}', which is not in the net")
        if (transition.guard !== Guard.TRUE) report("$where cannot have a guard")
        val arcs = page.arcsOf(transition)
        arcs.filter { it.inscription != null }.forEach { report("$where: arc '$it' cannot have an inscription") }

        for ((socket, port) in substitution.assignments) {
            val pair = "socket '${socket.name}' -> port '${port.name}'"
            if (socket !in page.places) report("$where: $pair, socket is not on page '${page.name}'")
            if (port !in subpage.ports) report("$where: $pair, port is not a port of page '${subpage.name}'")
            if (socket.colourSet != port.colourSet) report("$where: $pair, colour sets differ (${socket.colourSet} vs ${port.colourSet})")
            val directions = arcs.filter { it.place === socket }.map { it.direction }.toSet()
            val expected = port.portType?.let(::socketDirection)
            if (expected != null && directions != setOf(expected)) {
                report("$where: $pair, a ${port.portType} port needs a single $expected arc, found $directions")
            }
        }
        val unassigned = subpage.ports - substitution.assignments.values.toSet()
        unassigned.forEach { report("$where: port '${it.name}' of page '${subpage.name}' is not assigned") }
        val connected = arcs.map { it.place }.toSet() - substitution.assignments.keys
        connected.forEach { report("$where: place '${it.name}' is connected but not assigned to a port") }
    }

    private fun checkFusionSets() {
        for ((name, places) in net.fusionSets) {
            val first = places.first()
            for (place in places.drop(1)) {
                if (place.colourSet !=
                    first.colourSet
                ) {
                    report("Fusion set '$name': '${place.name}' and '${first.name}' have different colour sets")
                }
                if (place.initialMarking != first.initialMarking) {
                    report("Fusion set '$name': '${place.name}' and '${first.name}' have different initial markings")
                }
            }
        }
    }

    private fun socketDirection(portType: PortType): ArcDirection =
        when (portType) {
            PortType.IN -> ArcDirection.PLACE_TO_TRANSITION
            PortType.OUT -> ArcDirection.TRANSITION_TO_PLACE
            PortType.IN_OUT -> ArcDirection.BIDIRECTIONAL
        }
}
