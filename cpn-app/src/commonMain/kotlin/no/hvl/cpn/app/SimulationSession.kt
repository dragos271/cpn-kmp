package no.hvl.cpn.app

import no.hvl.cpn.core.FlatNet
import no.hvl.cpn.core.Net
import no.hvl.cpn.core.PageInstance
import no.hvl.cpn.core.Transition
import no.hvl.cpn.io.model.CpnDocument
import no.hvl.cpn.sim.BindingElement
import no.hvl.cpn.sim.Occurrence
import no.hvl.cpn.sim.RunResult
import no.hvl.cpn.sim.Simulator
import no.hvl.cpn.sim.StopReason

/** Tokens on one place, for the canvas: CPN Tools' count bubble and marking box. */
data class PlaceTokens(
    val count: Int,
    val text: String,
)

/** What the canvas overlays on one page while simulating, keyed by document element id. */
data class PageOverlay(
    val tokens: Map<String, PlaceTokens>,
    val enabledTransitions: Set<String>,
) {
    companion object {
        val EMPTY = PageOverlay(emptyMap(), emptySet())
    }
}

/** What the screen shows after a change: computed once per change, so the binding search runs once. */
data class SessionSnapshot(
    val revision: Int,
    val stepCount: Int,
    val enabled: List<BindingElement>,
    val trace: List<Occurrence>,
    val lastStop: StopReason?,
    val overlays: Map<String, PageOverlay>,
    /** Enabled bindings per document transition id (`Page.Name`). */
    val bindings: Map<String, List<BindingElement>>,
) {
    val isDead: Boolean get() = enabled.isEmpty()

    fun overlay(pageId: String): PageOverlay = overlays[pageId] ?: PageOverlay.EMPTY
}

/**
 * The app's view of one simulation: a thin, UI-free wrapper around the engine's [Simulator], so the
 * app shows exactly what `cpn simulate` computes. It maps the engine's flattened net back onto the
 * document's pages, so the canvas can draw tokens and enabled transitions on the right shapes.
 * Pages used several times (by several substitution transitions) show their first instance.
 */
class SimulationSession(
    val document: CpnDocument,
    private val net: Net,
    seed: Long,
) {
    var seed: Long = seed
        private set

    private var simulator = Simulator(net, seed)

    var revision: Int = 0
        private set

    var lastStop: StopReason? = null
        private set

    private val flat: FlatNet get() = simulator.flatNet

    /** The page instance shown for a document page (net pages have the document's page ids). */
    private fun instanceOf(pageId: String): PageInstance? =
        net.pages
            .firstOrNull { it.id == pageId }
            ?.let { flat.instancesOf(it).firstOrNull() }

    private fun flatTransition(
        pageId: String,
        transitionId: String,
    ): Transition? {
        val instance = instanceOf(pageId) ?: return null
        val transition = instance.page.transitions.firstOrNull { it.id == transitionId && !it.isSubstitution } ?: return null
        return flat.transition(instance, transition)
    }

    fun snapshot(): SessionSnapshot {
        val enabled = simulator.enabled()
        val enabledByFlat = enabled.groupBy { it.transition }
        val overlays = HashMap<String, PageOverlay>()
        val bindings = HashMap<String, List<BindingElement>>()
        for (page in document.pages) {
            val instance = instanceOf(page.id) ?: continue
            val tokens =
                instance.page.places.associate { place ->
                    val flatPlace = flat.place(instance, place)
                    place.id to PlaceTokens(simulator.marking[flatPlace].size, simulator.marking.tokensOf(flatPlace))
                }
            val enabledHere = mutableSetOf<String>()
            for (transition in instance.page.transitions) {
                if (transition.isSubstitution) continue
                val elements = enabledByFlat[flat.transition(instance, transition)].orEmpty()
                if (elements.isNotEmpty()) {
                    enabledHere += transition.id
                    bindings[transition.id] = elements
                }
            }
            overlays[page.id] = PageOverlay(tokens, enabledHere)
        }
        return SessionSnapshot(revision, simulator.stepCount, enabled, simulator.trace, lastStop, overlays, bindings)
    }

    fun fire(element: BindingElement): Occurrence = simulator.fire(element).also { changed(null) }

    /** Clicking a transition: fire it with a random enabled binding. Null if it is not enabled. */
    fun fireTransition(
        pageId: String,
        transitionId: String,
    ): Occurrence? {
        val transition = flatTransition(pageId, transitionId) ?: return null
        return simulator.fireRandom(transition)?.also { changed(null) }
    }

    fun step(): Occurrence? = simulator.step().also { changed(if (it == null) StopReason.DEAD else null) }

    fun run(maxSteps: Int): RunResult = simulator.run(maxSteps).also { changed(it.reason) }

    fun reset(newSeed: Long = seed) {
        if (newSeed != seed) {
            seed = newSeed
            simulator = Simulator(net, newSeed)
        } else {
            simulator.reset()
        }
        changed(null)
    }

    private fun changed(stop: StopReason?) {
        lastStop = stop
        revision++
    }
}
